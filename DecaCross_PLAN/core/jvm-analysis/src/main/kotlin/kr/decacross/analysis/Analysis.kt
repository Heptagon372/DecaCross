package kr.decacross.analysis

import java.io.IOException
import java.nio.file.Path

/**
 * 분석기(코드) 버전. 파서·규칙 코드가 바뀌면 올린다.
 * 수집기는 여기에 규칙 리소스 지문을 붙인 **유효 버전**(`0.1.0+rules.<sha256 앞 8자>`)을
 * `content_versions.analyzer_version` 에 기록하고, 그 값이 다르면 재분석한다 (규칙 리소스만 바뀌어도 재분석되게).
 */
public const val ANALYZER_VERSION: String = "0.1.0"

/** jar 하나를 한 번에 분석한 결과. 수집기가 저장하는 단위다. */
public data class JarAnalysis(
    val descriptors: JarDescriptors,
    val bytecode: BytecodeProfile,
    /** STORE 와 CANDIDATE 모두. 저장하는 쪽이 STORE 만 골라 쓴다 ([EvidenceConfidence] 불변식). */
    val capabilities: List<CapabilityEvidence>,
    /** jar 루트 `pack.mcmeta` 가 있을 때만 non-null. */
    val packMeta: PackMcmetaResult?,
    val notes: List<String>,
)

/** [analyzeJar] 결과. 예외 대신 sealed. */
public sealed interface JarAnalysisResult {
    public data class Ok(val analysis: JarAnalysis) : JarAnalysisResult

    /** zip 으로 열 수 없음 (jar 가 아니거나 손상). */
    public data class Unreadable(val reason: String) : JarAnalysisResult
}

/**
 * descriptor + 바이트코드 프로파일 + Capability 추론 + 루트 pack.mcmeta 를 한 번에 수행한다.
 * I/O 실패는 [JarAnalysisResult.Unreadable] 로 돌려주고 throw 하지 않는다.
 *
 * # 불변식
 * - 반환 시점에 jar 파일 핸들을 쥐고 있지 않다 → 호출자가 곧바로 삭제할 수 있다.
 * - [JarIndexCache] 를 쓰지 않는다 (임시 jar 의 분석 결과가 전역 캐시에 남지 않게).
 */
public fun analyzeJar(jar: Path, rules: CapabilityRules): JarAnalysisResult {
    val notes = ArrayList<String>()
    return try {
        // jar 를 한 번만 열고 네 단계가 같은 ZipFile 을 쓴다. use{} 가 닫으므로 반환 시 핸들이 없다
        withZipFile(jar) { zip ->
            val descriptors = analysisStage("descriptor", notes, { JarDescriptors(emptyList()) }) { readJarDescriptors(zip) }
            val bytecode = analysisStage("bytecode", notes, ::emptyBytecodeProfile) { bytecodeProfile(zip) }
            val capabilities = analysisStage("capability", notes, { emptyList() }) {
                inferCapabilities(zip, descriptors.primary, rules, notes)
            }
            val packMeta = analysisStage("pack.mcmeta", notes, { null }) { readPackMcmeta(zip) }
            JarAnalysisResult.Ok(JarAnalysis(descriptors, bytecode, capabilities, packMeta, notes.toList()))
        }
    } catch (e: IOException) {
        JarAnalysisResult.Unreadable(describeFailure(e))
    } catch (e: UnsupportedOperationException) {
        // 기본 파일시스템이 아닌 Path (Path.toFile 불가)
        JarAnalysisResult.Unreadable(describeFailure(e))
    }
}

private fun describeFailure(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "(메시지 없음)"}"

/**
 * 분석 단계 하나를 실행한다. [IOException] 은 그대로 전파(→ Unreadable)하고,
 * 그 밖의 RuntimeException·StackOverflowError 는 note 를 남기고 빈 결과로 계속한다. 취소 예외는 삼키지 않는다.
 */
private inline fun <T> analysisStage(stage: String, notes: MutableList<String>, empty: (String) -> T, block: () -> T): T = try {
    block()
} catch (e: RuntimeException) {
    rethrowIfCancellation(e)
    val note = "$stage 실패: ${describeFailure(e)}"
    notes += note
    empty(note)
} catch (e: StackOverflowError) {
    val note = "$stage 실패: ${describeFailure(e)}"
    notes += note
    empty(note)
}

/** bytecode 단계가 실패했을 때의 빈 프로파일. */
private fun emptyBytecodeProfile(note: String): BytecodeProfile = BytecodeProfile(
    requiredJavaFeature = null,
    outerMaxMajor = null,
    nestedMaxMajor = null,
    majorHistogram = emptyMap(),
    multiRelease = false,
    versionedFeatures = emptySet(),
    previewMajor = null,
    invalidClassEntries = 0,
    nms = NmsSignal(binaryRefs = false, versionedTokens = emptySet(), reflectiveStrings = false, mappingsNamespace = null),
    notes = listOf(note),
)
