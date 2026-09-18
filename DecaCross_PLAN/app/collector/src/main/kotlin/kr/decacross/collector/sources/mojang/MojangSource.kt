package kr.decacross.collector.sources.mojang

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.config.JarMetaMode
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.Conditional
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.store.McFacts
import kr.decacross.collector.store.McInsertResult
import kr.decacross.collector.store.McRow
import kr.decacross.collector.store.NewMcVersion
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Mojang `version_manifest_v2.json` → mc_versions (서수 발급) + client/server jar 의 version.json/pack.mcmeta 를
 * HTTP Range 로 읽어 rp/dp 포맷·protocol 을 채운다. jar 전체를 받지 않는다.
 */
class MojangSource : CollectorSource {
    override val id: SourceId = SourceId.MOJANG
    override val interval: Duration = 5.minutes
    override val dependsOn: Set<SourceId> = emptySet()
    override val required: Boolean = true

    override suspend fun collect(ctx: CollectContext): SourceReport = MojangRun(ctx).run()
}

/** AE-9: `javaVersion` 이 없는 per-version JSON(전부 2013년 무렵)의 최소 Java. 런처 기본 런타임. */
internal const val JAVA_FALLBACK_MAJOR: Int = 8

/** 빈 비개발 DB 에 최초 서수 시딩을 막는 메시지 (D46). */
internal const val SEED_REQUIRED_MESSAGE: String = "빈 mc_versions 에 최초 서수 시딩은 --seed-ordinals 필요 (SCP-2 결정 후)"

private const val TYPE_RELEASE = "release"
private const val TYPE_SNAPSHOT = "snapshot"
private const val MAX_FAILED_LABELS_IN_WARNING = 10
private const val MAX_OVERFLOW_LABELS_LISTED = 5
private const val BACKOFF_MAX_EXPONENT = 16
private val JARMETA_BACKOFF_BASE: Duration = 15.minutes
private val JARMETA_BACKOFF_MAX: Duration = 24.hours

/** 같은 client jar 면 다시 읽지 않는 jar 메타 상태. */
private val JARMETA_DONE: Set<String> = setOf(JarMetaStatus.FOUND.name, JarMetaStatus.NONE.name, JarMetaStatus.TOO_LARGE.name)

private val log = LoggerFactory.getLogger(MojangSource::class.java)

private sealed interface VersionJsonFetch {
    data class Ok(val json: VersionJson) : VersionJsonFetch

    data class Failed(val reason: String) : VersionJsonFetch
}

/** 발급 단계 결과. */
private sealed interface Issuance {
    /** 소스 FAILED. 아무것도 INSERT 하지 않았다. */
    data class Failed(val error: String) : Issuance

    data object Done : Issuance
}

/**
 * 한 번의 Mojang 수집 (§9.2 단계 1~8).
 *
 * # 불변식
 * - 서수는 [OrdinalIssuer] 계획의 INSERT 로만 생긴다. 기존 행은 releaseTime 이 달라도 바꾸지 않는다 (D7, 경고만).
 * - `mojang.manifest` ETag 는 발급이 완전히 끝났을 때만 기록한다 (D12).
 */
private class MojangRun(private val ctx: CollectContext) {
    private val settings = ctx.settings.mojang
    private val report = ReportBuilder(SourceId.MOJANG)

    suspend fun run(): SourceReport {
        // ── 1. 매니페스트 (--loop 만 조건부) ──
        val stored = if (ctx.mode == RunMode.LOOP) decodeStateOrNull(ManifestState.serializer(), ctx.store.getState(MANIFEST_STATE_KEY)) else null
        val conditional = stored?.takeIf { it.etag != null || it.lastModified != null }?.let { Conditional(it.etag, it.lastModified) }
        var manifestById: Map<String, Manifest.Entry>? = null
        when (val r = ctx.http.get(settings.manifestUrl, conditional)) {
            is HttpResult.NotModified -> report.inc("manifest.notModified")

            is HttpResult.Status -> return report.build(SourceStatus.FAILED, "manifest: HTTP ${r.meta.status} ${r.bodySnippet.take(200)}")

            is HttpResult.Failure -> return report.build(SourceStatus.FAILED, "manifest: ${r.kind} ${r.message}")

            is HttpResult.Ok -> {
                val manifest = decodeOrNull(Manifest.serializer(), r.value)
                    ?: return report.build(SourceStatus.FAILED, "manifest: JSON 해석 실패")
                when (val issuance = issue(manifest, ManifestState(r.meta.etag, r.meta.lastModified))) {
                    is Issuance.Failed -> return report.build(SourceStatus.FAILED, issuance.error)
                    Issuance.Done -> Unit
                }
                manifestById = manifest.versions.associateBy { it.id }
            }
        }

        // ── 7. jar 메타 ──
        if (settings.jarMeta != JarMetaMode.OFF) readJarMetaBacklog(manifestById)

        // ── 8. 상태 ──
        val partial = PARTIAL_COUNTERS.any { report.count(it) > 0 }
        return report.build(if (partial) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    /** 단계 2~6: 항목 → 기존 행 → 계획 → per-version JSON → INSERT. */
    private suspend fun issue(manifest: Manifest, newManifestState: ManifestState): Issuance {
        // ── 2. 항목 (release, includeSnapshots 면 snapshot. old_alpha/old_beta 는 무시) ──
        val entryByLabel = LinkedHashMap<String, Manifest.Entry>()
        val issuerEntries = ArrayList<IssuerEntry>()
        for (e in manifest.versions) {
            val snapshot = e.type == TYPE_SNAPSHOT
            if (e.type != TYPE_RELEASE && !(snapshot && settings.includeSnapshots)) continue
            val releasedAt = parseInstantOrNull(e.releaseTime)
            if (releasedAt == null) {
                report.inc("manifest.badReleaseTime")
                report.warn("mojang.badReleaseTime ${e.id} '${e.releaseTime}'")
                continue
            }
            if (entryByLabel.putIfAbsent(e.id, e) != null) {
                report.inc("manifest.duplicateId")
                continue
            }
            issuerEntries += IssuerEntry(e.id, snapshot, releasedAt)
        }

        // ── 3. 기존 행 ──
        val existing = ctx.store.mcIndex()
        if (existing.isEmpty() && !(ctx.store.isDevDatabase || settings.allowInitialSeed)) {
            return Issuance.Failed(SEED_REQUIRED_MESSAGE)
        }
        checkDrift(existing, manifest)

        // ── 4. 계획 ──
        val existingOrdinals = existing.map { ExistingOrdinal(it.label, it.ordinal, it.isSnapshot, it.releasedAt) }
        val plan = when (val p = OrdinalIssuer.plan(existingOrdinals, issuerEntries)) {
            is PlanResult.Aborted -> return Issuance.Failed(p.reason)
            is PlanResult.Planned -> p.plan
        }
        report.inc("plan.issued", plan.issued.size.toLong())
        reportSkips(plan)

        // ── 5. per-version JSON ──
        val planned = plan.issued.mapNotNull { (e, _) -> entryByLabel[e.label] }
        val results = fetchAll(planned)
        val failed = planned.filter { results[it.id] !is VersionJsonFetch.Ok }
        val failedLabels = failed.mapTo(LinkedHashSet()) { it.id }
        val retained = OrdinalIssuer.retain(plan, failedLabels)
        val dropped = plan.issued.size - retained.size - failedLabels.size
        if (failedLabels.isNotEmpty()) {
            report.inc("versionJson.failed", failedLabels.size.toLong())
            val listed = failed.take(MAX_FAILED_LABELS_IN_WARNING).joinToString(", ") { e ->
                "${e.id} (${(results[e.id] as? VersionJsonFetch.Failed)?.reason ?: "결과 없음"})"
            }
            report.warn("mojang.versionJson 실패 ${failedLabels.size}건 (다음 실행에서 이어서 발급): $listed")
        }
        if (dropped > 0) report.inc("versionJson.dropped", dropped.toLong())

        // ── 6. INSERT ──
        val rows = retained.mapNotNull { (e, ordinal) ->
            val vj = (results[e.label] as? VersionJsonFetch.Ok)?.json ?: return@mapNotNull null
            val javaMin = vj.javaVersion?.majorVersion ?: JAVA_FALLBACK_MAJOR
            NewMcVersion(
                label = e.label,
                ordinal = ordinal,
                releasedAt = e.releasedAt,
                isSnapshot = e.isSnapshot,
                javaMin = javaMin,
                javaRecommended = javaMin,
                clientJarUrl = vj.downloads.client.url,
                clientJarSha1 = vj.downloads.client.sha1,
            )
        }
        if (rows.isNotEmpty()) {
            when (val inserted = ctx.store.insertMcVersions(rows)) {
                is McInsertResult.Conflict -> return Issuance.Failed("mc_versions INSERT 충돌 (계획이 낡음): ${inserted.detail}")
                is McInsertResult.Inserted -> report.inc("issue.inserted", inserted.count.toLong())
            }
            for (row in rows) {
                val entry = entryByLabel[row.label] ?: continue
                val vj = (results[row.label] as? VersionJsonFetch.Ok)?.json ?: continue
                ctx.store.putState(versionStateKey(row.label), encodeState(VersionState.serializer(), VersionState.of(entry, vj)))
            }
        }
        // D12: 떨어뜨린 항목이 없을 때만 ETag 를 남긴다. 아니면 다음 사이클이 무조건 요청으로 나머지를 발급한다
        if (failedLabels.isEmpty() && dropped == 0) {
            ctx.store.putState(MANIFEST_STATE_KEY, encodeState(ManifestState.serializer(), newManifestState))
        }
        return Issuance.Done
    }

    /** 기존 행의 releaseTime 이 매니페스트와 다르면 경고만 한다 (D7: 서수는 절대 UPDATE 하지 않는다). */
    private fun checkDrift(existing: List<McRow>, manifest: Manifest) {
        if (existing.isEmpty()) return
        val byId = manifest.versions.associateBy { it.id }
        for (row in existing) {
            val e = byId[row.label] ?: continue
            val at = parseInstantOrNull(e.releaseTime) ?: continue
            if (at != row.releasedAt) {
                report.inc("drift")
                report.warn("mojang.drift ${row.label}: DB ${row.releasedAt} ≠ manifest $at (서수 유지)")
            }
        }
    }

    /** SCP-19: SLOT_OVERFLOW 는 예상된 결과라 카운트 + 예시 5개를 담은 한 줄, 나머지 사유는 label 마다 경고. 릴리스 역전(INV-2)도 릴리스마다 경고. */
    private fun reportSkips(plan: OrdinalPlan) {
        for ((reason, labels) in plan.skipped) {
            report.inc("skip.${reason.name}", labels.size.toLong())
            if (reason == SkipReason.SLOT_OVERFLOW) {
                val examples = labels.take(MAX_OVERFLOW_LABELS_LISTED).joinToString(", ")
                report.warn("mojang.skip SLOT_OVERFLOW ${labels.size}건 (예: $examples)")
                log.info("mojang skip SLOT_OVERFLOW {}건 (예: {})", labels.size, examples)
            } else {
                for (label in labels) report.warn("mojang.skip ${reason.name} $label")
            }
        }
        // INV-2: 늦게 온 릴리스는 발급하되, 그보다 늦게 나왔는데 서수가 더 작게 남는 기존 스냅샷을 드러낸다 (서수는 바꾸지 않는다)
        for ((release, snapshots) in plan.releaseInversions) {
            report.inc("issue.releaseAfterIssuedSnapshots")
            report.warn("mojang.inversion $release: 더 늦게 나온 스냅샷 ${snapshots.size}건의 서수가 이 릴리스보다 작게 남는다 (서수 유지): ${snapshots.joinToString(", ")}")
        }
    }

    /** 동시에 최대 `versionJsonConcurrency` 개씩 받고, 실패한 것은 끝에서 순서대로 한 번 더 받는다. */
    private suspend fun fetchAll(planned: List<Manifest.Entry>): Map<String, VersionJsonFetch> {
        val results = ConcurrentHashMap<String, VersionJsonFetch>()
        val permits = Semaphore(settings.versionJsonConcurrency.coerceAtLeast(1))
        coroutineScope {
            for (entry in planned) {
                launch { permits.withPermit { results[entry.id] = fetchVersionJson(entry) } }
            }
        }
        // 회로가 열려 있어도 기다리지 않는다 — CIRCUIT_OPEN 이면 그대로 실패로 남는다 (다음 실행이 이어서 발급)
        for (entry in planned) {
            if (results[entry.id] is VersionJsonFetch.Ok) continue
            report.inc("versionJson.retried")
            results[entry.id] = fetchVersionJson(entry)
        }
        return results
    }

    /** URL 은 매니페스트 문자열 그대로 (`%20` 보존). sha1 이 매니페스트와 다르면 실패. */
    private suspend fun fetchVersionJson(entry: Manifest.Entry): VersionJsonFetch = when (val r = ctx.http.get(entry.url)) {
        is HttpResult.Ok -> {
            val actual = sha1Hex(r.value)
            if (actual != entry.sha1.lowercase()) {
                VersionJsonFetch.Failed("sha1 불일치 $actual ≠ ${entry.sha1}")
            } else {
                decodeOrNull(VersionJson.serializer(), r.value)?.let { VersionJsonFetch.Ok(it) } ?: VersionJsonFetch.Failed("JSON 해석 실패")
            }
        }

        is HttpResult.NotModified -> VersionJsonFetch.Failed("HTTP 304")

        is HttpResult.Status -> VersionJsonFetch.Failed("HTTP ${r.meta.status}")

        is HttpResult.Failure -> VersionJsonFetch.Failed("${r.kind}: ${r.message}")
    }

    /**
     * 단계 7: 서수 내림차순으로 jar 메타가 필요한 행을 최대 `maxJarMetaPerCycle` 개 순차로 읽는다.
     * [manifestById] 가 null 이면 이번 사이클에 매니페스트가 바뀌지 않은 것 — 버전 상태가 없는 label 은 건너뛴다.
     */
    private suspend fun readJarMetaBacklog(manifestById: Map<String, Manifest.Entry>?) {
        val now = ctx.clock.now()
        val rows = ctx.store.mcIndex()
            .filter { settings.jarMeta == JarMetaMode.ALL || !it.isSnapshot }
            .sortedByDescending { it.ordinal }
        var taken = 0
        for (row in rows) {
            val label = row.label
            val previous = decodeStateOrNull(JarMetaState.serializer(), ctx.store.getState(jarMetaStateKey(label)))
            var version = decodeStateOrNull(VersionState.serializer(), ctx.store.getState(versionStateKey(label)))
            val entry = manifestById?.get(label)
            // 매니페스트의 per-version JSON sha1 이 바뀌었으면 저장된 jar 위치를 믿지 않는다 (D7: 상태만 갱신)
            val stale = version != null && entry != null && !version.jsonSha1.equals(entry.sha1, ignoreCase = true)
            val currentSha1 = if (stale) null else version?.clientSha1 ?: row.clientJarSha1
            if (currentSha1 != null && isSettled(previous, currentSha1, now)) continue

            // jar 위치를 알 수 없는 label(상태 없음 + 이번 사이클 매니페스트 미변경)은 예산과 무관하게 건너뛴다.
            // 먼저 거르지 않으면 예산이 찼을 때 deferred 로 세어 고칠 수 없는 PARTIAL 이 매 사이클 반복된다
            if (version == null && entry == null) {
                report.inc("jarmeta.noVersionState")
                continue
            }
            if (taken >= settings.maxJarMetaPerCycle) {
                report.inc("jarmeta.deferred")
                continue
            }
            if (version == null || stale) {
                if (entry == null) {
                    report.inc("jarmeta.noVersionState")
                    continue
                }
                val fresh = when (val f = fetchVersionJson(entry)) {
                    is VersionJsonFetch.Failed -> {
                        report.inc("jarmeta.versionJsonFailed")
                        report.warn("mojang.jarmeta $label: per-version JSON 실패 (${f.reason})")
                        continue
                    }

                    is VersionJsonFetch.Ok -> VersionState.of(entry, f.json)
                }
                ctx.store.putState(versionStateKey(label), encodeState(VersionState.serializer(), fresh))
                version = fresh
                if (isSettled(previous, fresh.clientSha1, now)) continue
            }
            taken++
            val outcome = readJarMeta(ctx.http, settings, version.server(), version.client())
            record(label, version, previous, outcome, now)
        }
    }

    /** 같은 client jar 를 이미 끝냈거나(FOUND/NONE/TOO_LARGE), FAILED 백오프 중이면 true. */
    private fun isSettled(previous: JarMetaState?, clientSha1: String, now: Instant): Boolean {
        if (previous == null || !previous.clientSha1.equals(clientSha1, ignoreCase = true)) return false
        if (previous.status in JARMETA_DONE) return true
        if (previous.status == JarMetaStatus.FAILED.name) {
            val next = previous.nextAttemptAt?.let { parseInstantOrNull(it) } ?: return false
            if (next > now) {
                report.inc("jarmeta.backoff")
                return true
            }
        }
        return false
    }

    private suspend fun record(label: String, version: VersionState, previous: JarMetaState?, outcome: JarMetaOutcome, now: Instant) {
        val facts = outcome.facts
        when (outcome.status) {
            JarMetaStatus.FOUND -> {
                ctx.store.updateMcFacts(label, McFacts(facts?.rp, facts?.dp, facts?.protocol))
                report.inc("jarmeta.found")
            }

            JarMetaStatus.NONE -> {
                ctx.store.updateMcFacts(label, McFacts(null, null, null))
                report.inc("jarmeta.none")
            }

            // 모름 ≠ 없음 → 사실은 건드리지 않는다
            JarMetaStatus.TOO_LARGE -> {
                report.inc("jarmeta.tooLarge")
                report.warn("mojang.jarmeta TOO_LARGE $label: ${outcome.reason.orEmpty()}")
            }

            JarMetaStatus.FAILED -> {
                report.inc("jarmeta.failed")
                report.warn("mojang.jarmeta FAILED $label: ${outcome.reason.orEmpty()}")
            }
        }
        report.inc("jarmeta.bytes", outcome.bytes)
        report.inc("jarmeta.requests", outcome.requests.toLong())

        var attempts: Int? = null
        var nextAttemptAt: String? = null
        if (outcome.status == JarMetaStatus.FAILED) {
            // 같은 client jar 의 연속 실패만 누적한다
            val previousAttempts = previous
                ?.takeIf { it.status == JarMetaStatus.FAILED.name && it.clientSha1.equals(version.clientSha1, ignoreCase = true) }
                ?.attempts ?: 0
            val n = previousAttempts + 1
            attempts = n
            nextAttemptAt = (now + jarMetaBackoff(n)).toString()
        }
        // S7: FOUND/NONE 상태의 rp/dp/protocol 은 mc_versions 에 쓴 값과 같아야 한다 → NONE 은 셋 다 null
        val none = outcome.status == JarMetaStatus.NONE
        val state = JarMetaState(
            clientSha1 = version.clientSha1,
            status = outcome.status.name,
            jar = outcome.jar,
            era = facts?.era,
            rp = if (none) null else facts?.rp?.toString(),
            dp = if (none) null else facts?.dp?.toString(),
            protocol = if (none) null else facts?.protocol,
            worldVersion = facts?.worldVersion,
            seriesId = facts?.seriesId,
            bytes = outcome.bytes,
            requests = outcome.requests,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            reason = outcome.reason,
        )
        ctx.store.putState(jarMetaStateKey(label), encodeState(JarMetaState.serializer(), state))
    }

    private companion object {
        /** 하나라도 0 보다 크면 PARTIAL. */
        val PARTIAL_COUNTERS = listOf(
            "versionJson.failed",
            "versionJson.dropped",
            "jarmeta.failed",
            "jarmeta.deferred",
            "jarmeta.versionJsonFailed",
        )
    }
}

/** FAILED 재시도 간격: `min(24 h, 15 min · 2^(attempts − 1))`. */
internal fun jarMetaBackoff(attempts: Int): Duration {
    val exponent = (attempts - 1).coerceIn(0, BACKOFF_MAX_EXPONENT)
    return minOf(JARMETA_BACKOFF_MAX, JARMETA_BACKOFF_BASE * (1 shl exponent))
}

private fun sha1Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))

private fun <T> decodeOrNull(serializer: KSerializer<T>, bytes: ByteArray): T? = try {
    CollectorJson.decodeFromString(serializer, bytes.decodeToString())
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** ISO-8601 (오프셋 포함). 해석할 수 없으면 null. */
internal fun parseInstantOrNull(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: IllegalArgumentException) {
    null
}
