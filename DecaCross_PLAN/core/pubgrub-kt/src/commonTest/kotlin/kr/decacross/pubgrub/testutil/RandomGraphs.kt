package kr.decacross.pubgrub.testutil

import kr.decacross.pubgrub.Range
import kr.decacross.pubgrub.VersionSet
import kotlin.random.Random

/**
 * 무작위 의존 그래프. [table] 은 `패키지 → (버전 → 의존 맵)` 이고 의존 맵이 null 이면 그 버전은 `Unavailable` 이다.
 * 순회 순서가 결과를 좌우하므로 전부 [LinkedHashMap] 으로 만든다.
 */
internal class RandomGraph(
    val pkgs: List<String>,
    val table: Map<String, Map<Int, Map<String, VersionSet<Int>>?>>,
)

/** 전체·단일·구간·상한·하한·두 점 합집합·빈 집합을 고루 섞은 무작위 범위. */
private fun randomSet(rnd: Random): VersionSet<Int> =
    when (rnd.nextInt(7)) {
        0 -> Range.full()
        1 -> Range.singleton(rnd.nextInt(1, 4))
        2 -> Range.between(rnd.nextInt(1, 3), rnd.nextInt(2, 5))
        3 -> Range.higherThan(rnd.nextInt(1, 4))
        4 -> Range.strictlyLowerThan(rnd.nextInt(2, 4))
        5 -> Range.singleton<Int>(rnd.nextInt(1, 4)).union(Range.singleton(rnd.nextInt(1, 4)))
        else -> if (rnd.nextInt(4) == 0) Range.empty() else Range.full()
    }

/** 패키지 3~5개, 버전 1..3(일부 누락), 자기 의존·빈 의존·`Unavailable` 이 섞인 그래프. */
internal fun randomGraph(rnd: Random): RandomGraph {
    val pkgs = listOf("root", "a", "b", "c", "d").take(rnd.nextInt(3, 6))
    val table = LinkedHashMap<String, Map<Int, Map<String, VersionSet<Int>>?>>()
    for (p in pkgs) {
        val versions = LinkedHashMap<Int, Map<String, VersionSet<Int>>?>()
        val vs = if (p == "root") listOf(1) else (1..3).filter { rnd.nextInt(4) != 0 }
        for (v in vs) {
            if (p != "root" && rnd.nextInt(8) == 0) {
                versions[v] = null
                continue
            }
            val deps = LinkedHashMap<String, VersionSet<Int>>()
            for (q in pkgs) {
                if (q == "root" && rnd.nextInt(6) != 0) continue
                if (rnd.nextInt(if (q == p) 8 else 3) == 0) deps[q] = randomSet(rnd)
            }
            versions[v] = deps
        }
        table[p] = versions
    }
    return RandomGraph(pkgs, table)
}

/** 그래프를 그대로 담은 제공자. 같은 그래프로 두 번 만들면 완전히 같은 제공자가 나온다. */
internal fun providerOf(g: RandomGraph): OfflineDependencyProvider<String, Int> {
    val pr = OfflineDependencyProvider<String, Int>()
    for ((p, vs) in g.table) {
        for ((v, deps) in vs) {
            if (deps == null) pr.addUnavailable(p, v, "x") else pr.add(p, v, *deps.entries.map { it.key to it.value }.toTypedArray())
        }
    }
    return pr
}

/** 해가 그래프를 실제로 만족하는가 (솔버를 쓰지 않는 독립 판정). */
internal fun isValidSolution(g: RandomGraph, sel: Map<String, Int>): Boolean {
    if (sel["root"] != 1) return false
    for ((p, v) in sel) {
        val deps = g.table[p]?.get(v) ?: return false
        for ((d, set) in deps) {
            val dv = sel[d] ?: return false
            if (dv !in set) return false
        }
    }
    return true
}

/** 완전 탐색 오라클: 패키지마다 "고르지 않음"까지 포함해 전부 시도. 패키지 ≤ 5, 버전 ≤ 3 이라 충분히 빠르다. */
internal fun bruteForceSatisfiable(g: RandomGraph): Boolean {
    val others = g.pkgs.filter { it != "root" }

    fun rec(i: Int, sel: MutableMap<String, Int>): Boolean {
        if (i == others.size) return isValidSolution(g, sel)
        val p = others[i]
        if (rec(i + 1, sel)) return true
        for (v in g.table[p]?.keys ?: emptySet()) {
            sel[p] = v
            if (rec(i + 1, sel)) return true
            sel.remove(p)
        }
        return false
    }
    return rec(0, linkedMapOf("root" to 1))
}
