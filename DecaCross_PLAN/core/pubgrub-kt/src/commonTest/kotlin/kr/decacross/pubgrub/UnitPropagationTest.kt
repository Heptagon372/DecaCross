package kr.decacross.pubgrub

import kr.decacross.pubgrub.testutil.formatLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

private fun one(v: Int): VersionSet<Int> = Range.singleton(v)

/** [SolverState.unitPropagation] 과 [SolverState.addPackageVersionDependencies] — 결정 루프 없이 코어 API 만으로. */
class UnitPropagationTest {
    /** 루트 결정 뒤 전파가 의존 항을 유도한다. 같은 패키지의 비호환은 **나중에 추가된 것부터** 본다. */
    @Test
    fun direct_newestFirst() {
        val st = SolverState<String, Int>("root", 1)
        assertEquals(PropagationResult.Ok, st.unitPropagation(st.rootPackage), "루트 전파")
        assertEquals(Term.exact(1), st.partialSolution.termOf(st.rootPackage), "notRoot 에서 루트 버전 유도")
        assertNull(
            st.addPackageVersionDependencies(st.rootPackage, 1, linkedMapOf("x" to r(1, 5), "y" to Range.higherThan(3))),
            "충돌 없음 → 결정까지 끝",
        )
        assertEquals(PropagationResult.Ok, st.unitPropagation(st.rootPackage), "의존 추가 뒤 전파")

        val x = st.packages.intern("x")
        val y = st.packages.intern("y")
        assertEquals(Term.Positive(r(1, 5)), st.partialSolution.termOf(x), "x 유도")
        assertEquals(Term.Positive(Range.higherThan(3)), st.partialSolution.termOf(y), "y 유도")
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L1 root = 1",
                "g2 L1 y ← i2 >=3 ⇒ >=3",
                "g3 L1 x ← i1 >=1, <5 ⇒ >=1, <5",
            ),
            formatLog(st),
            "역순 전파: y(i2) 가 x(i1) 보다 먼저 유도된다",
        )
    }

    /**
     * 결정 없이 레벨 0 에서만 4단계 전파: 루트 → a → b → c → a (버퍼에서 빠졌던 a 가 다시 들어가고, c 는 부정 항으로 유도된다).
     */
    @Test
    fun multiHop_level0() {
        val st = SolverState<String, Int>("root", 1)
        val pa = st.packages.intern("a")
        val pb = st.packages.intern("b")
        val pc = st.packages.intern("c")
        val dc = IncompatibilityCause.DerivedFrom<Int>(IncompId(0), IncompId(0))
        st.addIncompatibility(
            Incompatibility.fromTerms(listOf(st.rootPackage to Term.exact(1), pa to Term.Negative(r(1, 5))), dc),
        )
        st.addIncompatibility(
            Incompatibility.fromTerms(listOf(pa to Term.Positive(r(0, 9)), pb to Term.Negative(one(2))), dc),
        )
        st.addIncompatibility(
            Incompatibility.fromTerms(listOf(pb to Term.exact(2), pc to Term.exact(3)), dc),
        )
        st.addIncompatibility(
            Incompatibility.fromTerms(listOf(pc to Term.Negative(one(3)), pa to Term.exact(4)), dc),
        )
        assertEquals(PropagationResult.Ok, st.unitPropagation(st.rootPackage), "충돌 없이 고정점")
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L0 a ← i1 >=1, <5 ⇒ >=1, <5",
                "g2 L0 b ← i2 2 ⇒ 2",
                "g3 L0 c ← i3 Not ( 3 ) ⇒ Not ( 3 )",
                "g4 L0 a ← i4 Not ( 4 ) ⇒ >=1, <4 | >4, <5",
            ),
            formatLog(st),
            "4단계 전파 로그",
        )
        assertNull(st.partialSolution.invariantViolation(), "전파 뒤 불변식")
    }

    /**
     * [PartialSolution.hasBacktracked] 가 켜진 뒤의 느린 경로: 새 의존 비호환이 곧바로 만족되면 결정하지 않고 그 id 를 돌려준다.
     */
    @Test
    fun slowPath_afterBacktrack() {
        val st = SolverState<String, Int>("root", 1)
        assertEquals(PropagationResult.Ok, st.unitPropagation(st.rootPackage), "루트 전파")
        assertNull(
            st.addPackageVersionDependencies(st.rootPackage, 1, linkedMapOf("a" to Range.full(), "b" to one(5))),
            "루트 의존 추가",
        )
        assertEquals(PropagationResult.Ok, st.unitPropagation(st.rootPackage), "의존 추가 뒤 전파")
        val a = st.packages.intern("a")
        val b = st.packages.intern("b")

        st.partialSolution.backtrack(st.partialSolution.currentDecisionLevel)
        assertTrue(st.partialSolution.hasBacktracked, "무연산 되돌리기도 이력은 남는다")
        val before = st.partialSolution.snapshot()

        assertEquals(IncompId(3), st.addPackageVersionDependencies(a, 1, linkedMapOf("b" to one(1))), "b=5 와 충돌")
        assertEquals(
            IncompatibilityCause.FromDependencyOf(a, one(1), b, one(1)),
            st.incompatibilities[IncompId(3)].cause,
            "충돌한 비호환의 원인",
        )
        assertEquals(before, st.partialSolution.snapshot(), "충돌이면 결정하지 않는다")

        assertNull(st.addPackageVersionDependencies(a, 2, linkedMapOf("b" to Range.higherThan(5))), "충돌 없음")
        assertEquals(2, st.partialSolution.currentDecisionLevel, "결정 레벨 증가")
        assertEquals(Term.exact(2), st.partialSolution.termOf(a), "a = 2 결정")
    }
}
