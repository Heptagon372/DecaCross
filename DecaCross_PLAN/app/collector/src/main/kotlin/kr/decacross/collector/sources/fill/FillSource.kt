package kr.decacross.collector.sources.fill

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.Conditional
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.store.CoreBuildRow
import kr.decacross.compat.model.CoreKey
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** PaperMC Fill v3 프로젝트. 프록시(velocity/waterfall)는 대상이 아니다. */
enum class FillProject(val apiId: String, val core: CoreKey, val sourceId: SourceId) {
    PAPER("paper", CoreKey.PAPER, SourceId.PAPER),
    FOLIA("folia", CoreKey.FOLIA, SourceId.FOLIA),
}

/** Fill v3 `/v3/projects/{p}/versions/{v}/builds` → core_builds. sha256·size 는 API 값을 그대로 쓴다. */
class FillSource(val project: FillProject) : CollectorSource {
    override val id: SourceId = project.sourceId
    override val interval: Duration = 15.minutes
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = project == FillProject.PAPER

    override suspend fun collect(ctx: CollectContext): SourceReport = FillRun(project, ctx).run()
}

/** `downloads` 에서 쓰는 유일한 키 (D15: `server:mojang` 은 다른 산출물). */
internal const val FILL_SERVER_DOWNLOAD: String = "server:default"

private const val SUPPORTED = "SUPPORTED"
private val FULL_SWEEP_INTERVAL: Duration = 24.hours
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** Fill 버전 id → URL 경로 세그먼트 (공백은 `%20`). */
internal fun fillPathSegment(id: String): String = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

internal fun fillVersionsStateKey(apiId: String): String = "fill.$apiId.versions"

internal fun fillBuildsStateKey(apiId: String, versionId: String): String = "fill.$apiId.builds.$versionId"

internal fun fillSweepStateKey(apiId: String): String = "fill.$apiId.lastFullSweep"

/** 처리할 버전. [builds] 는 versions 목록에서 온 빌드 번호 (304 로 목록이 없으면 null). */
private data class Candidate(val id: String, val builds: Set<String>?)

/**
 * 한 번의 Fill 수집 (§9.3).
 *
 * # 불변식
 * - core 수집기는 mc_versions 행을 만들지 않는다. label 이 없으면 건너뛰고 그 버전의 ETag 를 저장하지 않는다 (D16).
 * - 채널은 UPDATE 로 승격될 수 있다 (D13) — upsert 가 처리한다.
 * - versions 목록의 ETag·전체 순회 시각은 builds 실패가 없을 때만 기록한다 (실패한 버전이 304 뒤에 묻히지 않게).
 */
private class FillRun(private val project: FillProject, private val ctx: CollectContext) {
    private val base = ctx.settings.fill.baseUrl.trimEnd('/')
    private val apiId = project.apiId
    private val report = ReportBuilder(project.sourceId)
    private val unknownChannels = HashSet<String>()

    suspend fun run(): SourceReport {
        val now = ctx.clock.now()
        val loop = ctx.mode == RunMode.LOOP
        val versionsState = decode(FillVersionsState.serializer(), ctx.store.getState(fillVersionsStateKey(apiId)))
        val lastSweep = decode(FillSweepState.serializer(), ctx.store.getState(fillSweepStateKey(apiId)))?.at?.let { parseInstantOrNull(it) }
        val sweepDue = loop && (lastSweep == null || now - lastSweep >= FULL_SWEEP_INTERVAL)

        // ── 1. versions 목록 (--loop 이고 전체 순회 차례가 아니면 조건부) ──
        val conditional = versionsState
            ?.takeIf { loop && !sweepDue && (it.etag != null || it.lastModified != null) }
            ?.let { Conditional(it.etag, it.lastModified) }
        val versionsUrl = "$base/v3/projects/$apiId/versions"
        var response: FillVersionsResponse? = null
        var responseEtag: String? = null
        var responseLastModified: String? = null
        when (val r = ctx.http.get(versionsUrl, conditional)) {
            is HttpResult.NotModified -> report.inc("versions.notModified")

            is HttpResult.Status -> return report.build(SourceStatus.FAILED, "versions: HTTP ${r.meta.status} ${r.bodySnippet.take(200)}")

            is HttpResult.Failure -> return report.build(SourceStatus.FAILED, "versions: ${r.kind} ${r.message}")

            is HttpResult.Ok -> {
                response = decode(FillVersionsResponse.serializer(), r.value.decodeToString())
                    ?: return report.build(SourceStatus.FAILED, "versions: JSON 해석 실패")
                responseEtag = r.meta.etag
                responseLastModified = r.meta.lastModified
            }
        }

        // ── 2. 조회 ──
        val mcLabels = ctx.store.mcIndex().mapTo(HashSet()) { it.label }
        val known = ctx.store.coreBuildKeys(project.core)

        // ── 3. 후보 ──
        val candidates: List<Candidate> = if (response == null) {
            // 304: 목록 자체는 캐시하지 않는다. 지난번 SUPPORTED id 만 버전별 조건부 요청으로 확인한다
            versionsState?.supported.orEmpty().map { Candidate(it, null) }
        } else {
            response.versions
                .filter { v ->
                    val builds = v.builds.mapTo(HashSet()) { it.toString() }
                    !loop || sweepDue || v.version.support?.status == SUPPORTED || (builds - known[v.version.id].orEmpty()).isNotEmpty()
                }
                .map { v -> Candidate(v.version.id, v.builds.mapTo(HashSet()) { it.toString() }) }
        }
        report.inc("versions.candidates", candidates.size.toLong())

        // ── 4. 버전별 builds (순차) ──
        for (c in candidates) processVersion(c, mcLabels, known[c.id].orEmpty())

        // ── 5. 상태 ──
        val buildsFailed = report.count("builds.failed") > 0
        if (response != null && !buildsFailed) {
            val supported = response.versions.filter { it.version.support?.status == SUPPORTED }.map { it.version.id }
            val state = FillVersionsState(responseEtag, responseLastModified, supported)
            ctx.store.putState(fillVersionsStateKey(apiId), CollectorJson.encodeToString(FillVersionsState.serializer(), state))
            if (!loop || sweepDue) {
                val sweep = FillSweepState(now.toString())
                ctx.store.putState(fillSweepStateKey(apiId), CollectorJson.encodeToString(FillSweepState.serializer(), sweep))
            }
        }
        return report.build(if (buildsFailed) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    private suspend fun processVersion(c: Candidate, mcLabels: Set<String>, knownBuilds: Set<String>) {
        if (c.id !in mcLabels) {
            report.inc("skip.noMcVersion")
            return
        }
        val stateKey = fillBuildsStateKey(apiId, c.id)
        val url = "$base/v3/projects/$apiId/versions/${fillPathSegment(c.id)}/builds"
        val stored = decode(FillBuildsState.serializer(), ctx.store.getState(stateKey))
        val conditional = stored?.takeIf { it.etag != null || it.lastModified != null }?.let { Conditional(it.etag, it.lastModified) }
        var r = ctx.http.get(url, conditional)
        if (r is HttpResult.NotModified) {
            if (knownBuilds.isNotEmpty()) {
                report.inc("builds.notModified")
                return
            }
            // DB 에 이 버전 빌드가 없는데 304 → 상태가 DB 와 어긋났다. 조건 없이 다시 받는다
            r = ctx.http.get(url)
        }
        val builds = (r as? HttpResult.Ok)?.let { decode(ListSerializer(FillBuild.serializer()), it.value.decodeToString()) }
        if (r !is HttpResult.Ok || builds == null) {
            report.inc("builds.failed")
            report.warn("fill.$apiId builds ${c.id}: ${describe(r)}")
            return
        }

        val rows = ArrayList<CoreBuildRow>(builds.size)
        var badTimes = 0
        for (b in builds) {
            val d = b.downloads[FILL_SERVER_DOWNLOAD]
            if (d == null) {
                report.inc("skip.noServerDefault")
                continue
            }
            val sha256 = d.checksums.sha256?.lowercase()
            if (sha256 == null || !SHA256_HEX.matches(sha256) || d.size <= 0) {
                report.inc("skip.badChecksum")
                continue
            }
            val (channel, knownChannel) = foldFillChannel(b.channel)
            if (!knownChannel && unknownChannels.add(b.channel)) {
                report.warn("fill.$apiId 알 수 없는 채널 '${b.channel}' → EXPERIMENTAL")
            }
            val publishedAt = parseInstantOrNull(b.time)
            if (publishedAt == null) badTimes++
            rows += CoreBuildRow(c.id, b.id.toString(), channel, d.url, sha256, d.size, publishedAt)
        }
        if (badTimes > 0) {
            report.inc("publishedAt.unparsed", badTimes.toLong())
            report.warn("fill.$apiId builds ${c.id}: time 해석 실패 ${badTimes}건 (published_at = null)")
        }
        if (rows.isNotEmpty()) {
            val count = ctx.store.upsertCoreBuilds(project.core, rows)
            report.inc("inserted", count.inserted.toLong())
            report.inc("updated", count.updated.toLong())
            report.inc("unchanged", count.unchanged.toLong())
            report.inc("skipped", count.skipped.toLong())
        }
        val meta = r.meta
        if (meta.etag != null || meta.lastModified != null) {
            val state = FillBuildsState(meta.etag, meta.lastModified)
            ctx.store.putState(stateKey, CollectorJson.encodeToString(FillBuildsState.serializer(), state))
        }
    }
}

private fun describe(r: HttpResult<*>): String = when (r) {
    is HttpResult.Ok -> "JSON 해석 실패"
    is HttpResult.NotModified -> "HTTP 304 (조건 없는 요청)"
    is HttpResult.Status -> "HTTP ${r.meta.status}"
    is HttpResult.Failure -> "${r.kind}: ${r.message}"
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

private fun parseInstantOrNull(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: IllegalArgumentException) {
    null
}
