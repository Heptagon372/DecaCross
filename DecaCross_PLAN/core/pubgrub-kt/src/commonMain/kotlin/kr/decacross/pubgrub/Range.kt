package kr.decacross.pubgrub

/**
 * 구간의 한쪽 경계.
 *
 * - [Unbounded]: 경계 없음 (하한이면 -∞, 상한이면 +∞)
 * - [Included]: 값을 포함하는 경계 (`[v` 또는 `v]`)
 * - [Excluded]: 값을 제외하는 경계 (`(v` 또는 `v)`)
 */
public sealed interface Bound<out V> {
    public data object Unbounded : Bound<Nothing>

    public data class Included<V>(val value: V) : Bound<V>

    public data class Excluded<V>(val value: V) : Bound<V>
}

/**
 * 하한·상한 한 쌍으로 이루어진 연속 구간. [Range] 의 구성 단위.
 *
 * # 불변식
 * - [Range] 안에 들어 있는 Interval 은 항상 비어 있지 않다 (정규화 시 빈 구간은 제거된다).
 */
public data class Interval<V : Comparable<V>>(val lower: Bound<V>, val upper: Bound<V>) {
    /** 이 구간에 원소가 하나라도 있는가. */
    public val isNonEmpty: Boolean
        get() = isNonEmptyInterval(lower, upper)

    override fun toString(): String = formatInterval(lower, upper)
}

/**
 * 정렬된 disjoint 구간들의 합집합으로 표현한 기본 [VersionSet].
 *
 * # 불변식 (정규형)
 * - [intervals] 는 하한 기준 오름차순으로 정렬되어 있다.
 * - 구간끼리 겹치거나 맞닿지 않는다 (맞닿는 구간은 병합되어 있다).
 *   `[1, 2)` 와 `[2, 3]` 은 맞닿으므로 `[1, 3]` 하나로 존재한다.
 *   `[1, 2)` 와 `(2, 3]` 은 2 가 빠져 있으므로 두 구간으로 남는다.
 * - 빈 구간은 없다.
 * - 정규형은 유일하므로 `equals` 는 구조 비교로 충분하다.
 *
 * 모든 연산은 정규형을 입력받아 정규형을 반환하며, 구간 수에 대해 선형이다.
 * 다른 [VersionSet] 구현과 섞어 쓸 수 없다 (인자가 Range 가 아니면 [IllegalArgumentException]).
 */
public class Range<V : Comparable<V>> private constructor(
    /** 정규형 구간 목록. */
    public val intervals: List<Interval<V>>,
) : VersionSet<V> {
    override val isEmpty: Boolean
        get() = intervals.isEmpty()

    /** 전체집합인가. */
    public val isFull: Boolean
        get() = intervals.size == 1 && intervals[0].lower is Bound.Unbounded && intervals[0].upper is Bound.Unbounded

    override fun contains(v: V): Boolean {
        // 정렬돼 있으므로 이진 탐색
        var lo = 0
        var hi = intervals.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val iv = intervals[mid]
            when {
                isBelowLower(v, iv.lower) -> hi = mid - 1
                isAboveUpper(v, iv.upper) -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    override fun intersect(other: VersionSet<V>): Range<V> {
        val b = other.asRange()
        val a = this
        if (a.isEmpty || b.isEmpty) return empty()
        if (a.isFull) return b
        if (b.isFull) return a
        val out = ArrayList<Interval<V>>(minOf(a.intervals.size, b.intervals.size))
        var i = 0
        var j = 0
        while (i < a.intervals.size && j < b.intervals.size) {
            val x = a.intervals[i]
            val y = b.intervals[j]
            val lower = if (compareLower(x.lower, y.lower) >= 0) x.lower else y.lower
            val upper = if (compareUpper(x.upper, y.upper) <= 0) x.upper else y.upper
            if (isNonEmptyInterval(lower, upper)) out += Interval(lower, upper)
            // 상한이 작은 쪽을 전진시킨다
            if (compareUpper(x.upper, y.upper) <= 0) i++ else j++
        }
        return Range(out)
    }

    override fun union(other: VersionSet<V>): Range<V> {
        val b = other.asRange()
        if (isEmpty) return b
        if (b.isEmpty) return this
        // 드모르간: a ∪ b = ¬(¬a ∩ ¬b). 각 단계가 선형이므로 전체도 선형.
        return complement().intersect(b.complement()).complement()
    }

    override fun complement(): Range<V> {
        if (intervals.isEmpty()) return full()
        val out = ArrayList<Interval<V>>(intervals.size + 1)
        val first = intervals.first()
        if (first.lower !is Bound.Unbounded) out += Interval(Bound.Unbounded, flip(first.lower))
        for (k in 1 until intervals.size) {
            out += Interval(flip(intervals[k - 1].upper), flip(intervals[k].lower))
        }
        val last = intervals.last()
        if (last.upper !is Bound.Unbounded) out += Interval(flip(last.upper), Bound.Unbounded)
        return Range(out)
    }

    /** 이 집합이 [other] 의 부분집합인가. */
    public fun subsetOf(other: VersionSet<V>): Boolean = intersect(other.complement()).isEmpty

    /** 두 집합이 서로소인가. */
    public fun isDisjoint(other: VersionSet<V>): Boolean = intersect(other).isEmpty

    override fun equals(other: Any?): Boolean = other is Range<*> && other.intervals == intervals

    override fun hashCode(): Int = intervals.hashCode()

    /**
     * 사람이 읽을 수 있는 표기.
     * `∅`, `*`, `1.0.0`(단일 버전), `>=1.0.0 <2.0.0`, `>1.0.0`, `<=2.0.0`, 여러 구간은 ` || ` 로 연결.
     */
    override fun toString(): String =
        when {
            isEmpty -> "∅"
            isFull -> "*"
            else -> intervals.joinToString(" || ") { formatInterval(it.lower, it.upper) }
        }

    public companion object {
        /** 공집합. */
        public fun <V : Comparable<V>> empty(): Range<V> = Range(emptyList())

        /** 전체집합. */
        public fun <V : Comparable<V>> full(): Range<V> = Range(listOf(Interval(Bound.Unbounded, Bound.Unbounded)))

        /** 단일 버전 `{v}`. */
        public fun <V : Comparable<V>> singleton(v: V): Range<V> = Range(listOf(Interval(Bound.Included(v), Bound.Included(v))))

        /** [singleton] 의 별칭. */
        public fun <V : Comparable<V>> exact(v: V): Range<V> = singleton(v)

        /** `[v, +∞)` — v 이상. */
        public fun <V : Comparable<V>> higherThan(v: V): Range<V> = Range(listOf(Interval(Bound.Included(v), Bound.Unbounded)))

        /** `(v, +∞)` — v 초과. */
        public fun <V : Comparable<V>> strictlyHigherThan(v: V): Range<V> = Range(listOf(Interval(Bound.Excluded(v), Bound.Unbounded)))

        /** `(-∞, v)` — v 미만. */
        public fun <V : Comparable<V>> strictlyLowerThan(v: V): Range<V> = Range(listOf(Interval(Bound.Unbounded, Bound.Excluded(v))))

        /** `(-∞, v]` — v 이하. */
        public fun <V : Comparable<V>> atMost(v: V): Range<V> = Range(listOf(Interval(Bound.Unbounded, Bound.Included(v))))

        /** `[lo, hiExcl)` — lo 이상 hiExcl 미만. 순서가 뒤집히면 공집합. */
        public fun <V : Comparable<V>> between(lo: V, hiExcl: V): Range<V> = of(Bound.Included(lo), Bound.Excluded(hiExcl))

        /** `[lo, hi]` — lo 이상 hi 이하. 순서가 뒤집히면 공집합. */
        public fun <V : Comparable<V>> betweenInclusive(lo: V, hi: V): Range<V> = of(Bound.Included(lo), Bound.Included(hi))

        /** 임의 경계 한 쌍으로 만든 단일 구간. 비어 있으면 공집합. */
        public fun <V : Comparable<V>> of(lower: Bound<V>, upper: Bound<V>): Range<V> =
            if (isNonEmptyInterval(lower, upper)) Range(listOf(Interval(lower, upper))) else empty()

        /** 임의의 구간 목록을 정규화해서 만든다 (정렬·병합·빈 구간 제거). `O(n log n)`. */
        public fun <V : Comparable<V>> fromIntervals(raw: Iterable<Interval<V>>): Range<V> {
            val sorted = raw.filter { it.isNonEmpty }.sortedWith { a, b -> compareLower(a.lower, b.lower) }
            if (sorted.isEmpty()) return empty()
            val out = ArrayList<Interval<V>>(sorted.size)
            var cur = sorted[0]
            for (k in 1 until sorted.size) {
                val next = sorted[k]
                if (touchesOrOverlaps(cur.upper, next.lower)) {
                    val upper = if (compareUpper(cur.upper, next.upper) >= 0) cur.upper else next.upper
                    cur = Interval(cur.lower, upper)
                } else {
                    out += cur
                    cur = next
                }
            }
            out += cur
            return Range(out)
        }

        /** 여러 버전의 유한 집합. */
        public fun <V : Comparable<V>> ofVersions(versions: Iterable<V>): Range<V> =
            fromIntervals(versions.map { Interval(Bound.Included(it), Bound.Included(it)) })
    }
}

// ---- 내부 유틸 --------------------------------------------------------------

internal fun <V : Comparable<V>> VersionSet<V>.asRange(): Range<V> =
    this as? Range<V>
        ?: throw IllegalArgumentException("Range 는 다른 VersionSet 구현과 섞어 쓸 수 없습니다: ${this::class.simpleName}")

/** v 가 하한보다 아래인가. */
private fun <V : Comparable<V>> isBelowLower(v: V, lower: Bound<V>): Boolean =
    when (lower) {
        Bound.Unbounded -> false
        is Bound.Included -> v < lower.value
        is Bound.Excluded -> v <= lower.value
    }

/** v 가 상한보다 위인가. */
private fun <V : Comparable<V>> isAboveUpper(v: V, upper: Bound<V>): Boolean =
    when (upper) {
        Bound.Unbounded -> false
        is Bound.Included -> v > upper.value
        is Bound.Excluded -> v >= upper.value
    }

/** 하한끼리 비교. Unbounded < Included(v) < Excluded(v) (같은 v 기준: 포함이 더 넓다). */
private fun <V : Comparable<V>> compareLower(a: Bound<V>, b: Bound<V>): Int {
    if (a is Bound.Unbounded) return if (b is Bound.Unbounded) 0 else -1
    if (b is Bound.Unbounded) return 1
    val c = valueOf(a).compareTo(valueOf(b))
    if (c != 0) return c
    return lowerRank(a) - lowerRank(b)
}

/** 상한끼리 비교. Excluded(v) < Included(v) < Unbounded. */
private fun <V : Comparable<V>> compareUpper(a: Bound<V>, b: Bound<V>): Int {
    if (a is Bound.Unbounded) return if (b is Bound.Unbounded) 0 else 1
    if (b is Bound.Unbounded) return -1
    val c = valueOf(a).compareTo(valueOf(b))
    if (c != 0) return c
    return upperRank(a) - upperRank(b)
}

private fun <V> lowerRank(b: Bound<V>): Int = if (b is Bound.Included) 0 else 1

private fun <V> upperRank(b: Bound<V>): Int = if (b is Bound.Excluded) 0 else 1

private fun <V> valueOf(b: Bound<V>): V =
    when (b) {
        is Bound.Included -> b.value
        is Bound.Excluded -> b.value
        Bound.Unbounded -> throw IllegalStateException("Unbounded 에는 값이 없습니다")
    }

/** 경계 뒤집기: 상한 `v]` 은 여집합에서 하한 `(v` 가 되고, `v)` 은 `[v` 가 된다. */
private fun <V> flip(b: Bound<V>): Bound<V> =
    when (b) {
        is Bound.Included -> Bound.Excluded(b.value)
        is Bound.Excluded -> Bound.Included(b.value)
        Bound.Unbounded -> Bound.Unbounded
    }

internal fun <V : Comparable<V>> isNonEmptyInterval(lower: Bound<V>, upper: Bound<V>): Boolean {
    if (lower is Bound.Unbounded || upper is Bound.Unbounded) return true
    val c = valueOf(lower).compareTo(valueOf(upper))
    return when {
        c < 0 -> true
        c > 0 -> false
        else -> lower is Bound.Included && upper is Bound.Included
    }
}

/** 앞 구간의 상한과 뒤 구간의 하한이 겹치거나 맞닿는가 (병합 가능한가). */
private fun <V : Comparable<V>> touchesOrOverlaps(prevUpper: Bound<V>, nextLower: Bound<V>): Boolean {
    if (prevUpper is Bound.Unbounded || nextLower is Bound.Unbounded) return true
    val c = valueOf(nextLower).compareTo(valueOf(prevUpper))
    return when {
        c < 0 -> true

        c > 0 -> false

        // 같은 값에서 만남: 둘 다 Excluded 면 그 값이 빠져 있어 병합 불가
        else -> prevUpper is Bound.Included || nextLower is Bound.Included
    }
}

private fun <V : Comparable<V>> formatInterval(lower: Bound<V>, upper: Bound<V>): String {
    if (lower is Bound.Included && upper is Bound.Included && lower.value == upper.value) return lower.value.toString()
    val lo =
        when (lower) {
            Bound.Unbounded -> null
            is Bound.Included -> ">=${lower.value}"
            is Bound.Excluded -> ">${lower.value}"
        }
    val hi =
        when (upper) {
            Bound.Unbounded -> null
            is Bound.Included -> "<=${upper.value}"
            is Bound.Excluded -> "<${upper.value}"
        }
    return listOfNotNull(lo, hi).ifEmpty { listOf("*") }.joinToString(" ")
}
