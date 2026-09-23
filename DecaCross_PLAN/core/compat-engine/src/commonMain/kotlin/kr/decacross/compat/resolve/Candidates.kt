package kr.decacross.compat.resolve

import kr.decacross.compat.McSelector
import kr.decacross.compat.ResolveRequest
import kr.decacross.compat.Want
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion

/**
 * 판정 파이프라인 ①NORMALIZE + ②PRUNE (설계서 §3.5). 솔버 입력을 줄인다.
 *
 * - MC 후보: 선택자(정확/계열/최신/아무거나) ∩ 스냅샷 정책 ∩ "요청 코어의 빌드가 있는 버전"
 * - 코어 후보: MC 후보별 최신 빌드 하나 (STABLE 우선, 허용 시 EXPERIMENTAL 폴백)
 * - 콘텐츠 후보: 로더 계열이 맞고 MC 지원 범위가 후보와 겹치는 버전만
 *
 * # 불변식
 * - 여기서 잘라낸 것은 "명백히 불가능한" 것뿐이다. 애매한 것은 남겨 솔버가 판단하게 한다.
 */
public class Candidates private constructor(
    public val request: ResolveRequest,
    public val core: CoreKey,
    public val mcs: List<McVersion>,
    public val buildsByMc: Map<McOrdinal, CoreBuild>,
    public val contents: Map<String, Content>,
    public val versionsBySlug: Map<String, List<ContentVersion>>,
    /** 요청했지만 DB 에 없는 슬러그 */
    public val unknownSlugs: List<String>,
    /** 요청했지만 후보 MC 에서 쓸 수 있는 버전이 하나도 없는 슬러그 → 사유 */
    public val emptySlugs: Map<String, String>,
    public val javaFeatures: List<Int>,
    /** 요청한 정확한 버전이 DB 에 아예 없는 슬러그 → 요청 버전 */
    public val missingVersions: Map<String, String> = emptyMap(),
) {
    public val mcByOrdinal: Map<McOrdinal, McVersion> = mcs.associateBy { it.ordinal }
    public val wantBySlug: Map<String, Want> = request.wants.associateBy { it.slug }

    public fun loaderOf(core: CoreKey): LoaderFamily = loaderFamily(core)

    public companion object {
        public fun loaderFamily(core: CoreKey): LoaderFamily = when (core) {
            CoreKey.PAPER, CoreKey.PURPUR, CoreKey.FOLIA, CoreKey.SPIGOT -> LoaderFamily.BUKKIT
            CoreKey.VANILLA -> LoaderFamily.VANILLA
            CoreKey.FABRIC -> LoaderFamily.FABRIC
            CoreKey.NEOFORGE, CoreKey.FORGE -> LoaderFamily.FORGE
        }

        public fun build(req: ResolveRequest, db: CompatDb): Candidates {
            val core = req.core ?: CoreKey.PAPER
            val selected: List<McVersion> = when (val s = req.mc) {
                is McSelector.Exact -> listOfNotNull(db.mcByLabel(s.label))
                is McSelector.Family -> db.mcInFamily(s.prefix)
                McSelector.Latest -> listOfNotNull(db.mcLatest(req.allowSnapshots))
                McSelector.Any -> db.mcAll(req.allowSnapshots)
            }.filter { req.allowSnapshots || !it.isSnapshot }.sortedBy { it.ordinal }

            val builds = LinkedHashMap<McOrdinal, CoreBuild>()
            for (mc in selected) {
                val b = db.coreBuilds(core, mc.ordinal, stableOnly = true).firstOrNull()
                    ?: if (req.allowExperimental) db.coreBuilds(core, mc.ordinal, stableOnly = false).firstOrNull() else null
                if (b != null) builds[mc.ordinal] = b
            }
            // Exact 선택은 빌드가 없어도 MC 후보로 남긴다 — "이 버전용 빌드가 없다"를 솔버가 말하게 하기 위해
            val mcs = if (req.mc is McSelector.Exact) selected else selected.filter { it.ordinal in builds }
            val minMc = mcs.firstOrNull()?.ordinal
            val maxMc = mcs.lastOrNull()?.ordinal
            val loader = loaderFamily(core)

            val contents = LinkedHashMap<String, Content>()
            val versions = LinkedHashMap<String, List<ContentVersion>>()
            val unknown = ArrayList<String>()
            val empty = LinkedHashMap<String, String>()
            val missingVersions = LinkedHashMap<String, String>()
            // 요청 콘텐츠 + REQUIRE 의존 클로저를 전부 후보에 넣는다 (솔버가 자동 추가하려면 후보에 있어야 한다)
            val queue = ArrayDeque(req.wants.map { it.slug })
            val seen = HashSet<String>()
            while (queue.isNotEmpty()) {
                val slug = queue.removeFirst()
                if (!seen.add(slug)) continue
                val c = db.content(slug)
                if (c == null) {
                    if (slug in req.wants.map { it.slug }) unknown += slug
                    continue
                }
                contents[slug] = c
                val all = db.contentVersions(slug)
                val want = req.wants.firstOrNull { it.slug == slug }
                val requested = want?.version?.let { rv -> all.filter { LooseVersion(it.version).compareTo(LooseVersion(rv)) == 0 } }.orEmpty()
                if (want?.version != null && requested.isEmpty()) missingVersions[slug] = want.version
                // 명시 요청한 버전은 MC 범위 밖이어도 후보에 남긴다 — "버전이 없다"가 아니라 "지원 범위가 다르다"를 솔버가 말하게
                val usable = (
                    all.filter { v ->
                        (v.loaders.isEmpty() || loader in v.loaders) &&
                            (minMc == null || v.mcMax == null || v.mcMax >= minMc) &&
                            (maxMc == null || v.mcMin == null || v.mcMin <= maxMc)
                    } + requested.filter { v -> v.loaders.isEmpty() || loader in v.loaders }
                ).distinct()
                if (usable.isEmpty() && slug in req.wants.map { it.slug }) {
                    empty[slug] = when {
                        all.isEmpty() -> "수집된 버전이 없습니다"
                        all.none { it.loaders.isEmpty() || loader in it.loaders } -> "${core.name.lowercase()} 계열(${loader.name.lowercase()})용 버전이 없습니다"
                        else -> "선택한 MC 버전 범위를 지원하는 버전이 없습니다"
                    }
                }
                versions[slug] = usable.sortedByDescending { LooseVersion(it.version) }
                usable.flatMap { it.deps }.forEach { d ->
                    (d.target as? kr.decacross.compat.model.DepTarget.Slug)?.value?.let { queue.addLast(it) }
                }
            }
            val javaFeatures = (mcs.flatMap { listOf(it.javaMin, it.javaRecommended) } + versions.values.flatten().mapNotNull { it.javaMajor })
                .distinct().sorted()
            return Candidates(req, core, mcs, builds, contents, versions, unknown, empty, javaFeatures, missingVersions)
        }
    }
}
