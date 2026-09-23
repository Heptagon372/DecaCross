package kr.decacross.compat.version

import kr.decacross.compat.model.McOrdinal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McRangeTest {
    private fun o(v: Int) = McOrdinal(v)

    private fun r(lo: Int, hi: Int) = McRange.closed(o(lo), o(hi))

    @Test
    fun union_mergesOverlappingAndAdjacent() {
        val u = r(1, 5).union(r(6, 9)).union(r(20, 30)).union(r(25, 27))
        assertEquals(listOf(McRange.Span(o(1), o(9)), McRange.Span(o(20), o(30))), u.spans())
    }

    @Test
    fun intersect_keepsOnlyOverlap() {
        val a = r(1, 10).union(r(20, 30))
        val b = r(5, 25)
        assertEquals(r(5, 10).union(r(20, 25)), a.intersect(b))
        assertTrue(r(1, 2).intersect(r(3, 4)).isEmpty)
    }

    @Test
    fun complement_coversEverythingElse() {
        val c = r(10, 20).complement()
        assertTrue(o(9) in c)
        assertTrue(o(21) in c)
        assertFalse(o(10) in c)
        assertFalse(o(20) in c)
        assertTrue(o(Int.MIN_VALUE) in c)
        assertTrue(o(Int.MAX_VALUE) in c)
        assertEquals(McRange.full(), McRange.empty().complement())
        assertEquals(McRange.empty(), McRange.full().complement())
    }

    @Test
    fun closed_withInvertedBounds_isEmpty() {
        assertTrue(McRange.closed(o(5), o(4)).isEmpty)
    }

    @Test
    fun toString_isReadable() {
        assertEquals("∅", McRange.empty().toString())
        assertEquals("*", McRange.full().toString())
        assertEquals("{5}", McRange.of(o(5)).toString())
        assertEquals("1..3 ∪ 7..", r(1, 3).union(McRange.atLeast(o(7))).toString())
        assertEquals("..3", McRange.atMost(o(3)).toString())
    }

    /** 속성 테스트: 무작위 구간 집합에 대해 대수 법칙과 정규형 불변식이 유지된다. */
    @Test
    fun algebra_properties_hold() {
        val rnd = Random(20260917)
        fun randomRange(): McRange {
            var acc = McRange.empty()
            repeat(rnd.nextInt(0, 5)) {
                val lo = rnd.nextInt(-50, 50)
                val hi = lo + rnd.nextInt(0, 15)
                acc = acc.union(r(lo, hi))
            }
            return acc
        }
        fun assertNormal(x: McRange) {
            val s = x.spans()
            for (i in s.indices) {
                assertTrue(s[i].lo <= s[i].hi)
                if (i > 0) assertTrue(s[i - 1].hi.value.toLong() + 1 < s[i].lo.value.toLong(), "인접/중첩 구간은 병합되어야 한다: $x")
            }
        }
        repeat(3000) {
            val a = randomRange()
            val b = randomRange()
            val c = randomRange()
            assertNormal(a)
            assertNormal(a.union(b))
            assertNormal(a.intersect(b))
            assertNormal(a.complement())
            assertTrue(a.intersect(a.complement()).isEmpty, "a ∩ ¬a = ∅")
            assertTrue(a.union(a.complement()).isFull, "a ∪ ¬a = full")
            assertEquals(a, a.complement().complement())
            assertEquals(a.union(b), b.union(a))
            assertEquals(a.intersect(b), b.intersect(a))
            assertEquals(a.intersect(b).intersect(c), a.intersect(b.intersect(c)))
            assertEquals(a.union(b).union(c), a.union(b.union(c)))
            assertEquals(a.intersect(b.union(c)), a.intersect(b).union(a.intersect(c)))
            for (v in -60..60) {
                val ov = o(v)
                assertEquals((ov in a) && (ov in b), ov in a.intersect(b))
                assertEquals((ov in a) || (ov in b), ov in a.union(b))
                assertEquals(ov !in a, ov in a.complement())
            }
        }
    }
}
