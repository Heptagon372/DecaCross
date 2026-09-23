package kr.decacross.collector.sources

import kotlinx.serialization.Serializable
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.HttpOutcome
import kr.decacross.collector.JavaRuntimeEntry
import org.slf4j.LoggerFactory

const val ADOPTIUM_BASE_URL: String = "https://api.adoptium.net/v3"

/** 우리가 쓰는 Java feature 목록 (명세 §2 javaMin 후보). 8 | 16 | 17 | 21 | 25. */
val JAVA_FEATURES: List<Int> = listOf(8, 16, 17, 21, 25)

// ── api.adoptium.net v3 (2026-09 실측) ───────────────────────────────────────

@Serializable
data class AdoptiumAvailable(
    val available_releases: List<Int> = emptyList(),
    val available_lts_releases: List<Int> = emptyList(),
    val most_recent_lts: Int? = null,
)

/** `GET /v3/assets/latest/{feature}/hotspot?os=windows&architecture=x64&image_type=jre` → 배열 (보통 1개). */
@Serializable
data class AdoptiumAsset(val release_name: String = "", val binary: AdoptiumBinary = AdoptiumBinary())

@Serializable
data class AdoptiumBinary(
    val os: String = "",
    val architecture: String = "",
    val image_type: String = "",
    val `package`: AdoptiumPackage? = null,
)

@Serializable
data class AdoptiumPackage(val name: String = "", val link: String = "", val checksum: String? = null, val size: Long? = null)

/** Adoptium(Temurin) 최신 JRE 목록. 보고용 `java-runtimes.json` 만 만든다 (엔진 픽스처와 무관). */
class AdoptiumSource(private val http: CollectorHttp, private val baseUrl: String = ADOPTIUM_BASE_URL) {
    suspend fun available(): HttpOutcome<AdoptiumAvailable> = http.getJson("$baseUrl/info/available_releases")

    suspend fun latest(feature: Int, os: String = "windows", arch: String = "x64", image: String = "jre"): HttpOutcome<List<AdoptiumAsset>> =
        http.getJson("$baseUrl/assets/latest/$feature/hotspot?os=$os&architecture=$arch&image_type=$image")

    suspend fun collect(features: List<Int> = JAVA_FEATURES): List<JavaRuntimeEntry> {
        val avail = available().let { (it as? HttpOutcome.Ok)?.value }
        val out = ArrayList<JavaRuntimeEntry>()
        for (f in features) {
            if (avail != null && f !in avail.available_releases) {
                log.warn("Adoptium 에 Java {} 없음 (available_releases={})", f, avail.available_releases)
                continue
            }
            when (val r = latest(f)) {
                is HttpOutcome.Failed -> log.warn("Adoptium latest {} 실패: {}", f, r.message)

                is HttpOutcome.Ok -> r.value.firstOrNull()?.let { a ->
                    val pkg = a.binary.`package` ?: return@let
                    out += JavaRuntimeEntry(
                        feature = f,
                        releaseName = a.release_name,
                        os = a.binary.os,
                        arch = a.binary.architecture,
                        imageType = a.binary.image_type,
                        fileName = pkg.name,
                        url = pkg.link,
                        sha256 = pkg.checksum,
                        size = pkg.size,
                    )
                }
            }
        }
        log.info("adoptium: 런타임 {}개", out.size)
        return out
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdoptiumSource::class.java)
    }
}
