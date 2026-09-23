package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 무작위 의존성 그래프 검증.
 * - 해가 있으면: 루트를 포함하고, 선택된 모든 패키지의 모든 의존성이 만족된다.
 * - 해가 없으면: 트리는 그래프의 패키지만 언급한다.
 * - 작은 그래프는 완전 탐색(brute force)과 해의 존재 여부가 일치해야 한다.
 */
class RandomGraphTest {
    private class Graph(
        val packages: List<Int>,
        val versions: Map<Int, List<Int>>,
        val deps: Map<Pair<Int, Int>, Map<Int, VersionSet<Int>>>,
        val rootDeps: Map<Int, VersionSet<Int>>,
    ) {
        val root = -1

        fun provider(): MapDependencyProvider<Int, Int> =
            MapDependencyProvider.build {
                add(root, 0, rootDeps)
                for (p in packages) for (v in versions.getValue(p)) add(p, v, deps.getValue(p to v))
            }
    }

    private fun randomRange(rnd: Random, maxVersion: Int): VersionSet<Int> =
        when (rnd.nextInt(5)) {
            0 -> Range.full()

            1 -> Range.singleton(rnd.nextInt(1, maxVersion + 1))

            2 -> Range.higherThan(rnd.nextInt(1, maxVersion + 1))

            3 -> Range.strictlyLowerThan(rnd.nextInt(1, maxVersion + 2))

            else -> {
                val lo = rnd.nextInt(1, maxVersion + 1)
                Range.between(lo, lo + rnd.nextInt(1, 3))
            }
        }

    private fun randomGraph(rnd: Random, n: Int, maxVersions: Int, maxDeps: Int): Graph {
        val packages = (0 until n).toList()
        val versions = packages.associateWith { (1..rnd.nextInt(1, maxVersions + 1)).toList() }
        val deps = HashMap<Pair<Int, Int>, Map<Int, VersionSet<Int>>>()
        for (p in packages) {
            for (v in versions.getValue(p)) {
                val m = LinkedHashMap<Int, VersionSet<Int>>()
                repeat(rnd.nextInt(0, maxDeps + 1)) {
                    val q = rnd.nextInt(n)
                    if (q != p) m[q] = randomRange(rnd, maxVersions)
                }
                deps[p to v] = m
            }
        }
        val rootDeps = LinkedHashMap<Int, VersionSet<Int>>()
        repeat(rnd.nextInt(1, minOf(n, 3) + 1)) { rootDeps[rnd.nextInt(n)] = randomRange(rnd, maxVersions) }
        return Graph(packages, versions, deps, rootDeps)
    }

    private fun isValid(g: Graph, selected: Map<Int, Int>): Boolean {
        if (selected[g.root] != 0) return false
        for ((p, v) in selected) {
            val deps = if (p == g.root) g.rootDeps else g.deps[p to v] ?: return false
            for ((q, range) in deps) {
                val qv = selected[q] ?: return false
                if (!range.contains(qv)) return false
            }
        }
        return true
    }

    /** 완전 탐색: 각 패키지를 "미선택" 또는 버전 하나로 두고 모든 조합을 검사. */
    private fun bruteForceHasSolution(g: Graph): Boolean {
        val choice = IntArray(g.packages.size) // 0 = 미선택, k = k번째 버전
        while (true) {
            val selected = HashMap<Int, Int>()
            selected[g.root] = 0
            for ((i, p) in g.packages.withIndex()) if (choice[i] > 0) selected[p] = g.versions.getValue(p)[choice[i] - 1]
            if (isValid(g, selected)) return true
            var i = 0
            while (i < choice.size) {
                choice[i]++
                if (choice[i] <= g.versions.getValue(g.packages[i]).size) break
                choice[i] = 0
                i++
            }
            if (i == choice.size) return false
        }
    }

    @Test
    fun smallGraphs_agreeWithBruteForce() {
        val rnd = Random(20260923)
        var solved = 0
        repeat(1500) { iter ->
            val g = randomGraph(rnd, n = rnd.nextInt(2, 6), maxVersions = 3, maxDeps = 2)
            val result = resolve(g.provider(), g.root, 0)
            val expected = bruteForceHasSolution(g)
            when (result) {
                is SolverResult.Solution -> {
                    assertTrue(expected, "#$iter 완전 탐색은 해가 없다는데 솔버가 해를 냈다: ${result.selected}")
                    assertTrue(isValid(g, result.selected), "#$iter 해가 제약을 만족하지 않는다: ${result.selected}")
                    solved++
                }

                is SolverResult.NoSolution -> {
                    assertTrue(!expected, "#$iter 완전 탐색은 해가 있다는데 솔버가 해가 없다고 했다:\n${result.tree.render()}")
                    val mentioned = result.tree.packages()
                    assertTrue(mentioned.all { it == g.root || it in g.packages }, "#$iter 트리가 그래프 밖 패키지를 언급한다: $mentioned")
                    assertTrue(g.root in mentioned, "#$iter 트리에 루트가 없다")
                }
            }
        }
        println("[random-small] 1500개 중 해 있음 $solved")
        assertTrue(solved in 100..1400, "생성기가 한쪽으로 치우쳤다: $solved")
    }

    @Test
    fun largerGraphs_solutionsAreValid() {
        val rnd = Random(777)
        var solved = 0
        repeat(300) { iter ->
            val g = randomGraph(rnd, n = 30, maxVersions = 5, maxDeps = 3)
            when (val result = resolve(g.provider(), g.root, 0)) {
                is SolverResult.Solution -> {
                    if (!isValid(g, result.selected)) fail("#$iter 해가 제약을 만족하지 않는다")
                    solved++
                }

                is SolverResult.NoSolution -> {
                    val mentioned = result.tree.packages()
                    assertTrue(mentioned.all { it == g.root || it in g.packages })
                    assertEquals(true, g.root in mentioned)
                }
            }
        }
        println("[random-large] 300개 중 해 있음 $solved")
    }
}
