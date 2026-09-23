package kr.decacross.pubgrub

/** 해결 결과. 예외 대신 sealed 결과 타입. */
public sealed interface SolverResult<P, V : Comparable<V>> {
    public data class Solution<P, V : Comparable<V>>(val selected: Map<P, V>) : SolverResult<P, V>

    public data class NoSolution<P, V : Comparable<V>>(val tree: DerivationTree<P, V>) : SolverResult<P, V>
}

/** 한 번의 [resolve] 가 수행할 수 있는 최대 단계 수 (단위 전파 검사 + 충돌 해결 + 결정). */
public const val MAX_SOLVER_STEPS: Int = 1_000_000

/**
 * PubGrub 해결 진입점.
 *
 * `root@rootVersion` 에서 시작해 `provider` 가 알려주는 의존성을 전부 만족하는 버전 할당을 찾는다.
 * 찾지 못하면 [SolverResult.NoSolution] 에 최소 충돌 유도 트리를 담아 돌려준다.
 *
 * 루프 한 바퀴 = 단위 전파 → (후보 없음이면 해 완성) → 결정할 패키지 선택([DependencyProvider.prioritize])
 * → 버전 선택([DependencyProvider.chooseVersion]) → 의존성을 incompatibility 로 등록 → 결정.
 *
 * # 불변식
 * - 제공자가 범위 밖 버전을 고르면 [IllegalStateException] (제공자 계약 위반).
 * - [MAX_SOLVER_STEPS] 를 넘기면 [IllegalStateException] (포팅 버그 방어).
 * - 반환된 해는 루트를 포함하고, 선택된 모든 패키지의 의존성을 만족한다.
 */
public fun <P : Any, V : Comparable<V>> resolve(
    provider: DependencyProvider<P, V>,
    root: P,
    rootVersion: V,
): SolverResult<P, V> {
    val budget = StepBudget(MAX_SOLVER_STEPS)
    val state = SolverState(root, rootVersion, budget)
    // 이미 의존성을 등록한 (패키지, 버전). 백트랙 후 같은 버전을 다시 결정할 때 중복 등록을 막는다.
    val addedDependencies = HashMap<P, MutableSet<V>>()
    var next = root
    while (true) {
        budget.tick()
        val terminal = state.unitPropagation(next)
        if (terminal != null) return SolverResult.NoSolution(buildDerivationTree(terminal, root, rootVersion))

        val (pkg, term) = state.partialSolution.pickHighestPriorityPackage(provider::prioritize)
            ?: return SolverResult.Solution(state.partialSolution.extractSolution())
        next = pkg

        val version = provider.chooseVersion(pkg, term.set)
        if (version == null) {
            state.addNoVersions(pkg, term)
            continue
        }
        check(term.set.contains(version)) { "chooseVersion 이 범위 밖 버전을 골랐습니다: $pkg $version ∉ ${term.set}" }

        val isNew = addedDependencies.getOrPut(pkg) { HashSet() }.add(version)
        if (!isNew) {
            // 의존성 incompatibility 는 이미 등록돼 있고 단위 전파가 끝난 상태이므로 곧바로 결정해도 안전하다
            state.partialSolution.addDecision(pkg, version)
            continue
        }
        when (val deps = provider.getDependencies(pkg, version)) {
            is Dependencies.Unavailable -> state.addUnavailable(pkg, version, deps.reasonKo)

            is Dependencies.Available -> {
                val incompats = state.addDependencies(pkg, version, deps.map)
                state.partialSolution.addVersion(pkg, version, incompats)
            }
        }
    }
}
