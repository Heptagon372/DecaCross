package kr.decacross.pubgrub

/**
 * 해가 없을 때 "왜 없는지"의 유도 트리. 데카크로스의 차별점(한국어 설명)은 전부 이 위에 올라간다.
 */
public sealed interface DerivationTree<P, V : Comparable<V>> {
    /** 외부 사실 — 사용자에게 보여줄 수 있는 것 */
    public data class External<P, V : Comparable<V>>(val kind: ExternalKind<P, V>) : DerivationTree<P, V>

    /** 내부 유도 — 두 원인의 결합 */
    public data class Derived<P, V : Comparable<V>>(
        val cause1: DerivationTree<P, V>,
        val cause2: DerivationTree<P, V>,
        val terms: Map<P, VersionSet<V>>,
    ) : DerivationTree<P, V>
}

public sealed interface ExternalKind<P, V : Comparable<V>> {
    public data class NotRoot<P, V : Comparable<V>>(val pkg: P, val v: V) : ExternalKind<P, V>

    public data class NoVersions<P, V : Comparable<V>>(val pkg: P, val range: VersionSet<V>) : ExternalKind<P, V>

    public data class Unavailable<P, V : Comparable<V>>(
        val pkg: P,
        val range: VersionSet<V>,
        val reasonKo: String,
    ) : ExternalKind<P, V>

    public data class FromDependencyOf<P, V : Comparable<V>>(
        val pkg: P,
        val range: VersionSet<V>,
        val depPkg: P,
        val depRange: VersionSet<V>,
    ) : ExternalKind<P, V>
}
