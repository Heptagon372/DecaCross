package kr.decacross.pubgrub

// 출처: pubgrub-rs (MPL-2.0) src/internal/core.rs, src/solver.rs 의 알고리즘 번역 (commit 0399630, 2026-09-17 확인). 라이선스 정책은 Q3 (사용자 결정 대기).

/** 단위 전파 결과 (P1-b). P1-c 는 [Conflict] 를 없애고 충돌 해결 후 `Terminal(IncompId)` 를 추가한다. */
internal sealed interface PropagationResult {
    data object Ok : PropagationResult

    /** 부분해가 이 비호환을 만족시켰다. P1-b 는 표시만 하고 멈춘다 — 이 상태는 더 쓰지 마라. */
    data class Conflict(val incompatibility: IncompId) : PropagationResult
}

private data class DependencyKey(val dependent: PackageId, val dependency: PackageId)

/**
 * PubGrub 솔버 상태 (rs `internal::core::State`).
 *
 * # 불변식
 * - S1 `incompatibilitiesOf(p)` 의 모든 id 는 p 에 대한 항을 가진다. 추가 순서 유지 (전파는 역순으로 본다).
 * - S2 인덱스에 든 비호환에는 [Term.any] 항이 없다.
 * - S3 병합 버킷의 id 는 인덱스에 들어 있는 최신 병합본이다.
 */
internal class SolverState<P : Any, V : Comparable<V>>(root: P, val rootVersion: V) {
    val packages = PackageStore<P>()
    val rootPackage: PackageId = packages.intern(root)
    val incompatibilities = IncompatibilityStore<V>()
    val partialSolution = PartialSolution<V>()

    private val byPackage = ArrayList<ArrayList<IncompId>>()
    private val mergedDependencies = HashMap<DependencyKey, ArrayList<IncompId>>()
    private val buffer = ArrayList<PackageId>()

    // 의존을 이미 펼친 버전 (rs AddedDependencies, 역추적해도 지우지 않는다). V 의 hashCode 에 기대지 않도록 VersionSet 합집합.
    private val added = HashMap<PackageId, VersionSet<V>>()

    init {
        addIncompatibility(Incompatibility.notRoot(rootPackage, rootVersion))
    }

    fun incompatibilitiesOf(pkg: PackageId): List<IncompId> = if (pkg.raw < byPackage.size) byPackage[pkg.raw] else emptyList()

    fun addIncompatibility(incompat: Incompatibility<V>): IncompId {
        val id = incompatibilities.alloc(incompat)
        mergeIncompatibility(id)
        return id
    }

    /**
     * 의존 비호환을 모두 할당한 뒤(연속 id) 하나씩 병합한다. 반환값 = 새로 할당한 원본 id 구간.
     * 패키지 id 는 [dependencies] 의 순회 순서대로 매겨지고 동점 우선순위는 작은 id 가 이긴다 →
     * 제공자는 순서가 정해진 맵(`LinkedHashMap`, `mapOf`)을 돌려줘야 결과가 실행마다 같다.
     */
    fun addIncompatibilitiesFromDependencies(
        pkg: PackageId,
        versions: VersionSet<V>,
        dependencies: Map<P, VersionSet<V>>,
    ): IntRange {
        val first = incompatibilities.size
        for ((depP, depSet) in dependencies) {
            val dep = packages.intern(depP)
            val incompat = Incompatibility.fromDependency(pkg, versions, dep, depSet) ?: continue
            incompatibilities.alloc(incompat)
        }
        val end = incompatibilities.size
        for (raw in first until end) mergeIncompatibility(IncompId(raw))
        return first until end
    }

    /** rs `add_package_version_dependencies`: 의존 추가 후, 충돌 없으면 결정. 충돌하면 결정하지 않고 그 id 반환. */
    fun addPackageVersionDependencies(pkg: PackageId, version: V, dependencies: Map<P, VersionSet<V>>): IncompId? {
        val ids = addIncompatibilitiesFromDependencies(pkg, VersionSet.singleton(version), dependencies)
        if (!partialSolution.hasBacktracked) {
            partialSolution.addDecision(pkg, version)
            return null
        }
        val exact = Term.exact(version)
        val lookup = TermLookup { p -> if (p == pkg) exact else partialSolution.termOf(p) }
        for (raw in ids) {
            if (incompatibilities[IncompId(raw)].relation(lookup) == IncompatibilityRelation.Satisfied) return IncompId(raw)
        }
        partialSolution.addDecision(pkg, version)
        return null
    }

    /** rs `unit_propagation` 에서 충돌 해결만 뺀 것. */
    fun unitPropagation(pkg: PackageId): PropagationResult {
        buffer.clear()
        buffer.add(pkg)
        while (buffer.isNotEmpty()) {
            val current = buffer.removeAt(buffer.lastIndex)
            val ids = incompatibilitiesOf(current)
            for (k in ids.lastIndex downTo 0) {
                val id = ids[k]
                val incompat = incompatibilities[id]
                when (val r = partialSolution.relation(incompat)) {
                    IncompatibilityRelation.Satisfied -> return PropagationResult.Conflict(id)

                    is IncompatibilityRelation.AlmostSatisfied -> {
                        val almost = incompat.packageAt(r.index)
                        if (almost !in buffer) buffer.add(almost)
                        partialSolution.addDerivation(almost, id, incompat.termAt(r.index).negate())
                    }

                    is IncompatibilityRelation.Contradicted, IncompatibilityRelation.Inconclusive -> {}
                }
            }
        }
        return PropagationResult.Ok
    }

    /**
     * 명세 §4.2 `decisionMaking()` (rs `resolve` 루프 본문의 뒷부분): 우선순위가 가장 높은 미결정 패키지를 골라
     * `chooseVersion` → (처음 보는 버전이면) `getDependencies` → 비호환 추가 → 충돌 없으면 결정.
     * 고를 버전이 없거나 쓸 수 없으면 비호환만 추가하고 같은 패키지를 [DecisionStep.Next] 로 돌려준다 (다음 전파가 반영).
     */
    fun decisionMaking(provider: DependencyProvider<P, V>): DecisionStep<P, V> {
        val candidate =
            partialSolution.pickHighestPriority { id, range -> provider.prioritize(packages[id], range) }
                ?: return DecisionStep.Solved(extractSolution())
        val next = candidate.pkg
        val pkg = packages[next]
        val v = provider.chooseVersion(pkg, candidate.range)
        if (v == null) {
            addIncompatibility(Incompatibility.noVersions(next, candidate.range))
            return DecisionStep.Next(next)
        }
        if (v !in candidate.range) invariantViolated("chooseVersion($pkg) = $v ∉ ${candidate.range} (제공자 계약 위반)")
        val tried = added[next] ?: VersionSet.empty()
        if (v !in tried) {
            added[next] = tried.union(VersionSet.singleton(v))
            when (val deps = provider.getDependencies(pkg, v)) {
                is Dependencies.Unavailable -> addIncompatibility(Incompatibility.unavailable(next, v, deps.reasonKo))
                is Dependencies.Available -> addPackageVersionDependencies(next, v, deps.map)
            }
        } else {
            partialSolution.addDecision(next, v)
        }
        return DecisionStep.Next(next)
    }

    fun extractSolution(): Map<P, V> {
        val out = LinkedHashMap<P, V>()
        for ((id, v) in partialSolution.decisions()) out[packages[id]] = v
        return out
    }

    private fun mergeIncompatibility(start: IncompId) {
        var id = start
        val dep = incompatibilities[id].cause as? IncompatibilityCause.FromDependencyOf
        if (dep != null && dep.pkg != dep.depPkg) {
            val bucket = mergedDependencies.getOrPut(DependencyKey(dep.pkg, dep.depPkg)) { ArrayList() }
            var merged = false
            for (k in bucket.indices) {
                val past = bucket[k]
                val m = incompatibilities[id].mergeDependents(incompatibilities[past]) ?: continue
                val newId = incompatibilities.alloc(m)
                for (i in 0 until m.size) byPackage[m.packageAt(i).raw].remove(past)
                bucket[k] = newId
                id = newId
                merged = true
                break
            }
            if (!merged) bucket.add(id)
        }
        val incompat = incompatibilities[id]
        for (i in 0 until incompat.size) {
            val raw = incompat.packageAt(i).raw
            while (byPackage.size <= raw) byPackage.add(ArrayList())
            byPackage[raw].add(id)
        }
    }
}

/** [SolverState.decisionMaking] 한 번의 결과. */
internal sealed interface DecisionStep<P : Any, V : Comparable<V>> {
    /** 결정할 패키지가 남지 않았다 = 부분해가 곧 해. */
    data class Solved<P : Any, V : Comparable<V>>(val selected: Map<P, V>) : DecisionStep<P, V>

    /** 다음 단위 전파를 시작할 패키지 (rs `next`). */
    data class Next<P : Any, V : Comparable<V>>(val pkg: PackageId) : DecisionStep<P, V>
}

/** P1-b 결정 루프 결과. P1-c 에서 [ConflictFlagged] → 충돌 해결로 대체되고 `NoSolution(terminal)` 가 생긴다. */
internal sealed interface LoopOutcome<P : Any, V : Comparable<V>> {
    val state: SolverState<P, V>

    data class Solved<P : Any, V : Comparable<V>>(
        override val state: SolverState<P, V>,
        val selected: Map<P, V>,
    ) : LoopOutcome<P, V>

    data class ConflictFlagged<P : Any, V : Comparable<V>>(
        override val state: SolverState<P, V>,
        val incompatibility: IncompId,
    ) : LoopOutcome<P, V>
}

/**
 * rs `resolve` 루프와 같은 순서: `unitPropagation(next)` → `decisionMaking()` 반복 (명세 §4.2 단계 이름).
 * 충돌 해결·반복 상한은 P1-c. P1-b 는 충돌을 [LoopOutcome.ConflictFlagged] 로 표시만 한다.
 */
internal fun <P : Any, V : Comparable<V>> runDecisionLoop(
    provider: DependencyProvider<P, V>,
    root: P,
    rootVersion: V,
): LoopOutcome<P, V> {
    val state = SolverState(root, rootVersion)
    var next = state.rootPackage
    while (true) {
        when (val r = state.unitPropagation(next)) {
            is PropagationResult.Conflict -> return LoopOutcome.ConflictFlagged(state, r.incompatibility)
            PropagationResult.Ok -> {}
        }
        when (val d = state.decisionMaking(provider)) {
            is DecisionStep.Solved -> return LoopOutcome.Solved(state, d.selected)
            is DecisionStep.Next -> next = d.pkg
        }
    }
}
