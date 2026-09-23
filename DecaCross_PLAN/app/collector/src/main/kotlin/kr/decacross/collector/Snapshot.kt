package kr.decacross.collector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.decacross.compat.db.CompatFixture
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.serial.CompatJson
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = LoggerFactory.getLogger("kr.decacross.collector.Snapshot")

/** 픽스처는 엔진과 같은 규칙(CompatJson) + 들여쓰기. 사람이 diff 를 볼 파일이다. */
val PrettyCompatJson: Json = Json(CompatJson) { prettyPrint = true }

private val reportJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/** `collector-report.json`. 카운트와 폴백·생략 내역. 운영자가 "왜 이 값이 없나" 를 여기서 본다. */
@Serializable
data class CollectorReport(
    val startedAt: String,
    val finishedAt: String,
    val sources: List<String>,
    val dryRun: Boolean = false,
    val counts: Counts = Counts(),
    /** javaVersion 이 없어 8 로 둔 버전들 */
    val javaMinFallback: List<String> = emptyList(),
    /** 서수 없이 생략한 스냅샷 */
    val ordinalOmitted: List<String> = emptyList(),
    /** client.jar 를 읽어 pack 포맷을 채운 버전들 */
    val packFormatsFilled: List<String> = emptyList(),
    /** 우리 mc 목록에 없어 건너뛴 코어 버전 라벨 (project:label) */
    val coreVersionsSkipped: List<String> = emptyList(),
    /** slug 충돌 등으로 건너뛴 콘텐츠 */
    val contentSkipped: List<String> = emptyList(),
    /** 소스별 노트 (표현 못 한 의존 등) */
    val notes: List<String> = emptyList(),
    /** 분석한 jar (slug@version) */
    val jarsAnalyzed: List<String> = emptyList(),
    val javaRuntimes: Int = 0,
    val postgres: String? = null,
) {
    @Serializable
    data class Counts(
        val mcVersions: Int = 0,
        val mcReleases: Int = 0,
        val mcSnapshots: Int = 0,
        val coreBuilds: Int = 0,
        val content: Int = 0,
        val contentVersions: Int = 0,
        val redistributable: Int = 0,
    )
}

/** jar 분석 상태 사이드카 (`<out>.analysis.json`): "slug@version" → analyzerVersion. 같은 값이면 재분석하지 않는다. */
@Serializable
data class AnalysisState(val analyzed: Map<String, String> = emptyMap())

fun analysisKey(cv: ContentVersion): String = "${cv.slug}@${cv.version}"

fun analysisStatePath(out: Path): Path = out.resolveSibling(out.fileName.toString() + ".analysis.json")

fun readAnalysisState(out: Path): AnalysisState {
    val p = analysisStatePath(out)
    if (!Files.isRegularFile(p)) return AnalysisState()
    return runCatching { reportJson.decodeFromString<AnalysisState>(Files.readString(p)) }
        .getOrElse {
            log.warn("분석 상태 파일을 읽지 못함 {}: {}", p, it.message)
            AnalysisState()
        }
}

fun writeAnalysisState(out: Path, state: AnalysisState) {
    Files.writeString(analysisStatePath(out), reportJson.encodeToString(AnalysisState.serializer(), state))
}

/** `--in` 스냅샷 읽기. 없거나 깨졌으면 null (호출자가 최초 실행으로 취급). */
fun readSnapshot(path: Path): CompatFixture? {
    if (!Files.isRegularFile(path)) return null
    return runCatching { CompatFixture.fromJson(Files.readString(path)) }
        .onFailure { log.warn("스냅샷을 읽지 못함 {}: {}", path, it.message) }
        .getOrNull()
}

/** 임시 파일에 쓰고 원자적 이동 — 반쯤 쓴 스냅샷을 남기지 않는다. */
fun writeSnapshot(fixture: CompatFixture, out: Path) {
    out.parent?.let { Files.createDirectories(it) }
    val tmp = out.resolveSibling(out.fileName.toString() + ".tmp")
    Files.writeString(tmp, fixture.toJson(PrettyCompatJson))
    runCatching { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        .recover { Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING) }
        .getOrThrow()
    log.info("스냅샷 저장 {} (mc={}, cores={}, content={}, versions={})", out, fixture.mcVersions.size, fixture.coreBuilds.size, fixture.content.size, fixture.contentVersions.size)
}

fun writeReport(report: CollectorReport, out: Path) {
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, reportJson.encodeToString(CollectorReport.serializer(), report))
}

fun writeJavaRuntimes(entries: List<JavaRuntimeEntry>, out: Path) {
    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, reportJson.encodeToString(entries))
}
