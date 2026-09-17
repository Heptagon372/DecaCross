package kr.decacross.pubgrub

/**
 * 구간 경계. 하한 자리의 [Unbounded] 는 -∞, 상한 자리의 [Unbounded] 는 +∞ 를 뜻한다.
 */
public sealed interface Bound<out V> {
    /** 경계값 포함 (`[v` 또는 `v]`). */
    public data class Inclusive<out V>(public val value: V) : Bound<V>

    /** 경계값 미포함 (`(v` 또는 `v)`). */
    public data class Exclusive<out V>(public val value: V) : Bound<V>

    /** 무한 (하한이면 -∞, 상한이면 +∞). */
    public data object Unbounded : Bound<Nothing>
}

/**
 * 검증되지 않은 경계 한 쌍. 비어 있거나 역전된 구간도 표현할 수 있다.
 * 정규형(원소가 있고, 정렬·병합됨)은 [Range] 만 보장한다.
 */
public data class Interval<out V>(public val lower: Bound<V>, public val upper: Bound<V>)
