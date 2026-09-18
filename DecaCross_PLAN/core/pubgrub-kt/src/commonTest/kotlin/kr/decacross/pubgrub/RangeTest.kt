package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Range] 의 정확한 기대값 테스트. 표기는 pubgrub-rs version-ranges `Display` 와 같다. */
class RangeTest {
    @Test
    fun empty_and_full_toString() {
        assertEquals("∅", Range.empty<Int>().toString())
        assertEquals("*", Range.full<Int>().toString())
    }

    @Test
    fun singleton_toString_isBareValue() {
        assertEquals("3", Range.singleton(3).toString())
    }

    @Test
    fun toString_allSegmentShapes() {
        assertEquals("<=5", Range.lowerThan(5).toString())
        assertEquals("<5", Range.strictlyLowerThan(5).toString())
        assertEquals(">=5", Range.higherThan(5).toString())
        assertEquals(">5", Range.strictlyHigherThan(5).toString())
        assertEquals(">=1, <=2", Range.of(Bound.Inclusive(1), Bound.Inclusive(2)).toString())
        assertEquals(">=1, <2", Range.of(Bound.Inclusive(1), Bound.Exclusive(2)).toString())
        assertEquals(">1, <=2", Range.of(Bound.Exclusive(1), Bound.Inclusive(2)).toString())
        assertEquals(">1, <2", Range.of(Bound.Exclusive(1), Bound.Exclusive(2)).toString())
        assertEquals(
            "1 | >=3, <5 | >7",
            Range.singleton(1).union(Range.between(3, 5)).union(Range.strictlyHigherThan(7)).toString(),
        )
    }

    @Test
    fun between_isHalfOpen_emptyWhenLoNotBelowHi() {
        assertTrue(Range.between(3, 3).isEmpty)
        assertTrue(Range.between(4, 3).isEmpty)
        val r = Range.between(1, 3)
        assertTrue(1 in r)
        assertTrue(2 in r)
        assertFalse(3 in r)
    }

    @Test
    fun union_mergesTouchingComplementaryBounds() {
        assertEquals(">=1, <3", Range.between(1, 2).union(Range.between(2, 3)).toString())
        val closedOpen = Range.of(Bound.Inclusive(1), Bound.Inclusive(2))
        val openOpen = Range.of(Bound.Exclusive(2), Bound.Exclusive(3))
        assertEquals(">=1, <3", closedOpen.union(openOpen).toString())
    }

    @Test
    fun union_keepsHole_whenBothExclusive() {
        val left = Range.of(Bound.Exclusive(1), Bound.Exclusive(2))
        val right = Range.of(Bound.Exclusive(2), Bound.Exclusive(3))
        assertEquals(">1, <2 | >2, <3", left.union(right).toString())
    }

    @Test
    fun lowerThan_union_higherThan_isFull() {
        assertTrue(Range.lowerThan(1).union(Range.higherThan(1)).isFull)
        assertEquals("<1 | >1", Range.strictlyLowerThan(1).union(Range.strictlyHigherThan(1)).toString())
    }

    @Test
    fun complement_ofSingletonUnion_matchesVersionRangesDisplay() {
        val holes = (1..5).map { Range.singleton(it) }.reduce { a, b -> a.union(b) }.complement()
        assertEquals("<1 | >1, <2 | >2, <3 | >3, <4 | >4, <5 | >5", holes.toString())
    }

    @Test
    fun fromIntervals_normalizes_unsorted_overlapping_inverted_empty() {
        val raw =
            listOf(
                Interval(Bound.Inclusive(5), Bound.Exclusive(7)),
                Interval(Bound.Inclusive(1), Bound.Inclusive(3)),
                Interval(Bound.Exclusive(2), Bound.Inclusive(6)),
                Interval(Bound.Inclusive(9), Bound.Exclusive(9)),
                Interval(Bound.Inclusive(8), Bound.Inclusive(4)),
            )
        val r = Range.fromIntervals(raw)
        assertEquals(">=1, <7", r.toString())
        assertNull(r.canonicalViolation())
    }

    @Test
    fun contains_binarySearch_manySegments() {
        val evens = (0..99).map { Range.singleton(2 * it) }.fold(Range.empty<Int>()) { acc, s -> acc.union(s) }
        assertEquals(100, evens.intervals.size)
        for (n in 0..198) {
            assertEquals(n % 2 == 0, n in evens, "contains($n)")
        }
        assertFalse(-1 in evens)
        assertFalse(200 in evens)
    }

    @Test
    fun genericType_double() {
        val r = Range.lowerThan(1.0).union(Range.higherThan(2.0))
        assertFalse(1.5 in r)
        assertTrue(2.0 in r)
        assertTrue(1.0 in r)
    }

    @Test
    fun companion_delegatesToRange() {
        assertEquals(Range.empty<Int>(), VersionSet.empty<Int>())
        assertEquals(Range.full<Int>(), VersionSet.full<Int>())
        assertEquals(Range.singleton(7), VersionSet.singleton(7))
    }

    @Test
    fun asSingleton_cases() {
        assertEquals(4, Range.singleton(4).asSingleton())
        assertNull(Range.between(1, 2).asSingleton())
        assertNull(Range.full<Int>().asSingleton())
        assertNull(Range.empty<Int>().asSingleton())
        assertNull(Range.singleton(1).union(Range.singleton(3)).asSingleton())
    }

    @Test
    fun setPredicates_basic() {
        assertTrue(Range.between(1, 5).isSubsetOf(Range.higherThan(0)))
        assertTrue(Range.between(1, 5).isDisjointFrom(Range.higherThan(5)))
        assertFalse(Range.between(1, 5).isDisjointFrom(Range.higherThan(4)))
    }

    @Test
    fun equality_isStructural_hashConsistent() {
        val a = Range.of(Bound.Inclusive(1), Bound.Exclusive(3))
        val b = Range.between(1, 3)
        assertEquals(b, a)
        assertEquals(b.hashCode(), a.hashCode())
    }

    @Test
    fun noOperationThrows_onEmptyAndFull() {
        val empty = Range.empty<Int>()
        val full = Range.full<Int>()
        val sets = listOf(empty, full)
        for (x in sets) {
            for (y in sets) {
                val xFull = x.isFull
                val yFull = y.isFull
                assertEquals(xFull && yFull, x.intersect(y).isFull, "$x ∩ $y")
                assertEquals(xFull || yFull, x.union(y).isFull, "$x ∪ $y")
                assertEquals(xFull && !yFull, x.difference(y).isFull, "$x \\ $y")
                assertEquals(!(xFull && yFull), x.isDisjointFrom(y), "$x disjoint $y")
                assertEquals(!xFull || yFull, x.isSubsetOf(y), "$x ⊆ $y")
            }
            assertEquals(!x.isFull, x.complement().isFull, "¬$x")
            assertEquals(x.isFull, 0 in x, "0 in $x")
            assertNull(x.asSingleton())
            assertTrue(x.toString().isNotEmpty())
        }
        assertTrue(empty.isEmpty)
        assertFalse(full.isEmpty)
        assertTrue(full.complement().isEmpty)
        assertEquals(full, empty.complement())
    }
}
