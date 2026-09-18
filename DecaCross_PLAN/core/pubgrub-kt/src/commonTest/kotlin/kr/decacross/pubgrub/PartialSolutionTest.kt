package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

private fun one(v: Int): VersionSet<Int> = Range.singleton(v)

private val p0 = PackageId(0)
private val p1 = PackageId(1)
private val p2 = PackageId(2)
private val p3 = PackageId(3)

/** 결정 레벨 0~3 의 고정 연산열과 레벨마다의 스냅샷. 두 테스트가 같은 열을 쓴다. */
private class Fixture {
    val ps = PartialSolution<Int>()
    val snaps = ArrayList<PartialSolutionSnapshot<Int>>()

    init {
        ps.addDerivation(p0, IncompId(0), Term.exact(1))
        snaps.add(ps.snapshot()) // L0
        ps.addDecision(p0, 1)
        ps.addDerivation(p1, IncompId(1), Term.Positive(Range.full()))
        ps.addDerivation(p2, IncompId(2), Term.Positive(Range.strictlyLowerThan(4)))
        snaps.add(ps.snapshot()) // L1
        ps.addDecision(p1, 2)
        ps.addDerivation(p2, IncompId(3), Term.Positive(Range.higherThan(2)))
        ps.addDerivation(p3, IncompId(4), Term.Negative(one(7)))
        snaps.add(ps.snapshot()) // L2
        ps.addDecision(p2, 3)
        snaps.add(ps.snapshot()) // L3
    }
}

/** [PartialSolution] 의 되돌리기·재실행·불변식 P1~P5. */
class PartialSolutionTest {
    /** 레벨 3 에서 2 → 1 → 0 으로 되돌리면 각 레벨의 스냅샷과 정확히 같아진다. */
    @Test
    fun backtrack_restoresSnapshots() {
        val f = Fixture()
        val ps = f.ps
        assertNull(ps.invariantViolation(), "레벨 3 불변식")
        assertEquals(3, ps.currentDecisionLevel, "결정 레벨")
        assertEquals(Term.exact(3), ps.termOf(p2), "결정된 패키지의 누적 항은 그 버전")
        assertEquals(8, ps.nextGlobalIndex, "할당 8개")

        for (target in intArrayOf(2, 1, 0)) {
            ps.backtrack(target)
            assertEquals(f.snaps[target], ps.snapshot(), "backtrack($target) 스냅샷")
            assertNull(ps.invariantViolation(), "backtrack($target) 뒤 불변식")
        }
        assertTrue(ps.hasBacktracked, "hasBacktracked 는 되돌린 이력을 남긴다")
    }

    /** 여러 레벨을 한 번에 건너뛰고, 지운 연산을 그대로 다시 넣으면 전역 번호까지 원래대로 복원된다. */
    @Test
    fun jump_replay_noop() {
        val f = Fixture()
        val replay = f.ps
        replay.backtrack(1)
        assertEquals(f.snaps[1], replay.snapshot(), "3 → 1 한 번에 되돌리기")
        assertEquals(Term.Positive(Range.full<Int>()), replay.termOf(p1), "p1 은 다시 미결정")
        assertNull(replay.termOf(p3), "p3 은 슬롯째 사라진다")
        assertEquals(p2, replay.pickHighestPriority { id, _ -> if (id == p2) 5 else 1 }?.pkg, "우선순위 최대")
        assertEquals(p1, replay.pickHighestPriority { _, _ -> 0 }?.pkg, "동점이면 작은 id")

        replay.addDecision(p1, 2)
        replay.addDerivation(p2, IncompId(3), Term.Positive(Range.higherThan(2)))
        replay.addDerivation(p3, IncompId(4), Term.Negative(one(7)))
        replay.addDecision(p2, 3)
        assertEquals(f.snaps[3], replay.snapshot(), "재실행하면 전역 번호까지 원래와 같다")
        assertEquals(
            Term.Positive(r(2, 4)),
            replay.snapshot().packages[p2]?.derivations?.last()?.accumulated,
            "p2 누적 = (<4) ∩ (>=2)",
        )

        val before = replay.snapshot()
        replay.backtrack(replay.currentDecisionLevel)
        assertEquals(before, replay.snapshot(), "현재 레벨로 되돌리기는 무연산")
    }

    /**
     * 무작위 연산열(2,000회 × 40연산, 패키지 4개, `Dom(3)` 항)을 순진한 모델과 대조한다.
     * 매 연산 뒤 불변식 P1~P5, 결정 레벨, 패키지별 누적 항(로그를 접어 다시 계산한 값)이 모두 일치해야 한다.
     */
    @Test
    fun randomModel_seed20260917() {
        val d = Dom(3)
        val rnd = Random(20260917)
        val probes = (-1 until d.atoms - 1).toList()
        repeat(2_000) {
            val ps = PartialSolution<Int>()
            // 모델: (패키지, 레벨, 항) — 결정의 항은 exact(버전)
            val model = ArrayList<Triple<Int, Int, Term<Int>>>()
            val decided = HashSet<Int>()
            var level = 0
            repeat(40) {
                val pkg = rnd.nextInt(4)
                when (rnd.nextInt(10)) {
                    in 0..5 ->
                        if (pkg !in decided) {
                            val t: Term<Int> =
                                if (rnd.nextBoolean()) {
                                    Term.Positive(d.randomRange(rnd))
                                } else {
                                    Term.Negative(d.randomRange(rnd))
                                }
                            ps.addDerivation(PackageId(pkg), IncompId(0), t)
                            model.add(Triple(pkg, level, t))
                        }

                    in 6..7 -> {
                        val acc = model.filter { it.first == pkg }.map { it.third }.reduceOrNull { x, y -> x.intersection(y) }
                        if (acc != null && pkg !in decided) {
                            val v = probes.filter { it in acc }.randomOrNull(rnd)
                            if (v != null) {
                                ps.addDecision(PackageId(pkg), v)
                                level++
                                decided.add(pkg)
                                model.add(Triple(pkg, level, Term.exact(v)))
                            }
                        }
                    }

                    else ->
                        if (level > 0) {
                            val target = rnd.nextInt(level + 1)
                            ps.backtrack(target)
                            model.removeAll { it.second > target }
                            // 결정은 자기 레벨에 기록되므로 레벨 > target 인 결정이 모두 사라진다
                            decided.clear()
                            level = target
                            var lv = 0
                            for (e in model) {
                                if (e.second > lv) {
                                    lv = e.second
                                    decided.add(e.first)
                                }
                            }
                        }
                }
                val violation = ps.invariantViolation()
                law(violation == null) { "불변식 위반: $violation" }
                law(ps.currentDecisionLevel == level) { "레벨 ${ps.currentDecisionLevel} != $level" }
                for (p in 0 until 4) {
                    val entries = model.filter { it.first == p }.map { it.third }
                    val acc =
                        if (p in decided) entries.last() else entries.reduceOrNull { x, y -> x.intersection(y) }
                    law(ps.termOf(PackageId(p)) == acc) { "항 p$p ${ps.termOf(PackageId(p))} != $acc" }
                }
            }
        }
    }
}
