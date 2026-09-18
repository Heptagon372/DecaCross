package kr.decacross.pubgrub

import kr.decacross.pubgrub.testutil.OfflineDependencyProvider
import kr.decacross.pubgrub.testutil.bruteForceSatisfiable
import kr.decacross.pubgrub.testutil.formatLog
import kr.decacross.pubgrub.testutil.isValidSolution
import kr.decacross.pubgrub.testutil.providerOf
import kr.decacross.pubgrub.testutil.randomGraph
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun one(v: Int): VersionSet<Int> = Range.singleton(v)

/** [runDecisionLoop] — P1-b 범위(충돌 해결 없음)의 결정 루프. */
class DecisionLoopTest {
    /** root → a → b 사슬. 결정 레벨이 하나씩 올라가고 각 의존이 제때 유도된다. */
    @Test
    fun linear() {
        val provider =
            OfflineDependencyProvider<String, Int>()
                .add("root", 1, "a" to Range.between(1, 3))
                .add("a", 1, "b" to Range.full())
                .add("a", 2, "b" to Range.higherThan(1))
                .add("a", 3)
                .add("b", 1)
                .add("b", 2)
        val out = assertIs<LoopOutcome.Solved<String, Int>>(runDecisionLoop(provider, "root", 1), "선형 그래프는 풀린다")
        assertEquals(linkedMapOf("root" to 1, "a" to 2, "b" to 2), out.selected, "선형 해")
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L1 root = 1",
                "g2 L1 a ← i1 >=1, <3 ⇒ >=1, <3",
                "g3 L2 a = 2",
                "g4 L2 b ← i2 >=1 ⇒ >=1",
                "g5 L3 b = 2",
            ),
            formatLog(out.state),
            "선형 로그",
        )
        assertNull(out.state.partialSolution.invariantViolation(), "선형 불변식")
    }

    /** 다이아몬드(root → a, b → c). 결정 순서까지 고정이다 (후보 수가 적은 패키지가 먼저). */
    @Test
    fun diamond() {
        val provider =
            OfflineDependencyProvider<String, Int>()
                .add("root", 1, "a" to Range.full(), "b" to Range.full())
                .add("a", 1, "c" to Range.higherThan(1))
                .add("a", 2, "c" to Range.higherThan(2))
                .add("b", 1, "c" to Range.strictlyLowerThan(4))
                .add("c", 1)
                .add("c", 2)
                .add("c", 3)
                .add("c", 4)
        val out = assertIs<LoopOutcome.Solved<String, Int>>(runDecisionLoop(provider, "root", 1), "다이아몬드는 풀린다")
        assertEquals(
            listOf("root" to 1, "b" to 1, "a" to 2, "c" to 3),
            out.selected.entries.map { it.key to it.value },
            "다이아몬드 해 (결정 순서 포함)",
        )
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L1 root = 1",
                "g2 L1 b ← i2 * ⇒ *",
                "g3 L1 a ← i1 * ⇒ *",
                "g4 L2 b = 1",
                "g5 L2 c ← i3 <4 ⇒ <4",
                "g6 L3 a = 2",
                "g7 L3 c ← i4 >=2 ⇒ >=2, <4",
                "g8 L4 c = 3",
            ),
            formatLog(out.state),
            "다이아몬드 로그",
        )
    }

    /** `Unavailable` 버전은 비호환만 남기고 같은 패키지를 다시 고르게 한다 (결정하지 않는다). */
    @Test
    fun unavailable() {
        val provider =
            OfflineDependencyProvider<String, Int>()
                .add("root", 1, "a" to Range.full())
                .add("a", 1)
                .addUnavailable("a", 2, "테스트: 받을 수 없음")
        val out = assertIs<LoopOutcome.Solved<String, Int>>(runDecisionLoop(provider, "root", 1), "낮은 버전으로 풀린다")
        assertEquals(linkedMapOf("root" to 1, "a" to 1), out.selected, "쓸 수 없는 2 를 건너뛴 해")
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L1 root = 1",
                "g2 L1 a ← i1 * ⇒ *",
                "g3 L1 a ← i2 Not ( 2 ) ⇒ <2 | >2",
                "g4 L2 a = 1",
            ),
            formatLog(out.state),
            "Unavailable 로그",
        )
        assertEquals(
            IncompatibilityCause.Unavailable(PackageId(1), one(2), "테스트: 받을 수 없음"),
            out.state.incompatibilities[IncompId(2)].cause,
            "사유가 보존된다",
        )
    }

    /** 범위 안에 버전이 하나도 없으면 `NoVersions` 비호환이 곧바로 만족되어 충돌로 표시된다. */
    @Test
    fun noVersions_flagged() {
        val provider =
            OfflineDependencyProvider<String, Int>()
                .add("root", 1, "a" to Range.higherThan(5))
                .add("a", 1)
                .add("a", 2)
        val out =
            assertIs<LoopOutcome.ConflictFlagged<String, Int>>(runDecisionLoop(provider, "root", 1), "충돌 표시")
        assertEquals(
            IncompatibilityCause.NoVersions(PackageId(1), Range.higherThan(5)),
            out.state.incompatibilities[out.incompatibility].cause,
            "표시된 원인",
        )
        assertEquals(3, out.state.partialSolution.nextGlobalIndex, "표시 뒤에는 할당이 늘지 않는다")
    }

    /**
     * 만족 가능한 그래프(`{root:1, b:1, a:1}`)지만 P1-b 는 역추적이 없어 충돌로 표시한다.
     * ★ P1-c 에서 이 테스트는 `Solved` 로 뒤집힌다 — 회귀가 아니다.
     */
    @Test
    fun dependencyConflict_flagged() {
        val provider =
            OfflineDependencyProvider<String, Int>()
                .add("root", 1, "a" to Range.full(), "b" to Range.strictlyLowerThan(2))
                .add("a", 1)
                .add("a", 2, "b" to Range.higherThan(2))
                .add("b", 1)
                .add("b", 2)
        val out =
            assertIs<LoopOutcome.ConflictFlagged<String, Int>>(runDecisionLoop(provider, "root", 1), "충돌 표시")
        assertEquals(3, out.incompatibility.raw, "표시된 비호환 id")
        assertEquals(
            IncompatibilityCause.FromDependencyOf(PackageId(1), one(2), PackageId(2), Range.higherThan(2)),
            out.state.incompatibilities[out.incompatibility].cause,
            "a=2 가 요구하는 b 범위",
        )
        assertEquals(
            listOf(
                "g0 L0 root ← i0 1 ⇒ 1",
                "g1 L1 root = 1",
                "g2 L1 b ← i2 <2 ⇒ <2",
                "g3 L1 a ← i1 * ⇒ *",
                "g4 L2 b = 1",
                "g5 L3 a = 2",
            ),
            formatLog(out.state),
            "빠른 경로라 a=2 를 그대로 결정한 뒤 전파에서 충돌",
        )
    }

    /** 자기 의존: 항상 참이면 비호환이 생기지 않고, 불가능하면 종단 비호환이 된다. */
    @Test
    fun selfDependency() {
        val good =
            assertIs<LoopOutcome.Solved<String, Int>>(
                runDecisionLoop(OfflineDependencyProvider<String, Int>().add("a", 0, "a" to Range.full()), "a", 0),
                "항상 참인 자기 의존",
            )
        assertEquals(linkedMapOf("a" to 0), good.selected, "자기 의존 해")

        val bad =
            assertIs<LoopOutcome.ConflictFlagged<String, Int>>(
                runDecisionLoop(OfflineDependencyProvider<String, Int>().add("a", 66, "a" to one(111)), "a", 66),
                "불가능한 자기 의존",
            )
        val incompat = bad.state.incompatibilities[bad.incompatibility]
        assertTrue(incompat.isTerminal(bad.state.rootPackage, 66), "종단 비호환 (P1-c 에서 NoSolution 이 된다)")
    }

    /**
     * 무작위 그래프 2,000개 건전성. 루프를 직접 돌면서 전파 직후마다 부분해 불변식과 "색인된 비호환이 만족·거의 만족이 아님"
     * (고정점)을 확인하고, 결과를 [runDecisionLoop] · 완전 탐색과 대조한다.
     */
    @Test
    fun randomGraphs_soundness() {
        val rnd = Random(4242)
        var solved = 0
        var flagged = 0
        repeat(2_000) { n ->
            val g = randomGraph(rnd)
            val provider = providerOf(g)
            val state = SolverState("root", 1)
            var next = state.rootPackage
            var outcome: LoopOutcome<String, Int>? = null
            while (outcome == null) {
                val propagation = state.unitPropagation(next)
                if (propagation is PropagationResult.Conflict) {
                    outcome = LoopOutcome.ConflictFlagged(state, propagation.incompatibility)
                    break
                }
                val violation = state.partialSolution.invariantViolation()
                law(violation == null) { "무작위 #$n 불변식: $violation" }
                for (raw in 0 until state.packages.size) {
                    for (id in state.incompatibilitiesOf(PackageId(raw))) {
                        val rel = state.partialSolution.relation(state.incompatibilities[id])
                        law(rel != IncompatibilityRelation.Satisfied && rel !is IncompatibilityRelation.AlmostSatisfied) {
                            "무작위 #$n 고정점 아님 i${id.raw} $rel"
                        }
                    }
                }
                when (val step = state.decisionMaking(provider)) {
                    is DecisionStep.Solved -> outcome = LoopOutcome.Solved(state, step.selected)
                    is DecisionStep.Next -> next = step.pkg
                }
            }
            val done = checkNotNull(outcome)
            val reference = runDecisionLoop(providerOf(g), "root", 1)
            law(done::class == reference::class) { "무작위 #$n 결과 종류가 runDecisionLoop 과 다르다" }
            when (done) {
                is LoopOutcome.Solved -> {
                    solved++
                    law(done.selected == (reference as LoopOutcome.Solved).selected) { "무작위 #$n 해 불일치" }
                    law(isValidSolution(g, done.selected)) { "무작위 #$n 해가 그래프를 만족하지 않음 ${done.selected}" }
                    law(bruteForceSatisfiable(g)) { "무작위 #$n 풀렸는데 완전 탐색은 불가능이라고 한다" }
                }

                is LoopOutcome.ConflictFlagged -> {
                    flagged++
                    val rel = done.state.partialSolution.relation(done.state.incompatibilities[done.incompatibility])
                    law(rel == IncompatibilityRelation.Satisfied) { "무작위 #$n 표시된 비호환이 만족 상태가 아님" }
                }
            }
        }
        assertTrue(solved > 0 && flagged > 0, "두 결과가 모두 나와야 한다 (solved=$solved flagged=$flagged)")
        // 참조 구현과 같은 난수열·같은 판정이면 분포까지 같다 (결정성 회귀 감시)
        assertEquals(1194, solved, "풀린 그래프 수 (solved=$solved flagged=$flagged)")
        assertEquals(806, flagged, "충돌로 표시된 그래프 수 (solved=$solved flagged=$flagged)")
    }
}
