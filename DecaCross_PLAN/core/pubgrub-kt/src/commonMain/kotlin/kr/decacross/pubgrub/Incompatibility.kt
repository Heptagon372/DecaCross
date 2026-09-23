package kr.decacross.pubgrub

/**
 * "이 항들이 동시에 참일 수 없다"는 진술. PubGrub 의 지식 단위.
 *
 * 예: `{foo: 1.0.0, bar: not ^2.0.0}` 는 "foo 1.0.0 이 선택되면 bar 는 ^2.0.0 안에 있어야 한다"(의존성).
 * `{foo: ^1.0.0}` 는 "foo ^1.0.0 은 선택될 수 없다".
 * 항이 하나도 없으면 "아무것도 성립할 수 없다" = 모순.
 *
 * # 불변식
 * - [terms] 에 항상 참인 항(`Term.isAny`)은 들어가지 않는다 (생성 시 제거).
 * - 한 패키지당 항은 하나다.
 * - 불변 객체다. 의존성 병합은 새 객체를 만들어 색인을 교체한다.
 * - [id] 는 솔버 상태 안에서 유일하다. 유도 트리를 만들 때 공유 노드 판별에 쓴다.
 */
public class Incompatibility<P : Any, V : Comparable<V>> internal constructor(
    public val id: Int,
    public val terms: Map<P, Term<V>>,
    public val cause: Cause<P, V>,
) {
    public operator fun get(pkg: P): Term<V>? = terms[pkg]

    /**
     * 종료 조건: 해가 없음이 확정되는 incompatibility 인가.
     * 항이 없거나(모순), 루트 항 하나뿐이고 그 항이 루트 버전을 포함할 때(루트를 선택할 수 없음).
     */
    public fun isTerminal(root: P, rootVersion: V): Boolean {
        if (terms.isEmpty()) return true
        if (terms.size > 1) return false
        val (pkg, term) = terms.entries.first()
        return pkg == root && term.contains(rootVersion)
    }

    /**
     * 부분해와의 관계. [assignment] 는 패키지별 누적 항을 돌려준다 (없으면 null).
     * pubgrub-rs `Incompatibility::relation` 과 동일.
     */
    public fun relation(assignment: (P) -> Term<V>?): Relation<P> {
        var result: Relation<P> = Relation.Satisfied
        for ((pkg, term) in terms) {
            val assigned = assignment(pkg)
            when (assigned?.let { term.relationWith(it) }) {
                TermRelation.Satisfied -> {}

                TermRelation.Contradicted -> return Relation.Contradicted(pkg)

                null, TermRelation.Inconclusive ->
                    result = if (result is Relation.Satisfied) Relation.AlmostSatisfied(pkg) else Relation.Inconclusive
            }
        }
        return result
    }

    /** 의존성 형태 `{p: +r, dep: -d}` 이면 `(p, dep)`, 아니면 null. */
    public fun asDependency(): Pair<P, P>? {
        val c = cause as? Cause.Dependency ?: return null
        if (c.pkg == c.depPkg) return null
        return c.pkg to c.depPkg
    }

    override fun toString(): String =
        terms.entries.joinToString(", ", prefix = "{", postfix = "}") { (p, t) -> "$p: $t" }

    public companion object {
        /** 루트는 반드시 선택된다: `{root: not {v}}` 는 성립할 수 없다. */
        internal fun <P : Any, V : Comparable<V>> notRoot(id: Int, root: P, version: V): Incompatibility<P, V> =
            Incompatibility(id, mapOf(root to Term.negative(VersionSet.singleton(version))), Cause.NotRoot(root, version))

        /** [set] 안에 선택 가능한 버전이 없다: `{pkg: +set}` 는 성립할 수 없다. */
        internal fun <P : Any, V : Comparable<V>> noVersions(id: Int, pkg: P, set: VersionSet<V>): Incompatibility<P, V> =
            Incompatibility(id, mapOf(pkg to Term.positive(set)), Cause.NoVersions(pkg, set))

        /** [set] 의 버전은 (제공자 사정으로) 쓸 수 없다. */
        internal fun <P : Any, V : Comparable<V>> unavailable(id: Int, pkg: P, set: VersionSet<V>, reasonKo: String): Incompatibility<P, V> =
            Incompatibility(id, mapOf(pkg to Term.positive(set)), Cause.Unavailable(pkg, set, reasonKo))

        /**
         * 의존성: `pkg ∈ set` 이면 `depPkg ∈ depSet` 이어야 한다 → `{pkg: +set, depPkg: -depSet}`.
         *
         * - depSet 이 공집합이면 `{pkg: +set}` (그 버전들은 선택 불가).
         * - 자기 자신에 대한 의존이면 두 항을 교집합으로 합친다. 결과가 항상 거짓(공집합)이면
         *   아무 정보도 없는 진술이므로 null 을 돌려준다 (호출자가 건너뛴다).
         */
        internal fun <P : Any, V : Comparable<V>> fromDependency(
            id: Int,
            pkg: P,
            set: VersionSet<V>,
            depPkg: P,
            depSet: VersionSet<V>,
        ): Incompatibility<P, V>? {
            val cause = Cause.Dependency(pkg, set, depPkg, depSet)
            if (pkg == depPkg) {
                val merged = set.intersect(depSet.complement())
                if (merged.isEmpty) return null
                return Incompatibility(id, mapOf(pkg to Term.positive(merged)), cause)
            }
            val terms =
                if (depSet.isEmpty) {
                    mapOf(pkg to Term.positive(set))
                } else {
                    linkedMapOf(pkg to Term.positive(set), depPkg to Term.negative(depSet))
                }
            return Incompatibility(id, terms, cause)
        }

        /**
         * 충돌 해결의 한 걸음(resolvent). [incompat] 이 부분해에 의해 만족되었고, [pkg] 의 만족자(satisfier)가
         * [satisfierCause] 에서 유도된 것일 때, 두 진술을 결합해 [pkg] 를 (가능하면) 제거한 새 진술을 만든다.
         *
         * 결과 항: pkg 이외의 패키지는 두 항의 교집합, pkg 는 두 항의 합집합. 합집합이 항상 참이면 pkg 는 빠진다.
         * pubgrub-rs `Incompatibility::prior_cause` 와 동일.
         */
        internal fun <P : Any, V : Comparable<V>> priorCause(
            id: Int,
            incompat: Incompatibility<P, V>,
            satisfierCause: Incompatibility<P, V>,
            pkg: P,
        ): Incompatibility<P, V> {
            val terms = LinkedHashMap<P, Term<V>>()
            for ((p, t) in incompat.terms) {
                if (p == pkg) continue
                terms[p] = t
            }
            for ((p, t) in satisfierCause.terms) {
                if (p == pkg) continue
                val existing = terms[p]
                terms[p] = if (existing == null) t else existing.intersect(t)
            }
            val t1 = incompat.terms.getValue(pkg)
            val t2 = satisfierCause.terms.getValue(pkg)
            val union = t1.union(t2)
            if (!union.isAny) terms[pkg] = union
            return Incompatibility(id, terms, Cause.DerivedFrom(incompat, satisfierCause, pkg))
        }

        /**
         * 같은 의존 대상·같은 의존 범위를 가진 두 의존성 incompatibility 를 하나로 합친다:
         * `{p: +a, dep: -d}` + `{p: +b, dep: -d}` → `{p: +(a ∪ b), dep: -d}`.
         * 유도 트리에서 "p 1.0.0 은 …", "p 1.1.0 은 …" 대신 "p ^1.0.0 은 …" 로 읽히게 하기 위한 것이다.
         * 합칠 수 없으면 null.
         */
        internal fun <P : Any, V : Comparable<V>> mergeDependents(
            id: Int,
            a: Incompatibility<P, V>,
            b: Incompatibility<P, V>,
        ): Incompatibility<P, V>? {
            val ca = a.cause as? Cause.Dependency ?: return null
            val cb = b.cause as? Cause.Dependency ?: return null
            if (ca.pkg != cb.pkg || ca.depPkg != cb.depPkg || ca.pkg == ca.depPkg) return null
            if (ca.depSet != cb.depSet) return null
            return fromDependency(id, ca.pkg, ca.set.union(cb.set), ca.depPkg, ca.depSet)
        }
    }
}

/** 부분해 전체와 incompatibility 의 관계. */
public sealed interface Relation<out P> {
    /** 모든 항이 만족됨 → 충돌. */
    public data object Satisfied : Relation<Nothing>

    /** 항 하나([pkg])만 빼고 전부 만족됨 → 그 항의 부정을 유도할 수 있다. */
    public data class AlmostSatisfied<P>(val pkg: P) : Relation<P>

    /** 어떤 항([pkg])이 부분해와 모순 → 이 incompatibility 는 지금 상태에서 아무 정보도 주지 않는다. */
    public data class Contradicted<P>(val pkg: P) : Relation<P>

    /** 만족되지 않은 항이 둘 이상. */
    public data object Inconclusive : Relation<Nothing>
}

/**
 * incompatibility 가 생긴 이유. 나중에 [DerivationTree] 가 된다.
 * 외부 원인 넷은 [ExternalKind] 와 1:1 대응하고, [DerivedFrom] 은 충돌 해결이 학습한 것이다.
 */
public sealed interface Cause<P : Any, V : Comparable<V>> {
    /** 루트 패키지는 반드시 [version] 으로 선택된다. */
    public data class NotRoot<P : Any, V : Comparable<V>>(val pkg: P, val version: V) : Cause<P, V>

    /** [set] 에 해당하는 버전이 하나도 없다. */
    public data class NoVersions<P : Any, V : Comparable<V>>(val pkg: P, val set: VersionSet<V>) : Cause<P, V>

    /** [set] 의 버전은 제공자 사정으로 쓸 수 없다 ([Dependencies.Unavailable]). */
    public data class Unavailable<P : Any, V : Comparable<V>>(val pkg: P, val set: VersionSet<V>, val reasonKo: String) : Cause<P, V>

    /** `pkg ∈ set` 은 `depPkg ∈ depSet` 에 의존한다. */
    public data class Dependency<P : Any, V : Comparable<V>>(
        val pkg: P,
        val set: VersionSet<V>,
        val depPkg: P,
        val depSet: VersionSet<V>,
    ) : Cause<P, V>

    /** 충돌 해결이 두 incompatibility 를 [pkg] 를 축으로 결합(resolvent)해 학습했다. */
    public data class DerivedFrom<P : Any, V : Comparable<V>>(
        val incompat: Incompatibility<P, V>,
        val satisfierCause: Incompatibility<P, V>,
        val pkg: P,
    ) : Cause<P, V>
}
