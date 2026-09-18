package kr.decacross.pubgrub

// 출처: pubgrub-rs (MPL-2.0) src/internal/incompatibility.rs, src/internal/small_map.rs 의 알고리즘 번역 (commit 0399630, 2026-09-17 확인). 라이선스 정책은 Q3 (사용자 결정 대기).

/** 비호환의 원인. 이름은 명세 §4.1 `ExternalKind` 와 1:1 (P1-d 에서 그대로 옮긴다). */
internal sealed interface IncompatibilityCause<V : Comparable<V>> {
    /** `{root: Negative({v})}`. 루트를 반드시 고르게 만든다. */
    data class NotRoot<V : Comparable<V>>(val pkg: PackageId, val v: V) : IncompatibilityCause<V>

    /** `{pkg: Positive(set)}`. set 안에 고를 버전이 없다. */
    data class NoVersions<V : Comparable<V>>(val pkg: PackageId, val range: VersionSet<V>) : IncompatibilityCause<V>

    /** `{pkg: Positive(set)}`. 외부 사유로 쓸 수 없다 (`Dependencies.Unavailable`). */
    data class Unavailable<V : Comparable<V>>(
        val pkg: PackageId,
        val range: VersionSet<V>,
        val reasonKo: String,
    ) : IncompatibilityCause<V>

    /** `pkg ∈ range` 가 `depPkg ∈ depRange` 에 의존. 항에서 복원하지 않고 집합을 직접 보관한다 (자기 의존 정규화 때문). */
    data class FromDependencyOf<V : Comparable<V>>(
        val pkg: PackageId,
        val range: VersionSet<V>,
        val depPkg: PackageId,
        val depRange: VersionSet<V>,
    ) : IncompatibilityCause<V>

    /** 충돌 해결에서 두 비호환을 결합해 유도 (P1-c 가 생성). */
    data class DerivedFrom<V : Comparable<V>>(val cause1: IncompId, val cause2: IncompId) : IncompatibilityCause<V>
}

/** 부분해(또는 가정)에서 패키지의 누적 항을 조회. null = 해당 패키지 할당 없음(= any). */
internal fun interface TermLookup<V : Comparable<V>> {
    fun termOf(pkg: PackageId): Term<V>?
}

/** 비호환과 항 집합의 관계. 인덱스는 [Incompatibility.termAt] 위치. */
internal sealed interface IncompatibilityRelation {
    data object Satisfied : IncompatibilityRelation

    data class Contradicted(val index: Int) : IncompatibilityRelation

    data class AlmostSatisfied(val index: Int) : IncompatibilityRelation

    data object Inconclusive : IncompatibilityRelation
}

/**
 * "이 항들은 동시에 참일 수 없다".
 *
 * # 불변식
 * - 패키지당 항 하나 (packageIds 서로 다름). 순서는 삽입 순 (의존 비호환은 [depender, dependency]).
 * - 어떤 항도 [Term.any] 가 아니다.
 * - 생성 후 불변. 식별은 [IncompId] 로만 한다 (equals 없음).
 */
internal class Incompatibility<V : Comparable<V>> private constructor(
    private val packageIds: IntArray,
    private val terms: List<Term<V>>,
    val cause: IncompatibilityCause<V>,
) {
    val size: Int get() = packageIds.size

    fun packageAt(index: Int): PackageId = PackageId(packageIds[index])

    fun termAt(index: Int): Term<V> = terms[index]

    fun indexOf(pkg: PackageId): Int {
        for (i in packageIds.indices) if (packageIds[i] == pkg.raw) return i
        return -1
    }

    fun get(pkg: PackageId): Term<V>? {
        val i = indexOf(pkg)
        return if (i < 0) null else terms[i]
    }

    /** rs `Incompatibility::relation` 과 같은 조기 종료 규칙 (두 번째 미결 항에서 즉시 Inconclusive). */
    fun relation(lookup: TermLookup<V>): IncompatibilityRelation {
        var almost = -1
        for (i in packageIds.indices) {
            val acc = lookup.termOf(PackageId(packageIds[i]))
            val r = if (acc == null) TermRelation.Inconclusive else terms[i].relation(acc)
            when (r) {
                TermRelation.Satisfied -> {}

                TermRelation.Contradicted -> return IncompatibilityRelation.Contradicted(i)

                TermRelation.Inconclusive ->
                    if (almost < 0) almost = i else return IncompatibilityRelation.Inconclusive
            }
        }
        return if (almost < 0) IncompatibilityRelation.Satisfied else IncompatibilityRelation.AlmostSatisfied(almost)
    }

    /** 해가 없음을 뜻하는가: 항 0개, 또는 루트 단일 항이 루트 버전을 포함. */
    fun isTerminal(root: PackageId, rootVersion: V): Boolean =
        when (size) {
            0 -> true
            1 -> packageIds[0] == root.raw && rootVersion in terms[0]
            else -> false
        }

    /**
     * 같은 (의존자, 피의존자)이고 피의존 범위가 같으면 의존자 범위를 합친 새 비호환. 아니면 null.
     * rs `merge_dependents`. 자기 의존은 합치지 않는다.
     */
    fun mergeDependents(other: Incompatibility<V>): Incompatibility<V>? {
        val a = cause as? IncompatibilityCause.FromDependencyOf ?: return null
        val b = other.cause as? IncompatibilityCause.FromDependencyOf ?: return null
        if (a.pkg != b.pkg || a.depPkg != b.depPkg || a.pkg == a.depPkg) return null
        if (a.depRange != b.depRange) return null
        return fromDependency(a.pkg, a.range.union(b.range), a.depPkg, a.depRange)
    }

    override fun toString(): String =
        (0 until size).joinToString(", ", "{", "}") { "#${packageIds[it]}: ${terms[it]}" } + " ← $cause"

    companion object {
        fun <V : Comparable<V>> notRoot(root: PackageId, version: V): Incompatibility<V> =
            Incompatibility(
                intArrayOf(root.raw),
                listOf(Term.Negative(VersionSet.singleton(version))),
                IncompatibilityCause.NotRoot(root, version),
            )

        fun <V : Comparable<V>> noVersions(pkg: PackageId, set: VersionSet<V>): Incompatibility<V> =
            Incompatibility(intArrayOf(pkg.raw), listOf(Term.Positive(set)), IncompatibilityCause.NoVersions(pkg, set))

        fun <V : Comparable<V>> unavailable(pkg: PackageId, version: V, reasonKo: String): Incompatibility<V> {
            val set = VersionSet.singleton(version)
            return Incompatibility(
                intArrayOf(pkg.raw),
                listOf(Term.Positive(set)),
                IncompatibilityCause.Unavailable(pkg, set, reasonKo),
            )
        }

        /**
         * `pkg ∈ range` 가 `depPkg ∈ depRange` 에 의존.
         * - depPkg ≠ pkg, depRange ≠ ∅ → `{pkg: Pos(range), depPkg: Neg(depRange)}`
         * - depPkg ≠ pkg, depRange = ∅ → `{pkg: Pos(range)}` (rs 와 같음)
         * - depPkg = pkg → `{pkg: Pos(range \ depRange)}`, 그게 ∅ 이면 null (항상 참인 의존 → 비호환 없음)
         */
        fun <V : Comparable<V>> fromDependency(
            pkg: PackageId,
            range: VersionSet<V>,
            depPkg: PackageId,
            depRange: VersionSet<V>,
        ): Incompatibility<V>? {
            val cause = IncompatibilityCause.FromDependencyOf(pkg, range, depPkg, depRange)
            if (depPkg == pkg) {
                val rest = range.difference(depRange)
                return if (rest.isEmpty) null else Incompatibility(intArrayOf(pkg.raw), listOf(Term.Positive(rest)), cause)
            }
            if (depRange.isEmpty) return Incompatibility(intArrayOf(pkg.raw), listOf(Term.Positive(range)), cause)
            return Incompatibility(
                intArrayOf(pkg.raw, depPkg.raw),
                listOf(Term.Positive(range), Term.Negative(depRange)),
                cause,
            )
        }

        /** 임의 항 목록 (중복 패키지·any 금지를 검사). P1-c 의 prior cause 와 테스트가 쓴다. */
        fun <V : Comparable<V>> fromTerms(
            entries: List<Pair<PackageId, Term<V>>>,
            cause: IncompatibilityCause<V>,
        ): Incompatibility<V> {
            val ids = IntArray(entries.size) { entries[it].first.raw }
            if (ids.toSet().size != ids.size) invariantViolated("중복 패키지 항")
            if (entries.any { it.second.isAny }) invariantViolated("any 항")
            return Incompatibility(ids, entries.map { it.second }, cause)
        }
    }
}

/** 비호환 아레나 (rs `Arena<Incompatibility>`). 할당만 하고 지우지 않는다. */
internal class IncompatibilityStore<V : Comparable<V>> {
    private val arena = ArrayList<Incompatibility<V>>()

    val size: Int get() = arena.size

    fun alloc(incompat: Incompatibility<V>): IncompId {
        arena.add(incompat)
        return IncompId(arena.size - 1)
    }

    operator fun get(id: IncompId): Incompatibility<V> = arena[id.raw]
}
