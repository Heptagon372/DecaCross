package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartialSolutionTest {
    private var nextId = 0

    private fun dep(pkg: String, v: Int, depPkg: String, depSet: VersionSet<Int>): Incompatibility<String, Int> =
        Incompatibility.fromDependency(nextId++, pkg, Range.singleton(v), depPkg, depSet)
            ?: error("자기 의존이 아니어야 한다")

    private fun noVersions(pkg: String, set: VersionSet<Int>) = Incompatibility.noVersions(nextId++, pkg, set)

    @Test
    fun relation_classifiesIncompatibilities() {
        val ps = PartialSolution<String, Int>()
        val notRoot = Incompatibility.notRoot(nextId++, "root", 1)
        // 아무 할당도 없으면 거의 만족 (root 항 하나만 미정)
        assertEquals(Relation.AlmostSatisfied("root"), ps.relation(notRoot))
        ps.addDerivation("root", notRoot)
        assertEquals(Term.exact(1), ps.termIntersectionFor("root"))
        assertIs<Relation.Contradicted<String>>(ps.relation(notRoot))

        ps.addDecision("root", 1)
        val rootDep = dep("root", 1, "foo", Range.between(1, 2))
        assertEquals(Relation.AlmostSatisfied("foo"), ps.relation(rootDep))
        ps.addDerivation("foo", rootDep)
        assertEquals(Term.positive(Range.between(1, 2)), ps.termIntersectionFor("foo"))
        assertIs<Relation.Contradicted<String>>(ps.relation(rootDep))

        // foo ∈ [1,2) 와 {foo: +[1,2)} 는 완전히 만족 → 충돌
        assertEquals(Relation.Satisfied, ps.relation(noVersions("foo", Range.between(1, 2))))
        // 두 항 모두 미정이면 결론 없음
        assertEquals(Relation.Inconclusive, ps.relation(dep("bar", 1, "baz", Range.full())))
    }

    @Test
    fun backtrack_restoresState() {
        val ps = PartialSolution<String, Int>()
        val notRoot = Incompatibility.notRoot(nextId++, "root", 1)
        ps.addDerivation("root", notRoot)
        ps.addDecision("root", 1) // level 1
        val rootDep = dep("root", 1, "foo", Range.between(1, 10))
        ps.addDerivation("foo", rootDep)
        ps.addDecision("foo", 5) // level 2
        val fooDep = dep("foo", 5, "bar", Range.between(1, 3))
        ps.addDerivation("bar", fooDep)
        val barNarrow = noVersions("bar", Range.between(2, 3))
        ps.addDerivation("bar", barNarrow) // bar ∈ [1,2)
        ps.addDecision("bar", 1) // level 3
        assertEquals(3, ps.decisionLevel)
        assertEquals(mapOf("root" to 1, "foo" to 5, "bar" to 1), ps.extractSolution())

        ps.backtrack(2)
        assertEquals(2, ps.decisionLevel)
        assertEquals(mapOf("root" to 1, "foo" to 5), ps.extractSolution())
        // bar 의 유도 둘은 레벨 2 에서 생겼으므로 남고, 레벨 3 의 결정만 사라진다
        assertEquals(Term.positive(Range.between(1, 2)), ps.termIntersectionFor("bar"))
        assertFalse(ps.isDecided("bar"))

        ps.backtrack(1)
        assertEquals(mapOf("root" to 1), ps.extractSolution())
        assertNull(ps.termIntersectionFor("bar"), "레벨 1 이후에 처음 등장한 패키지는 제거된다")
        assertEquals(Term.positive(Range.between(1, 10)), ps.termIntersectionFor("foo"), "유도만 남고 결정은 사라진다")
        assertFalse(ps.isDecided("foo"))
        assertTrue(ps.isDecided("root"))
        assertEquals(2, ps.size)
    }

    @Test
    fun pickHighestPriorityPackage_prefersHigherPriorityThenInsertionOrder() {
        val ps = PartialSolution<String, Int>()
        val notRoot = Incompatibility.notRoot(nextId++, "root", 1)
        ps.addDerivation("root", notRoot)
        ps.addDecision("root", 1)
        ps.addDerivation("a", dep("root", 1, "a", Range.full()))
        ps.addDerivation("b", dep("root", 1, "b", Range.full()))
        ps.addDerivation("c", dep("root", 1, "c", Range.full()))
        assertEquals("a", ps.pickHighestPriorityPackage { _, _ -> 0 }?.first)
        assertEquals("b", ps.pickHighestPriorityPackage { p, _ -> if (p == "b") 5 else 0 }?.first)
        ps.addDecision("a", 1)
        ps.addDecision("b", 1)
        ps.addDecision("c", 1)
        assertNull(ps.pickHighestPriorityPackage { _, _ -> 0 })
    }

    @Test
    fun satisfierSearch_findsBackjumpLevel() {
        // 문서의 "conflict resolution" 예제를 손으로 재현: 레벨 1 에서 유도된 foo 범위, 레벨 2 의 결정이 충돌을 만든다.
        val ps = PartialSolution<String, Int>()
        ps.addDerivation("root", Incompatibility.notRoot(nextId++, "root", 1))
        ps.addDecision("root", 1) // level 1
        ps.addDerivation("foo", dep("root", 1, "foo", Range.higherThan(1))) // foo >=1 @1
        ps.addDecision("foo", 2) // level 2
        val fooDep = dep("foo", 2, "bar", Range.between(1, 2))
        ps.addDerivation("bar", fooDep) // bar ^1 @2
        ps.addDecision("bar", 1) // level 3
        val barDep = dep("bar", 1, "foo", Range.between(1, 2)) // bar 1 depends on foo ^1 — foo=2 와 충돌
        assertEquals(Relation.Satisfied, ps.relation(barDep))

        val (pkg, search) = ps.satisfierSearch(barDep)
        // 만족자는 bar 의 결정(레벨 3), 이전 만족자는 foo 의 결정(레벨 2)
        assertEquals("bar", pkg)
        assertIs<PartialSolution.SatisfierSearch.DifferentDecisionLevels<String, Int>>(search)
        assertEquals(2, search.previousSatisfierLevel)

        // resolvent: bar 항은 +{1} ∪ -[1,2) = -(1,2) — Range 는 이산성을 모르므로 (1,2) 는 비어 있지 않다
        val learned = Incompatibility.priorCause(nextId++, barDep, fooDep, "bar")
        assertEquals(Term.positive(Range.singleton(2)), learned.terms["foo"])
        assertEquals(Term.negative(Range.of(Bound.Excluded(1), Bound.Excluded(2))), learned.terms["bar"])
        assertIs<Cause.DerivedFrom<String, Int>>(learned.cause)
    }

    @Test
    fun satisfierSearch_sameLevelWalksUpToTerminal() {
        val ps = PartialSolution<String, Int>()
        ps.addDerivation("root", Incompatibility.notRoot(nextId++, "root", 1))
        ps.addDecision("root", 1) // level 1
        val rootDep = dep("root", 1, "foo", Range.full())
        ps.addDerivation("foo", rootDep) // foo * @1
        val unavailable = Incompatibility.unavailable(nextId++, "foo", Range.singleton(5), "테스트")
        ps.addDerivation("foo", unavailable) // foo not 5 @1
        val noVersions = noVersions("foo", Range.singleton(5).complement())
        assertEquals(Relation.Satisfied, ps.relation(noVersions))

        val (pkg, search) = ps.satisfierSearch(noVersions)
        assertEquals("foo", pkg)
        assertIs<PartialSolution.SatisfierSearch.SameDecisionLevels<String, Int>>(search)
        assertEquals(unavailable.id, search.satisfierCause.id)

        val step1 = Incompatibility.priorCause(nextId++, noVersions, unavailable, "foo")
        assertEquals(mapOf("foo" to Term.positive(Range.full())), step1.terms)
        val (pkg2, search2) = ps.satisfierSearch(step1)
        assertEquals("foo", pkg2)
        assertIs<PartialSolution.SatisfierSearch.SameDecisionLevels<String, Int>>(search2)
        assertEquals(rootDep.id, search2.satisfierCause.id)

        val step2 = Incompatibility.priorCause(nextId++, step1, rootDep, "foo")
        assertEquals(mapOf("root" to Term.exact(1)), step2.terms)
        assertTrue(step2.isTerminal("root", 1))
    }
}
