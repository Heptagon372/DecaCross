package kr.decacross.pubgrub

/**
 * 종료 incompatibility 의 원인 사슬을 [DerivationTree] 로 바꾼다. 재귀 대신 id 오름차순(원인이 결과보다 먼저)으로
 * 처리하므로 깊은 사슬에서도 스택을 쓰지 않는다.
 *
 * 정제 단계 (전부 내부 incompatibility DAG 위에서, 극성 정보가 있을 때 수행한다):
 * 1. 구멍(NoVersions) 흡수 — `Range` 는 이산성을 모르므로 `foo 1.0.0` 을 제외한 `^1.0.0` 의 나머지 `>1.0.0 <2.0.0` 이
 *    비어 있지 않게 남고, 솔버가 거기서 버전을 못 찾아 `NoVersions(foo, >1.0.0 <2.0.0)` 를 학습한다. 이 사실이
 *    `foo 1.0.0 은 bar 에 의존` 같은 긍정 진술과 결합될 때, 그 긍정 항이 유래한 External 까지 범위를 넓혀 넣는다
 *    (`foo ^1.0.0 은 bar 에 의존`). 조상 노드는 전부 [Incompatibility.priorCause] 로 다시 계산해 유효한 유도로 유지하고,
 *    결과의 꼭대기가 여전히 종료 조건이면 채택, 아니면 되돌린다. Dart pub 이 처음부터 넓은 범위로 만드는 것과 같은 결과.
 * 2. 군더더기 유도 접기 — 결과 항이 한쪽 원인의 항과 같은 Derived 는 그 원인으로 대체한다 (다른 쪽은 기여한 게 없다).
 * 3. 같은 종류의 External 병합 — 같은 (pkg, dep, depSet) 의존성, 같은 pkg 의 NoVersions, 같은 (pkg, 사유) 의
 *    Unavailable 은 버전 범위를 합집합으로 합쳐 한 진술로 만든다.
 * 4. 공유 노드 재사용 — 같은 incompatibility 에서 나온 부분 트리는 같은 객체다. 구조가 같은 노드는 data class 동일성으로 겹친다.
 *
 * # 불변식
 * - 트리에 등장하는 패키지는 종료 incompatibility 의 유도에 실제로 쓰인 패키지뿐이다 (충돌과 무관한 패키지는 없다).
 * - 모든 Derived 는 두 원인의 resolvent 다 (1·2 단계가 다시 계산한다). External 은 참인 진술이며, 넓히기만 한다.
 * - 꼭대기는 항상 종료 조건(루트 항 하나 또는 빈 항)을 만족한다.
 */
internal fun <P : Any, V : Comparable<V>> buildDerivationTree(
    terminal: Incompatibility<P, V>,
    root: P,
    rootVersion: V,
): DerivationTree<P, V> {
    val builder = TreeBuilder(terminal, root, rootVersion)
    builder.absorbHoles()
    builder.collapseRedundant()
    return builder.convert()
}

private class TreeBuilder<P : Any, V : Comparable<V>>(
    var top: Incompatibility<P, V>,
    private val root: P,
    private val rootVersion: V,
) {
    private var nextId = reachableInCausalOrder(top).maxOf { it.id } + 1

    private fun newId(): Int = nextId++

    /**
     * DAG 전체를 원인 순서로 다시 만든다. [transform] 은 (원래 노드, 새 원인1, 새 원인2) 를 받아 새 노드를 돌려주거나
     * null 을 돌려줘 기본 처리(원인이 바뀌었으면 priorCause 재계산)를 맡긴다.
     */
    private fun rebuild(
        from: Incompatibility<P, V>,
        transform: (Incompatibility<P, V>, Incompatibility<P, V>, Incompatibility<P, V>) -> Incompatibility<P, V>?,
    ): Incompatibility<P, V> {
        val result = HashMap<Int, Incompatibility<P, V>>()
        for (i in reachableInCausalOrder(from)) {
            val c = i.cause
            if (c !is Cause.DerivedFrom) {
                result[i.id] = i
                continue
            }
            val c1 = result.getValue(c.incompat.id)
            val c2 = result.getValue(c.satisfierCause.id)
            result[i.id] =
                transform(i, c1, c2)
                    ?: if (c1 === c.incompat && c2 === c.satisfierCause) i else Incompatibility.priorCause(newId(), c1, c2, c.pkg)
        }
        return result.getValue(from.id)
    }

    /** 1단계: 구멍 흡수. 흡수 후보를 하나씩 시도하고, 꼭대기가 종료 조건을 잃으면 그 시도는 버린다. */
    fun absorbHoles() {
        var attempts = 0
        val tried = HashSet<Int>()
        while (attempts++ < 64) {
            val hole = findAbsorbableHole(tried) ?: return
            tried += hole.id
            val holeCause = hole.cause as Cause.NoVersions
            val memo = HashMap<Int, Incompatibility<P, V>>()
            val candidate =
                rebuild(top) { _, c1, c2 ->
                    when {
                        c1.id == hole.id && c2.terms[holeCause.pkg]?.positive == true ->
                            widen(c2, holeCause.pkg, holeCause.set, memo)

                        c2.id == hole.id && c1.terms[holeCause.pkg]?.positive == true ->
                            widen(c1, holeCause.pkg, holeCause.set, memo)

                        else -> null
                    }
                }
            if (candidate.isTerminal(root, rootVersion)) top = candidate
        }
    }

    /** 긍정 항을 가진 형제와 직접 결합된 NoVersions 노드 (아직 시도하지 않은 것). */
    private fun findAbsorbableHole(tried: Set<Int>): Incompatibility<P, V>? {
        for (i in reachableInCausalOrder(top)) {
            val c = i.cause as? Cause.DerivedFrom ?: continue
            for ((a, b) in listOf(c.incompat to c.satisfierCause, c.satisfierCause to c.incompat)) {
                val nv = a.cause as? Cause.NoVersions ?: continue
                if (a.id in tried || nv.pkg == root) continue
                if (b.terms[nv.pkg]?.positive == true) return a
            }
        }
        return null
    }

    /** [pkg] 의 긍정 항을 [extra] 만큼 넓힌 부분 트리. 긍정 항이 없는 노드는 그대로 둔다. */
    private fun widen(
        node: Incompatibility<P, V>,
        pkg: P,
        extra: VersionSet<V>,
        memo: HashMap<Int, Incompatibility<P, V>>,
    ): Incompatibility<P, V> {
        val term = node.terms[pkg]
        if (term == null || !term.positive) return node
        memo[node.id]?.let { return it }
        val out: Incompatibility<P, V> =
            when (val c = node.cause) {
                is Cause.Dependency ->
                    if (c.pkg == pkg) {
                        Incompatibility.fromDependency(newId(), pkg, c.set.union(extra), c.depPkg, c.depSet) ?: node
                    } else {
                        node
                    }

                is Cause.NoVersions -> Incompatibility.noVersions(newId(), pkg, c.set.union(extra))

                is Cause.Unavailable -> Incompatibility.unavailable(newId(), pkg, c.set.union(extra), c.reasonKo)

                is Cause.NotRoot -> node

                is Cause.DerivedFrom -> {
                    val c1 = widen(c.incompat, pkg, extra, memo)
                    val c2 = widen(c.satisfierCause, pkg, extra, memo)
                    if (c1 === c.incompat && c2 === c.satisfierCause) node else Incompatibility.priorCause(newId(), c1, c2, c.pkg)
                }
            }
        memo[node.id] = out
        return out
    }

    /** 2단계: 결과 항이 한쪽 원인과 같은 Derived 는 그 원인으로 대체. */
    fun collapseRedundant() {
        top =
            rebuild(top) { i, c1, c2 ->
                val c = i.cause as Cause.DerivedFrom
                val rebuilt =
                    if (c1 === c.incompat && c2 === c.satisfierCause) i else Incompatibility.priorCause(newId(), c1, c2, c.pkg)
                when {
                    rebuilt.terms == c1.terms -> c1
                    rebuilt.terms == c2.terms -> c2
                    else -> rebuilt
                }
            }
    }

    /** 3·4단계: External 병합 + 변환. */
    fun convert(): DerivationTree<P, V> {
        val nodes = reachableInCausalOrder(top)
        val depMerge = HashMap<Triple<P, P, VersionSet<V>>, VersionSet<V>>()
        val noVersionsMerge = HashMap<P, VersionSet<V>>()
        val unavailableMerge = HashMap<Pair<P, String>, VersionSet<V>>()
        for (i in nodes) {
            when (val c = i.cause) {
                is Cause.Dependency -> depMerge.unionInto(Triple(c.pkg, c.depPkg, c.depSet), c.set)
                is Cause.NoVersions -> noVersionsMerge.unionInto(c.pkg, c.set)
                is Cause.Unavailable -> unavailableMerge.unionInto(c.pkg to c.reasonKo, c.set)
                is Cause.NotRoot, is Cause.DerivedFrom -> {}
            }
        }
        val built = HashMap<Int, DerivationTree<P, V>>()
        // 같은 내용의 External 은 같은 객체를 재사용한다
        val canonical = HashMap<ExternalKind<P, V>, DerivationTree.External<P, V>>()
        fun external(kind: ExternalKind<P, V>): DerivationTree<P, V> = canonical.getOrPut(kind) { DerivationTree.External(kind) }
        for (i in nodes) {
            built[i.id] =
                when (val c = i.cause) {
                    is Cause.NotRoot -> external(ExternalKind.NotRoot(c.pkg, c.version))

                    is Cause.NoVersions -> external(ExternalKind.NoVersions(c.pkg, noVersionsMerge.getValue(c.pkg)))

                    is Cause.Unavailable ->
                        external(ExternalKind.Unavailable(c.pkg, unavailableMerge.getValue(c.pkg to c.reasonKo), c.reasonKo))

                    is Cause.Dependency ->
                        external(
                            ExternalKind.FromDependencyOf(
                                c.pkg,
                                depMerge.getValue(Triple(c.pkg, c.depPkg, c.depSet)),
                                c.depPkg,
                                c.depSet,
                            ),
                        )

                    is Cause.DerivedFrom ->
                        DerivationTree.Derived(
                            built.getValue(c.incompat.id),
                            built.getValue(c.satisfierCause.id),
                            i.terms.mapValues { (_, t) -> t.allowed },
                        )
                }
        }
        return built.getValue(top.id)
    }

    private fun <K> MutableMap<K, VersionSet<V>>.unionInto(key: K, set: VersionSet<V>) {
        val prev = this[key]
        this[key] = if (prev == null) set else prev.union(set)
    }
}

/** [root] 에서 닿는 incompatibility 를 id 오름차순으로 (원인이 먼저 오도록) 모은다. */
private fun <P : Any, V : Comparable<V>> reachableInCausalOrder(root: Incompatibility<P, V>): List<Incompatibility<P, V>> {
    val seen = HashMap<Int, Incompatibility<P, V>>()
    val stack = ArrayList<Incompatibility<P, V>>()
    stack += root
    while (stack.isNotEmpty()) {
        val i = stack.removeAt(stack.size - 1)
        if (seen.put(i.id, i) != null) continue
        val c = i.cause
        if (c is Cause.DerivedFrom) {
            stack += c.incompat
            stack += c.satisfierCause
        }
    }
    return seen.values.sortedBy { it.id }
}
