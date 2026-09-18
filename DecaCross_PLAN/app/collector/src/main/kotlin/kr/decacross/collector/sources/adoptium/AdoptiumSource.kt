package kr.decacross.collector.sources.adoptium

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.Conditional
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.store.JavaRuntimeRow
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.Os
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Adoptium API v3 `assets/latest` → java_runtimes (0008). 기능 버전 목록은 mc_versions 에서 온다 (하드코딩 금지). */
class AdoptiumSource : CollectorSource {
    override val id: SourceId = SourceId.ADOPTIUM
    override val interval: Duration = 24.hours
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = AdoptiumRun(ctx).run()
}

internal fun adoptiumLatestStateKey(feature: Int): String = "adoptium.latest.$feature"

private const val MAX_COVERAGE_WARNINGS = 10
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** API 원문 → 모델. 대상이 아니면 null. */
private fun osOf(raw: String): Os? = when (raw) {
    "windows" -> Os.WINDOWS
    "mac" -> Os.MAC
    "linux" -> Os.LINUX
    else -> null
}

private fun archOf(raw: String): Arch? = when (raw) {
    "x64" -> Arch.X64
    "aarch64" -> Arch.AARCH64
    else -> null
}

private fun imageTypeOf(raw: String): ImageType? = when (raw) {
    "jre" -> ImageType.JRE
    "jdk" -> ImageType.JDK
    else -> null
}

/** 경고 문구용 API 표기 (`windows/x64`). */
private fun Os.apiName(): String = name.lowercase()

private fun Arch.apiName(): String = name.lowercase()

/**
 * 한 번의 Adoptium 수집 (§9.5, D23).
 *
 * # 불변식
 * - 기능 버전은 DB(`java_min ∪ java_recommended`)에서만 온다. DB 가 비었을 때만 API 의 LTS 목록을 쓰고 경고한다.
 * - 행이 없는 조합은 "그 바이너리가 없음"을 뜻한다 (예: JRE 16). 만들어 넣지 않고 coverage.missing 으로 센다.
 */
private class AdoptiumRun(private val ctx: CollectContext) {
    private val settings = ctx.settings.adoptium
    private val base = settings.baseUrl.trimEnd('/')
    private val report = ReportBuilder(SourceId.ADOPTIUM)
    private var coverageWarnings = 0
    private var badUpdatedAtWarned = false

    suspend fun run(): SourceReport {
        // ── 1. 제공 기능 버전 ──
        val available = when (val r = ctx.http.get("$base/v3/info/available_releases")) {
            is HttpResult.Ok -> decode(AvailableReleases.serializer(), r.value.decodeToString())
                ?: return report.build(SourceStatus.FAILED, "available_releases: JSON 해석 실패")

            is HttpResult.NotModified -> return report.build(SourceStatus.FAILED, "available_releases: HTTP 304")

            is HttpResult.Status -> return report.build(SourceStatus.FAILED, "available_releases: HTTP ${r.meta.status}")

            is HttpResult.Failure -> return report.build(SourceStatus.FAILED, "available_releases: ${r.kind} ${r.message}")
        }

        // ── 2. 대상 기능 버전 ──
        val inUse = ctx.store.javaFeaturesInUse()
        val features: List<Int> = if (inUse.isEmpty()) {
            report.warn("adoptium: mc_versions 에 Java 기능 버전이 없어 LTS 목록 ${available.available_lts_releases} 을 쓴다")
            available.available_lts_releases.distinct().sorted()
        } else {
            val offered = available.available_releases.toSet()
            for (f in (inUse - offered).sorted()) {
                report.inc("features.unavailable")
                report.warn("adoptium: Java $f 는 available_releases 에 없다")
            }
            inUse.filter { it in offered }.sorted()
        }
        report.inc("features", features.size.toLong())

        // ── 3~7. 기능 버전별 (순차) ──
        for (f in features) processFeature(f)

        return report.build(if (report.count("latest.failed") > 0) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    private suspend fun processFeature(feature: Int) {
        val url = "$base/v3/assets/latest/$feature/hotspot?vendor=eclipse"
        val stateKey = adoptiumLatestStateKey(feature)
        val stored = decode(AdoptiumLatestState.serializer(), ctx.store.getState(stateKey))
        val conditional = stored?.takeIf { it.etag != null || it.lastModified != null }?.let { Conditional(it.etag, it.lastModified) }
        val r = ctx.http.get(url, conditional)
        if (r is HttpResult.NotModified) {
            report.inc("latest.notModified")
            return
        }
        val assets = (r as? HttpResult.Ok)?.let { decode(ListSerializer(AdoptAsset.serializer()), it.value.decodeToString()) }
        if (r !is HttpResult.Ok || assets == null) {
            report.inc("latest.failed")
            report.warn("adoptium latest $feature: ${describe(r)}")
            return
        }

        // ── 4~5. 거르기 → 행 ──
        val rows = LinkedHashMap<List<Any>, JavaRuntimeRow>()
        for (a in assets) {
            val row = toRow(a)
            if (row == null) {
                report.inc("assets.excluded")
                continue
            }
            // 같은 upsert 키가 두 번 오면 첫 행만 (한 문장에서 같은 행을 두 번 갱신하지 않게)
            val key = listOf(row.distribution, row.feature, row.os, row.arch, row.imageType, row.releaseName)
            if (rows.putIfAbsent(key, row) != null) report.inc("assets.duplicate")
        }

        // ── 6. 커버리지 ──
        for (os in Os.entries.filter { it in settings.oses }) {
            for (arch in Arch.entries.filter { it in settings.arches }) {
                for (image in ImageType.entries.filter { it in settings.imageTypes }) {
                    if (rows.values.none { it.feature == feature && it.os == os && it.arch == arch && it.imageType == image }) {
                        report.inc("coverage.missing")
                        if (coverageWarnings < MAX_COVERAGE_WARNINGS) {
                            coverageWarnings++
                            report.warn("${image.name} $feature ${os.apiName()}/${arch.apiName()} 없음")
                        }
                    }
                }
            }
        }

        // ── 7. upsert → ETag ──
        if (rows.isNotEmpty()) {
            val count = ctx.store.upsertJavaRuntimes(rows.values.toList())
            report.inc("inserted", count.inserted.toLong())
            report.inc("updated", count.updated.toLong())
            report.inc("unchanged", count.unchanged.toLong())
        }
        val meta = r.meta
        if (meta.etag != null || meta.lastModified != null) {
            val state = AdoptiumLatestState(meta.etag, meta.lastModified)
            ctx.store.putState(stateKey, CollectorJson.encodeToString(AdoptiumLatestState.serializer(), state))
        }
    }

    private fun toRow(a: AdoptAsset): JavaRuntimeRow? {
        val b = a.binary
        val os = osOf(b.os)?.takeIf { it in settings.oses } ?: return null
        val arch = archOf(b.architecture)?.takeIf { it in settings.arches } ?: return null
        val image = imageTypeOf(b.image_type)?.takeIf { it in settings.imageTypes } ?: return null
        if (b.heap_size != "normal" || b.jvm_impl != "hotspot") return null
        val pkg = b.pkg ?: return null
        val sha256 = pkg.checksum?.lowercase()?.takeIf { SHA256_HEX.matches(it) } ?: return null
        val publishedAt = b.updated_at?.let { raw ->
            val parsed = parseInstantOrNull(raw)
            if (parsed == null && !badUpdatedAtWarned) {
                badUpdatedAtWarned = true
                report.warn("adoptium: updated_at 해석 실패 '$raw' (published_at = null)")
            }
            parsed
        }
        return JavaRuntimeRow(
            feature = a.version.major,
            os = os,
            arch = arch,
            imageType = image,
            releaseName = a.release_name,
            openjdkVersion = a.version.openjdk_version,
            packageName = pkg.name,
            downloadUrl = pkg.link,
            sha256 = sha256,
            size = pkg.size,
            publishedAt = publishedAt,
        )
    }
}

private fun describe(r: HttpResult<*>): String = when (r) {
    is HttpResult.Ok -> "JSON 해석 실패"
    is HttpResult.NotModified -> "HTTP 304"
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
