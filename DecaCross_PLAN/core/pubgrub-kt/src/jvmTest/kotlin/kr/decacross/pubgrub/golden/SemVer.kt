package kr.decacross.pubgrub.golden

import kr.decacross.pubgrub.Range

/** 테스트 전용 단순 SemVer (major.minor.patch). 프리릴리스·빌드 메타데이터는 다루지 않는다. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(s: String): SemVer {
            val parts = s.trim().split('.')
            require(parts.size == 3) { "SemVer 형식이 아닙니다: '$s'" }
            return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

/**
 * 테스트 전용 범위 문자열 파서.
 * `*`, `1.0.0`, `=1.0.0`, `>=1.0.0`, `>1.0.0`, `<2.0.0`, `<=2.0.0`, `^1.2.0`, `~1.2.0`,
 * 공백으로 이은 조건은 교집합(`>=1.0.0 <2.0.0`), ` || ` 로 이은 절은 합집합.
 */
object RangeParser {
    fun parse(text: String): Range<SemVer> {
        val s = text.trim()
        if (s == "*" || s.isEmpty()) return Range.full()
        var union = Range.empty<SemVer>()
        for (clause in s.split("||")) {
            var acc = Range.full<SemVer>()
            for (token in clause.trim().split(Regex("\\s+"))) {
                if (token.isEmpty()) continue
                acc = acc.intersect(parseToken(token))
            }
            union = union.union(acc)
        }
        return union
    }

    private fun parseToken(token: String): Range<SemVer> =
        when {
            token == "*" -> Range.full()

            token.startsWith(">=") -> Range.higherThan(SemVer.parse(token.drop(2)))

            token.startsWith("<=") -> Range.atMost(SemVer.parse(token.drop(2)))

            token.startsWith(">") -> Range.strictlyHigherThan(SemVer.parse(token.drop(1)))

            token.startsWith("<") -> Range.strictlyLowerThan(SemVer.parse(token.drop(1)))

            token.startsWith("=") -> Range.singleton(SemVer.parse(token.drop(1)))

            token.startsWith("^") -> {
                val v = SemVer.parse(token.drop(1))
                val hi = if (v.major > 0) SemVer(v.major + 1, 0, 0) else SemVer(0, v.minor + 1, 0)
                Range.between(v, hi)
            }

            token.startsWith("~") -> {
                val v = SemVer.parse(token.drop(1))
                Range.between(v, SemVer(v.major, v.minor + 1, 0))
            }

            else -> Range.singleton(SemVer.parse(token))
        }
}
