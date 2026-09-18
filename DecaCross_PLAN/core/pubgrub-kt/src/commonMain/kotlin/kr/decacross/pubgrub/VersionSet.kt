package kr.decacross.pubgrub

/**
 * 버전 집합 대수. PubGrub 의 항(term)이 다루는 "버전 범위"의 추상.
 *
 * 구현체는 [Range] 하나뿐이다(sealed). 솔버는 서로 다른 구현끼리 교집합·합집합을 계산할 수 없으므로
 * 한 솔버 안에서 버전 집합 타입은 하나여야 한다 (pubgrub-rs 의 연관 타입 `VS` 와 같은 제약).
 *
 * # 불변식
 * - **조밀 순서 의미론**: 임의의 두 값 사이, 그리고 양 끝 바깥에는 항상 다른 값이 있다고 가정한다.
 *   그래서 `(1,2) ∪ (2,3)` 은 병합되지 않는다(2 가 빠진다). 이산 타입(정수 등)을 쓸 때는 제약을
 *   반열린 구간 `[lo, 첫 불일치 버전)` 으로 만들어라 — 같은 정수 집합이 서로 다른 구조로 표현될 수 있다.
 * - 모든 연산 결과는 정규형이다. 따라서 `equals`/`hashCode` 는 집합 동등성과 일치한다.
 * - `V` 는 `a.compareTo(b) == 0` ⇔ `a == b` 를 만족해야 한다 (경계 비교는 compareTo, 구조 비교는 equals).
 * - `contains(v)` 는 집합 연산 결과와 일치한다: `a.intersect(b).contains(v) == (a.contains(v) && b.contains(v))`.
 */
public sealed interface VersionSet<V : Comparable<V>> {
    /** 원소가 하나도 없는가. */
    public val isEmpty: Boolean

    /** 모든 값을 포함하는가. */
    public val isFull: Boolean

    public operator fun contains(v: V): Boolean

    public fun intersect(other: VersionSet<V>): VersionSet<V>

    public fun union(other: VersionSet<V>): VersionSet<V>

    public fun complement(): VersionSet<V>

    /** `this ∩ ¬other`. */
    public fun difference(other: VersionSet<V>): VersionSet<V>

    /** `this ∩ other == ∅` 를 교집합을 만들지 않고 한 번의 순회로 판정한다. */
    public fun isDisjointFrom(other: VersionSet<V>): Boolean

    /** `this ⊆ other` 를 한 번의 순회로 판정한다. */
    public fun isSubsetOf(other: VersionSet<V>): Boolean

    public companion object {
        /** 공집합. */
        public fun <V : Comparable<V>> empty(): VersionSet<V> = Range.empty()

        /** 전체집합. */
        public fun <V : Comparable<V>> full(): VersionSet<V> = Range.full()

        /** 단일 버전만 포함하는 집합. */
        public fun <V : Comparable<V>> singleton(v: V): VersionSet<V> = Range.singleton(v)
    }
}
