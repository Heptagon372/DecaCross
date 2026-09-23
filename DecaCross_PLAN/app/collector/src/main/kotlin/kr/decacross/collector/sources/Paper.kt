package kr.decacross.collector.sources

import kotlinx.serialization.Serializable
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.HttpOutcome
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import org.slf4j.LoggerFactory

const val FILL_BASE_URL: String = "https://fill.papermc.io/v3/projects"

// ── Fill v3 응답 (2026-09 실측) ───────────────────────────────────────────────

/** `GET /v3/projects/paper` → `versions`: { "26.3": ["26.3", "26.3-rc-3"], "1.21": ["1.21.8", ...] } (계열 → 버전 목록). */
@Serializable
data class FillProject(val project: FillProjectInfo? = null, val versions: Map<String, List<String>> = emptyMap())

@Serializable
data class FillProjectInfo(val id: String? = null, val name: String? = null)

/** `GET /v3/projects/paper/versions/{v}/builds` → 최신 빌드부터 내림차순 배열. */
@Serializable
data class FillBuild(
    val id: Int,
    val time: String? = null,
    /** STABLE | ALPHA | BETA */
    val channel: String = "STABLE",
    /** 키는 "server:default". */
    val downloads: Map<String, FillDownload> = emptyMap(),
)

@Serializable
data class FillDownload(
    val name: String,
    val url: String,
    val size: Long = 0,
    /** {"sha256": "..."} */
    val checksums: Map<String, String> = emptyMap(),
)

/** Fill 채널 → 엔진 채널. STABLE 만 STABLE, ALPHA/BETA/그 외 전부 EXPERIMENTAL (명세 §2 Channel). */
fun fillChannel(raw: String): Channel = if (raw.equals("STABLE", ignoreCase = true)) Channel.STABLE else Channel.EXPERIMENTAL

/** Fill 빌드 한 건 → [CoreBuild]. `server:default` 다운로드가 없으면 null. */
fun FillBuild.toCoreBuild(core: CoreKey, mc: McOrdinal): CoreBuild? {
    val dl = downloads["server:default"] ?: downloads.values.firstOrNull() ?: return null
    return CoreBuild(
        core = core,
        mc = mc,
        build = id.toString(),
        channel = fillChannel(channel),
        downloadUrl = dl.url,
        sha256 = dl.checksums["sha256"].orEmpty(),
        size = dl.size,
    )
}

/**
 * PaperMC Fill v3. [project] 는 paper | folia | velocity … 중 서버 코어만 의미 있다.
 * ★ 불변식 17: 요청은 [CollectorHttp] 의 식별 가능한 UA 로 나간다.
 */
class PaperSource(
    private val http: CollectorHttp,
    private val project: String = "paper",
    private val core: CoreKey = CoreKey.PAPER,
    private val baseUrl: String = FILL_BASE_URL,
) {
    suspend fun versions(): HttpOutcome<List<String>> =
        when (val r = http.getJson<FillProject>("$baseUrl/$project")) {
            is HttpOutcome.Failed -> r
            is HttpOutcome.Ok -> HttpOutcome.Ok(r.value.versions.values.flatten().distinct(), r.status)
        }

    suspend fun builds(version: String): HttpOutcome<List<FillBuild>> = http.getJson("$baseUrl/$project/versions/$version/builds")

    /**
     * 모든 버전의 모든 빌드. [mcByLabel] 에 없는 라벨(예: 우리가 서수를 못 준 rc)은 건너뛰고 [skipped] 에 남긴다.
     */
    suspend fun collect(mcByLabel: Map<String, McOrdinal>, skipped: MutableList<String> = ArrayList()): List<CoreBuild> {
        val versions = when (val r = versions()) {
            is HttpOutcome.Failed -> {
                log.warn("Fill 프로젝트 목록 실패 {}: {}", project, r.message)
                return emptyList()
            }

            is HttpOutcome.Ok -> r.value
        }
        val out = ArrayList<CoreBuild>()
        for (v in versions) {
            val mc = mcByLabel[v]
            if (mc == null) {
                skipped += "$project:$v"
                continue
            }
            when (val r = builds(v)) {
                is HttpOutcome.Failed -> log.warn("Fill 빌드 목록 실패 {} {}: {}", project, v, r.message)
                is HttpOutcome.Ok -> r.value.mapNotNullTo(out) { it.toCoreBuild(core, mc) }
            }
        }
        log.info("{}: 빌드 {}건 (버전 {}개, 건너뜀 {}개)", project, out.size, versions.size, skipped.size)
        return out
    }

    private companion object {
        val log = LoggerFactory.getLogger(PaperSource::class.java)
    }
}
