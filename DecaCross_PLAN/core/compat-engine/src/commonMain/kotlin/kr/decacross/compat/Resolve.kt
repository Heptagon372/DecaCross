package kr.decacross.compat

import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.JavaSpec
import kr.decacross.compat.model.McVersion
import kr.decacross.compat.model.Os
import kr.decacross.compat.resolve.Pkg

/**
 * 호환성 판정 진입점. 5단계 파이프라인(정규화 → 프루닝 → PubGrub → 사후검사 → 점수)을 돌린다.
 *
 * # 불변식 (테스트로 강제, 명세 §3.1)
 * - I1 `Conflict.explanation.fixes` 는 항상 1개 이상
 * - I2 `Plan` 의 모든 REQUIRE 의존성이 `items` 에 존재
 * - I3 같은 Capability 제공자가 2개 이상 선택되지 않음
 * - I4 `plan.java.feature >= plan.mc.javaMin`
 * - I5 `recommendedRamMb` 계산에 `hostOverheadMb` 가 반영됨
 */
public fun resolve(req: ResolveRequest, db: CompatDb): ResolveOutcome = TODO("07")

public data class ResolveRequest(
    val mc: McSelector = McSelector.Any,
    val core: CoreKey? = null,
    val wants: List<Want> = emptyList(),
    val os: Os,
    val arch: Arch,
    val ramMb: Int,
    val allowSnapshots: Boolean = false,
    val allowExperimental: Boolean = false,
    /** 런처/데몬이 이미 쓰고 있는 메모리(MB). 권장 RAM 계산에서 차감한다. */
    val hostOverheadMb: Int = 0,
)

public sealed interface McSelector {
    /** "1.21.8" */
    public data class Exact(val label: String) : McSelector

    /** "1.21" → 1.21..1.21.8 */
    public data class Family(val prefix: String) : McSelector

    public data object Latest : McSelector

    /** 엔진이 고름 — 입문자 경로 */
    public data object Any : McSelector
}

public data class Want(
    val slug: String,
    val kind: ContentKind,
    val version: String? = null,
    /** true 면 이 항목을 제거하는 해결책은 제시하지 않는다. */
    val pinned: Boolean = false,
)

public sealed interface ResolveOutcome {
    public data class Ok(val plan: Plan) : ResolveOutcome

    public data class Conflict(val explanation: Explanation) : ResolveOutcome
}

public data class Plan(
    val mc: McVersion,
    val core: CoreBuild,
    val java: JavaSpec,
    val items: List<PlanItem>,
    val warnings: List<Warning> = emptyList(),
    val confidence: Confidence,
    val score: Float,
    val estimatedDownloadBytes: Long,
    val recommendedRamMb: Int,
)

public data class PlanItem(
    val content: ContentVersion,
    /** 사용자가 고른 게 아니라 의존성으로 자동 추가됨. UI 에서 구분 표시할 것. */
    val autoAdded: Boolean = false,
    /** "EssentialsX 가 필요로 함" */
    val reason: String? = null,
    val confidence: Confidence,
)

public data class Warning(val textKo: String, val subject: String? = null)

public enum class Confidence { GREEN, YELLOW, ORANGE, RED }

// ── 실패 설명 ───────────────────────────────────────────

public data class Explanation(
    /** "26.3에서는 ProtocolLib을 쓸 수 없습니다." */
    val headlineKo: String,
    /** 최소 충돌 집합만. 전체 트리 노출 금지 */
    val causeChain: List<CauseNode>,
    /** # 불변식: 항상 1개 이상. 0개면 엔진 버그다. */
    val fixes: List<Fix>,
)

public data class CauseNode(val textKo: String, val subject: Pkg? = null)

public data class Fix(
    val labelKo: String,
    val action: FixAction,
    val sideEffectsKo: List<String> = emptyList(),
    val recommended: Boolean = false,
)

public sealed interface FixAction {
    public data class BumpContent(val slug: String, val to: String) : FixAction

    public data class ChangeMc(val to: String) : FixAction

    public data class ChangeCore(val to: CoreKey) : FixAction

    public data class ChangeJava(val to: Int) : FixAction

    public data class RemoveContent(val slugs: List<String>) : FixAction

    public data class ReplaceContent(val from: String, val to: String) : FixAction
}
