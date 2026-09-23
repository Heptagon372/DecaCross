package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeTest {
    private fun b(lo: Int, hi: Int) = Range.between(lo, hi)

    @Test
    fun factories_haveExpectedMembership() {
        assertTrue(Range.empty<Int>().isEmpty)
        assertTrue(Range.full<Int>().isFull)
        assertTrue(Range.full<Int>().contains(Int.MIN_VALUE))
        assertTrue(Range.singleton(5).contains(5))
        assertFalse(Range.singleton(5).contains(4))
        assertEquals(Range.singleton(5), Range.exact(5))
        assertTrue(Range.higherThan(3).contains(3))
        assertFalse(Range.higherThan(3).contains(2))
        assertFalse(Range.strictlyHigherThan(3).contains(3))
        assertTrue(Range.strictlyHigherThan(3).contains(4))
        assertTrue(Range.strictlyLowerThan(3).contains(2))
        assertFalse(Range.strictlyLowerThan(3).contains(3))
        assertTrue(Range.atMost(3).contains(3))
        assertFalse(Range.atMost(3).contains(4))
        assertTrue(b(1, 3).contains(1))
        assertTrue(b(1, 3).contains(2))
        assertFalse(b(1, 3).contains(3))
        assertTrue(Range.betweenInclusive(1, 3).contains(3))
        assertTrue(b(3, 1).isEmpty)
        assertTrue(Range.betweenInclusive(3, 1).isEmpty)
        assertEquals(Range.singleton(2), Range.betweenInclusive(2, 2))
        assertTrue(Range.of(Bound.Included(2), Bound.Excluded(2)).isEmpty)
        assertTrue(Range.of(Bound.Excluded(2), Bound.Included(2)).isEmpty)
    }

    @Test
    fun union_mergesTouchingButNotGappedIntervals() {
        // [1,2) ∪ [2,3] → [1,3]
        assertEquals(Range.betweenInclusive(1, 3), b(1, 2).union(Range.betweenInclusive(2, 3)))
        // [1,2) ∪ (2,3] → 두 구간 (2 가 빠짐)
        val gapped = b(1, 2).union(Range.of(Bound.Excluded(2), Bound.Included(3)))
        assertEquals(2, gapped.intervals.size)
        assertFalse(gapped.contains(2))
        // [1,2] ∪ (2,3] → [1,3]
        assertEquals(Range.betweenInclusive(1, 3), Range.betweenInclusive(1, 2).union(Range.of(Bound.Excluded(2), Bound.Included(3))))
    }

    @Test
    fun complement_ofSingletonLeavesHole() {
        val c = Range.singleton(5).complement()
        assertEquals(2, c.intervals.size)
        assertFalse(c.contains(5))
        assertTrue(c.contains(4))
        assertTrue(c.contains(6))
        assertEquals(Range.full(), Range.empty<Int>().complement())
        assertEquals(Range.empty(), Range.full<Int>().complement())
    }

    @Test
    fun toString_isReadable() {
        assertEquals("∅", Range.empty<Int>().toString())
        assertEquals("*", Range.full<Int>().toString())
        assertEquals("5", Range.singleton(5).toString())
        assertEquals(">=1 <3", b(1, 3).toString())
        assertEquals(">=1 <=3", Range.betweenInclusive(1, 3).toString())
        assertEquals(">=7", Range.higherThan(7).toString())
        assertEquals(">7", Range.strictlyHigherThan(7).toString())
        assertEquals("<3", Range.strictlyLowerThan(3).toString())
        assertEquals("<=3", Range.atMost(3).toString())
        assertEquals("<5 || >5", Range.singleton(5).complement().toString())
        assertEquals(">=1 <3 || >=7", b(1, 3).union(Range.higherThan(7)).toString())
    }

    @Test
    fun mixingWithOtherVersionSetImplementation_isRejected() {
        val other =
            object : VersionSet<Int> {
                override val isEmpty: Boolean = true

                override fun contains(v: Int): Boolean = false

                override fun intersect(other: VersionSet<Int>): VersionSet<Int> = this

                override fun union(other: VersionSet<Int>): VersionSet<Int> = other

                override fun complement(): VersionSet<Int> = this
            }
        assertFailsWith<IllegalArgumentException> { Range.full<Int>().intersect(other) }
        assertFailsWith<IllegalArgumentException> { Range.full<Int>().union(other) }
    }

    @Test
    fun fromIntervals_normalizes() {
        val r =
            Range.fromIntervals(
                listOf(
                    Interval(Bound.Included(5), Bound.Included(9)),
                    Interval(Bound.Included(1), Bound.Excluded(3)),
                    Interval(Bound.Included(3), Bound.Included(4)),
                    Interval(Bound.Included(8), Bound.Excluded(2)), // 빈 구간
                ),
            )
        // Range 는 후속 버전을 모르므로 4 와 5 는 맞닿은 것으로 보지 않는다
        assertEquals(Range.betweenInclusive(1, 4).union(Range.betweenInclusive(5, 9)), r)
        assertEquals(2, r.intervals.size)
        assertEquals(">=1 <=4 || >=5 <=9", r.toString())
        assertEquals(Range.ofVersions(listOf(3, 1, 2)), Range.betweenInclusive(1, 3).intersect(Range.ofVersions(listOf(1, 2, 3))))
    }

    // ---- 속성 테스트 -------------------------------------------------------

    private class Gen(seed: Int) {
        val rnd = Random(seed)

        fun bound(): Bound<Int> =
            when (rnd.nextInt(5)) {
                0 -> Bound.Unbounded
                1, 2 -> Bound.Included(rnd.nextInt(-20, 21))
                else -> Bound.Excluded(rnd.nextInt(-20, 21))
            }

        fun range(): Range<Int> {
            var acc = Range.empty<Int>()
            repeat(rnd.nextInt(0, 4)) {
                acc =
                    when (rnd.nextInt(6)) {
                        0 -> acc.union(Range.singleton(rnd.nextInt(-20, 21)))
                        1 -> acc.union(Range.of(bound(), bound()).complement())
                        else -> acc.union(Range.of(bound(), bound()))
                    }
            }
            return acc
        }
    }

    private fun assertNormal(x: Range<Int>) {
        val s = x.intervals
        for (i in s.indices) {
            assertTrue(s[i].isNonEmpty, "빈 구간이 남아 있다: $x")
            if (i > 0) {
                val prevUpper = s[i - 1].upper
                val lower = s[i].lower
                assertTrue(prevUpper !is Bound.Unbounded && lower !is Bound.Unbounded, "정렬/병합 위반: $x")
                val pu = (prevUpper as? Bound.Included)?.value ?: (prevUpper as Bound.Excluded).value
                val lo = (lower as? Bound.Included)?.value ?: (lower as Bound.Excluded).value
                assertTrue(pu <= lo, "정렬 위반: $x")
                if (pu == lo) {
                    assertTrue(prevUpper is Bound.Excluded && lower is Bound.Excluded, "맞닿은 구간은 병합되어야 한다: $x")
                }
            }
        }
    }

    @Test
    fun algebra_properties_hold() {
        val gen = Gen(20260923)
        val full = Range.full<Int>()
        repeat(20_000) {
            val a = gen.range()
            val b = gen.range()
            val c = gen.range()
            assertNormal(a)
            assertNormal(a.union(b))
            assertNormal(a.intersect(b))
            assertNormal(a.complement())

            assertTrue(a.intersect(a.complement()).isEmpty, "a ∩ ¬a = ∅: $a")
            assertEquals(full, a.union(a.complement()), "a ∪ ¬a = full: $a")
            assertEquals(a, a.complement().complement(), "¬¬a = a")
            assertEquals(a, a.union(a), "멱등 ∪")
            assertEquals(a, a.intersect(a), "멱등 ∩")
            assertEquals(a.union(b), b.union(a), "교환 ∪")
            assertEquals(a.intersect(b), b.intersect(a), "교환 ∩")
            assertEquals(a.intersect(b).intersect(c), a.intersect(b.intersect(c)), "결합 ∩")
            assertEquals(a.union(b).union(c), a.union(b.union(c)), "결합 ∪")
            assertEquals(a.intersect(b.union(c)), a.intersect(b).union(a.intersect(c)), "분배 ∩/∪")
            assertEquals(a.union(b.intersect(c)), a.union(b).intersect(a.union(c)), "분배 ∪/∩")
            assertEquals(a, a.intersect(full))
            assertEquals(a, a.union(Range.empty()))
            assertTrue(a.intersect(Range.empty()).isEmpty)
            assertEquals(a.subsetOf(b), a.intersect(b) == a)
            assertEquals(a.isDisjoint(b), a.intersect(b).isEmpty)

            val ab = a.intersect(b)
            val ub = a.union(b)
            val ca = a.complement()
            for (v in -22..22) {
                assertEquals(a.contains(v) && b.contains(v), ab.contains(v), "contains ∩ at $v: $a, $b")
                assertEquals(a.contains(v) || b.contains(v), ub.contains(v), "contains ∪ at $v: $a, $b")
                assertEquals(!a.contains(v), ca.contains(v), "contains ¬ at $v: $a")
            }
        }
    }
}
