package kr.decacross.pubgrub

/** 해결 결과. 예외 대신 sealed 결과 타입. */
public sealed interface SolverResult<P, V : Comparable<V>> {
    public data class Solution<P, V : Comparable<V>>(val selected: Map<P, V>) : SolverResult<P, V>

    public data class NoSolution<P, V : Comparable<V>>(val tree: DerivationTree<P, V>) : SolverResult<P, V>
}

/**
 * PubGrub 해결 진입점.
 *
 * `root@rootVersion` 에서 시작해 `provider` 가 알려주는 의존성을 전부 만족하는 버전 할당을 찾는다.
 * 찾지 못하면 [SolverResult.NoSolution] 에 최소 충돌 유도 트리를 담아 돌려준다.
 */
public fun <P : Any, V : Comparable<V>> resolve(
    provider: DependencyProvider<P, V>,
    root: P,
    rootVersion: V,
): SolverResult<P, V> = TODO("P1-b~d")
