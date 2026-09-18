package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

private fun one(v: Int): VersionSet<Int> = Range.singleton(v)

private fun lookup(vararg e: Pair<PackageId, Term<Int>>): TermLookup<Int> {
    val m = mapOf(*e)
    return TermLookup { m[it] }
}

private val root = PackageId(0)
private val a = PackageId(1)
private val b = PackageId(2)
private val c = PackageId(3)

/** [Incompatibility] 의 생성자·관계 판정·병합, 그리고 [SolverState] 인덱스의 반복 병합. */
class IncompatibilityTest {
    /** 팩토리별 항 구성과 [Incompatibility.isTerminal] (자기 의존 정규화 포함). */
    @Test
    fun factories() {
        val nr = Incompatibility.notRoot(root, 1)
        assertEquals(listOf<Term<Int>>(Term.Negative(one(1))), (0 until nr.size).map { nr.termAt(it) }, "notRoot 항")
        assertFalse(nr.isTerminal(root, 1), "notRoot 는 종단이 아니다")
        assertTrue(Incompatibility.noVersions(root, one(1)).isTerminal(root, 1), "루트 1 금지 → 종단")
        assertFalse(Incompatibility.noVersions(root, one(2)).isTerminal(root, 1), "루트 2 금지는 루트 1 에 무관")
        assertFalse(Incompatibility.noVersions(a, one(1)).isTerminal(root, 1), "루트가 아니면 종단이 아니다")

        val dep = assertNotNull(Incompatibility.fromDependency(a, one(1), b, r(1, 3)), "의존 비호환")
        assertEquals(2, dep.size, "의존 비호환은 항 2개")
        assertEquals(Term.Positive(one(1)), dep.termAt(0), "의존자 항")
        assertEquals(Term.Negative(r(1, 3)), dep.termAt(1), "피의존 항")
        assertEquals(a, dep.packageAt(0), "0번은 의존자")

        val depEmpty = assertNotNull(Incompatibility.fromDependency(a, one(1), b, Range.empty()), "빈 의존")
        assertEquals(1, depEmpty.size, "∅ 에 대한 의존 → 항 1개")
        assertEquals(
            IncompatibilityCause.FromDependencyOf(a, one(1), b, Range.empty()),
            depEmpty.cause,
            "항이 하나여도 원인은 피의존 패키지를 기억한다",
        )

        assertNull(Incompatibility.fromDependency(a, one(0), a, Range.full()), "항상 참인 자기 의존 → 비호환 없음")
        val selfBad = assertNotNull(Incompatibility.fromDependency(a, one(66), a, one(111)), "불가능한 자기 의존")
        assertEquals(Term.Positive(one(66)), selfBad.termAt(0), "자기 의존은 의존자 범위에서 피의존 범위를 뺀다")
        assertEquals(1, selfBad.size, "자기 의존은 항 1개")
    }

    /** rs 와 같은 조기 종료 규칙: 두 번째 미결 항을 보는 순간 Inconclusive. */
    @Test
    fun relation() {
        val dep = assertNotNull(Incompatibility.fromDependency(a, one(1), b, r(1, 3)), "의존 비호환")
        assertEquals(
            IncompatibilityRelation.AlmostSatisfied(1),
            dep.relation(lookup(a to Term.exact(1))),
            "피의존 미할당 → 거의 만족(피의존)",
        )
        assertEquals(
            IncompatibilityRelation.Satisfied,
            dep.relation(lookup(a to Term.exact(1), b to Term.Positive(one(5)))),
            "b=5 는 범위 밖 → 만족",
        )
        assertEquals(
            IncompatibilityRelation.Contradicted(1),
            dep.relation(lookup(a to Term.exact(1), b to Term.Positive(one(2)))),
            "b=2 는 범위 안 → 모순",
        )
        assertEquals(IncompatibilityRelation.Contradicted(0), dep.relation(lookup(a to Term.exact(2))), "a=2 → 0번에서 모순")
        assertEquals(IncompatibilityRelation.Inconclusive, dep.relation(lookup()), "할당 없음 → 미결")
        assertEquals(
            IncompatibilityRelation.AlmostSatisfied(0),
            dep.relation(lookup(a to Term.Positive(r(0, 5)), b to Term.Positive(one(7)))),
            "a 는 걸쳐 있고 b 는 만족 → 거의 만족(a)",
        )

        val three =
            Incompatibility.fromTerms(
                listOf(a to Term.exact(1), b to Term.exact(2), c to Term.exact(3)),
                IncompatibilityCause.DerivedFrom<Int>(IncompId(0), IncompId(1)),
            )
        assertEquals(
            IncompatibilityRelation.Inconclusive,
            three.relation(lookup(c to Term.Positive(one(9)))),
            "미결 두 번째에서 즉시 종료 — 뒤의 모순 항은 보지 않는다",
        )
        assertEquals(
            IncompatibilityRelation.Contradicted(2),
            three.relation(lookup(a to Term.exact(1), c to Term.Positive(one(9)))),
            "미결 하나 뒤의 모순은 모순으로 보고",
        )
    }

    /** 같은 (의존자, 피의존자)·같은 피의존 범위일 때만 의존자 범위를 합친다. */
    @Test
    fun mergeDependents() {
        val d1 = assertNotNull(Incompatibility.fromDependency(a, r(1, 4), b, r(7, 10)), "d1")
        val d2 = assertNotNull(Incompatibility.fromDependency(a, one(4), b, r(7, 10)), "d2")
        val merged = assertNotNull(d1.mergeDependents(d2), "병합 결과")
        assertEquals(
            IncompatibilityCause.FromDependencyOf(a, Range.of(Bound.Inclusive(1), Bound.Inclusive(4)), b, r(7, 10)),
            merged.cause,
            "병합 원인은 의존자 범위 합집합",
        )
        assertEquals(
            Term.Positive(Range.of(Bound.Inclusive(1), Bound.Inclusive(4))),
            merged.termAt(0),
            "병합된 의존자 항",
        )

        val otherRange = assertNotNull(Incompatibility.fromDependency(a, one(4), b, r(7, 11)), "다른 피의존 범위")
        assertNull(d1.mergeDependents(otherRange), "피의존 범위가 다르면 병합하지 않는다")
        val otherDep = assertNotNull(Incompatibility.fromDependency(a, one(4), c, r(7, 10)), "다른 피의존 패키지")
        assertNull(d1.mergeDependents(otherDep), "피의존 패키지가 다르면 병합하지 않는다")
        assertNull(d1.mergeDependents(Incompatibility.noVersions(a, one(4))), "의존 비호환이 아니면 병합하지 않는다")

        val e1 = assertNotNull(Incompatibility.fromDependency(a, one(1), b, Range.empty()), "빈 의존 1")
        val e2 = assertNotNull(Incompatibility.fromDependency(a, one(2), b, Range.empty()), "빈 의존 2")
        assertEquals(1, e1.mergeDependents(e2)?.size, "∅ 의존끼리 병합해도 항은 하나")
    }

    /**
     * rs `core.rs` 의 `merge_recurring_non_adjacent_dependencies` 대응.
     * 버전 홀짝으로 피의존 범위를 번갈아 두면 인덱스는 버킷 2개로 줄고, 아레나에는 원본과 병합본이 모두 남는다.
     */
    @Test
    fun storeMergeRecurring() {
        val state = SolverState<String, Int>("root", 0)
        val pkg = state.packages.intern("package")
        for (v in 0 until 10) {
            state.addIncompatibilitiesFromDependencies(pkg, one(v), linkedMapOf("dependency" to one((v % 2) * 2)))
        }
        val dep = state.packages.intern("dependency")
        assertEquals(2, state.incompatibilitiesOf(pkg).size, "의존자 인덱스는 2개로 병합")
        assertEquals(2, state.incompatibilitiesOf(dep).size, "피의존 인덱스도 2개")

        val causes = state.incompatibilitiesOf(pkg).map { state.incompatibilities[it].cause }
        val evens = listOf(0, 2, 4, 6, 8).map { one(it) }.reduce { x, y -> x.union(y) }
        val odds = listOf(1, 3, 5, 7, 9).map { one(it) }.reduce { x, y -> x.union(y) }
        assertEquals(
            setOf(
                IncompatibilityCause.FromDependencyOf(pkg, evens, dep, one(0)),
                IncompatibilityCause.FromDependencyOf(pkg, odds, dep, one(2)),
            ),
            causes.toSet(),
            "병합된 원인 두 개",
        )
        // 아레나: notRoot 1 + 원본 10 + 병합본 8 = 19, 인덱스 id 는 최신 병합본 (16 = 짝수, 18 = 홀수)
        assertEquals(19, state.incompatibilities.size, "아레나 크기")
        assertEquals(listOf(16, 18), state.incompatibilitiesOf(pkg).map { it.raw }, "인덱스는 최신 병합본만")
    }
}
