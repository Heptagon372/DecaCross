package kr.decacross.compat.explain

import kr.decacross.compat.CauseNode
import kr.decacross.compat.Explanation
import kr.decacross.compat.Fix
import kr.decacross.compat.FixAction
import kr.decacross.compat.McSelector
import kr.decacross.compat.ResolveOutcome
import kr.decacross.compat.ResolveRequest
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.resolve.Candidates
import kr.decacross.compat.resolve.Pkg
import kr.decacross.compat.resolve.Ver
import kr.decacross.pubgrub.DerivationTree
import kr.decacross.pubgrub.ExternalKind
import kr.decacross.pubgrub.externals

/**
 * DerivationTree → 한국어 [Explanation] (명세 §5.1).
 *
 * ① 후위 순회 External 노드만 수집 ② 같은 subject 병합 ③ ko 템플릿 ④ 축별 Fix 후보
 * ⑤ ★ 각 Fix 를 가정하고 [reResolve] 로 실제 재실행 → 진짜 되는 것만 남긴다 (불변식 8) ⑥ 부수효과 계산
 *
 * # 불변식
 * - 반환하는 [Explanation.fixes] 는 1개 이상이다 (불변식 7). 검증된 fix 가 하나도 없으면 "전부 제외" 폴백까지 검증해 넣는다.
 */
public class Explainer(
    private val req: ResolveRequest,
    private val db: CompatDb,
    private val c: Candidates,
    /** fix 검증용 재해결. explain 을 다시 부르지 않도록 withFixes=false 로 호출된다. */
    private val reResolve: (ResolveRequest) -> ResolveOutcome,
) {
    private val ko = Ko(c, db)

    public fun explain(tree: DerivationTree<Pkg, Ver>): Explanation {
        val externals = tree.externals()
        val causes = LinkedHashMap<String, CauseNode>()
        for (e in externals) describe(e)?.let { causes.putIfAbsent(it.textKo, it) }
        val causeChain = causes.values.toList().ifEmpty { listOf(CauseNode("요청한 조합을 만족하는 버전 조합이 없습니다", Pkg.Root)) }
        val involved = involvedSlugs(externals)
        val headline = headline(externals, involved)
        val fixes = buildFixes(involved, externals)
        return Explanation(headline, causeChain, fixes)
    }

    // ── ③ 문장 ──────────────────────────────────────────────
    private fun describe(e: ExternalKind<Pkg, Ver>): CauseNode? = when (e) {
        is ExternalKind.NotRoot -> null

        is ExternalKind.NoVersions -> when (val p = e.pkg) {
            is Pkg.Core -> CauseNode("${ko.range(p, e.range)} 에 맞는 ${ko.coreName(p)} 빌드가 없습니다", p)
            Pkg.Mc -> CauseNode("조건에 맞는 마인크래프트 버전이 없습니다 (${ko.range(p, e.range)})", p)
            is Pkg.Content -> CauseNode("${ko.pkg(p)} 에는 ${ko.range(p, e.range)} 에 해당하는 버전이 없습니다", p)
            Pkg.Java -> CauseNode("${ko.range(p, e.range)} 인 Java 런타임이 없습니다", p)
            else -> CauseNode("${ko.pkg(p)}: ${ko.range(p, e.range)} 에 맞는 버전이 없습니다", p)
        }

        is ExternalKind.Unavailable -> CauseNode("${ko.pkg(e.pkg)} ${ko.range(e.pkg, e.range)}: ${e.reasonKo}", e.pkg)

        is ExternalKind.FromDependencyOf -> dependency(e)
    }

    private fun dependency(e: ExternalKind.FromDependencyOf<Pkg, Ver>): CauseNode {
        val subject = e.pkg
        val dep = e.depPkg
        val subj = "${ko.pkg(subject)} ${ko.range(subject, e.range)}".trim()
        val text = when {
            subject == Pkg.Root -> when (dep) {
                Pkg.Mc -> "요청: 마인크래프트 ${ko.range(dep, e.depRange)}"
                is Pkg.Core -> "요청: 코어 ${ko.coreName(dep)}"
                is Pkg.Content -> "요청: ${ko.pkg(dep)} ${ko.range(dep, e.depRange)}"
                else -> "요청: ${ko.pkg(dep)} ${ko.range(dep, e.depRange)}"
            }

            subject == Pkg.Mc && dep == Pkg.Java -> "마인크래프트 ${ko.range(subject, e.range)} 은(는) ${ko.range(dep, e.depRange)} 로 실행됩니다"

            subject is Pkg.Core && dep == Pkg.Mc -> "${ko.coreName(subject)} ${ko.range(subject, e.range)} 은(는) 마인크래프트 ${ko.range(dep, e.depRange)} 전용입니다"

            subject is Pkg.Core && dep is Pkg.Api -> "${ko.coreName(subject)} ${ko.range(subject, e.range)} 은(는) API ${ko.range(dep, e.depRange)} 을 제공합니다"

            subject is Pkg.Content && dep == Pkg.Java -> "$subj 은(는) ${ko.range(dep, e.depRange)} 이 필요합니다"

            subject is Pkg.Content && dep == Pkg.Mc -> "$subj 은(는) 마인크래프트 ${ko.range(dep, e.depRange)} 만 지원합니다"

            subject is Pkg.Content && dep is Pkg.Api -> "$subj 은(는) API ${ko.range(dep, e.depRange)} 이 필요합니다"

            subject is Pkg.Content && dep is Pkg.Provides -> "$subj 은(는) ${ko.cap(dep.cap)} 역할을 맡습니다 (같은 역할은 하나만 가능)"

            subject is Pkg.Content && dep is Pkg.Content -> "$subj 은(는) ${ko.pkg(dep)} ${ko.range(dep, e.depRange)} 이 필요합니다"

            else -> "$subj → ${ko.pkg(dep)} ${ko.range(dep, e.depRange)}"
        }
        return CauseNode(text, subject)
    }

    private fun involvedSlugs(externals: List<ExternalKind<Pkg, Ver>>): List<String> {
        val out = LinkedHashSet<String>()
        fun add(p: Pkg) {
            if (p is Pkg.Content) out += p.slug
        }
        for (e in externals) {
            when (e) {
                is ExternalKind.NotRoot -> add(e.pkg)

                is ExternalKind.NoVersions -> add(e.pkg)

                is ExternalKind.Unavailable -> add(e.pkg)

                is ExternalKind.FromDependencyOf -> {
                    add(e.pkg)
                    add(e.depPkg)
                }
            }
        }
        return out.toList()
    }

    private fun headline(externals: List<ExternalKind<Pkg, Ver>>, involved: List<String>): String {
        val mcLabel = (req.mc as? McSelector.Exact)?.label
        val provides = externals.filterIsInstance<ExternalKind.FromDependencyOf<Pkg, Ver>>().firstOrNull { it.depPkg is Pkg.Provides }
        val coreMissing = externals.filterIsInstance<ExternalKind.NoVersions<Pkg, Ver>>().firstOrNull { it.pkg is Pkg.Core }
        // "요청한 버전이 아예 없다"는 후보에 그 버전이 없을 때만. (PubGrub 이 탐색 중 만드는 '구멍' NoVersions 와 구분)
        val unknownVersion = req.wants.firstOrNull { w -> w.slug in c.missingVersions }
        val wantSlugs = req.wants.map { it.slug }
        val wantedInvolved = involved.filter { it in wantSlugs }
        return when {
            provides != null -> {
                val cap = (provides.depPkg as Pkg.Provides).cap
                val names = involved.filter { s -> c.versionsBySlug[s].orEmpty().any { v -> v.deps.any { it.kind == DepKind.PROVIDES && (it.target as? DepTarget.Cap)?.value == cap } } }
                    .map { ko.pkg(Pkg.Content(it)) }
                "${names.joinToString(", ")} 은(는) 같은 ${ko.cap(cap)} 역할이라 함께 쓸 수 없습니다."
            }

            coreMissing != null && mcLabel != null -> "$mcLabel 용 ${ko.coreName(coreMissing.pkg as Pkg.Core)} 빌드가 아직 없습니다."

            unknownVersion != null -> "${ko.pkg(Pkg.Content(unknownVersion.slug))} 에 요청한 버전(${unknownVersion.version})이 없습니다."

            mcLabel != null && wantedInvolved.isNotEmpty() -> "$mcLabel 에서는 ${wantedInvolved.joinToString(", ") { ko.pkg(Pkg.Content(it)) }} 을(를) 쓸 수 없습니다."

            wantedInvolved.size >= 2 -> "${wantedInvolved.joinToString(", ") { ko.pkg(Pkg.Content(it)) }} 을(를) 함께 쓸 수 있는 조합이 없습니다."

            wantedInvolved.size == 1 -> "${ko.pkg(Pkg.Content(wantedInvolved[0]))} 을(를) 쓸 수 있는 마인크래프트 버전이 없습니다."

            else -> "요청한 조합을 만족하는 버전 조합이 없습니다."
        }
    }

    // ── ④⑤⑥ Fix ─────────────────────────────────────────────
    private fun buildFixes(involved: List<String>, externals: List<ExternalKind<Pkg, Ver>>): List<Fix> {
        val fixes = ArrayList<Fix>()
        val wants = req.wants
        val pinnedSlugs = wants.filter { it.pinned }.map { it.slug }.toSet()

        // (a) 버전 고정을 풀어 올리기 — BumpContent
        for (w in wants.filter { it.version != null && it.slug in involved && !it.pinned }) {
            val relaxed = req.copy(wants = wants.map { if (it.slug == w.slug) it.copy(version = null) else it })
            val r = reResolve(relaxed)
            if (r is ResolveOutcome.Ok) {
                val chosen = r.plan.items.firstOrNull { it.content.slug == w.slug }?.content?.version ?: continue
                if (chosen != w.version) fixes += Fix("${ko.pkg(Pkg.Content(w.slug))} 을(를) $chosen 으로 올리기", FixAction.BumpContent(w.slug, chosen), recommended = fixes.isEmpty())
            }
        }
        // (b) 마크 버전 바꾸기 — ChangeMc (가까운 버전부터 최대 4개, 요청 콘텐츠 전부 유지되는 것만)
        val exact = req.mc as? McSelector.Exact
        if (exact != null || req.mc is McSelector.Latest) {
            val current = exact?.let { db.mcByLabel(it.label) } ?: db.mcLatest(req.allowSnapshots)
            val all = db.mcAll(req.allowSnapshots).sortedBy { it.ordinal }
            val ordered = all.filter { it.label != current?.label }.sortedBy { v -> kotlin.math.abs(v.ordinal.value - (current?.ordinal?.value ?: 0)) }
            var found = 0
            for (v in ordered) {
                if (found >= 2) break
                val r = reResolve(req.copy(mc = McSelector.Exact(v.label)))
                if (r is ResolveOutcome.Ok) {
                    val kept = wants.size
                    val direction = if (current != null && v.ordinal < current.ordinal) "낮추기" else "올리기"
                    fixes += Fix("마크 버전을 ${v.label} 로 $direction — 요청한 ${kept}개 전부 호환", FixAction.ChangeMc(v.label), recommended = fixes.isEmpty())
                    found++
                }
            }
        }
        // (c) 제외 — RemoveContent (pinned 제외). 혼자 빼서 안 되면 그것을 요구하는 요청 항목까지 함께.
        for (slug in involved.filter { it in wants.map { w -> w.slug } && it !in pinnedSlugs }) {
            val alone = wants.filter { it.slug != slug }
            var r = reResolve(req.copy(wants = alone))
            var removed = listOf(slug)
            if (r !is ResolveOutcome.Ok) {
                val dependents = wants.filter { w -> w.slug != slug && !w.pinned && requires(w.slug, slug) }.map { it.slug }
                if (dependents.isNotEmpty()) {
                    removed = listOf(slug) + dependents
                    r = reResolve(req.copy(wants = wants.filter { it.slug !in removed }))
                }
            }
            if (r is ResolveOutcome.Ok) {
                val autoDropped = r.plan.let { p -> wants.map { it.slug }.filter { s -> s !in removed && p.items.none { it.content.slug == s } } }
                val side = (removed.drop(1) + autoDropped).distinct().map { "${ko.pkg(Pkg.Content(it))} 도 함께 빠짐" }
                fixes += Fix(
                    "${ko.pkg(Pkg.Content(slug))} 제외" + if (removed.size > 1) " (요구하는 ${removed.size - 1}개도 함께)" else "",
                    FixAction.RemoveContent(removed),
                    sideEffectsKo = side,
                    recommended = fixes.isEmpty(),
                )
            }
        }
        // (d) 폴백 — 요청 콘텐츠(비고정) 전부 제외. 그래도 안 되면 빌드가 있는 최신 MC 로.
        if (fixes.isEmpty()) {
            val nonPinned = wants.filter { !it.pinned }.map { it.slug }
            if (nonPinned.isNotEmpty() && reResolve(req.copy(wants = wants.filter { it.pinned })) is ResolveOutcome.Ok) {
                fixes += Fix("고정하지 않은 콘텐츠 ${nonPinned.size}개 전부 제외", FixAction.RemoveContent(nonPinned), recommended = true)
            }
        }
        if (fixes.isEmpty()) {
            val fallbackMc = db.mcAll(req.allowSnapshots).sortedByDescending { it.ordinal }
                .firstOrNull { reResolve(req.copy(mc = McSelector.Exact(it.label), wants = wants.filter { w -> w.pinned })) is ResolveOutcome.Ok }
            if (fallbackMc != null) fixes += Fix("마크 버전을 ${fallbackMc.label} 로 바꾸고 고정하지 않은 콘텐츠 제외", FixAction.ChangeMc(fallbackMc.label), recommended = true)
        }
        // (e) 마지막 수단 — 고정(pinned)한 항목끼리 충돌하면 고정을 풀어야만 한다. 검증된 것만, 라벨에 명시.
        if (fixes.isEmpty()) {
            for (w in wants.filter { it.pinned && it.slug in involved }) {
                if (reResolve(req.copy(wants = wants.filter { it.slug != w.slug })) is ResolveOutcome.Ok) {
                    fixes += Fix("고정 해제 필요: ${ko.pkg(Pkg.Content(w.slug))} 제외", FixAction.RemoveContent(listOf(w.slug)), recommended = fixes.isEmpty())
                }
            }
        }
        if (fixes.isEmpty()) {
            // 데이터 자체가 없는 극단 케이스 — 그래도 벽은 세우지 않는다. 검증 불가함을 라벨에 명시.
            fixes += Fix("호환성 데이터가 부족합니다 — 수집기를 실행한 뒤 다시 시도", FixAction.ChangeMc(db.mcLatest(true)?.label ?: "latest"), recommended = true)
        }
        return fixes
    }

    /** [slug] 의 어떤 후보 버전이든 [dep] 를 REQUIRE 하는가 (재귀 1단계). */
    private fun requires(slug: String, dep: String): Boolean =
        c.versionsBySlug[slug].orEmpty().any { v -> v.deps.any { it.kind == DepKind.REQUIRE && (it.target as? DepTarget.Slug)?.value == dep } }
}
