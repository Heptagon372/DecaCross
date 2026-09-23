package kr.decacross.pubgrub

/** 트리에 등장하는 모든 패키지 (External 의 주어·의존 대상, Derived 의 항 키). */
public fun <P, V : Comparable<V>> DerivationTree<P, V>.packages(): Set<P> {
    val out = LinkedHashSet<P>()
    walk { node ->
        when (node) {
            is DerivationTree.External ->
                when (val k = node.kind) {
                    is ExternalKind.NotRoot -> out += k.pkg

                    is ExternalKind.NoVersions -> out += k.pkg

                    is ExternalKind.Unavailable -> out += k.pkg

                    is ExternalKind.FromDependencyOf -> {
                        out += k.pkg
                        out += k.depPkg
                    }
                }

            is DerivationTree.Derived -> out += node.terms.keys
        }
    }
    return out
}

/** 후위 순회로 External 노드만 모은다 (중복 제거, 등장 순서 유지). explain 의 1단계가 쓰는 형태. */
public fun <P, V : Comparable<V>> DerivationTree<P, V>.externals(): List<ExternalKind<P, V>> {
    val out = LinkedHashSet<ExternalKind<P, V>>()
    walk { node -> if (node is DerivationTree.External) out += node.kind }
    return out.toList()
}

/** Derived 노드 수 (트리 깊이·크기 진단용). 공유 노드는 한 번만 센다. */
public fun <P, V : Comparable<V>> DerivationTree<P, V>.derivedCount(): Int {
    var n = 0
    walk { if (it is DerivationTree.Derived) n++ }
    return n
}

/**
 * 사람이 읽을 수 있는 들여쓰기 트리. 디버깅·테스트용이며 도메인 문장 생성은 상위 모듈의 몫이다.
 * 같은 부분 트리가 여러 번 나오면 두 번째부터는 `(위와 같음)` 으로 줄인다.
 */
public fun <P, V : Comparable<V>> DerivationTree<P, V>.render(): String {
    val sb = StringBuilder()
    val seen = HashSet<DerivationTree<P, V>>()
    fun line(depth: Int, text: String) {
        repeat(depth) { sb.append("  ") }
        sb.append(text).append('\n')
    }
    fun go(node: DerivationTree<P, V>, depth: Int) {
        when (node) {
            is DerivationTree.External -> line(depth, node.kind.describe())

            is DerivationTree.Derived -> {
                val terms = node.terms.entries.joinToString(", ") { (p, s) -> "$p $s" }
                val shared = node in seen
                line(depth, "$terms 은(는) 성립할 수 없다" + if (shared) " (위와 같음)" else "")
                if (shared) return
                seen += node
                go(node.cause1, depth + 1)
                go(node.cause2, depth + 1)
            }
        }
    }
    go(this, 0)
    return sb.toString().trimEnd()
}

/** External 사실 한 줄 설명. */
public fun <P, V : Comparable<V>> ExternalKind<P, V>.describe(): String =
    when (this) {
        is ExternalKind.NotRoot -> "$pkg $v 은(는) 루트다"
        is ExternalKind.NoVersions -> "$pkg 에는 $range 에 맞는 버전이 없다"
        is ExternalKind.Unavailable -> "$pkg $range 은(는) 쓸 수 없다: $reasonKo"
        is ExternalKind.FromDependencyOf -> "$pkg $range 은(는) $depPkg $depRange 에 의존한다"
    }

/** 후위 순회. 공유 노드는 한 번만 방문한다. 재귀 대신 명시적 스택. */
private fun <P, V : Comparable<V>> DerivationTree<P, V>.walk(visit: (DerivationTree<P, V>) -> Unit) {
    val visited = HashSet<DerivationTree<P, V>>()
    // (노드, 자식을 이미 밀어 넣었는가)
    val stack = ArrayList<Pair<DerivationTree<P, V>, Boolean>>()
    stack += this to false
    while (stack.isNotEmpty()) {
        val (node, expanded) = stack.removeAt(stack.size - 1)
        if (expanded) {
            visit(node)
            continue
        }
        if (!visited.add(node)) continue
        stack += node to true
        if (node is DerivationTree.Derived) {
            stack += node.cause2 to false
            stack += node.cause1 to false
        }
    }
}
