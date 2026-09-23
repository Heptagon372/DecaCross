package kr.decacross.pubgrub

/**
 * 솔버 내부 상태: incompatibility 저장소 + 부분해 + 단위 전파 + 충돌 해결.
 * pubgrub-rs `internal/core.rs` 의 `State` 를 따른다.
 *
 * # 불변식
 * - [byPackage] 는 어떤 패키지를 언급하는 모든 incompatibility 를 등록 순서대로 가진다.
 *   단위 전파는 이를 역순(최신 우선)으로 훑는다.
 * - [contradicted] 에 있는 incompatibility 는 기록된 결정 레벨 이하로 백트랙하기 전까지 다시 검사하지 않는다.
 * - 의존성 incompatibility 는 (pkg, dep, depSet) 이 같으면 하나로 병합되어 색인에 한 번만 존재한다.
 */
internal class SolverState<P : Any, V : Comparable<V>>(
    val root: P,
    val rootVersion: V,
    private val budget: StepBudget,
) {
    private var nextId = 0
    val partialSolution = PartialSolution<P, V>()
    private val byPackage = HashMap<P, ArrayList<Incompatibility<P, V>>>()
    private val dependencyIndex = HashMap<Pair<P, P>, ArrayList<Incompatibility<P, V>>>()
    private val contradicted = HashMap<Int, Int>()
    private val propagationBuffer = ArrayList<P>()

    init {
        register(Incompatibility.notRoot(newId(), root, rootVersion))
    }

    private fun newId(): Int = nextId++

    private fun register(incompat: Incompatibility<P, V>) {
        for (pkg in incompat.terms.keys) byPackage.getOrPut(pkg) { ArrayList() } += incompat
    }

    private fun replace(old: Incompatibility<P, V>, new: Incompatibility<P, V>) {
        for (pkg in old.terms.keys) {
            val list = byPackage.getValue(pkg)
            val idx = list.indexOfFirst { it === old }
            if (idx >= 0) list[idx] = new else list += new
        }
        for (pkg in new.terms.keys) {
            if (pkg !in old.terms) byPackage.getOrPut(pkg) { ArrayList() } += new
        }
    }

    /** 외부 사실(버전 없음·사용 불가)을 추가한다. */
    fun addNoVersions(pkg: P, term: Term<V>) {
        register(Incompatibility.noVersions(newId(), pkg, term.set))
    }

    fun addUnavailable(pkg: P, version: V, reasonKo: String) {
        register(Incompatibility.unavailable(newId(), pkg, VersionSet.singleton(version), reasonKo))
    }

    /**
     * `pkg@version` 의 의존성들을 incompatibility 로 변환해 등록한다.
     * 같은 (pkg, dep, depSet) 의존성이 이미 있으면 버전 범위를 합쳐 하나로 만든다.
     * 돌려주는 목록은 이번 호출로 (새로 또는 병합되어) 생긴 incompatibility 들이다.
     */
    fun addDependencies(pkg: P, version: V, deps: Map<P, VersionSet<V>>): List<Incompatibility<P, V>> {
        val out = ArrayList<Incompatibility<P, V>>(deps.size)
        val set = VersionSet.singleton(version)
        for ((depPkg, depSet) in deps) {
            val fresh = Incompatibility.fromDependency(newId(), pkg, set, depPkg, depSet) ?: continue
            val key = fresh.asDependency()
            if (key == null) {
                register(fresh)
                out += fresh
                continue
            }
            val siblings = dependencyIndex.getOrPut(key) { ArrayList() }
            var merged: Incompatibility<P, V>? = null
            var mergedIdx = -1
            for ((i, prev) in siblings.withIndex()) {
                val m = Incompatibility.mergeDependents(newId(), prev, fresh)
                if (m != null) {
                    merged = m
                    mergedIdx = i
                    break
                }
            }
            if (merged != null) {
                val prev = siblings[mergedIdx]
                siblings[mergedIdx] = merged
                replace(prev, merged)
                contradicted.remove(prev.id)
                out += merged
            } else {
                siblings += fresh
                register(fresh)
                out += fresh
            }
        }
        return out
    }

    /**
     * 단위 전파. [pkg] 에서 시작해, 거의 만족된 incompatibility 마다 남은 항의 부정을 유도한다.
     * 완전히 만족된 incompatibility(충돌)를 만나면 충돌 해결로 넘어가고, 해가 없음이 확정되면 그 incompatibility 를 돌려준다.
     */
    fun unitPropagation(pkg: P): Incompatibility<P, V>? {
        propagationBuffer.clear()
        propagationBuffer += pkg
        while (propagationBuffer.isNotEmpty()) {
            val current = propagationBuffer.removeAt(propagationBuffer.size - 1)
            var conflict: Incompatibility<P, V>? = null
            val list = byPackage[current] ?: continue
            // 최신 incompatibility 부터 (학습한 것이 가장 유용하다). 유도 중에 목록이 바뀌지 않는다.
            for (i in list.indices.reversed()) {
                budget.tick()
                val incompat = list[i]
                if (incompat.id in contradicted) continue
                when (val rel = partialSolution.relation(incompat)) {
                    Relation.Satisfied -> {
                        conflict = incompat
                        break
                    }

                    is Relation.AlmostSatisfied -> {
                        propagationBuffer += rel.pkg
                        partialSolution.addDerivation(rel.pkg, incompat)
                        contradicted[incompat.id] = partialSolution.decisionLevel
                    }

                    is Relation.Contradicted -> contradicted[incompat.id] = partialSolution.decisionLevel

                    Relation.Inconclusive -> {}
                }
            }
            if (conflict != null) {
                when (val resolved = conflictResolution(conflict)) {
                    is ConflictOutcome.NoSolution -> return resolved.terminal

                    is ConflictOutcome.Resolved -> {
                        propagationBuffer.clear()
                        propagationBuffer += resolved.pkg
                        partialSolution.addDerivation(resolved.pkg, resolved.rootCause)
                        contradicted[resolved.rootCause.id] = partialSolution.decisionLevel
                    }
                }
            }
        }
        return null
    }

    private sealed interface ConflictOutcome<P : Any, V : Comparable<V>> {
        class Resolved<P : Any, V : Comparable<V>>(val pkg: P, val rootCause: Incompatibility<P, V>) : ConflictOutcome<P, V>

        class NoSolution<P : Any, V : Comparable<V>>(val terminal: Incompatibility<P, V>) : ConflictOutcome<P, V>
    }

    /**
     * 충돌 해결 (pubgrub-rs `conflict_resolution`).
     *
     * 만족된 incompatibility 에서 출발해, 만족자와 이전 만족자가 같은 레벨이면 만족자의 원인과 결합(resolvent)해
     * 계속 올라가고, 다른 레벨이면 이전 만족자의 레벨로 백점프한 뒤 학습한 incompatibility 를 등록한다.
     * 도중에 종료 조건(루트 선택 불가 / 모순)에 닿으면 해가 없다.
     */
    private fun conflictResolution(incompat: Incompatibility<P, V>): ConflictOutcome<P, V> {
        var current = incompat
        var changed = false
        while (true) {
            budget.tick()
            if (current.isTerminal(root, rootVersion)) return ConflictOutcome.NoSolution(current)
            val (pkg, search) = partialSolution.satisfierSearch(current)
            when (search) {
                is PartialSolution.SatisfierSearch.DifferentDecisionLevels -> {
                    backtrack(search.previousSatisfierLevel)
                    if (changed) register(current)
                    return ConflictOutcome.Resolved(pkg, current)
                }

                is PartialSolution.SatisfierSearch.SameDecisionLevels -> {
                    current = Incompatibility.priorCause(newId(), current, search.satisfierCause, pkg)
                    changed = true
                }
            }
        }
    }

    private fun backtrack(level: Int) {
        partialSolution.backtrack(level)
        // 되돌린 레벨에서 모순 판정된 것들은 다시 검사 대상이 된다
        val it = contradicted.entries.iterator()
        while (it.hasNext()) if (it.next().value > level) it.remove()
    }

    /** 테스트용: 등록된 incompatibility 수. */
    val incompatibilityCount: Int
        get() = byPackage.values.flatten().distinctBy { it.id }.size
}

/** 반복 상한. 알고리즘상 종료가 보장되지만 포팅 버그로 안 끝날 수 있으므로 방어한다. */
internal class StepBudget(private val limit: Int) {
    var steps: Int = 0
        private set

    fun tick() {
        steps++
        if (steps > limit) {
            throw IllegalStateException(
                "PubGrub 해결이 반복 상한($limit)을 초과했습니다. 알고리즘상 종료가 보장되므로 포팅 버그일 가능성이 높습니다.",
            )
        }
    }
}
