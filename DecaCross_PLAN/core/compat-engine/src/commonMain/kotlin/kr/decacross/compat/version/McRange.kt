package kr.decacross.compat.version

import kr.decacross.compat.model.McOrdinal

/**
 * `McOrdinal` 구간 집합. 정렬된 disjoint 닫힌 구간 리스트로 표현한다.
 *
 * 서수는 이산 정수이므로 모든 경계를 "포함"으로 정규화할 수 있다 (`< x` 는 `<= x-1`).
 * 나중에 pubgrub-kt 의 VersionSet 으로 감싼다(07). 지금은 독립 타입.
 *
 * # 불변식 (정규형)
 * - 구간이 lo 기준 오름차순이고
 * - 인접(hi+1 == next.lo)·중첩 구간이 병합되어 있고
 * - 빈 구간(lo > hi)이 없다
 */
public class McRange private constructor(
    private val spans: List<Span>,
) {
    /** 양 끝 포함 구간. */
    public data class Span(val lo: McOrdinal, val hi: McOrdinal) {
        init {
            require(lo <= hi) { "빈 구간: $lo..$hi" }
        }
    }

    public val isEmpty: Boolean get() = spans.isEmpty()

    public val isFull: Boolean
        get() = spans.size == 1 && spans[0].lo.value == Int.MIN_VALUE && spans[0].hi.value == Int.MAX_VALUE

    /** 정규형 구간 목록 (읽기 전용). */
    public fun spans(): List<Span> = spans

    public val min: McOrdinal? get() = spans.firstOrNull()?.lo

    public val max: McOrdinal? get() = spans.lastOrNull()?.hi

    public operator fun contains(o: McOrdinal): Boolean = spans.any { o >= it.lo && o <= it.hi }

    public fun union(other: McRange): McRange = normalize(spans + other.spans)

    public fun intersect(other: McRange): McRange {
        val out = ArrayList<Span>()
        var i = 0
        var j = 0
        // 두 정규형 리스트를 병합 순회 — 구간 수에 선형
        while (i < spans.size && j < other.spans.size) {
            val a = spans[i]
            val b = other.spans[j]
            val lo = maxOf(a.lo, b.lo)
            val hi = minOf(a.hi, b.hi)
            if (lo <= hi) out += Span(lo, hi)
            if (a.hi < b.hi) i++ else j++
        }
        return McRange(out)
    }

    public fun complement(): McRange {
        if (spans.isEmpty()) return full()
        val out = ArrayList<Span>()
        var cursor: Long = Int.MIN_VALUE.toLong()
        for (s in spans) {
            val before = s.lo.value.toLong() - 1
            if (cursor <= before) out += Span(McOrdinal(cursor.toInt()), McOrdinal(before.toInt()))
            cursor = s.hi.value.toLong() + 1
        }
        if (cursor <= Int.MAX_VALUE.toLong()) out += Span(McOrdinal(cursor.toInt()), McOrdinal(Int.MAX_VALUE))
        return McRange(out)
    }

    public fun minus(other: McRange): McRange = intersect(other.complement())

    override fun equals(other: Any?): Boolean = other is McRange && other.spans == spans

    override fun hashCode(): Int = spans.hashCode()

    override fun toString(): String =
        if (spans.isEmpty()) {
            "∅"
        } else {
            spans.joinToString(" ∪ ") { s ->
                when {
                    s.lo == s.hi -> "{${s.lo.value}}"
                    s.lo.value == Int.MIN_VALUE && s.hi.value == Int.MAX_VALUE -> "*"
                    s.lo.value == Int.MIN_VALUE -> "..${s.hi.value}"
                    s.hi.value == Int.MAX_VALUE -> "${s.lo.value}.."
                    else -> "${s.lo.value}..${s.hi.value}"
                }
            }
        }

    public companion object {
        public fun empty(): McRange = McRange(emptyList())

        public fun full(): McRange = McRange(listOf(Span(McOrdinal(Int.MIN_VALUE), McOrdinal(Int.MAX_VALUE))))

        public fun of(o: McOrdinal): McRange = McRange(listOf(Span(o, o)))

        /** `[lo, hi]` 양 끝 포함. lo > hi 면 공집합. */
        public fun closed(lo: McOrdinal, hi: McOrdinal): McRange = if (lo <= hi) McRange(listOf(Span(lo, hi))) else empty()

        public fun atLeast(lo: McOrdinal): McRange = closed(lo, McOrdinal(Int.MAX_VALUE))

        public fun atMost(hi: McOrdinal): McRange = closed(McOrdinal(Int.MIN_VALUE), hi)

        /** 임의 구간 목록 → 정규형. */
        public fun fromSpans(spans: List<Span>): McRange = normalize(spans)

        /** 특정 서수 집합 → 정규형 (인접한 것은 병합). */
        public fun ofAll(ordinals: Iterable<McOrdinal>): McRange = normalize(ordinals.map { Span(it, it) })

        private fun normalize(raw: List<Span>): McRange {
            if (raw.isEmpty()) return McRange(emptyList())
            val sorted = raw.sortedWith(compareBy({ it.lo }, { it.hi }))
            val out = ArrayList<Span>(sorted.size)
            var cur = sorted[0]
            for (k in 1 until sorted.size) {
                val s = sorted[k]
                // 중첩 또는 인접(hi+1 == lo)이면 병합. Long 으로 올려 오버플로 방지.
                if (s.lo.value.toLong() <= cur.hi.value.toLong() + 1) {
                    if (s.hi > cur.hi) cur = Span(cur.lo, s.hi)
                } else {
                    out += cur
                    cur = s
                }
            }
            out += cur
            return McRange(out)
        }
    }
}
