package kr.decacross.pubgrub

// 출처: pubgrub-rs (MPL-2.0) src/term.rs 의 알고리즘 번역 (commit 0399630, 2026-09-17 확인). 라이선스 정책은 Q3 (사용자 결정 대기).

/**
 * PubGrub 의 항(term). 한 패키지에 대한 긍정/부정 진술이며, 패키지 자체는 항을 담는 쪽(비호환의 키, 부분해의 슬롯)이 들고 있다.
 *
 * - [Positive] `S`: 패키지가 **선택되었고** 버전이 `S` 안에 있다.
 * - [Negative] `S`: 패키지가 **선택되지 않았거나** 버전이 `S` 밖에 있다.
 *
 * 그래서 `Positive(S)` 와 `Negative(¬S)` 는 다르다 (미선택 허용 여부).
 *
 * # 불변식
 * - `Positive(∅)` = 거짓(“empty”), `Negative(∅)` = 항상 참(“any”). [VersionSet] 정규형 덕에 `equals` 로 판별된다.
 * - 대수 연산은 전부 internal 확장 함수다. 이 타입을 P1-d 에서 public 으로 열어도 데이터만 공개된다.
 */
internal sealed interface Term<V : Comparable<V>> {
    val set: VersionSet<V>

    data class Positive<V : Comparable<V>>(override val set: VersionSet<V>) : Term<V> {
        override fun toString(): String = set.toString()
    }

    data class Negative<V : Comparable<V>>(override val set: VersionSet<V>) : Term<V> {
        override fun toString(): String = "Not ( $set )"
    }

    companion object {
        /** 항상 참인 항 `Negative(∅)`. 비호환에는 절대 들어가지 않는다. */
        fun <V : Comparable<V>> any(): Term<V> = Negative(VersionSet.empty())

        /** 절대 참이 아닌 항 `Positive(∅)`. */
        fun <V : Comparable<V>> empty(): Term<V> = Positive(VersionSet.empty())

        /** `Positive({v})`. */
        fun <V : Comparable<V>> exact(v: V): Term<V> = Positive(VersionSet.singleton(v))
    }
}

/** 항 집합 S 와 항 t 의 관계. rs `term::Relation`. */
internal enum class TermRelation { Satisfied, Contradicted, Inconclusive }

internal val <V : Comparable<V>> Term<V>.isPositive: Boolean get() = this is Term.Positive

internal val <V : Comparable<V>> Term<V>.isAny: Boolean get() = this is Term.Negative && set.isEmpty

internal fun <V : Comparable<V>> Term<V>.negate(): Term<V> =
    when (this) {
        is Term.Positive -> Term.Negative(set)
        is Term.Negative -> Term.Positive(set)
    }

/** 버전 v 가 선택됐을 때 이 항이 참인가. */
internal operator fun <V : Comparable<V>> Term<V>.contains(v: V): Boolean =
    when (this) {
        is Term.Positive -> v in set
        is Term.Negative -> v !in set
    }

/** 교집합. 둘 다 부정일 때만 부정. */
internal fun <V : Comparable<V>> Term<V>.intersection(other: Term<V>): Term<V> =
    when (this) {
        is Term.Positive ->
            when (other) {
                is Term.Positive -> Term.Positive(set.intersect(other.set))
                is Term.Negative -> Term.Positive(set.difference(other.set))
            }

        is Term.Negative ->
            when (other) {
                is Term.Positive -> Term.Positive(other.set.difference(set))
                is Term.Negative -> Term.Negative(set.union(other.set))
            }
    }

/** 합집합. 하나라도 부정이면 부정. */
internal fun <V : Comparable<V>> Term<V>.union(other: Term<V>): Term<V> =
    when (this) {
        is Term.Positive ->
            when (other) {
                is Term.Positive -> Term.Positive(set.union(other.set))
                is Term.Negative -> Term.Negative(other.set.difference(set))
            }

        is Term.Negative ->
            when (other) {
                is Term.Positive -> Term.Negative(set.difference(other.set))
                is Term.Negative -> Term.Negative(set.intersect(other.set))
            }
    }

/** `this ∩ other == Term.empty()` 를 교집합 없이 판정 (rs `is_disjoint`). */
internal fun <V : Comparable<V>> Term<V>.isDisjoint(other: Term<V>): Boolean =
    when (this) {
        is Term.Positive ->
            when (other) {
                is Term.Positive -> set.isDisjointFrom(other.set)
                is Term.Negative -> set.isSubsetOf(other.set)
            }

        is Term.Negative ->
            when (other) {
                is Term.Positive -> other.set.isSubsetOf(set)
                is Term.Negative -> false
            }
    }

/** `this ∩ other == this` (rs `subset_of`, rs 에선 테스트 전용). */
internal fun <V : Comparable<V>> Term<V>.isSubsetOf(other: Term<V>): Boolean =
    when (this) {
        is Term.Positive ->
            when (other) {
                is Term.Positive -> set.isSubsetOf(other.set)
                is Term.Negative -> set.isDisjointFrom(other.set)
            }

        is Term.Negative ->
            when (other) {
                is Term.Positive -> false
                is Term.Negative -> other.set.isSubsetOf(set)
            }
    }

/** 프롬프트 P1-b 이름: `this` 가 참이면 [other] 도 참인가 (`this ⊆ other`, Dart `Term.satisfies`). */
internal fun <V : Comparable<V>> Term<V>.satisfies(other: Term<V>): Boolean = isSubsetOf(other)

/** 프롬프트 P1-b 이름: 둘이 동시에 참일 수 없는가 (`this ∩ other = ∅`, rs `contradicted_by`). */
internal fun <V : Comparable<V>> Term<V>.contradicts(other: Term<V>): Boolean = isDisjoint(other)

/**
 * 프롬프트 P1-b 의 `relation(other)`.
 * `this` 가 비호환의 항 t, [accumulated] 가 부분해의 같은 패키지 누적 교집합 ⋂S 일 때 S 와 t 의 관계.
 * Satisfied ⇔ ⋂S ⊆ t, Contradicted ⇔ ⋂S ∩ t = ∅, 둘 다면 Satisfied 우선 (`⋂S = Positive(∅)` 일 때만 겹친다).
 */
internal fun <V : Comparable<V>> Term<V>.relation(accumulated: Term<V>): TermRelation =
    when (this) {
        is Term.Positive ->
            when (accumulated) {
                is Term.Positive ->
                    when {
                        accumulated.set.isSubsetOf(set) -> TermRelation.Satisfied
                        accumulated.set.isDisjointFrom(set) -> TermRelation.Contradicted
                        else -> TermRelation.Inconclusive
                    }

                is Term.Negative ->
                    if (set.isSubsetOf(accumulated.set)) TermRelation.Contradicted else TermRelation.Inconclusive
            }

        is Term.Negative ->
            when (accumulated) {
                is Term.Positive ->
                    when {
                        // rs #443 특례: Positive(∅) 는 모든 항을 만족시키며, 만족이 모순보다 우선한다.
                        accumulated.set.isEmpty -> TermRelation.Satisfied

                        accumulated.set.isSubsetOf(set) -> TermRelation.Contradicted

                        accumulated.set.isDisjointFrom(set) -> TermRelation.Satisfied

                        else -> TermRelation.Inconclusive
                    }

                is Term.Negative ->
                    if (set.isSubsetOf(accumulated.set)) TermRelation.Satisfied else TermRelation.Inconclusive
            }
    }
