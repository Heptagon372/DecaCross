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

/** 도메인 → PubGrub 버전 매핑. 같은 Pkg 안에서만 비교된다. */
public sealed interface Ver : Comparable<Ver> {
    public data class Mc(val o: McOrdinal) : Ver

    public data class Java(val feature: Int) : Ver

    public data class Build(val n: Long) : Ver

    public data class Sem(val v: LooseVersion) : Ver

    /** Provides 가상 패키지 — 항상 하나 */
    public data object Unit : Ver

    override fun compareTo(other: Ver): Int = TODO("07")
}

/**
 * 느슨한 버전. "2.21.0", "5.1.0-SNAPSHOT", "build-62" 같은 플러그인 버전 문자열을 비교 가능하게 만든다.
 * SemVer 가 아니어도 죽지 않는다 — 숫자 구간은 수치로, 나머지는 문자열로 비교한다.
 */
public data class LooseVersion(val raw: String) : Comparable<LooseVersion> {
    override fun compareTo(other: LooseVersion): Int = TODO("07")
}
