package kr.decacross.pubgrub

/**
 * Map 기반 [DependencyProvider]. 테스트·오프라인 해결용 (pubgrub-rs `OfflineDependencyProvider` 에 해당).
 *
 * - [chooseVersion]: 범위 안에서 가장 높은 버전.
 * - [prioritize]: 후보 버전 수의 음수 — 후보가 적은 패키지를 먼저 결정한다.
 * - 모르는 패키지는 버전이 없는 것으로 본다.
 */
public class MapDependencyProvider<P : Any, V : Comparable<V>>(
    source: Map<P, Map<V, Dependencies<P, V>>>,
) : DependencyProvider<P, V> {
    private val packages: Map<P, Map<V, Dependencies<P, V>>> = source.mapValues { it.value.toMap() }
    private val sortedVersions: Map<P, List<V>> = source.mapValues { it.value.keys.sortedDescending() }

    /** 등록된 패키지 이름들. */
    public val packageNames: Set<P>
        get() = packages.keys

    /** 패키지의 버전 목록 (내림차순). */
    public fun versionsOf(pkg: P): List<V> = sortedVersions[pkg].orEmpty()

    override fun chooseVersion(pkg: P, range: VersionSet<V>): V? = sortedVersions[pkg]?.firstOrNull { range.contains(it) }

    override fun getDependencies(pkg: P, version: V): Dependencies<P, V> =
        packages[pkg]?.get(version) ?: Dependencies.Unavailable("등록되지 않은 패키지/버전입니다: $pkg $version")

    override fun prioritize(pkg: P, range: VersionSet<V>): Int = -(sortedVersions[pkg]?.count { range.contains(it) } ?: 0)

    /** 선언적으로 만들기 위한 빌더. */
    public class Builder<P : Any, V : Comparable<V>> {
        private val packages = LinkedHashMap<P, LinkedHashMap<V, Dependencies<P, V>>>()

        /** `pkg@version` 이 [deps] 에 의존한다고 등록한다. 의존성이 없으면 빈 맵. */
        public fun add(pkg: P, version: V, deps: Map<P, VersionSet<V>> = emptyMap()): Builder<P, V> {
            packages.getOrPut(pkg) { LinkedHashMap() }[version] = Dependencies.Available(deps.toMap())
            return this
        }

        /** `pkg@version` 은 존재하지만 쓸 수 없다고 등록한다. */
        public fun addUnavailable(pkg: P, version: V, reasonKo: String): Builder<P, V> {
            packages.getOrPut(pkg) { LinkedHashMap() }[version] = Dependencies.Unavailable(reasonKo)
            return this
        }

        public fun build(): MapDependencyProvider<P, V> = MapDependencyProvider(packages)
    }

    public companion object {
        public fun <P : Any, V : Comparable<V>> build(block: Builder<P, V>.() -> Unit): MapDependencyProvider<P, V> =
            Builder<P, V>().apply(block).build()
    }
}
