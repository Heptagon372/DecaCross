package kr.decacross.pubgrub

/**
 * 정렬된 서로소 구간 리스트로 표현한 [VersionSet] 의 유일한 구현.
 *
 * # 불변식 (정규형)
 * - N1: 모든 구간이 원소를 하나 이상 가진다 (`[a,a)`, `(a,a]`, `(a,a)`, 역전 구간 없음).
 * - N2: 이웃한 두 구간 사이에는 어느 쪽에도 속하지 않는 점이 있다 (정렬·병합 완료).
 *   → `Unbounded` 는 첫 구간의 하한이나 마지막 구간의 상한에만 올 수 있다.
 * - 공집합은 `[]`, 전체집합은 `[(-∞, +∞)]`.
 * - N1·N2 가 성립하면 구조적 동등 = 집합 동등이다 ([equals] 가 구간 리스트 비교로 충분한 이유).
 *
 * 비용: [intersect]·[union]·[isDisjointFrom]·[isSubsetOf] 는 구간 수에 선형, [contains] 는 O(log n),
 * [fromIntervals] 는 O(k log k). 어떤 연산도 throw 하지 않는다.
 *
 * 팩토리 이름은 pubgrub-rs `version-ranges` 와 같다 (골든 케이스 1:1 이식). ★ [higherThan] 은 `>=` (포함) 이다.
 * 표기·팩토리 이름은 pubgrub-rs version-ranges(MPL-2.0)를 참고한 독립 구현이다.
 */
public class Range<V : Comparable<V>> private constructor(
    private val segments: List<Interval<V>>,
) : VersionSet<V> {
    /** 정규형 구간 목록 (읽기 전용 뷰). */
    public val intervals: List<Interval<V>> get() = segments

    override val isEmpty: Boolean get() = segments.isEmpty()

    override val isFull: Boolean
        get() = segments.size == 1 && segments[0].lower is Bound.Unbounded && segments[0].upper is Bound.Unbounded

    /** 구간이 정렬돼 있으므로 이진 탐색한다. 람다를 받는 `binarySearch` 대신 직접 구현해 할당이 없다. */
    override operator fun contains(v: V): Boolean {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                belowLower(v, s.lower) -> hi = mid - 1
                aboveUpper(v, s.upper) -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * 두 포인터. 늦게 시작하는 하한과 먼저 끝나는 상한으로 후보 구간을 만들고, 먼저 끝나는 쪽을 전진한다.
     * 입력이 정규형이면 출력도 정규형이라 별도 정규화 단계가 없다.
     */
    override fun intersect(other: VersionSet<V>): Range<V> {
        val a = segments
        val b = other.asRange().segments
        val out = ArrayList<Interval<V>>(minOf(a.size + b.size, INITIAL_CAPACITY))
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val x = a[i]
            val y = b[j]
            val lo = if (cmpStart(x.lower, y.lower) >= 0) x.lower else y.lower
            val c = cmpEnd(x.upper, y.upper)
            val hi = if (c <= 0) x.upper else y.upper
            if (valid(lo, hi)) out += Interval(lo, hi)
            if (c <= 0) i++ else j++
        }
        return Range(out)
    }

    /** 두 목록을 시작 경계 순으로 병합하면서 겹치거나 맞닿은 구간을 합친다. */
    override fun union(other: VersionSet<V>): Range<V> {
        val o = other.asRange()
        val a = segments
        val b = o.segments
        if (a.isEmpty()) return o
        if (b.isEmpty()) return this
        val out = ArrayList<Interval<V>>(a.size + b.size)
        var i = 0
        var j = 0
        // 누적 구간은 시작이 가장 이른 구간으로 연다 (같으면 A 우선).
        val first = if (cmpStart(a[0].lower, b[0].lower) <= 0) a[i++] else b[j++]
        var accLo = first.lower
        var accHi = first.upper
        while (i < a.size || j < b.size) {
            val next =
                when {
                    i >= a.size -> b[j++]
                    j >= b.size -> a[i++]
                    cmpStart(a[i].lower, b[j].lower) <= 0 -> a[i++]
                    else -> b[j++]
                }
            if (gap(accHi, next.lower)) {
                out += Interval(accLo, accHi)
                accLo = next.lower
                accHi = next.upper
            } else if (cmpEnd(next.upper, accHi) > 0) {
                accHi = next.upper
            }
        }
        out += Interval(accLo, accHi)
        return Range(out)
    }

    /** 구간 사이의 틈(과 양 끝 바깥)을 경계 종류를 뒤집어 구간으로 만든다. */
    override fun complement(): Range<V> {
        if (segments.isEmpty()) return full()
        val out = ArrayList<Interval<V>>(segments.size + 1)
        var start: Bound<V> = Bound.Unbounded
        for (k in segments.indices) {
            val s = segments[k]
            // 첫 구간이 -∞ 에서 시작하면 그 앞에는 여집합 구간이 없다.
            if (k != 0 || s.lower !is Bound.Unbounded) {
                out += Interval(start, flip(s.lower))
            }
            // 마지막 구간이 +∞ 까지 가면 뒤에도 여집합 구간이 없다.
            if (s.upper is Bound.Unbounded) return Range(out)
            start = flip(s.upper)
        }
        out += Interval(start, Bound.Unbounded)
        return Range(out)
    }

    override fun difference(other: VersionSet<V>): Range<V> = intersect(other.asRange().complement())

    override fun isDisjointFrom(other: VersionSet<V>): Boolean {
        val a = segments
        val b = other.asRange().segments
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                // a[i] 가 b[j] 보다 완전히 앞에 있다.
                !valid(b[j].lower, a[i].upper) -> i++

                // b[j] 가 a[i] 보다 완전히 앞에 있다.
                !valid(a[i].lower, b[j].upper) -> j++

                else -> return false
            }
        }
        return true
    }

    override fun isSubsetOf(other: VersionSet<V>): Boolean {
        val b = other.asRange().segments
        var j = 0
        for (s in segments) {
            // s 보다 완전히 앞에 있는 B 구간은 건너뛴다. 정규형 B 에서 s 를 덮을 수 있는 구간은 그다음 하나뿐이다.
            while (j < b.size && !valid(s.lower, b[j].upper)) j++
            if (j >= b.size) return false
            val c = b[j]
            if (cmpStart(c.lower, s.lower) > 0 || cmpEnd(s.upper, c.upper) > 0) return false
        }
        return true
    }

    /** 정확히 한 버전만 담은 집합이면 그 버전, 아니면 null. */
    public fun asSingleton(): V? {
        if (segments.size != 1) return null
        val lo = segments[0].lower
        val hi = segments[0].upper
        return if (lo is Bound.Inclusive && hi is Bound.Inclusive && lo.value.compareTo(hi.value) == 0) lo.value else null
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Range<*> && other.segments == segments)

    override fun hashCode(): Int = segments.hashCode()

    /** version-ranges `Display` 와 같은 형식: `∅`, `*`, `3`, `>=1, <3`, `<1 | >1, <2` … */
    override fun toString(): String {
        if (segments.isEmpty()) return "∅"
        return segments.joinToString(" | ") { segmentToString(it) }
    }

    public companion object {
        /** 교집합 결과 목록의 초기 용량 상한. 대부분 구간이 몇 개뿐이라 과할당을 피한다. */
        private const val INITIAL_CAPACITY = 8

        /** 공집합 `∅`. */
        public fun <V : Comparable<V>> empty(): Range<V> = Range(emptyList())

        /** 전체집합 `*`. */
        public fun <V : Comparable<V>> full(): Range<V> = Range(listOf(Interval(Bound.Unbounded, Bound.Unbounded)))

        /** `{v}`. */
        public fun <V : Comparable<V>> singleton(v: V): Range<V> =
            Range(listOf(Interval(Bound.Inclusive(v), Bound.Inclusive(v))))

        /** `>= v` (포함). */
        public fun <V : Comparable<V>> higherThan(v: V): Range<V> =
            Range(listOf(Interval(Bound.Inclusive(v), Bound.Unbounded)))

        /** `> v`. */
        public fun <V : Comparable<V>> strictlyHigherThan(v: V): Range<V> =
            Range(listOf(Interval(Bound.Exclusive(v), Bound.Unbounded)))

        /** `<= v` (포함). */
        public fun <V : Comparable<V>> lowerThan(v: V): Range<V> =
            Range(listOf(Interval(Bound.Unbounded, Bound.Inclusive(v))))

        /** `< v`. */
        public fun <V : Comparable<V>> strictlyLowerThan(v: V): Range<V> =
            Range(listOf(Interval(Bound.Unbounded, Bound.Exclusive(v))))

        /** `>= lo, < hi`. `lo >= hi` 면 공집합 (throw 하지 않는다). */
        public fun <V : Comparable<V>> between(lo: V, hi: V): Range<V> = of(Bound.Inclusive(lo), Bound.Exclusive(hi))

        /** 임의 경계 한 쌍. 원소가 없으면 공집합. */
        public fun <V : Comparable<V>> of(lower: Bound<V>, upper: Bound<V>): Range<V> =
            if (valid(lower, upper)) Range(listOf(Interval(lower, upper))) else empty()

        /**
         * 임의(겹침·접촉·빈·역전) 구간 목록 → 정규형.
         * 원소가 없는 구간을 거르고 시작 경계로 정렬한 뒤, [Range.union] 과 같은 방식으로 훑으며 합친다.
         */
        public fun <V : Comparable<V>> fromIntervals(raw: Iterable<Interval<V>>): Range<V> {
            val sorted = raw.filter { valid(it.lower, it.upper) }.sortedWith { x, y -> cmpStart(x.lower, y.lower) }
            if (sorted.isEmpty()) return empty()
            val out = ArrayList<Interval<V>>(sorted.size)
            var accLo = sorted[0].lower
            var accHi = sorted[0].upper
            for (k in 1 until sorted.size) {
                val s = sorted[k]
                if (gap(accHi, s.lower)) {
                    out += Interval(accLo, accHi)
                    accLo = s.lower
                    accHi = s.upper
                } else if (cmpEnd(s.upper, accHi) > 0) {
                    accHi = s.upper
                }
            }
            out += Interval(accLo, accHi)
            return Range(out)
        }
    }
}

/**
 * 테스트용 정규형 검사. N1(빈 구간 없음)과 N2(이웃 구간 사이에 틈 있음)가 성립하면 null, 아니면 위반 설명.
 */
internal fun <V : Comparable<V>> Range<V>.canonicalViolation(): String? {
    val s = intervals
    for (k in s.indices) {
        if (!valid(s[k].lower, s[k].upper)) return "N1: 구간 $k 에 원소가 없다: $this"
        if (k > 0 && !gap(s[k - 1].upper, s[k].lower)) return "N2: 구간 ${k - 1} 과 $k 사이에 틈이 없다: $this"
    }
    return null
}

/** sealed [VersionSet] 의 구현은 [Range] 뿐이므로 캐스트 없이 좁힌다. */
private fun <V : Comparable<V>> VersionSet<V>.asRange(): Range<V> =
    when (this) {
        is Range -> this
    }

/** 구간 `lower..upper` 에 (조밀 순서 가정으로) 원소가 하나 이상 있는가. */
private fun <V : Comparable<V>> valid(lower: Bound<V>, upper: Bound<V>): Boolean =
    when (lower) {
        Bound.Unbounded -> true

        is Bound.Inclusive ->
            when (upper) {
                Bound.Unbounded -> true
                is Bound.Inclusive -> lower.value <= upper.value
                is Bound.Exclusive -> lower.value < upper.value
            }

        is Bound.Exclusive ->
            when (upper) {
                Bound.Unbounded -> true
                is Bound.Inclusive -> lower.value < upper.value
                is Bound.Exclusive -> lower.value < upper.value
            }
    }

/** 앞 구간의 상한과 뒤 구간의 하한 사이에 어느 쪽에도 속하지 않는 점이 있는가. */
private fun <V : Comparable<V>> gap(prevUpper: Bound<V>, nextLower: Bound<V>): Boolean =
    when (prevUpper) {
        Bound.Unbounded -> false

        is Bound.Inclusive ->
            when (nextLower) {
                Bound.Unbounded -> false
                is Bound.Inclusive -> prevUpper.value < nextLower.value
                is Bound.Exclusive -> prevUpper.value < nextLower.value
            }

        is Bound.Exclusive ->
            when (nextLower) {
                Bound.Unbounded -> false

                is Bound.Inclusive -> prevUpper.value < nextLower.value

                // 둘 다 미포함이면 경계값 자체가 틈이다.
                is Bound.Exclusive -> prevUpper.value <= nextLower.value
            }
    }

/** 하한(시작 경계) 비교: -∞ 가 가장 작고, 값이 같으면 Inclusive 가 먼저다. */
private fun <V : Comparable<V>> cmpStart(x: Bound<V>, y: Bound<V>): Int =
    when (x) {
        Bound.Unbounded ->
            when (y) {
                Bound.Unbounded -> 0
                is Bound.Inclusive, is Bound.Exclusive -> -1
            }

        is Bound.Inclusive ->
            when (y) {
                Bound.Unbounded -> 1
                is Bound.Inclusive -> x.value.compareTo(y.value)
                is Bound.Exclusive -> tieBreak(x.value.compareTo(y.value), -1)
            }

        is Bound.Exclusive ->
            when (y) {
                Bound.Unbounded -> 1
                is Bound.Inclusive -> tieBreak(x.value.compareTo(y.value), 1)
                is Bound.Exclusive -> x.value.compareTo(y.value)
            }
    }

/** 상한(끝 경계) 비교: +∞ 가 가장 크고, 값이 같으면 Exclusive 가 먼저다. */
private fun <V : Comparable<V>> cmpEnd(x: Bound<V>, y: Bound<V>): Int =
    when (x) {
        Bound.Unbounded ->
            when (y) {
                Bound.Unbounded -> 0
                is Bound.Inclusive, is Bound.Exclusive -> 1
            }

        is Bound.Inclusive ->
            when (y) {
                Bound.Unbounded -> -1
                is Bound.Inclusive -> x.value.compareTo(y.value)
                is Bound.Exclusive -> tieBreak(x.value.compareTo(y.value), 1)
            }

        is Bound.Exclusive ->
            when (y) {
                Bound.Unbounded -> -1
                is Bound.Inclusive -> tieBreak(x.value.compareTo(y.value), -1)
                is Bound.Exclusive -> x.value.compareTo(y.value)
            }
    }

/** 값 비교 결과가 0 이면 경계 종류로 정한 [onTie] 를 돌려준다. */
private fun tieBreak(valueCmp: Int, onTie: Int): Int = if (valueCmp != 0) valueCmp else onTie

/** 경계 종류 뒤집기: Inclusive ↔ Exclusive, Unbounded 는 그대로. */
private fun <V : Comparable<V>> flip(b: Bound<V>): Bound<V> =
    when (b) {
        is Bound.Inclusive -> Bound.Exclusive(b.value)
        is Bound.Exclusive -> Bound.Inclusive(b.value)
        Bound.Unbounded -> Bound.Unbounded
    }

/** v 가 하한보다 아래에 있는가 (구간에 못 미침). */
private fun <V : Comparable<V>> belowLower(v: V, lower: Bound<V>): Boolean =
    when (lower) {
        is Bound.Inclusive -> v < lower.value
        is Bound.Exclusive -> v <= lower.value
        Bound.Unbounded -> false
    }

/** v 가 상한보다 위에 있는가 (구간을 넘어섬). */
private fun <V : Comparable<V>> aboveUpper(v: V, upper: Bound<V>): Boolean =
    when (upper) {
        is Bound.Inclusive -> v > upper.value
        is Bound.Exclusive -> v >= upper.value
        Bound.Unbounded -> false
    }

/** 구간 하나를 version-ranges `Display` 형식으로 쓴다. */
private fun <V : Comparable<V>> segmentToString(s: Interval<V>): String {
    val lo = s.lower
    val hi = s.upper
    return when (lo) {
        Bound.Unbounded ->
            when (hi) {
                Bound.Unbounded -> "*"
                is Bound.Inclusive -> "<=${hi.value}"
                is Bound.Exclusive -> "<${hi.value}"
            }

        is Bound.Inclusive ->
            when (hi) {
                Bound.Unbounded -> ">=${lo.value}"

                is Bound.Inclusive ->
                    if (lo.value.compareTo(hi.value) == 0) "${lo.value}" else ">=${lo.value}, <=${hi.value}"

                is Bound.Exclusive -> ">=${lo.value}, <${hi.value}"
            }

        is Bound.Exclusive ->
            when (hi) {
                Bound.Unbounded -> ">${lo.value}"
                is Bound.Inclusive -> ">${lo.value}, <=${hi.value}"
                is Bound.Exclusive -> ">${lo.value}, <${hi.value}"
            }
    }
}
