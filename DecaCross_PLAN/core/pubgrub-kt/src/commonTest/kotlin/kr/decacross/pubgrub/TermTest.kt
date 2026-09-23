package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermTest {
    private fun pos(lo: Int, hi: Int) = Term.positive(Range.between(lo, hi))

    private fun neg(lo: Int, hi: Int) = Term.negative(Range.between(lo, hi))

    @Test
    fun subsetOf_followsAbsenceSemantics() {
        // +[1,3) ⇒ +[0,5)
        assertTrue(pos(1, 3).subsetOf(pos(0, 5)))
        assertFalse(pos(0, 5).subsetOf(pos(1, 3)))
        // +[1,3) ⇒ -[5,9) (서로소)
        assertTrue(pos(1, 3).subsetOf(neg(5, 9)))
        assertFalse(pos(1, 3).subsetOf(neg(2, 9)))
        // 부정 항은 "선택 안 됨"을 허용하므로 긍정 항을 함의하지 못한다
        assertFalse(neg(5, 9).subsetOf(pos(0, 5)))
        assertFalse(Term.negative(Range.empty<Int>()).subsetOf(Term.positive(Range.full())))
        // -[0,5) ⇒ -[1,3) (더 작은 집합을 빼는 것)
        assertTrue(neg(0, 5).subsetOf(neg(1, 3)))
        assertFalse(neg(1, 3).subsetOf(neg(0, 5)))
    }

    @Test
    fun intersect_and_union_areDual() {
        val a = pos(0, 10)
        val b = neg(3, 5)
        assertEquals(Term.positive(Range.between(0, 3).union(Range.between(5, 10))), a.intersect(b))
        assertEquals(Term.negative(Range.between(3, 5).union(Range.between(7, 9))), neg(3, 5).intersect(neg(7, 9)))
        assertEquals(Term.positive(Range.between(0, 12)), pos(0, 10).union(pos(3, 12)))
        assertEquals(Term.negative(Range.between(3, 5)), neg(0, 5).union(neg(3, 9)))
        // +a ∪ -b = -(b ∖ a)
        assertEquals(Term.negative(Range.between(10, 12)), pos(0, 10).union(neg(3, 12)))
        assertTrue(pos(0, 10).union(neg(0, 10)).isAny)
        assertTrue(pos(0, 3).intersect(pos(5, 9)).isNever)
        assertFalse(neg(0, 3).intersect(neg(3, 9)).isNever)
    }

    @Test
    fun relationWith_matchesPubgrubRs() {
        val incompatTerm = pos(1, 5)
        assertEquals(TermRelation.Satisfied, incompatTerm.relationWith(pos(2, 3)))
        assertEquals(TermRelation.Contradicted, incompatTerm.relationWith(pos(7, 9)))
        assertEquals(TermRelation.Inconclusive, incompatTerm.relationWith(pos(3, 9)))
        assertEquals(TermRelation.Inconclusive, incompatTerm.relationWith(neg(0, 1)))
        assertEquals(TermRelation.Contradicted, incompatTerm.relationWith(neg(0, 9)))
        // 결정 {v} 는 항상 Satisfied 아니면 Contradicted
        assertEquals(TermRelation.Satisfied, incompatTerm.relationWith(Term.exact(3)))
        assertEquals(TermRelation.Contradicted, incompatTerm.relationWith(Term.exact(5)))
        assertEquals(TermRelation.Satisfied, neg(1, 5).relationWith(Term.exact(5)))
        assertEquals(TermRelation.Contradicted, neg(1, 5).relationWith(Term.exact(4)))
    }

    @Test
    fun allowed_isComplementForNegative() {
        assertEquals(Range.between(1, 5), pos(1, 5).allowed)
        assertEquals(Range.between(1, 5).complement(), neg(1, 5).allowed)
        assertEquals("not >=1 <5", neg(1, 5).toString())
    }
}
