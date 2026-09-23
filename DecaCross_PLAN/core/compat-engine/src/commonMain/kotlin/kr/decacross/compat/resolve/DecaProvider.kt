package kr.decacross.compat.resolve

import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.pubgrub.Dependencies
import kr.decacross.pubgrub.DependencyProvider
import kr.decacross.pubgrub.Range
import kr.decacross.pubgrub.VersionSet

/**
 * 도메인 → PubGrub (명세 §5). R1~R12 를 전부 "의존성"으로 번역한다. 규칙이 늘어나도 엔진 코드는 안 바뀐다 — 데이터가 바뀐다.
 *
 * | 패키지 | 의존성 |
 * |---|---|
 * | Root | Mc ∈ 선택자, Core(key) ∈ *, Content(slug) ∈ 요청범위 |
 * | Mc@o | Java ≥ javaMin (R1) |
 * | Core@build | Mc = 빌드의 MC (R3), Api(kind) = 코어 API 수준 |
 * | Content@ver | Api ≥ api-version (R4), Java ≥ javaMajor (R1'), Mc ∈ [mcMin,mcMax] (R3/R12), Content(dep) ∈ range (R6/R7), Provides(cap) = 자기 슬러그 (R9) |
 *
 * 한 번의 resolve 동안만 쓰는 상태(마지막에 고른 MC)를 가진다 — 코어 빌드·Java 선택을 그 MC 에 맞추기 위한 힌트일 뿐, 정확성에는 영향이 없다.
 */
public class DecaProvider(private val c: Candidates) : DependencyProvider<Pkg, Ver> {
    private var lastMc: McOrdinal? = null

    override fun chooseVersion(pkg: Pkg, range: VersionSet<Ver>): Ver? = when (pkg) {
        Pkg.Root -> Ver.Unit.takeIf { range.contains(it) }

        Pkg.Mc -> c.mcs.asReversed().map { Ver.Mc(it.ordinal) }.firstOrNull { range.contains(it) }?.also { lastMc = it.o }

        Pkg.Java -> chooseJava(range)

        is Pkg.Core -> chooseBuild(range)

        is Pkg.Content -> c.versionsBySlug[pkg.slug].orEmpty().map { Ver.Sem(LooseVersion(it.version)) }.firstOrNull { range.contains(it) }

        is Pkg.Api -> apiLevels().firstOrNull { range.contains(it) }

        Pkg.Nms -> null

        // ★ 배타: 가상 패키지의 "버전" = 제공자 슬러그. 한 패키지에서 버전 하나만 선택되므로 제공자 둘이 동시에 뽑히지 못한다.
        //   (명세 §5 는 "버전이 Unit 하나"라고 썼지만, 그러면 둘 다 같은 버전에 의존해 만족돼 버린다 — 제공자별 버전이 맞는 인코딩)
        is Pkg.Provides -> providersOf(pkg).firstOrNull { range.contains(it) }
    }

    private fun providersOf(p: Pkg.Provides): List<Ver> =
        c.versionsBySlug.entries
            .filter { (_, vs) -> vs.any { v -> v.deps.any { it.kind == DepKind.PROVIDES && (it.target as? DepTarget.Cap)?.value == p.cap } } }
            .map { Ver.Sem(LooseVersion(it.key)) }

    /** 권장 Java(마지막 MC 기준)에 가장 가까운 것부터: 권장 → 그보다 큰 것 오름차순 → 작은 것 내림차순. */
    private fun chooseJava(range: VersionSet<Ver>): Ver? {
        val rec = lastMc?.let { c.mcByOrdinal[it]?.javaRecommended }
        val ordered = if (rec == null) {
            c.javaFeatures.asReversed()
        } else {
            listOf(rec) + c.javaFeatures.filter { it > rec }.sorted() + c.javaFeatures.filter { it < rec }.sortedDescending()
        }
        return ordered.map { Ver.Java(it) }.firstOrNull { range.contains(it) }
    }

    /** 마지막에 고른 MC 의 빌드를 먼저, 그다음 최신 MC 순. */
    private fun chooseBuild(range: VersionSet<Ver>): Ver? {
        val all = c.buildsByMc.values.map { Ver.Build.of(it.mc, it.build.filter(Char::isDigit).toLongOrNull() ?: 0L) }
        val preferred = lastMc?.let { mc -> all.filter { it.mcOrdinal == mc } }.orEmpty()
        return (preferred + all.sortedByDescending { it.n }).firstOrNull { range.contains(it) }
    }

    private fun apiLevels(): List<Ver> = c.mcs.asReversed().map { Ver.Sem(LooseVersion(apiLevelOf(it))) }.distinct()

    override fun getDependencies(pkg: Pkg, version: Ver): Dependencies<Pkg, Ver> = when (pkg) {
        Pkg.Root -> rootDeps()

        Pkg.Mc -> {
            val mc = c.mcByOrdinal[(version as Ver.Mc).o]
            available(mapOf(Pkg.Java to Range.higherThan(Ver.Java(mc?.javaMin ?: 8))))
        }

        Pkg.Java, Pkg.Nms, is Pkg.Api, is Pkg.Provides -> available(emptyMap())

        is Pkg.Core -> {
            val b = version as Ver.Build
            val mc = c.mcByOrdinal[b.mcOrdinal]
            val deps = LinkedHashMap<Pkg, VersionSet<Ver>>()
            deps[Pkg.Mc] = Range.singleton(Ver.Mc(b.mcOrdinal))
            if (mc != null && c.loaderOf(pkg.key) == LoaderFamily.BUKKIT) deps[Pkg.Api(ApiKind.BUKKIT)] = Range.singleton(Ver.Sem(LooseVersion(apiLevelOf(mc))))
            available(deps)
        }

        is Pkg.Content -> contentDeps(pkg.slug, version as Ver.Sem)
    }

    private fun rootDeps(): Dependencies<Pkg, Ver> {
        val deps = LinkedHashMap<Pkg, VersionSet<Ver>>()
        deps[Pkg.Mc] = if (c.mcs.isEmpty()) Range.empty() else Range.ofVersions(c.mcs.map { Ver.Mc(it.ordinal) })
        deps[Pkg.Core(c.core)] = Range.full()
        for (w in c.request.wants) {
            deps[Pkg.Content(w.slug)] = w.version?.let { Range.singleton(Ver.Sem(LooseVersion(it))) } ?: Range.full()
        }
        return available(deps)
    }

    private fun contentDeps(slug: String, ver: Ver.Sem): Dependencies<Pkg, Ver> {
        val cv = c.versionsBySlug[slug].orEmpty().firstOrNull { LooseVersion(it.version) == ver.v }
            ?: return Dependencies.Unavailable("$slug ${ver.v} 버전 데이터가 없습니다")
        val deps = LinkedHashMap<Pkg, VersionSet<Ver>>()
        // R4: api-version 은 "이 API 이상에서 컴파일됨"
        val api = cv.apiVersion?.takeIf { it.isNotBlank() }
        if (api != null && (cv.loaders.isEmpty() || LoaderFamily.BUKKIT in cv.loaders) && c.loaderOf(c.core) == LoaderFamily.BUKKIT) {
            deps[Pkg.Api(ApiKind.BUKKIT)] = Range.higherThan(Ver.Sem(LooseVersion(api)))
        }
        // R1': ASM 실측 Java 하한
        cv.javaMajor?.let { deps[Pkg.Java] = Range.higherThan(Ver.Java(it)) }
        // R3/R12: MC 지원 구간
        if (cv.mcMin != null || cv.mcMax != null) {
            deps[Pkg.Mc] = when {
                cv.mcMin != null && cv.mcMax != null -> Range.betweenInclusive(Ver.Mc(cv.mcMin), Ver.Mc(cv.mcMax))
                cv.mcMin != null -> Range.higherThan(Ver.Mc(cv.mcMin))
                else -> Range.atMost(Ver.Mc(cv.mcMax ?: return@contentDeps available(deps)))
            }
        }
        for (d in cv.deps) {
            when (d.kind) {
                DepKind.REQUIRE -> (d.target as? DepTarget.Slug)?.let { t ->
                    deps[Pkg.Content(t.value)] = deps[Pkg.Content(t.value)]?.intersect(VersionRange.parse(d.range)) ?: VersionRange.parse(d.range)
                }

                DepKind.PROVIDES -> (d.target as? DepTarget.Cap)?.let { t -> deps[Pkg.Provides(t.value)] = Range.singleton(Ver.Sem(LooseVersion(slug))) }

                DepKind.OPTIONAL -> Unit // softdepend: 있으면 좋고 없어도 된다 — 제약 아님
            }
        }
        return available(deps)
    }

    override fun prioritize(pkg: Pkg, range: VersionSet<Ver>): Int = when (pkg) {
        Pkg.Root -> 1_000_000

        Pkg.Mc -> 900_000

        is Pkg.Core -> 800_000

        is Pkg.Api -> 700_000

        Pkg.Java -> 600_000

        is Pkg.Provides -> 500_000

        Pkg.Nms -> 400_000

        // 후보 수가 적은 콘텐츠를 먼저 (탐색 감소)
        is Pkg.Content -> 100_000 - c.versionsBySlug[pkg.slug].orEmpty().count { range.contains(Ver.Sem(LooseVersion(it.version))) }
    }

    private fun available(map: Map<Pkg, VersionSet<Ver>>): Dependencies<Pkg, Ver> = Dependencies.Available(map)

    public companion object {
        /** 코어의 API 수준: "1.21.8" → "1.21", "26.3" → "26.3" (새 체계는 라벨 그대로). */
        public fun apiLevelOf(mc: McVersion): String {
            val parts = mc.label.split('-').first().split('.')
            return if (parts.firstOrNull() == "1" && parts.size >= 2) "1.${parts[1]}" else mc.label.split('-').first()
        }
    }
}
