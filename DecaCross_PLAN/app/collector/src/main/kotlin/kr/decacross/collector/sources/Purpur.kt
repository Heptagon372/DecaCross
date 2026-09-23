package kr.decacross.collector.sources

import kotlinx.serialization.Serializable
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.HttpOutcome
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import org.slf4j.LoggerFactory

const val PURPUR_BASE_URL: String = "https://api.purpurmc.org/v2/purpur"

// ── api.purpurmc.org v2 (2026-09 실측) ───────────────────────────────────────

/** `GET /v2/purpur` → `{"project":"purpur","metadata":{"current":"26.2"},"versions":["1.14.1", ...]}` */
@Serializable
data class PurpurProject(val project: String = "purpur", val versions: List<String> = emptyList())

/** `GET /v2/purpur/{v}` → `{"builds":{"latest":"2497","all":["2478", ...]}}` */
@Serializable
data class PurpurVersion(val version: String = "", val builds: PurpurBuilds = PurpurBuilds())

@Serializable
data class PurpurBuilds(val latest: String? = null, val all: List<String> = emptyList())

/**
 * Purpur (선택 소스, `--sources purpur`). 빌드별 상세(`/{v}/{build}`)는 md5 만 주고 size 도 없다 — 빌드마다
 * 호출하지 않고 목록만 쓴다.
 *
 * ★ 제한: `sha256 = ""`, `size = 0`. VERIFY 단계(설치 파이프라인)가 다운로드 후 직접 해시를 계산해야 한다.
 * 채널은 Purpur 에 개념이 없어 전부 STABLE 로 둔다.
 */
class PurpurSource(private val http: CollectorHttp, private val baseUrl: String = PURPUR_BASE_URL) {
    suspend fun versions(): HttpOutcome<List<String>> =
        when (val r = http.getJson<PurpurProject>(baseUrl)) {
            is HttpOutcome.Failed -> r
            is HttpOutcome.Ok -> HttpOutcome.Ok(r.value.versions, r.status)
        }

    suspend fun builds(version: String): HttpOutcome<List<String>> =
        when (val r = http.getJson<PurpurVersion>("$baseUrl/$version")) {
            is HttpOutcome.Failed -> r
            is HttpOutcome.Ok -> HttpOutcome.Ok(r.value.builds.all, r.status)
        }

    fun downloadUrl(version: String, build: String): String = "$baseUrl/$version/$build/download"

    suspend fun collect(mcByLabel: Map<String, McOrdinal>, skipped: MutableList<String> = ArrayList()): List<CoreBuild> {
        val versions = when (val r = versions()) {
            is HttpOutcome.Failed -> {
                log.warn("Purpur 버전 목록 실패: {}", r.message)
                return emptyList()
            }

            is HttpOutcome.Ok -> r.value
        }
        val out = ArrayList<CoreBuild>()
        for (v in versions) {
            val mc = mcByLabel[v]
            if (mc == null) {
                skipped += "purpur:$v"
                continue
            }
            when (val r = builds(v)) {
                is HttpOutcome.Failed -> log.warn("Purpur 빌드 목록 실패 {}: {}", v, r.message)

                is HttpOutcome.Ok -> r.value.mapTo(out) { b ->
                    CoreBuild(CoreKey.PURPUR, mc, b, Channel.STABLE, downloadUrl(v, b), sha256 = "", size = 0)
                }
            }
        }
        log.info("purpur: 빌드 {}건 (버전 {}개, 건너뜀 {}개) — sha256 없음, VERIFY 가 해시해야 함", out.size, versions.size, skipped.size)
        return out
    }

    private companion object {
        val log = LoggerFactory.getLogger(PurpurSource::class.java)
    }
}
