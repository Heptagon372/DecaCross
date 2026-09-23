package kr.decacross.compat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.explain.Explainer
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.JavaSpec
import kr.decacross.compat.model.McVersion
import kr.decacross.compat.model.Os
import kr.decacross.compat.resolve.Candidates
import kr.decacross.compat.resolve.DecaProvider
import kr.decacross.compat.resolve.LooseVersion
import kr.decacross.compat.resolve.Pkg
import kr.decacross.compat.resolve.PostCheck
import kr.decacross.compat.resolve.Score
import kr.decacross.compat.resolve.Ver
import kr.decacross.compat.resolve.recommendedRamMb
import kr.decacross.pubgrub.SolverResult

/**
 * 호환성 판정 진입점. 5단계 파이프라인(정규화 → 프루닝 → PubGrub → 사후검사 → 점수)을 돌린다.
 *
 * # 불변식 (테스트로 강제, 명세 §3.1)
 * - I1 `Conflict.explanation.fixes` 는 항상 1개 이상
 * - I2 `Plan` 의 모든 REQUIRE 의존성이 `items` 에 존재
 * - I3 같은 Capability 제공자가 2개 이상 선택되지 않음
 * - I4 `plan.java.feature >= plan.mc.javaMin`
 * - I5 `recommendedRamMb` 계산에 `hostOverheadMb` 가 반영됨
 * - I6 콘텐츠 30개 입력 시 50ms 미만 (JIT 예열 후)
 */
public fun resolve(req: ResolveRequest, db: CompatDb): ResolveOutcome = resolveInternal(req, db, withFixes = true)

private fun resolveInternal(req: ResolveRequest, db: CompatDb, withFixes: Boolean): ResolveOutcome {
    val c = Candidates.build(req, db)

    // 프루닝 단계에서 이미 답이 나오는 경우 — 솔버를 돌릴 필요가 없다
    if (c.unknownSlugs.isNotEmpty() || c.emptySlugs.isNotEmpty()) {
        if (!withFixes) return ResolveOutcome.Conflict(Explanation("불가", emptyList(), listOf(Fix("-", FixAction.RemoveContent(emptyList())))))
        return pruneConflict(req, db, c)
    }

    val provider = DecaProvider(c)
    return when (val r = kr.decacross.pubgrub.resolve(provider, Pkg.Root, Ver.Unit)) {
        is SolverResult.Solution -> ResolveOutcome.Ok(buildPlan(req, db, c, r.selected))

        is SolverResult.NoSolution -> {
            if (!withFixes) {
                ResolveOutcome.Conflict(Explanation("불가", emptyList(), listOf(Fix("-", FixAction.RemoveContent(emptyList())))))
            } else {
                val explainer = Explainer(req, db, c) { r2 -> resolveInternal(r2, db, withFixes = false) }
                ResolveOutcome.Conflict(explainer.explain(r.tree))
            }
        }
    }
}

private fun pruneConflict(req: ResolveRequest, db: CompatDb, c: Candidates): ResolveOutcome {
    val causes = ArrayList<CauseNode>()
    c.unknownSlugs.forEach { causes += CauseNode("'$it' 콘텐츠를 찾을 수 없습니다 (슬러그 확인 또는 수집 필요)", Pkg.Content(it)) }
    c.emptySlugs.forEach { (slug, why) -> causes += CauseNode("${c.contents[slug]?.name ?: slug}: $why", Pkg.Content(slug)) }
    val bad = (c.unknownSlugs + c.emptySlugs.keys).distinct()
    val fixes = ArrayList<Fix>()
    val nonPinnedBad = bad.filter { s -> req.wants.none { it.slug == s && it.pinned } }
    if (nonPinnedBad.isNotEmpty()) {
        val without = req.copy(wants = req.wants.filter { it.slug !in nonPinnedBad })
        if (resolveInternal(without, db, withFixes = false) is ResolveOutcome.Ok) {
            fixes += Fix("${nonPinnedBad.joinToString(", ")} 제외", FixAction.RemoveContent(nonPinnedBad), recommended = true)
        }
    }
    // MC 를 바꾸면 되는 경우 (지원 범위 밖)
    if (req.mc is McSelector.Exact && c.emptySlugs.isNotEmpty()) {
        for (v in db.mcAll(req.allowSnapshots).sortedByDescending { it.ordinal }) {
            if (fixes.size >= 3) break
            if (v.label == (req.mc as McSelector.Exact).label) continue
            if (resolveInternal(req.copy(mc = McSelector.Exact(v.label)), db, withFixes = false) is ResolveOutcome.Ok) {
                fixes += Fix("마크 버전을 ${v.label} 로 바꾸기 — 요청한 ${req.wants.size}개 전부 호환", FixAction.ChangeMc(v.label), recommended = fixes.isEmpty())
            }
        }
    }
    if (fixes.isEmpty()) fixes += Fix("문제 콘텐츠 전부 제외", FixAction.RemoveContent(bad), recommended = true)
    val headline = when {
        c.unknownSlugs.isNotEmpty() -> "${c.unknownSlugs.joinToString(", ")} 을(를) 찾을 수 없습니다."
        else -> "${c.emptySlugs.keys.joinToString(", ") { c.contents[it]?.name ?: it }} 을(를) 이 구성에서 쓸 수 없습니다."
    }
    return ResolveOutcome.Conflict(Explanation(headline, causes, fixes))
}

private fun buildPlan(req: ResolveRequest, db: CompatDb, c: Candidates, selected: Map<Pkg, Ver>): Plan {
    val mcOrdinal = (selected[Pkg.Mc] as Ver.Mc).o
    val mc: McVersion = c.mcByOrdinal.getValue(mcOrdinal)
    val core: CoreBuild = c.buildsByMc.getValue(mcOrdinal)
    val javaFeature = (selected[Pkg.Java] as? Ver.Java)?.feature ?: mc.javaRecommended
    val wantSlugs = req.wants.map { it.slug }.toSet()

    val chosen: Map<String, ContentVersion> = selected.entries
        .mapNotNull { (p, v) -> if (p is Pkg.Content && v is Ver.Sem) p.slug to v else null }
        .associate { (slug, v) -> slug to c.versionsBySlug.getValue(slug).first { LooseVersion(it.version) == v.v } }

    // 자동 추가 사유: 누가 이걸 REQUIRE 하는가
    fun reasonFor(slug: String): String? =
        chosen.entries.firstOrNull { (other, cv) -> other != slug && cv.deps.any { it.kind == DepKind.REQUIRE && (it.target as? DepTarget.Slug)?.value == slug } }
            ?.let { (other, _) -> "${c.contents[other]?.name ?: other} 가 필요로 함" }

    val items = chosen.entries.sortedBy { it.key }.map { (slug, cv) ->
        PlanItem(
            content = cv,
            autoAdded = slug !in wantSlugs,
            reason = if (slug !in wantSlugs) reasonFor(slug) else null,
            confidence = db.confidenceOf(slug, cv.version, mcOrdinal, core.core),
        )
    }
    val kinds = c.contents.mapValues { it.value.kind }
    val warnings = PostCheck.run(mc, core.core, items.map { it.content }, kinds)
    val confidences = items.map { it.confidence }
    val score = Score.compute(
        wantsTotal = req.wants.size,
        wantsSatisfied = req.wants.count { it.slug in chosen },
        itemConfidences = confidences,
        chosenMc = mcOrdinal,
        candidateMcs = c.mcs.map { it.ordinal },
        coreChannel = core.channel,
    )
    return Plan(
        mc = mc,
        core = core,
        java = JavaSpec(javaFeature),
        items = items,
        warnings = warnings,
        confidence = Score.overall(confidences),
        score = score,
        estimatedDownloadBytes = core.size + items.sumOf { it.content.size ?: 0L },
        recommendedRamMb = recommendedRamMb(req.ramMb, req.hostOverheadMb),
    )
}

@Serializable
public data class ResolveRequest(
    val mc: McSelector = McSelector.Any,
    val core: CoreKey? = null,
    val wants: List<Want> = emptyList(),
    val os: Os,
    val arch: Arch,
    /** 이 PC 의 전체 메모리(MB). 권장 RAM 계산의 입력이다. */
    val ramMb: Int,
    val allowSnapshots: Boolean = false,
    val allowExperimental: Boolean = false,
    /** 런처/데몬이 이미 쓰고 있는 메모리(MB). 권장 RAM 계산에서 차감한다. */
    val hostOverheadMb: Int = 0,
)

@Serializable
public sealed interface McSelector {
    /** "1.21.8" */
    @Serializable
    @SerialName("exact")
    public data class Exact(val label: String) : McSelector

    /** "1.21" → 1.21..1.21.8 */
    @Serializable
    @SerialName("family")
    public data class Family(val prefix: String) : McSelector

    @Serializable
    @SerialName("latest")
    public data object Latest : McSelector

    /** 엔진이 고름 — 입문자 경로 */
    @Serializable
    @SerialName("any")
    public data object Any : McSelector
}

@Serializable
public data class Want(
    val slug: String,
    val kind: ContentKind,
    val version: String? = null,
    /** true 면 이 항목을 제거하는 해결책은 제시하지 않는다. */
    val pinned: Boolean = false,
)

@Serializable
public sealed interface ResolveOutcome {
    @Serializable
    @SerialName("ok")
    public data class Ok(val plan: Plan) : ResolveOutcome

    @Serializable
    @SerialName("conflict")
    public data class Conflict(val explanation: Explanation) : ResolveOutcome
}

@Serializable
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

@Serializable
public data class PlanItem(
    val content: ContentVersion,
    /** 사용자가 고른 게 아니라 의존성으로 자동 추가됨. UI 에서 구분 표시할 것. */
    val autoAdded: Boolean = false,
    /** "EssentialsX 가 필요로 함" */
    val reason: String? = null,
    val confidence: Confidence,
)

@Serializable
public data class Warning(val textKo: String, val subject: String? = null)

@Serializable
public enum class Confidence { GREEN, YELLOW, ORANGE, RED }

// ── 실패 설명 ───────────────────────────────────────────

@Serializable
public data class Explanation(
    /** "26.3에서는 ProtocolLib을 쓸 수 없습니다." */
    val headlineKo: String,
    /** 최소 충돌 집합만. 전체 트리 노출 금지 */
    val causeChain: List<CauseNode>,
    /** # 불변식: 항상 1개 이상. 0개면 엔진 버그다. */
    val fixes: List<Fix>,
)

@Serializable
public data class CauseNode(val textKo: String, val subject: Pkg? = null)

@Serializable
public data class Fix(
    val labelKo: String,
    val action: FixAction,
    val sideEffectsKo: List<String> = emptyList(),
    val recommended: Boolean = false,
)

@Serializable
public sealed interface FixAction {
    @Serializable
    @SerialName("bump_content")
    public data class BumpContent(val slug: String, val to: String) : FixAction

    @Serializable
    @SerialName("change_mc")
    public data class ChangeMc(val to: String) : FixAction

    @Serializable
    @SerialName("change_core")
    public data class ChangeCore(val to: CoreKey) : FixAction

    @Serializable
    @SerialName("change_java")
    public data class ChangeJava(val to: Int) : FixAction

    @Serializable
    @SerialName("remove_content")
    public data class RemoveContent(val slugs: List<String>) : FixAction

    @Serializable
    @SerialName("replace_content")
    public data class ReplaceContent(val from: String, val to: String) : FixAction
}
