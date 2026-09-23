package kr.decacross.compat.resolve

import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal

/** 도메인 → PubGrub 패키지 매핑 (명세 §5). */
public sealed interface Pkg {
    public data object Root : Pkg

    public data object Mc : Pkg

    public data object Java : Pkg

    public data class Core(val key: CoreKey) : Pkg

    public data class Content(val slug: String) : Pkg

    public data class Api(val kind: ApiKind) : Pkg

    public data object Nms : Pkg

    /** ★ 배타 제약. 같은 Capability 를 제공하는 패키지는 하나만 선택된다. */
    public data class Provides(val cap: Capability) : Pkg
}

public enum class ApiKind { BUKKIT, FABRIC, FORGE }

/**
 * 도메인 → PubGrub 버전 매핑. 같은 Pkg 안에서만 비교된다.
 * 서로 다른 종류끼리의 비교는 종류 순서(Mc < Java < Build < Sem < Unit)로 정의해 전순서를 보장한다 — 방어적 정의일 뿐 실제로는 일어나지 않는다.
 */
public sealed interface Ver : Comparable<Ver> {
    public data class Mc(val o: McOrdinal) : Ver

    public data class Java(val feature: Int) : Ver

    /**
     * 코어 빌드. 빌드 번호는 MC 버전마다 따로 매겨지므로(Paper 1.21.8 build 60 과 1.20.4 build 60 이 공존)
     * `mcOrdinal × 1_000_000 + build` 로 인코딩해 전역 유일 + "새 MC 가 더 크다" 순서를 만든다.
     */
    public data class Build(val n: Long) : Ver {
        public val mcOrdinal: McOrdinal get() = McOrdinal((n / BUILD_BASE).toInt())
        public val buildNumber: Long get() = n % BUILD_BASE

        public companion object {
            public const val BUILD_BASE: Long = 1_000_000L

            public fun of(mc: McOrdinal, build: Long): Build = Build(mc.value.toLong() * BUILD_BASE + build)
        }
    }

    public data class Sem(val v: LooseVersion) : Ver

    /** Provides 가상 패키지 — 항상 하나 */
    public data object Unit : Ver

    override fun compareTo(other: Ver): Int {
        val r = rank(this).compareTo(rank(other))
        if (r != 0) return r
        return when (this) {
            is Mc -> o.compareTo((other as Mc).o)
            is Java -> feature.compareTo((other as Java).feature)
            is Build -> n.compareTo((other as Build).n)
            is Sem -> v.compareTo((other as Sem).v)
            Unit -> 0
        }
    }

    private companion object {
        fun rank(v: Ver): Int = when (v) {
            is Mc -> 0
            is Java -> 1
            is Build -> 2
            is Sem -> 3
            Unit -> 4
        }
    }
}

/**
 * 느슨한 버전. "2.21.0", "5.1.0-SNAPSHOT", "1.19.4-R0.1", "build-62" 같은 플러그인 버전 문자열을 비교 가능하게 만든다.
 * SemVer 가 아니어도 죽지 않는다 — 숫자 구간은 수치로, 나머지는 문자열로 비교한다.
 *
 * 규칙: 본체(`-` 앞)를 `.` 로 나눠 앞에서부터 비교 (없는 자리는 0). 숫자 < 문자열이 아니라 숫자끼리는 수치, 문자열끼리는 사전순,
 * 섞이면 숫자가 작다. 프리릴리스(`-` 뒤)는 같은 본체의 정식보다 작다 ("1.0.0-rc1" < "1.0.0").
 */
public data class LooseVersion(val raw: String) : Comparable<LooseVersion> {
    private val body: List<String>
    private val pre: List<String>?

    init {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val plusIdx = trimmed.indexOf('+')
        val noBuild = if (plusIdx >= 0) trimmed.substring(0, plusIdx) else trimmed
        val dash = noBuild.indexOf('-')
        val main = if (dash >= 0) noBuild.substring(0, dash) else noBuild
        body = main.split('.').filter { it.isNotEmpty() }
        pre = if (dash >= 0) noBuild.substring(dash + 1).split('.', '-').filter { it.isNotEmpty() } else null
    }

    /** 첫 숫자 세그먼트 (major). 없으면 null. */
    public val major: Int? get() = body.firstOrNull()?.toIntOrNull()

    public val segments: List<String> get() = body

    override fun compareTo(other: LooseVersion): Int {
        val c = compareSegments(body, other.body)
        if (c != 0) return c
        val a = pre
        val b = other.pre
        return when {
            a == null && b == null -> 0

            a == null -> 1

            // 정식 > 프리릴리스
            b == null -> -1

            else -> compareSegments(a, b)
        }
    }

    override fun toString(): String = raw

    private companion object {
        fun compareSegments(a: List<String>, b: List<String>): Int {
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrNull(i) ?: "0"
                val y = b.getOrNull(i) ?: "0"
                val xi = x.toLongOrNull()
                val yi = y.toLongOrNull()
                val c = when {
                    xi != null && yi != null -> xi.compareTo(yi)
                    xi != null -> -1
                    yi != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
