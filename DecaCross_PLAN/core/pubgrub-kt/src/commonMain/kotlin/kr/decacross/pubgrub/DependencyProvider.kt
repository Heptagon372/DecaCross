package kr.decacross.pubgrub

/**
 * 솔버가 패키지 정보를 질의하는 유일한 통로. 도메인 지식은 이 인터페이스의 구현 쪽에만 둔다.
 */
public interface DependencyProvider<P : Any, V : Comparable<V>> {
    /** 후보 중 하나 선택. 선호 순서대로 반환(STABLE 먼저, 그다음 최신). null 이면 해당 없음. */
    public fun chooseVersion(pkg: P, range: VersionSet<V>): V?

    /** [version] 의 의존. ★ [Dependencies.Available.map] 은 순회 순서가 정해진 맵(`LinkedHashMap`·`mapOf`)이어야 한다 — 솔버의 패키지 번호와 동점 우선순위가 그 순서를 따르므로, 순서 없는 맵이면 실행마다 해가 달라질 수 있다. */
    public fun getDependencies(pkg: P, version: V): Dependencies<P, V>

    /** 높을수록 먼저 결정. 후보 수가 적은 패키지를 먼저 고르면 탐색이 줄어든다. */
    public fun prioritize(pkg: P, range: VersionSet<V>): Int = 0
}

/** `getDependencies` 의 결과. */
public sealed interface Dependencies<P, V : Comparable<V>> {
    public data class Available<P, V : Comparable<V>>(val map: Map<P, VersionSet<V>>) : Dependencies<P, V>

    public data class Unavailable<P, V : Comparable<V>>(val reasonKo: String) : Dependencies<P, V>
}
