package kr.decacross.daemon.diagnosis

import kotlinx.serialization.Serializable

/**
 * 진단 카드 (기획서 §4.6, 설계서 §3.7). 로그를 "현상"이 아니라 "원인과 해결책"으로 바꾼다.
 *
 * # 불변식
 * - [fixes] 는 항상 1개 이상 (CLAUDE.md 7). 0개면 엔진 버그다 — [DiagnosisEngine] 이 보장한다.
 */
@Serializable
data class Diagnosis(
    val id: String,
    val signatureKey: String,
    val category: String,
    val titleKo: String,
    val causeKo: String,
    val fixes: List<DiagFix>,
    /** 근거가 된 로그 원문 (첫 줄 + 핵심 연속 줄 몇 개) */
    val evidence: List<String>,
    val at: String,
    /** 같은 진단이 반복된 횟수 */
    val occurrences: Int = 1,
)

@Serializable
data class DiagFix(
    val labelKo: String,
    val action: FixActionDto,
    val recommended: Boolean = false,
    /** 사용자에게 미리 알려줄 부수효과 */
    val noteKo: String? = null,
)

/** 데몬이 실제로 수행할 수 있는 fix 액션. `type` 은 시드의 액션 이름과 같다. */
@Serializable
data class FixActionDto(
    val type: String,
    /** ChangeJava: feature / SuggestPort: port / InstallDependency, DisablePlugin, BlamePluginFromStack: 이름 */
    val arg: String? = null,
    /** 실행 가능한가 (false 면 안내만) */
    val executable: Boolean = false,
)
