package kr.decacross.pubgrub.testutil

import kr.decacross.pubgrub.Dependencies
import kr.decacross.pubgrub.DependencyProvider
import kr.decacross.pubgrub.VersionSet

/**
 * Map 기반 테스트 제공자 (rs `OfflineDependencyProvider` 대응).
 * - chooseVersion: 범위 안 최고 버전.
 * - prioritize: 범위 안 버전 수 c → c == 0 이면 Int.MAX_VALUE, 아니면 -c (rs 의 충돌 0회일 때 순서와 같다).
 * - 모르는 (패키지, 버전)의 getDependencies → Unavailable.
 */
internal class OfflineDependencyProvider<P : Any, V : Comparable<V>> : DependencyProvider<P, V> {
    private val table = LinkedHashMap<P, ArrayList<Pair<V, Dependencies<P, V>>>>()

    fun add(pkg: P, version: V, vararg deps: Pair<P, VersionSet<V>>): OfflineDependencyProvider<P, V> =
        put(pkg, version, Dependencies.Available(linkedMapOf(*deps)))

    fun addUnavailable(pkg: P, version: V, reasonKo: String): OfflineDependencyProvider<P, V> =
        put(pkg, version, Dependencies.Unavailable(reasonKo))

    private fun put(pkg: P, version: V, d: Dependencies<P, V>): OfflineDependencyProvider<P, V> {
        val list = table.getOrPut(pkg) { ArrayList() }
        list.removeAll { it.first.compareTo(version) == 0 }
        var i = 0
        while (i < list.size && list[i].first < version) i++
        list.add(i, version to d)
        return this
    }

    override fun chooseVersion(pkg: P, range: VersionSet<V>): V? = table[pkg]?.lastOrNull { it.first in range }?.first

    override fun getDependencies(pkg: P, version: V): Dependencies<P, V> =
        table[pkg]?.firstOrNull { it.first.compareTo(version) == 0 }?.second
            ?: Dependencies.Unavailable("알 수 없는 버전")

    override fun prioritize(pkg: P, range: VersionSet<V>): Int {
        val c = table[pkg]?.count { it.first in range } ?: 0
        return if (c == 0) Int.MAX_VALUE else -c
    }
}
