package kr.decacross.pubgrub

/**
 * 부분해(partial solution): 지금까지의 결정(decision)과 유도(derivation)의 스택.
 *
 * pubgrub-rs `partial_solution.rs` 의 구조를 따른다. 할당은 전역 순번([DatedDerivation.globalIndex])과
 * 결정 레벨을 가진다. 패키지별로 유도들의 누적 교집합을 미리 계산해 두어 만족자(satisfier) 탐색이 이진 탐색으로 끝난다.
 *
 * # 불변식
 * - 한 패키지의 결정은 그 패키지의 마지막 할당이다. 결정 뒤에 유도가 추가되지 않는다
 *   (결정 `{v}` 와 어떤 항의 관계는 항상 Satisfied/Contradicted 이므로 AlmostSatisfied 가 나올 수 없다).
 * - `datedDerivations[i].accumulated` 는 그 패키지의 0..i 번째 유도의 교집합이다 (단조 감소).
 * - [decisionLevel] 은 지금까지의 결정 수와 같다. 루트 결정이 레벨 1 이다.
 */
internal class PartialSolution<P : Any, V : Comparable<V>> {
    private var nextGlobalIndex = 0

    /** 현재 결정 레벨. 결정마다 1 씩 오르고 백트랙으로 내려간다. */
    var decisionLevel: Int = 0
        private set

    /** 삽입 순서를 유지한다 — 우선순위 동률일 때 먼저 등장한 패키지를 고르기 위해. */
    private val assignments = LinkedHashMap<P, PackageAssignments<P, V>>()

    /** 유도 하나. [accumulated] 는 이 유도까지의 누적 교집합. */
    internal class DatedDerivation<P : Any, V : Comparable<V>>(
        val globalIndex: Int,
        val decisionLevel: Int,
        val cause: Incompatibility<P, V>,
        val accumulated: Term<V>,
    )

    /** 한 패키지의 현재 누적 항. 결정이 있으면 결정이 우선한다. */
    internal sealed interface AssignmentsIntersection<V : Comparable<V>> {
        val term: Term<V>

        class Decision<V : Comparable<V>>(val globalIndex: Int, val version: V, override val term: Term<V>) : AssignmentsIntersection<V>

        class Derivations<V : Comparable<V>>(override val term: Term<V>) : AssignmentsIntersection<V>
    }

    /** 만족자 탐색 결과: 어떤 할당(유도 cause 또는 결정=null)이 언제 추가됐는가. */
    internal class Satisfier<P : Any, V : Comparable<V>>(
        val cause: Incompatibility<P, V>?,
        val globalIndex: Int,
        val decisionLevel: Int,
    )

    internal class PackageAssignments<P : Any, V : Comparable<V>>(
        var smallestDecisionLevel: Int,
        var highestDecisionLevel: Int,
        val datedDerivations: ArrayList<DatedDerivation<P, V>>,
        var intersection: AssignmentsIntersection<V>,
    ) {
        /**
         * [startTerm] 과의 교집합이 항상 거짓이 되는 첫 유도를 찾는다. 없으면 결정이 만족자다.
         * 누적 교집합이 단조 감소하므로 이진 탐색이 가능하다.
         */
        fun satisfier(startTerm: Term<V>): Satisfier<P, V> {
            var lo = 0
            var hi = datedDerivations.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (datedDerivations[mid].accumulated.intersect(startTerm).isNever) hi = mid else lo = mid + 1
            }
            if (lo < datedDerivations.size) {
                val dd = datedDerivations[lo]
                return Satisfier(dd.cause, dd.globalIndex, dd.decisionLevel)
            }
            val decision = intersection as? AssignmentsIntersection.Decision
                ?: throw IllegalStateException("만족자를 찾지 못했습니다 (포팅 버그): 유도 ${datedDerivations.size}개, 시작 항 $startTerm")
            return Satisfier(null, decision.globalIndex, highestDecisionLevel)
        }
    }

    /** 결정 추가. 새 결정 레벨을 연다. */
    fun addDecision(pkg: P, version: V) {
        decisionLevel++
        val term = Term.exact(version)
        val pa = assignments[pkg]
        if (pa == null) {
            assignments[pkg] =
                PackageAssignments(decisionLevel, decisionLevel, ArrayList(), AssignmentsIntersection.Decision(nextGlobalIndex, version, term))
        } else {
            val current = pa.intersection
            check(current is AssignmentsIntersection.Derivations) { "이미 결정된 패키지에 다시 결정을 추가할 수 없습니다: $pkg" }
            check(current.term.contains(version)) { "부분해와 모순되는 결정입니다: $pkg $version ∉ ${current.term}" }
            pa.highestDecisionLevel = decisionLevel
            pa.intersection = AssignmentsIntersection.Decision(nextGlobalIndex, version, term)
        }
        nextGlobalIndex++
    }

    /** 유도 추가: [cause] 의 [pkg] 항의 부정을 현재 결정 레벨에 기록한다. */
    fun addDerivation(pkg: P, cause: Incompatibility<P, V>) {
        val term = cause.terms.getValue(pkg).negate()
        val pa = assignments[pkg]
        if (pa == null) {
            val dd = DatedDerivation(nextGlobalIndex, decisionLevel, cause, term)
            assignments[pkg] =
                PackageAssignments(decisionLevel, decisionLevel, arrayListOf(dd), AssignmentsIntersection.Derivations(term))
        } else {
            val current = pa.intersection
            check(current is AssignmentsIntersection.Derivations) { "결정된 패키지에 유도를 추가할 수 없습니다: $pkg" }
            val accumulated = current.term.intersect(term)
            pa.intersection = AssignmentsIntersection.Derivations(accumulated)
            pa.highestDecisionLevel = decisionLevel
            pa.datedDerivations += DatedDerivation(nextGlobalIndex, decisionLevel, cause, accumulated)
        }
        nextGlobalIndex++
    }

    /** 패키지의 현재 누적 항. 아직 언급되지 않았으면 null. */
    fun termIntersectionFor(pkg: P): Term<V>? = assignments[pkg]?.intersection?.term

    fun relation(incompat: Incompatibility<P, V>): Relation<P> = incompat.relation { termIntersectionFor(it) }

    /** 결정만 모아 해로 만든다. */
    fun extractSolution(): Map<P, V> {
        val out = LinkedHashMap<P, V>()
        for ((pkg, pa) in assignments) {
            val d = pa.intersection as? AssignmentsIntersection.Decision ?: continue
            out[pkg] = d.version
        }
        return out
    }

    /**
     * [level] 보다 높은 레벨의 할당을 전부 되돌린다.
     * 패키지 전체가 그 이후에 생겼으면 제거하고, 아니면 유도 목록을 잘라 누적 항을 복원한다.
     */
    fun backtrack(level: Int) {
        decisionLevel = level
        val iterator = assignments.entries.iterator()
        while (iterator.hasNext()) {
            val pa = iterator.next().value
            if (pa.smallestDecisionLevel > level) {
                iterator.remove()
                continue
            }
            if (pa.highestDecisionLevel <= level) continue
            var keep = pa.datedDerivations.size
            while (keep > 0 && pa.datedDerivations[keep - 1].decisionLevel > level) keep--
            check(keep > 0) { "백트랙 후 남는 유도가 없습니다 (포팅 버그)" }
            pa.datedDerivations.subList(keep, pa.datedDerivations.size).clear()
            val last = pa.datedDerivations.last()
            pa.highestDecisionLevel = last.decisionLevel
            pa.intersection = AssignmentsIntersection.Derivations(last.accumulated)
        }
    }

    /**
     * 다음에 결정할 패키지. 긍정 유도 항을 가졌지만 아직 결정되지 않은 패키지 중 [prioritize] 가 가장 큰 것.
     * 동률이면 먼저 등장한 패키지. 후보가 없으면 null (= 해 완성).
     */
    fun pickHighestPriorityPackage(prioritize: (P, VersionSet<V>) -> Int): Pair<P, Term<V>>? {
        var best: Pair<P, Term<V>>? = null
        var bestPriority = Int.MIN_VALUE
        for ((pkg, pa) in assignments) {
            val derivations = pa.intersection as? AssignmentsIntersection.Derivations ?: continue
            if (!derivations.term.positive) continue
            val priority = prioritize(pkg, derivations.term.set)
            if (best == null || priority > bestPriority) {
                best = pkg to derivations.term
                bestPriority = priority
            }
        }
        return best
    }

    /**
     * 결정을 추가하되, 방금 만든 의존성 incompatibility 들([newIncompats]) 중 이 결정으로 곧바로 만족되는
     * (= 충돌하는) 것이 있으면 결정을 보류한다. 그러면 단위 전파가 그 버전을 제외하는 유도를 만든다
     * ("결정 중 충돌 회피").
     */
    fun addVersion(pkg: P, version: V, newIncompats: List<Incompatibility<P, V>>) {
        val exact = Term.exact(version)
        val wouldConflict =
            newIncompats.any { incompat ->
                incompat.relation { p -> if (p == pkg) exact else termIntersectionFor(p) } is Relation.Satisfied
            }
        if (!wouldConflict) addDecision(pkg, version)
    }

    internal sealed interface SatisfierSearch<P : Any, V : Comparable<V>> {
        /** 이전 만족자가 더 낮은 레벨 → 그 레벨로 백점프. */
        class DifferentDecisionLevels<P : Any, V : Comparable<V>>(val previousSatisfierLevel: Int) : SatisfierSearch<P, V>

        /** 같은 레벨 → 만족자의 원인과 결합해 계속 역추적. */
        class SameDecisionLevels<P : Any, V : Comparable<V>>(val satisfierCause: Incompatibility<P, V>) : SatisfierSearch<P, V>
    }

    /**
     * 만족자 탐색 (pubgrub-rs `satisfier_search`).
     *
     * 1. 각 항마다 "그 항이 만족되기 시작한 할당"(만족자)을 찾고, 그중 가장 늦은 것이 incompatibility 의 만족자.
     * 2. 만족자의 항을 맨 앞에 둔 셈 치고 다시 찾으면 "이전 만족자"가 나온다.
     * 3. 이전 만족자의 레벨이 만족자의 레벨보다 낮으면 그 레벨로 백점프, 같으면 원인 결합.
     */
    fun satisfierSearch(incompat: Incompatibility<P, V>): Pair<P, SatisfierSearch<P, V>> {
        val satisfied = LinkedHashMap<P, Satisfier<P, V>>()
        for ((pkg, term) in incompat.terms) {
            val pa = assignments[pkg] ?: throw IllegalStateException("만족된 incompatibility 의 패키지가 부분해에 없습니다: $pkg")
            satisfied[pkg] = pa.satisfier(term.negate())
        }
        val (satisfierPkg, satisfier) = satisfied.entries.maxBy { it.value.globalIndex }.let { it.key to it.value }

        val satisfierPa = assignments.getValue(satisfierPkg)
        val satisfierTerm =
            satisfier.cause?.terms?.getValue(satisfierPkg)?.negate()
                ?: (satisfierPa.intersection as? AssignmentsIntersection.Decision)?.term
                ?: throw IllegalStateException("결정이 아닌 만족자에 원인이 없습니다 (포팅 버그)")
        val incompatTerm = incompat.terms.getValue(satisfierPkg)
        satisfied[satisfierPkg] = satisfierPa.satisfier(satisfierTerm.intersect(incompatTerm.negate()))
        val previousLevel = maxOf(1, satisfied.values.maxBy { it.globalIndex }.decisionLevel)

        val search: SatisfierSearch<P, V> =
            if (previousLevel >= satisfier.decisionLevel) {
                val cause = satisfier.cause
                    ?: throw IllegalStateException("결정이 만족자인데 이전 만족자가 같은 레벨입니다 (포팅 버그): $satisfierPkg")
                SatisfierSearch.SameDecisionLevels(cause)
            } else {
                SatisfierSearch.DifferentDecisionLevels(previousLevel)
            }
        return satisfierPkg to search
    }

    /** 테스트·디버깅용: 언급된 패키지 수. */
    val size: Int
        get() = assignments.size

    /** 테스트·디버깅용: 패키지가 결정됐는가. */
    fun isDecided(pkg: P): Boolean = assignments[pkg]?.intersection is AssignmentsIntersection.Decision
}
