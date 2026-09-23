package kr.decacross.pubgrub

/**
 * PubGrub 의 항(term). 한 패키지에 대한 진술이다.
 *
 * - `positive = true`  : "패키지가 선택되어 있고, 그 버전은 [set] 안에 있다"
 * - `positive = false` : "패키지가 선택되지 않았거나, 선택됐다면 그 버전은 [set] 밖에 있다"
 *
 * 부정 항은 "선택 안 됨"을 허용한다는 점이 핵심이다. 그래서 `¬(p ∈ r)` 은 `p ∈ ¬r` 과 같지 않다.
 * 부분집합·교집합 판정은 전부 이 의미론을 따른다 (pubgrub-rs `term.rs` 와 동일).
 *
 * # 불변식
 * - `Term(false, ∅)` 는 항상 참(any), `Term(true, ∅)` 는 항상 거짓이다.
 * - 항의 동일성은 (positive, set) 구조 비교다. 정규형 VersionSet 을 전제로 한다.
 */
public data class Term<V : Comparable<V>>(val positive: Boolean, val set: VersionSet<V>) {
    /** 부정: 긍정 ↔ 부정을 뒤집는다. */
    public fun negate(): Term<V> = Term(!positive, set)

    /** 이 항이 버전 [v] 의 선택을 허용하는가. */
    public fun contains(v: V): Boolean = if (positive) set.contains(v) else !set.contains(v)

    /** 항상 참인 항 (`¬(p ∈ ∅)`). */
    public val isAny: Boolean
        get() = !positive && set.isEmpty

    /** 항상 거짓인 항 (`p ∈ ∅`). */
    public val isNever: Boolean
        get() = positive && set.isEmpty

    /**
     * 이 항이 허용하는 버전 집합. 부정 항은 여집합으로 표현한다.
     * [DerivationTree.Derived.terms] 가 이 규약을 쓴다 (극성 정보는 사라진다).
     */
    public val allowed: VersionSet<V>
        get() = if (positive) set else set.complement()

    /** 두 항의 논리곱. */
    public fun intersect(other: Term<V>): Term<V> =
        when {
            positive && other.positive -> Term(true, set.intersect(other.set))
            positive && !other.positive -> Term(true, set.intersect(other.set.complement()))
            !positive && other.positive -> Term(true, set.complement().intersect(other.set))
            else -> Term(false, set.union(other.set))
        }

    /** 두 항의 논리합 (드모르간). */
    public fun union(other: Term<V>): Term<V> = negate().intersect(other.negate()).negate()

    /** `this ⇒ other` 인가 (this 가 참이면 other 도 참). */
    public fun subsetOf(other: Term<V>): Boolean =
        when {
            positive && other.positive -> set.subsetOf(other.set)

            positive && !other.positive -> set.isDisjoint(other.set)

            // 부정 항은 "선택 안 됨"을 허용하므로 긍정 항을 함의할 수 없다
            !positive && other.positive -> false

            else -> other.set.subsetOf(set)
        }

    /**
     * 부분해의 누적 항 [assignment] 가 이 항(incompatibility 의 항)과 어떤 관계인가.
     *
     * - [TermRelation.Satisfied]: assignment ⇒ this
     * - [TermRelation.Contradicted]: assignment ∧ this 가 항상 거짓
     * - [TermRelation.Inconclusive]: 그 외
     */
    public fun relationWith(assignment: Term<V>): TermRelation =
        when {
            assignment.subsetOf(this) -> TermRelation.Satisfied
            intersect(assignment).isNever -> TermRelation.Contradicted
            else -> TermRelation.Inconclusive
        }

    override fun toString(): String = if (positive) set.toString() else "not $set"

    public companion object {
        public fun <V : Comparable<V>> positive(set: VersionSet<V>): Term<V> = Term(true, set)

        public fun <V : Comparable<V>> negative(set: VersionSet<V>): Term<V> = Term(false, set)

        /** 정확히 한 버전 (결정에 쓴다). */
        public fun <V : Comparable<V>> exact(v: V): Term<V> = Term(true, VersionSet.singleton(v))

        /** 항상 참인 항. */
        public fun <V : Comparable<V>> any(): Term<V> = Term(false, VersionSet.empty())
    }
}

/** 항 하나와 부분해 누적 항의 관계. */
public enum class TermRelation { Satisfied, Contradicted, Inconclusive }
