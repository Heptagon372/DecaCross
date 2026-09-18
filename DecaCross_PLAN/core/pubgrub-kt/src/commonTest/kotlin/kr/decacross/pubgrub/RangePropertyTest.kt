package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Range] 대수 법칙 속성 테스트. 오라클은 [Dom] 의 비트마스크.
 *
 * 전수: K=3 모든 쌍 16,384 + 모든 삼중 2,097,152, K=4 모든 쌍 262,144.
 * 무작위(고정 시드): K=12 삼중 1,000,000 (프롬프트 P1 "100만 조합"), K=31 다구간 삼중 20,000.
 * 합계 3,395,680 조합, 결정적. 이 수를 줄이지 마라 (DESIGN §6.4).
 */
class RangePropertyTest {
    @Test
    fun exhaustive_k3_allPairs_binaryLaws() {
        val d = Dom(3)
        val sets = allSets(d)
        assertEquals(128, sets.size)
        var pairs = 0
        for ((ma, a) in sets) {
            for ((mb, b) in sets) {
                binaryLaws(d, a, ma, b, mb)
                pairs++
            }
            // contains ↔ 단일 원소 교집합
            for (p in -1 until d.atoms - 1) {
                law(a.contains(p) == !a.intersect(Range.singleton(p)).isEmpty) { "contains/singleton: $a, $p" }
            }
        }
        assertEquals(16_384, pairs)
    }

    @Test
    fun exhaustive_k3_allTriples_associativityAndDistributivity() {
        val sets = allSets(Dom(3)).map { it.second }
        var triples = 0
        for (a in sets) {
            for (b in sets) {
                for (c in sets) {
                    ternaryLaws(a, b, c)
                    triples++
                }
            }
        }
        assertEquals(2_097_152, triples)
    }

    @Test
    fun exhaustive_k4_allPairs_binaryLaws() {
        val d = Dom(4)
        val sets = allSets(d)
        assertEquals(512, sets.size)
        var pairs = 0
        for ((ma, a) in sets) {
            for ((mb, b) in sets) {
                binaryLaws(d, a, ma, b, mb)
                pairs++
            }
        }
        assertEquals(262_144, pairs)
    }

    @Test
    fun random_k12_triples_seed20260917() {
        val count = randomTriples(Dom(12), Random(20260917), triples = 1_000_000, maxRaw = 4)
        assertEquals(1_000_000, count)
    }

    @Test
    fun random_k31_manySegments_seed20260918() {
        val count = randomTriples(Dom(31), Random(20260918), triples = 20_000, maxRaw = 16)
        assertEquals(20_000, count)
    }

    /** 도메인의 모든 집합 `2^atoms` 개를 (마스크, fromMask) 쌍으로. fromMask 결과도 정규형이어야 한다. */
    private fun allSets(d: Dom): List<Pair<Long, Range<Int>>> =
        (0L..d.fullMask).map { m ->
            val r = d.fromMask(m)
            assertCanonical(d, r, m) { "fromMask ${m.toString(2)}" }
            m to r
        }

    /** 무작위 삼중마다 생성 정규형, (a,b) 이항 법칙, 삼항 법칙, 삼중 교집합 마스크를 검사하고 검사한 삼중 수를 돌려준다. */
    private fun randomTriples(
        d: Dom,
        rnd: Random,
        triples: Int,
        maxRaw: Int,
    ): Int {
        var count = 0
        repeat(triples) {
            val a = d.randomRange(rnd, maxRaw)
            val b = d.randomRange(rnd, maxRaw)
            val c = d.randomRange(rnd, maxRaw)
            val ma = d.mask(a)
            val mb = d.mask(b)
            val mc = d.mask(c)
            assertCanonical(d, a, ma) { "생성 a" }
            assertCanonical(d, b, mb) { "생성 b" }
            assertCanonical(d, c, mc) { "생성 c" }
            binaryLaws(d, a, ma, b, mb)
            ternaryLaws(a, b, c)
            law(d.mask(a.intersect(b).intersect(c)) == (ma and mb and mc)) { "삼중 교집합 마스크: $a, $b, $c" }
            count++
        }
        return count
    }

    /** 집합 `(a, ma)`, `(b, mb)` 에 대한 이항 법칙. */
    private fun binaryLaws(
        d: Dom,
        a: Range<Int>,
        ma: Long,
        b: Range<Int>,
        mb: Long,
    ) {
        val f = d.fullMask
        val i = a.intersect(b)
        val u = a.union(b)
        val na = a.complement()
        val nb = b.complement()
        val diff = a.difference(b)

        // 1. 결과가 구조적으로 정규형이고 소속이 마스크 연산과 같다
        assertCanonical(d, i, ma and mb) { "$a ∩ $b" }
        assertCanonical(d, u, ma or mb) { "$a ∪ $b" }
        assertCanonical(d, na, ma.inv() and f) { "¬($a)" }
        assertCanonical(d, diff, ma and mb.inv()) { "$a \\ $b" }

        // 2. 구조적 동등 ⇔ 집합 동등, 같으면 hashCode 도 같다
        law((a == b) == (ma == mb)) { "동등 ⇔ 소속: $a vs $b" }
        if (a == b) law(a.hashCode() == b.hashCode()) { "hashCode: $a vs $b" }

        // 3. 교환 법칙
        law(i == b.intersect(a)) { "∩ 교환: $a, $b" }
        law(u == b.union(a)) { "∪ 교환: $a, $b" }

        // 4. 여집합 법칙
        law(a.intersect(na).isEmpty) { "a ∩ ¬a = ∅: $a" }
        val aOrNa = a.union(na)
        law(aOrNa.isFull && aOrNa == Range.full<Int>()) { "a ∪ ¬a = 전체: $a" }
        law(na.complement() == a) { "¬¬a = a: $a" }

        // 5. 드모르간
        law(i.complement() == na.union(nb)) { "드모르간 ¬(a∩b): $a, $b" }
        law(u.complement() == na.intersect(nb)) { "드모르간 ¬(a∪b): $a, $b" }

        // 6. 흡수
        law(a.union(i) == a) { "흡수 a ∪ (a∩b): $a, $b" }
        law(a.intersect(u) == a) { "흡수 a ∩ (a∪b): $a, $b" }

        // 7. 술어
        law(a.isDisjointFrom(b) == ((ma and mb) == 0L)) { "isDisjointFrom: $a, $b" }
        val subset = a.isSubsetOf(b)
        law(subset == ((ma and mb.inv()) == 0L)) { "isSubsetOf: $a, $b" }
        law(subset == (i == a)) { "isSubsetOf ⇔ a∩b = a: $a, $b" }
    }

    /** 결합·분배 법칙. */
    private fun ternaryLaws(
        a: Range<Int>,
        b: Range<Int>,
        c: Range<Int>,
    ) {
        law(a.intersect(b).intersect(c) == a.intersect(b.intersect(c))) { "∩ 결합: $a, $b, $c" }
        law(a.union(b).union(c) == a.union(b.union(c))) { "∪ 결합: $a, $b, $c" }
        law(a.intersect(b.union(c)) == a.intersect(b).union(a.intersect(c))) { "∩ 의 ∪ 분배: $a, $b, $c" }
        law(a.union(b.intersect(c)) == a.union(b).intersect(a.union(c))) { "∪ 의 ∩ 분배: $a, $b, $c" }
    }
}
