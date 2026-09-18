package kr.decacross.collector.sources.purpur

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.config.PurpurHashMode
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.DownloadRequest
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.store.CoreBuildRow
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Purpur API v2 → core_builds. API 가 md5 만 주므로 선택된 빌드를 스트리밍 해시해 sha256 을 만든다 (디스크 기록 없음). */
class PurpurSource : CollectorSource {
    override val id: SourceId = SourceId.PURPUR
    override val interval: Duration = 15.minutes
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = PurpurRun(ctx).run()
}

private const val RESULT_SUCCESS = "SUCCESS"
private const val TYPE_EXPERIMENTAL = "experimental"

/** `purpur.budget.<UTC yyyy-MM-dd>`. */
internal fun purpurBudgetStateKey(now: Instant): String {
    val date = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atOffset(ZoneOffset.UTC).toLocalDate()
    return "purpur.budget.$date"
}

/**
 * 한 번의 Purpur 수집 (§9.4, D17).
 *
 * # 불변식
 * - jar 는 디스크에 쓰지 않는다 ([kr.decacross.collector.http.Http.digest]).
 * - [PurpurHashMode.LATEST]/[PurpurHashMode.NEW] 는 과거 빌드를 백필하지 않는다. 전체 백필은 [PurpurHashMode.BACKFILL] 명시 때만.
 * - 하루 누적 바이트는 jar 본문을 받을 때마다(해시 성공·md5 불일치·중간 실패 모두) 상태에 기록한다
 *   (중간에 죽거나 같은 빌드가 계속 실패해도 사이클·일일 상한이 우회되지 않게).
 * - md5 가 틀린 빌드는 그날 다시 스트리밍하지 않는다.
 */
private class PurpurRun(private val ctx: CollectContext) {
    private val settings = ctx.settings.purpur
    private val base = settings.baseUrl.trimEnd('/')
    private val report = ReportBuilder(SourceId.PURPUR)

    private var jarsLeft = settings.maxJarsPerCycle
    private var bytesLeft = 0L
    private var usedToday = 0L
    private var budgetKey = ""
    private val mismatchedToday = LinkedHashSet<String>()

    suspend fun run(): SourceReport {
        if (settings.hashMode == PurpurHashMode.OFF) return report.build(SourceStatus.SKIPPED)

        // ── 1. 버전 목록 → mc_versions 에 있는 것만, 서수 내림차순 ──
        val project = when (val r = getJson("$base/v2/purpur", PurpurProject.serializer())) {
            is Fetched.Ok -> r.value
            is Fetched.Failed -> return report.build(SourceStatus.FAILED, "purpur 버전 목록: ${r.reason}")
        }
        val ordinalByLabel = ctx.store.mcIndex().associate { it.label to it.ordinal }
        val sorted = project.versions.filter { it in ordinalByLabel }.sortedByDescending { ordinalByLabel[it] }
        val versions = if (settings.hashMode == PurpurHashMode.BACKFILL) sorted else sorted.take(settings.latestVersions)

        // ── 2. 알려진 빌드·예산 ──
        val known = ctx.store.coreBuildKeys(CoreKey.PURPUR)
        budgetKey = purpurBudgetStateKey(ctx.clock.now())
        val budgetState = decode(PurpurBudgetState.serializer(), ctx.store.getState(budgetKey))
        usedToday = budgetState?.bytes ?: 0L
        budgetState?.mismatched?.let { mismatchedToday += it }
        bytesLeft = minOf(settings.maxBytesPerCycle, settings.maxBytesPerDay - usedToday)

        // ── 3. 버전별 (순차) ──
        for (v in versions) {
            if (!processVersion(v, known[v].orEmpty())) break
        }

        val partial = report.count("budget.exhausted") > 0 || report.count("failed") > 0 || report.count("versions.failed") > 0
        return report.build(if (partial) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    /** @return false 면 예산 소진으로 사이클을 멈춘다. */
    private suspend fun processVersion(v: String, knownBuilds: Set<String>): Boolean {
        val versionUrl = "$base/v2/purpur/$v"
        val brief = when (val r = getJson(versionUrl, PurpurVersion.serializer())) {
            is Fetched.Ok -> r.value

            is Fetched.Failed -> {
                report.inc("versions.failed")
                report.warn("purpur $v: ${r.reason}")
                return true
            }
        }
        val incremental = settings.hashMode == PurpurHashMode.LATEST || settings.hashMode == PurpurHashMode.NEW
        if (incremental && brief.builds.latest in knownBuilds) {
            report.inc("latest.known")
            return true
        }
        val detailed = when (val r = getJson("$versionUrl?detailed=true", PurpurVersionDetailed.serializer())) {
            is Fetched.Ok -> r.value

            is Fetched.Failed -> {
                report.inc("versions.failed")
                report.warn("purpur $v detailed: ${r.reason}")
                return true
            }
        }

        // 해시 가능한 빌드: SUCCESS, md5 있음, timestamp 있음. 빌드 번호 내림차순
        val successes = detailed.builds.all
            .filter { it.result == RESULT_SUCCESS && !it.md5.isNullOrBlank() && it.timestamp > 0 && it.build.toLongOrNull() != null }
            .sortedByDescending { it.build.toLongOrNull() }
        val eligible = successes.filter { it.build !in knownBuilds }
        val selected = when (settings.hashMode) {
            // 최신 SUCCESS 빌드 하나. 그것이 이미 있으면 더 오래된 빌드로 내려가지 않는다 (백필 금지)
            PurpurHashMode.LATEST -> successes.take(1).filter { it.build !in knownBuilds }

            PurpurHashMode.NEW -> {
                val highestKnown = knownBuilds.mapNotNull { it.toLongOrNull() }.maxOrNull()
                if (highestKnown == null) eligible.take(1) else eligible.filter { (it.build.toLongOrNull() ?: 0L) > highestKnown }
            }

            PurpurHashMode.BACKFILL -> eligible

            PurpurHashMode.OFF -> emptyList()
        }

        val rows = ArrayList<CoreBuildRow>()
        var keepGoing = true
        for (b in selected) {
            val md5 = b.md5 ?: continue
            if (jarsLeft <= 0 || bytesLeft <= 0) {
                keepGoing = false
                break
            }
            val buildKey = "$v/${b.build}"
            // 오늘 이미 md5 가 틀렸던 빌드는 다시 받지 않는다 (같은 64 MiB 를 15분마다 받는 것 방지)
            if (buildKey in mismatchedToday) {
                report.inc("skip.md5MismatchToday")
                continue
            }
            val url = "$base/v2/purpur/$v/${b.build}/download"
            val length = when (val h = ctx.http.head(url)) {
                is HttpResult.Ok -> h.meta.contentLength

                // HEAD 자체 실패는 크기 초과가 아니라 실패다 (PARTIAL)
                is HttpResult.NotModified -> {
                    fail(buildKey, "HEAD HTTP 304")
                    continue
                }

                is HttpResult.Status -> {
                    fail(buildKey, "HEAD HTTP ${h.meta.status}")
                    continue
                }

                is HttpResult.Failure -> {
                    fail(buildKey, "HEAD ${h.kind} ${h.message}")
                    continue
                }
            }
            if (length == null || length > settings.maxJarBytes) {
                report.inc("skip.tooLarge")
                continue
            }
            if (length > bytesLeft) {
                keepGoing = false
                break
            }
            val request = DownloadRequest(
                maxBytes = settings.maxJarBytes,
                algorithms = setOf(DigestAlgo.SHA256, DigestAlgo.MD5),
                expected = mapOf(DigestAlgo.MD5 to md5),
            )
            when (val d = ctx.http.digest(url, request)) {
                is HttpResult.Ok -> {
                    charge(d.value.size)
                    val sha256 = d.value.digests[DigestAlgo.SHA256]
                    if (sha256 == null) {
                        fail(buildKey, "sha256 없음")
                        continue
                    }
                    val channel = if (b.metadata["type"] == TYPE_EXPERIMENTAL) Channel.EXPERIMENTAL else Channel.STABLE
                    rows += CoreBuildRow(v, b.build, channel, url, sha256.lowercase(), d.value.size, Instant.fromEpochMilliseconds(b.timestamp))
                    report.inc("hashed")
                    report.inc("hashed.bytes", d.value.size)
                }

                // 본문을 이미 받은 실패도 예산에 넣는다. 중간 끊김은 받은 양을 모르므로 HEAD 길이(상한)로 센다
                is HttpResult.Failure -> when (d.kind) {
                    FailureKind.DIGEST_MISMATCH -> {
                        mismatchedToday += buildKey
                        charge(length)
                        report.inc("skip.md5Mismatch")
                        report.warn("purpur $buildKey: md5 불일치 (${d.message})")
                    }

                    // maxBytes 에 닿아 멈췄으므로 maxJarBytes 만큼 받았다
                    FailureKind.TOO_LARGE -> {
                        charge(settings.maxJarBytes)
                        fail(buildKey, "${d.kind} ${d.message}")
                    }

                    FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.IO -> {
                        charge(length)
                        fail(buildKey, "${d.kind} ${d.message}")
                    }

                    // 본문을 받지 않은 실패
                    FailureKind.BAD_RANGE, FailureKind.CIRCUIT_OPEN -> fail(buildKey, "${d.kind} ${d.message}")
                }

                // 2xx 가 아닌 응답은 jar 본문을 받지 않았다
                is HttpResult.Status -> fail(buildKey, "HTTP ${d.meta.status}")

                is HttpResult.NotModified -> fail(buildKey, "HTTP 304")
            }
        }
        if (!keepGoing) report.inc("budget.exhausted")

        // ── 버전 단위로 바로 upsert ──
        if (rows.isNotEmpty()) {
            val count = ctx.store.upsertCoreBuilds(CoreKey.PURPUR, rows)
            report.inc("inserted", count.inserted.toLong())
            report.inc("updated", count.updated.toLong())
            report.inc("unchanged", count.unchanged.toLong())
            report.inc("skipped", count.skipped.toLong())
        }
        return keepGoing
    }

    /**
     * jar 본문 하나를 받은 만큼 예산에서 뺀다 (성공·실패 모두). 일일 누적은 바로 상태에 쓴다 —
     * 중간에 죽어도, 계속 실패하는 빌드가 있어도 일일 상한이 우회되지 않게.
     */
    private suspend fun charge(bytes: Long) {
        jarsLeft--
        bytesLeft -= bytes
        usedToday += bytes
        val state = PurpurBudgetState(usedToday, mismatchedToday.toList())
        ctx.store.putState(budgetKey, CollectorJson.encodeToString(PurpurBudgetState.serializer(), state))
    }

    private fun fail(buildKey: String, reason: String) {
        report.inc("failed")
        report.warn("purpur $buildKey: $reason")
    }

    private sealed interface Fetched<out T> {
        data class Ok<T>(val value: T) : Fetched<T>

        data class Failed(val reason: String) : Fetched<Nothing>
    }

    private suspend fun <T> getJson(url: String, serializer: KSerializer<T>): Fetched<T> = when (val r = ctx.http.get(url)) {
        is HttpResult.Ok -> decode(serializer, r.value.decodeToString())?.let { Fetched.Ok(it) } ?: Fetched.Failed("JSON 해석 실패")
        is HttpResult.NotModified -> Fetched.Failed("HTTP 304")
        is HttpResult.Status -> Fetched.Failed("HTTP ${r.meta.status}")
        is HttpResult.Failure -> Fetched.Failed("${r.kind}: ${r.message}")
    }
}

private fun <T> decode(serializer: KSerializer<T>, text: String?): T? {
    if (text == null) return null
    return try {
        CollectorJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
