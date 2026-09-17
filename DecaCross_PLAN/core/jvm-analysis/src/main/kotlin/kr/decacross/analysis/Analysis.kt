package kr.decacross.analysis

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
public fun analyzeJar(jar: Path, rules: CapabilityRules): JarAnalysisResult = TODO("WP-JA")
