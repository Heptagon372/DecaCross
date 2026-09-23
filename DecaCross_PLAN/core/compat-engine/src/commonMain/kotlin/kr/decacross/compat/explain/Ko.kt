package kr.decacross.compat.explain

import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.resolve.Candidates
import kr.decacross.compat.resolve.Pkg
import kr.decacross.compat.resolve.Ver
import kr.decacross.pubgrub.Bound
import kr.decacross.pubgrub.Range
import kr.decacross.pubgrub.VersionSet

/** 패키지·버전·범위를 한국어로. 전문 용어는 괄호로만 (설계서 §3.4 렌더링 규칙 4). */
public class Ko(private val c: Candidates, db: kr.decacross.compat.db.CompatDb? = null) {
    private val labels: Map<McOrdinal, String> = (db?.mcAll(true).orEmpty() + c.mcs).associate { it.ordinal to it.label }

    public fun pkg(p: Pkg): String = when (p) {
        Pkg.Root -> "요청한 구성"
        Pkg.Mc -> "마인크래프트 버전"
        Pkg.Java -> "Java"
        is Pkg.Core -> coreName(p)
        is Pkg.Content -> c.contents[p.slug]?.name ?: p.slug
        is Pkg.Api -> "${p.kind.name.lowercase()} API"
        Pkg.Nms -> "NMS 버전"
        is Pkg.Provides -> cap(p.cap)
    }

    public fun coreName(p: Pkg.Core): String = p.key.name.lowercase().replaceFirstChar { it.uppercase() }

    public fun cap(cap: Capability): String = when (cap) {
        Capability.CustomItemFramework -> "커스텀 아이템 프레임워크"
        Capability.EconomyProvider -> "경제 플러그인"
        Capability.PermissionProvider -> "권한 플러그인"
        Capability.AntiCheat -> "안티치트"
        Capability.ChunkGenerator -> "월드 생성기"
        is Capability.Other -> cap.key
    }

    public fun ver(p: Pkg, v: Ver): String = when (v) {
        is Ver.Mc -> mcLabel(v.o)
        is Ver.Java -> "Java ${v.feature}"
        is Ver.Build -> "${mcLabel(v.mcOrdinal)} 빌드 ${v.buildNumber}"
        is Ver.Sem -> if (p is Pkg.Provides) (c.contents[v.v.raw]?.name ?: v.v.raw) else v.v.raw
        Ver.Unit -> pkg(p)
    }

    public fun mcLabel(o: McOrdinal): String = labels[o] ?: "서수 ${o.value}"

    /** 범위를 문장 조각으로. "전부", "1.21.8", "1.20.4 ~ 1.21.8", "Java 21 이상", "5.4.0 미만" … */
    public fun range(p: Pkg, set: VersionSet<Ver>): String {
        val r = set as? Range<Ver> ?: return "일부 버전"
        if (r.isFull) return "모든 버전"
        if (r.isEmpty) return "어떤 버전도"
        return r.intervals.joinToString(" 또는 ") { iv ->
            val lo = iv.lower
            val hi = iv.upper
            when {
                lo is Bound.Included && hi is Bound.Included && lo.value == hi.value -> ver(p, lo.value)
                lo is Bound.Included && hi is Bound.Included -> "${ver(p, lo.value)} ~ ${ver(p, hi.value)}"
                lo is Bound.Unbounded && hi is Bound.Unbounded -> "모든 버전"
                lo is Bound.Unbounded -> boundText(p, hi, upper = true)
                hi is Bound.Unbounded -> boundText(p, lo, upper = false)
                else -> "${boundText(p, lo, upper = false)} ${boundText(p, hi, upper = true)}"
            }
        }
    }

    private fun boundText(p: Pkg, b: Bound<Ver>, upper: Boolean): String = when (b) {
        is Bound.Included -> ver(p, b.value) + if (upper) " 이하" else " 이상"
        is Bound.Excluded -> ver(p, b.value) + if (upper) " 미만" else " 초과"
        Bound.Unbounded -> ""
    }
}
