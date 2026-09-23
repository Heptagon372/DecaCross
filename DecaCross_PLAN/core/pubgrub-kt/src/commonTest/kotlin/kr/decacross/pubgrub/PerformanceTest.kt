package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** 100 패키지 × 20 버전 그래프가 2초 안에 풀려야 한다. 실제 소요 시간을 출력한다. */
class PerformanceTest {
    private fun bigProvider(seed: Int): MapDependencyProvider<Int, Int> {
        val rnd = Random(seed)
        val n = 100
        val m = 20
        return MapDependencyProvider.build {
            // 루트는 앞쪽 패키지 10개를 요구
            add(-1, 0, (0 until 10).associateWith { Range.full<Int>() })
            for (p in 0 until n) {
                for (v in 1..m) {
                    val deps = LinkedHashMap<Int, VersionSet<Int>>()
                    // 자기보다 뒤쪽 패키지 1~3개에 의존 (비순환). 최신 버전일수록 의존 하한이 높다.
                    repeat(rnd.nextInt(1, 4)) {
                        if (p + 1 < n) {
                            val q = rnd.nextInt(p + 1, n)
                            val lo = maxOf(1, v - rnd.nextInt(0, 6))
                            deps[q] = Range.between(lo, lo + rnd.nextInt(2, 8))
                        }
                    }
                    add(p, v, deps)
                }
            }
        }
    }

    @Test
    fun hundredPackagesTwentyVersions_underTwoSeconds() {
        val provider = bigProvider(20260923)
        // 워밍업 한 번 (JIT) 후 측정
        resolve(provider, -1, 0)
        val mark = TimeSource.Monotonic.markNow()
        val result = resolve(provider, -1, 0)
        val elapsed = mark.elapsedNow()
        println("[perf] 100 패키지 × 20 버전 해결: $elapsed → $result")
        assertIs<SolverResult.Solution<Int, Int>>(result, "이 그래프는 해가 있어야 한다: $result")
        assertTrue(result.selected.size > 10)
        assertTrue(elapsed.inWholeMilliseconds < 2000, "2초를 넘겼다: $elapsed")
    }

    @Test
    fun manyBacktracks_stillFast() {
        // 서로 다른 시드 여러 개 — 충돌이 많은 그래프도 포함되도록
        val mark = TimeSource.Monotonic.markNow()
        var solutions = 0
        for (seed in 1..5) {
            when (resolve(bigProvider(seed), -1, 0)) {
                is SolverResult.Solution -> solutions++
                is SolverResult.NoSolution -> {}
            }
        }
        val elapsed = mark.elapsedNow()
        println("[perf] 시드 5개 (해 $solutions 개): $elapsed")
        assertTrue(elapsed.inWholeMilliseconds < 10_000, "너무 느리다: $elapsed")
    }
}
