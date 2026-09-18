package kr.decacross.pubgrub

// 출처: pubgrub-rs (MPL-2.0) src/internal/partial_solution.rs 의 알고리즘 번역 (commit 0399630, 2026-09-17 확인). 라이선스 정책은 Q3 (사용자 결정 대기).

/** 부분해의 할당 하나. [globalIndex] 는 로그 위치와 같다. */
internal sealed interface Assignment<V : Comparable<V>> {
    val pkg: PackageId
    val globalIndex: Int
    val decisionLevel: Int
    val term: Term<V>

    /** 결정: 버전 하나를 추측으로 고름. 레벨 = 이 결정을 포함한 결정 수 (루트 결정 = 1). */
    data class Decision<V : Comparable<V>>(
        override val pkg: PackageId,
        override val globalIndex: Int,
        override val decisionLevel: Int,
        val version: V,
    ) : Assignment<V> {
        override val term: Term<V> = Term.exact(version)
    }

    /** 유도: [cause] 비호환에서 전파로 얻은 항 [term] (= cause 의 해당 항의 부정). [accumulated] = 이 패키지의 유도 누적 교집합. */
    data class Derivation<V : Comparable<V>>(
        override val pkg: PackageId,
        override val globalIndex: Int,
        override val decisionLevel: Int,
        val cause: IncompId,
        override val term: Term<V>,
        val accumulated: Term<V>,
    ) : Assignment<V>
}

/** 다음에 결정할 후보. */
internal data class Candidate<V : Comparable<V>>(val pkg: PackageId, val range: VersionSet<V>)

/** 테스트용 논리 상태 스냅샷 (hasBacktracked 제외). */
internal data class PartialSolutionSnapshot<V : Comparable<V>>(
    val decisionLevel: Int,
    val log: List<Assignment<V>>,
    val packages: Map<PackageId, PackageView<V>>,
)

internal data class PackageView<V : Comparable<V>>(
    val derivations: List<Assignment.Derivation<V>>,
    val decision: Assignment.Decision<V>?,
    val accumulated: Term<V>,
)

/** 패키지별 할당. derivations 는 비어 있지 않고 시간순, decision 은 있으면 마지막 할당이다. */
private class PackageAssignments<V : Comparable<V>>(first: Assignment.Derivation<V>) {
    val derivations: ArrayList<Assignment.Derivation<V>> = arrayListOf(first)
    var decision: Assignment.Decision<V>? = null

    val accumulated: Term<V>
        get() = decision?.term ?: derivations[derivations.lastIndex].accumulated
}

/**
 * 부분해: 결정·유도의 시간순 로그 + 패키지별 색인.
 *
 * # 불변식
 * - P1 `log[i].globalIndex == i`. 되돌리기 후 다음 할당은 지워진 번호를 재사용한다 (상대 순서만 의미가 있다).
 * - P2 결정 레벨은 로그를 따라 비감소, 결정마다 정확히 +1. 유도의 레벨 = 그 앞 결정 수. [currentDecisionLevel] = 결정 수.
 * - P3 패키지별: 유도 ≥ 1개(없으면 슬롯 없음), 결정 ≤ 1개이고 그 패키지의 마지막 할당. 결정 뒤 유도 없음.
 * - P4 `derivations[k].accumulated = derivations[k-1].accumulated ∩ derivations[k].term` (단조 축소 → P1-c 이진 탐색 가능).
 * - P5 결정 버전 ∈ 결정 직전 누적 항.
 */
internal class PartialSolution<V : Comparable<V>> : TermLookup<V> {
    private val log = ArrayList<Assignment<V>>()
    private val slots = ArrayList<PackageAssignments<V>?>()
    private val decisions = ArrayList<Assignment.Decision<V>>()

    /** 한 번이라도 [backtrack] 했는가 (rs `has_backtracked`). 논리 상태가 아니라 이력이다. */
    var hasBacktracked: Boolean = false
        private set

    val currentDecisionLevel: Int get() = decisions.size

    val nextGlobalIndex: Int get() = log.size

    override fun termOf(pkg: PackageId): Term<V>? = slotOrNull(pkg)?.accumulated

    fun relation(incompat: Incompatibility<V>): IncompatibilityRelation = incompat.relation(this)

    fun addDecision(pkg: PackageId, version: V) {
        val pa = slotOrNull(pkg) ?: invariantViolated("유도 없는 패키지 ${pkg.raw} 결정")
        if (pa.decision != null) invariantViolated("패키지 ${pkg.raw} 이미 결정됨")
        if (version !in pa.accumulated) invariantViolated("패키지 ${pkg.raw}: $version ∉ ${pa.accumulated}")
        val d = Assignment.Decision(pkg, log.size, decisions.size + 1, version)
        log.add(d)
        decisions.add(d)
        pa.decision = d
    }

    fun addDerivation(pkg: PackageId, cause: IncompId, term: Term<V>) {
        while (slots.size <= pkg.raw) slots.add(null)
        val pa = slots[pkg.raw]
        val level = decisions.size
        if (pa == null) {
            val d = Assignment.Derivation(pkg, log.size, level, cause, term, term)
            log.add(d)
            slots[pkg.raw] = PackageAssignments(d)
            return
        }
        if (pa.decision != null) invariantViolated("결정된 패키지 ${pkg.raw} 에 유도 추가")
        val acc = pa.derivations[pa.derivations.lastIndex].accumulated.intersection(term)
        val d = Assignment.Derivation(pkg, log.size, level, cause, term, acc)
        log.add(d)
        pa.derivations.add(d)
    }

    /** [level] 보다 높은 레벨의 할당을 전부 지운다. O(지운 할당 수). */
    fun backtrack(level: Int) {
        if (level < 0 || level > decisions.size) invariantViolated("backtrack($level), 현재 레벨 ${decisions.size}")
        hasBacktracked = true
        if (level == decisions.size) return
        val cut = decisions[level].globalIndex
        for (i in log.lastIndex downTo cut) {
            val a = log.removeAt(i)
            val pa = slots[a.pkg.raw] ?: invariantViolated("로그와 슬롯 불일치 ${a.pkg.raw}")
            when (a) {
                is Assignment.Decision -> pa.decision = null

                is Assignment.Derivation -> {
                    pa.derivations.removeAt(pa.derivations.lastIndex)
                    if (pa.derivations.isEmpty()) slots[a.pkg.raw] = null
                }
            }
        }
        while (decisions.size > level) decisions.removeAt(decisions.lastIndex)
    }

    /** 미결정·양의 누적 항 패키지 중 우선순위 최대, 동점이면 작은 [PackageId]. */
    fun pickHighestPriority(priority: (PackageId, VersionSet<V>) -> Int): Candidate<V>? {
        var best: Candidate<V>? = null
        var bestPriority = 0
        for (raw in slots.indices) {
            val pa = slots[raw] ?: continue
            if (pa.decision != null) continue
            val acc = pa.accumulated
            if (acc !is Term.Positive) continue
            val p = priority(PackageId(raw), acc.set)
            if (best == null || p > bestPriority) {
                best = Candidate(PackageId(raw), acc.set)
                bestPriority = p
            }
        }
        return best
    }

    /** 결정 순서대로 (패키지, 버전). */
    fun decisions(): List<Pair<PackageId, V>> = decisions.map { it.pkg to it.version }

    fun snapshot(): PartialSolutionSnapshot<V> {
        val packages = LinkedHashMap<PackageId, PackageView<V>>()
        for (raw in slots.indices) {
            val pa = slots[raw] ?: continue
            packages[PackageId(raw)] = PackageView(pa.derivations.toList(), pa.decision, pa.accumulated)
        }
        return PartialSolutionSnapshot(decisions.size, log.toList(), packages)
    }

    /** P1~P5 검사. 성립하면 null. 테스트 전용. */
    fun invariantViolation(): String? {
        var level = 0
        val perPkgDer = HashMap<Int, MutableList<Assignment.Derivation<V>>>()
        val perPkgDec = HashMap<Int, Assignment.Decision<V>>()
        val decs = ArrayList<Assignment.Decision<V>>()
        for (i in log.indices) {
            val a = log[i]
            if (a.globalIndex != i) return "P1: log[$i].globalIndex=${a.globalIndex}"
            when (a) {
                is Assignment.Decision -> {
                    level++
                    if (a.decisionLevel != level) return "P2: 결정 레벨 ${a.decisionLevel} != $level"
                    if (perPkgDec.containsKey(a.pkg.raw)) return "P3: 중복 결정 ${a.pkg.raw}"
                    val ders = perPkgDer[a.pkg.raw] ?: return "P3: 유도 없는 결정 ${a.pkg.raw}"
                    val before = ders.last().accumulated
                    if (a.version !in before) return "P5: ${a.version} ∉ $before"
                    perPkgDec[a.pkg.raw] = a
                    decs.add(a)
                }

                is Assignment.Derivation -> {
                    if (a.decisionLevel != level) return "P2: 유도 레벨 ${a.decisionLevel} != $level"
                    if (perPkgDec.containsKey(a.pkg.raw)) return "P3: 결정 뒤 유도 ${a.pkg.raw}"
                    val list = perPkgDer.getOrPut(a.pkg.raw) { ArrayList() }
                    val expected = if (list.isEmpty()) a.term else list.last().accumulated.intersection(a.term)
                    if (a.accumulated != expected) return "P4: ${a.accumulated} != $expected"
                    list.add(a)
                }
            }
        }
        if (decs != decisions) return "decisions 목록 불일치"
        for (raw in slots.indices) {
            val pa = slots[raw]
            val ders = perPkgDer[raw]
            if (pa == null) {
                if (ders != null) return "슬롯 $raw 없음, 로그엔 있음"
                continue
            }
            if (ders == null || ders != pa.derivations) return "슬롯 $raw 유도 불일치"
            if (perPkgDec[raw] != pa.decision) return "슬롯 $raw 결정 불일치"
        }
        for (raw in perPkgDer.keys) if (raw >= slots.size || slots[raw] == null) return "로그 패키지 $raw 슬롯 없음"
        return null
    }

    private fun slotOrNull(pkg: PackageId): PackageAssignments<V>? = if (pkg.raw < slots.size) slots[pkg.raw] else null
}
