package kr.decacross.pubgrub

/**
 * 버전 집합 대수. PubGrub 의 항(term)이 다루는 "버전 범위"의 추상.
 *
 * # 불변식
 * - `intersect` / `union` / `complement` 는 항상 정규형을 반환한다 (구현체 책임).
 * - `contains(v)` 는 집합 연산 결과와 일치해야 한다: `a.intersect(b).contains(v) == a.contains(v) && b.contains(v)`.
 * - 구현체는 정규형 기준의 구조적 `equals`/`hashCode` 를 제공해야 한다. 솔버는 항(term)의 동일성 판정에 이를 쓴다.
 * - 기본 구현은 [Range] 이며, 서로 다른 구현을 섞어 쓸 수 없다.
 */
public interface VersionSet<V : Comparable<V>> {
    /** 원소가 하나도 없는가. */
    public val isEmpty: Boolean

    public fun contains(v: V): Boolean

    public fun intersect(other: VersionSet<V>): VersionSet<V>

    public fun union(other: VersionSet<V>): VersionSet<V>

    public fun complement(): VersionSet<V>

    public companion object {
        /** 공집합. */
        public fun <V : Comparable<V>> empty(): VersionSet<V> = Range.empty()

        /** 전체집합. */
        public fun <V : Comparable<V>> full(): VersionSet<V> = Range.full()

        /** 단일 버전만 포함하는 집합. */
        public fun <V : Comparable<V>> singleton(v: V): VersionSet<V> = Range.singleton(v)
    }
}

/** 전체집합인가 (`complement()` 가 공집합). */
public val <V : Comparable<V>> VersionSet<V>.isFull: Boolean
    get() = complement().isEmpty

/** `this ⊆ other`. */
public fun <V : Comparable<V>> VersionSet<V>.subsetOf(other: VersionSet<V>): Boolean = intersect(other.complement()).isEmpty

/** `this ∩ other = ∅`. */
public fun <V : Comparable<V>> VersionSet<V>.isDisjoint(other: VersionSet<V>): Boolean = intersect(other).isEmpty
