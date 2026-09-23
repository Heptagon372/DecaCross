package kr.decacross.pubgrub

/**
 * 해가 없을 때 "왜 없는지"의 유도 트리. 데카크로스의 차별점(한국어 설명)은 전부 이 위에 올라간다.
 */
public sealed interface DerivationTree<P, V : Comparable<V>> {
    /** 외부 사실 — 사용자에게 보여줄 수 있는 것 */
    public data class External<P, V : Comparable<V>>(val kind: ExternalKind<P, V>) : DerivationTree<P, V>

    /**
     * 내부 유도 — 두 원인의 결합(resolvent).
     *
     * [terms] 는 "동시에 성립할 수 없는 버전 집합 조합"이다. 값은 각 항이 **허용하는** 버전 집합으로,
     * 긍정 항 `p ∈ r` 은 `r`, 부정 항 `¬(p ∈ r)` 은 `r` 의 여집합으로 기록된다 (극성 정보는 사라진다).
     * 예: `{foo: ^1.0.0, bar: <2.0.0 || >=3.0.0}` 는 "foo ^1.0.0 은 bar ^2.0.0 을 필요로 한다"로 읽는다.
     * 설명 생성은 External 만 모아도 충분하며, Derived 는 경로 추적용이다.
     */
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
