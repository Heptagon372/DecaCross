package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolverTest {
    private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

    private fun any(): VersionSet<Int> = Range.full()

    private fun solve(provider: DependencyProvider<String, Int>): SolverResult<String, Int> = resolve(provider, "root", 1)

    private fun solution(result: SolverResult<String, Int>): Map<String, Int> {
        assertIs<SolverResult.Solution<String, Int>>(result, "해가 있어야 한다: $result")
        return result.selected
    }

    private fun noSolution(result: SolverResult<String, Int>): DerivationTree<String, Int> {
        assertIs<SolverResult.NoSolution<String, Int>>(result, "해가 없어야 한다: $result")
        return result.tree
    }

    @Test
    fun simpleChain_noConflict() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to r(1, 2)))
                add("foo", 1, mapOf("bar" to r(1, 2)))
                add("bar", 1)
                add("bar", 2)
            }
        assertEquals(mapOf("root" to 1, "foo" to 1, "bar" to 1), solution(solve(p)))
    }

    @Test
    fun rootOnly() {
        val p = MapDependencyProvider.build<String, Int> { add("root", 1) }
        assertEquals(mapOf("root" to 1), solution(solve(p)))
    }

    @Test
    fun highestVersionPreferred_andUnrelatedPackagesNotSelected() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any()))
                add("a", 1)
                add("a", 2)
                add("a", 3)
                add("unrelated", 1)
            }
        assertEquals(mapOf("root" to 1, "a" to 3), solution(solve(p)))
    }

    @Test
    fun avoidingConflictDuringDecisionMaking() {
        // 문서 예제: foo 1.1.0 은 bar ^2 를 요구하지만 root 는 bar ^1 을 요구 → foo 1.0.0 으로 회피
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to r(100, 200), "bar" to r(100, 200)))
                add("foo", 110, mapOf("bar" to r(200, 300)))
                add("foo", 100)
                add("bar", 100)
                add("bar", 110)
                add("bar", 200)
            }
        assertEquals(mapOf("root" to 1, "foo" to 100, "bar" to 110), solution(solve(p)))
    }

    @Test
    fun performingConflictResolution() {
        // 문서 예제: foo 2 → bar ^1 → foo ^1 이므로 foo 2 는 불가, foo 1 선택
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to Range.higherThan(100)))
                add("foo", 200, mapOf("bar" to r(100, 200)))
                add("foo", 100)
                add("bar", 100, mapOf("foo" to r(100, 200)))
            }
        assertEquals(mapOf("root" to 1, "foo" to 100), solution(solve(p)))
    }

    @Test
    fun conflictResolutionWithPartialSatisfier() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to r(100, 200), "target" to r(200, 300)))
                add("foo", 110, mapOf("left" to r(100, 200), "right" to r(100, 200)))
                add("foo", 100)
                add("left", 100, mapOf("shared" to Range.higherThan(100)))
                add("right", 100, mapOf("shared" to Range.strictlyLowerThan(200)))
                add("shared", 200)
                add("shared", 100, mapOf("target" to r(100, 200)))
                add("target", 200)
                add("target", 100)
            }
        assertEquals(mapOf("root" to 1, "foo" to 100, "target" to 200), solution(solve(p)))
    }

    @Test
    fun backtracking_shallowChoiceBlockedLater() {
        // a 의 최신(3)을 고르면 c 에서 막힌다 → a 2 로 되돌아가야 한다
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any(), "b" to any()))
                add("a", 3, mapOf("c" to r(3, 4)))
                add("a", 2, mapOf("c" to r(1, 3)))
                add("a", 1)
                add("b", 1, mapOf("c" to r(1, 3)))
                add("c", 1)
                add("c", 2)
                add("c", 3)
            }
        assertEquals(mapOf("root" to 1, "a" to 2, "b" to 1, "c" to 2), solution(solve(p)))
    }

    @Test
    fun cyclicDependencies_resolve() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any()))
                add("a", 1, mapOf("b" to any()))
                add("b", 1, mapOf("a" to any(), "c" to any()))
                add("c", 1, mapOf("b" to r(1, 2)))
            }
        assertEquals(mapOf("root" to 1, "a" to 1, "b" to 1, "c" to 1), solution(solve(p)))
    }

    @Test
    fun selfDependency_isHandled() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any()))
                add("a", 2, mapOf("a" to r(1, 2))) // 자기 자신의 다른 버전을 요구 → 불가
                add("a", 1, mapOf("a" to r(1, 3))) // 자기 자신을 포함 → 무해
            }
        assertEquals(mapOf("root" to 1, "a" to 1), solution(solve(p)))
    }

    @Test
    fun noSolution_missingPackage_terminates() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to any()))
            }
        val tree = noSolution(solve(p))
        assertEquals(setOf("root", "foo"), tree.packages())
        assertTrue(tree.externals().any { it is ExternalKind.NoVersions<String, Int> && it.pkg == "foo" })
    }

    @Test
    fun noSolution_linearErrorReporting() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to r(100, 200), "baz" to r(100, 200)))
                add("foo", 100, mapOf("bar" to r(200, 300)))
                add("bar", 200, mapOf("baz" to r(300, 400)))
                add("baz", 100)
                add("baz", 300)
            }
        val tree = noSolution(solve(p))
        assertEquals(setOf("root", "foo", "bar", "baz"), tree.packages())
        println(tree.render())
    }

    @Test
    fun noSolution_branchingErrorReporting() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("foo" to r(100, 200)))
                add("foo", 100, mapOf("a" to r(100, 200), "b" to r(100, 200)))
                add("foo", 110, mapOf("x" to r(100, 200), "y" to r(100, 200)))
                add("a", 100, mapOf("b" to r(200, 300)))
                add("b", 100)
                add("b", 200)
                add("x", 100, mapOf("y" to r(200, 300)))
                add("y", 100)
                add("y", 200)
            }
        val tree = noSolution(solve(p))
        assertEquals(setOf("root", "foo", "a", "b", "x", "y"), tree.packages())
        println(tree.render())
    }

    @Test
    fun unavailableVersion_isSkippedAndExplained() {
        val p =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any()))
                addUnavailable("a", 2, "재배포 불가")
                add("a", 1)
            }
        assertEquals(mapOf("root" to 1, "a" to 1), solution(solve(p)))

        val q =
            MapDependencyProvider.build<String, Int> {
                add("root", 1, mapOf("a" to any()))
                addUnavailable("a", 2, "재배포 불가")
            }
        val tree = noSolution(solve(q))
        assertEquals(setOf("root", "a"), tree.packages())
        assertTrue(tree.externals().any { it is ExternalKind.Unavailable<String, Int> && it.reasonKo == "재배포 불가" })
    }

    @Test
    fun stepBudget_throwsKoreanMessageWhenExceeded() {
        val budget = StepBudget(3)
        repeat(3) { budget.tick() }
        val e = assertFailsWith<IllegalStateException> { budget.tick() }
        assertTrue("반복 상한" in (e.message ?: ""), e.message ?: "")
        assertTrue(MAX_SOLVER_STEPS >= 1_000_000)
    }

    @Test
    fun providerChoosingOutOfRange_isContractViolation() {
        val bad =
            object : DependencyProvider<String, Int> {
                override fun chooseVersion(pkg: String, range: VersionSet<Int>): Int? = 99

                override fun getDependencies(pkg: String, version: Int): Dependencies<String, Int> = Dependencies.Available(emptyMap())
            }
        assertFailsWith<IllegalStateException> { resolve(bad, "root", 1) }
    }
}
