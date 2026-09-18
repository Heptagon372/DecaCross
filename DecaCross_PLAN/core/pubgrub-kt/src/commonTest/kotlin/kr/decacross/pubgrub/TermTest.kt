package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals

private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

private fun one(v: Int): VersionSet<Int> = Range.singleton(v)

/** [Term] 대수 — 유한 도메인 전수 검사와 rs 이식에서 문제가 됐던 정확 케이스. */
class TermTest {
    /**
     * `Dom(3)`: 집합 128개 × 극성 2 = 항 256개, 모든 쌍 65,536개에 대해
     * `relation`·극성·`isDisjoint`·`isSubsetOf`·`satisfies`/`contradicts`·드모르간·교집합 교환법칙·소속을 검사한다.
     */
    @Test
    fun exhaustive_dom3_allPairs() {
        val d = Dom(3)
        val sets = (0L..d.fullMask).map { d.fromMask(it) }
        val terms = sets.flatMap { listOf<Term<Int>>(Term.Positive(it), Term.Negative(it)) }
        val probes = (-1 until d.atoms - 1).toList()
        val empty = Term.empty<Int>()
        var pairs = 0
        for (t in terms) {
            for (s in terms) {
                val inter = t.intersection(s)
                val union = t.union(s)
                val sat = s.isSubsetOf(t)
                val contra = s.intersection(t) == empty
                val expected =
                    when {
                        sat -> TermRelation.Satisfied
                        contra -> TermRelation.Contradicted
                        else -> TermRelation.Inconclusive
                    }
                law(t.relation(s) == expected) { "relation $t vs $s: ${t.relation(s)} != $expected" }
                law(inter.isPositive == (t.isPositive || s.isPositive)) { "polarity ∩ $t $s" }
                law(union.isPositive == (t.isPositive && s.isPositive)) { "polarity ∪ $t $s" }
                law(t.isDisjoint(s) == (inter == empty)) { "isDisjoint $t $s" }
                law(t.isSubsetOf(s) == (inter == t)) { "isSubsetOf $t $s" }
                law(t.satisfies(s) == t.isSubsetOf(s)) { "satisfies $t $s" }
                law(t.contradicts(s) == t.isDisjoint(s)) { "contradicts $t $s" }
                if (t.satisfies(s)) {
                    for (v in probes) law(!(v in t && v !in s)) { "satisfies => contains $t $s $v" }
                }
                law(union == t.negate().intersection(s.negate()).negate()) { "union via ∩ $t $s" }
                law(inter == s.intersection(t)) { "∩ commutes $t $s" }
                for (v in probes) {
                    law((v in inter) == (v in t && v in s)) { "contains ∩ $t $s $v" }
                    law((v in union) == (v in t || v in s)) { "contains ∪ $t $s $v" }
                }
                pairs++
            }
        }
        assertEquals(65_536, pairs, "검사한 항 쌍 수")
    }

    /** rs #443 특례(`Positive(∅)` 는 모든 항을 만족)와 부정 항의 표기. */
    @Test
    fun exactCases() {
        assertEquals(
            TermRelation.Satisfied,
            Term.Negative(one(1)).relation(Term.empty()),
            "rs #443 특례: 누적이 Positive(∅) 면 만족이 모순보다 우선",
        )
        assertEquals(
            TermRelation.Contradicted,
            Term.Negative(one(1)).relation(Term.Positive(one(1))),
            "누적 ⊆ 부정 항의 집합 → 모순",
        )
        assertEquals("Not ( >=1, <3 )", Term.Negative(r(1, 3)).toString(), "부정 항 toString")
    }
}
