package kr.decacross.collector.analysis

import kr.decacross.analysis.JarMeta
import kr.decacross.analysis.inferCapabilities
import kr.decacross.analysis.readJarMeta
import kr.decacross.analysis.requiredJavaFeature
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.HttpOutcome
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.Dep
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 분석기 버전. 추론 규칙(jvm-analysis)이나 이 파일의 매핑이 바뀌면 올린다 — 같은 값으로 이미 분석한 버전은 건너뛴다.
 */
const val ANALYZER_VERSION: String = "collector-jar-1"

/** 한 jar 의 분석 결과. [notes] 는 추론 근거·건너뛴 것. */
data class JarAnalysisResult(
    val sha256: String,
    val size: Long,
    val javaMajor: Int?,
    val meta: JarMeta?,
    val deps: List<Dep>,
    val notes: List<String>,
)

/**
 * `--analyze-jars`: 기본 jar 를 임시 경로에 받아 메타·바이트코드·Capability 를 읽고 **즉시 삭제**한다.
 *
 * - `readJarMeta` → apiVersion, depend/softdepend → REQUIRE/OPTIONAL. 이름은 [knownSlugs] 에 소문자로 맞으면 그 slug,
 *   아니면 `Slug(name.lowercase())` (엔진이 모르는 slug 면 해결 시 "없는 패키지" 로 뜬다 — 그게 맞는 신호다).
 * - `requiredJavaFeature` → javaMajor. ★ 메타데이터보다 우선 신뢰 (불변식: ASM 실측값).
 * - `inferCapabilities` → `Dep(PROVIDES, Cap)`. 애매한 건 jvm-analysis 가 이미 뺐다.
 * - sha256 은 다운로드 스트림에서 계산 (Modrinth 는 sha256 을 안 준다).
 */
class JarAnalyzer(
    private val http: CollectorHttp,
    private val tmpDir: Path,
    private val knownSlugs: Set<String>,
) {
    suspend fun analyze(url: String): JarAnalysisResult? {
        Files.createDirectories(tmpDir)
        val jar = tmpDir.resolve("${UUID.randomUUID()}.jar")
        try {
            val dl = when (val r = http.download(url, jar)) {
                is HttpOutcome.Failed -> {
                    log.warn("jar 다운로드 실패 {}: {}", url, r.message)
                    return null
                }

                is HttpOutcome.Ok -> r.value
            }
            val meta = readJarMeta(jar)
            val java = requiredJavaFeature(jar)
            val caps = inferCapabilities(jar, meta)
            val notes = ArrayList<String>()
            notes += caps.notes
            meta?.notes?.let { notes += it }
            val deps = ArrayList<Dep>()
            meta?.depend?.forEach { deps += Dep(DepKind.REQUIRE, DepTarget.Slug(slugFor(it))) }
            meta?.softDepend?.forEach { deps += Dep(DepKind.OPTIONAL, DepTarget.Slug(slugFor(it))) }
            caps.caps.forEach { deps += Dep(DepKind.PROVIDES, DepTarget.Cap(it)) }
            return JarAnalysisResult(dl.sha256, dl.size, java, meta, deps, notes)
        } finally {
            // ★ 분석한 jar 는 즉시 폐기 (CLAUDE.md 모듈 소유권: 메타데이터만 보관)
            runCatching { Files.deleteIfExists(jar) }
        }
    }

    /** plugin.yml 의 이름 → slug. 알려진 slug 와 소문자로 일치하면 그것, 아니면 소문자 이름. */
    fun slugFor(pluginName: String): String {
        val lower = pluginName.lowercase()
        if (lower in knownSlugs) return lower
        val compact = lower.replace("_", "-")
        return if (compact in knownSlugs) compact else lower
    }

    /** [ContentVersion] 에 분석 결과를 입힌다. 메타에서 온 의존과 같은 target 은 중복하지 않는다. */
    fun apply(cv: ContentVersion, r: JarAnalysisResult): ContentVersion {
        val existingTargets = cv.deps.map { it.kind to it.target }.toSet()
        val merged = cv.deps + r.deps.filter { (it.kind to it.target) !in existingTargets }
        return cv.copy(
            sha256 = r.sha256,
            size = r.size,
            javaMajor = r.javaMajor ?: cv.javaMajor,
            apiVersion = r.meta?.apiVersion ?: cv.apiVersion,
            deps = merged,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(JarAnalyzer::class.java)
    }
}
