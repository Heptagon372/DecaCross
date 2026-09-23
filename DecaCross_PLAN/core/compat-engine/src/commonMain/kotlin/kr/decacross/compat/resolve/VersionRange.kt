package kr.decacross.compat.resolve

import kr.decacross.pubgrub.Range
import kr.decacross.pubgrub.VersionSet

/**
 * 의존성 범위 문자열 → [Range] of [Ver.Sem].
 *
 * 지원: `*`, `1.2.3`(정확히), `=1.2.3`, `>=1.2`, `>1.2`, `<=2`, `<2`, `^1.2.3`(같은 major), `~1.2.3`(같은 minor),
 * `1.x` / `1.*`, 공백·쉼표로 이어진 여러 조건(교집합). 해석 불가면 전체 집합 (관대하게 — 데이터 오류로 해결을 막지 않는다).
 */
public object VersionRange {
    public fun parse(spec: String): VersionSet<Ver> {
        val s = spec.trim()
        if (s.isEmpty() || s == "*" || s == "latest" || s == "any") return Range.full()
        var acc: VersionSet<Ver> = Range.full()
        val tokens = s.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        for (tok in tokens) {
            val part = parseToken(tok) ?: return Range.full()
            acc = acc.intersect(part)
        }
        return acc
    }

    private fun sem(v: String): Ver = Ver.Sem(LooseVersion(v))

    private fun parseToken(tok: String): VersionSet<Ver>? {
        val t = tok.trim()
        return when {
            t.startsWith(">=") -> Range.higherThan(sem(t.drop(2)))
            t.startsWith("<=") -> Range.atMost(sem(t.drop(2)))
            t.startsWith(">") -> Range.strictlyHigherThan(sem(t.drop(1)))
            t.startsWith("<") -> Range.strictlyLowerThan(sem(t.drop(1)))
            t.startsWith("=") -> Range.singleton(sem(t.drop(1)))
            t.startsWith("^") -> caret(t.drop(1))
            t.startsWith("~") -> tilde(t.drop(1))
            t.endsWith(".x") || t.endsWith(".*") -> wildcard(t.dropLast(2))
            t.first().isDigit() -> Range.singleton(sem(t))
            else -> null
        }
    }

    /** `^1.2.3` → [1.2.3, 2). `^0.2.3` → [0.2.3, 0.3) (semver 관례). */
    private fun caret(v: String): VersionSet<Ver> {
        val lv = LooseVersion(v)
        val segs = lv.segments.map { it.toIntOrNull() ?: return Range.higherThan(sem(v)) }
        val major = segs.getOrElse(0) { 0 }
        val upper = if (major == 0 && segs.size >= 2) "0.${segs[1] + 1}" else "${major + 1}"
        return Range.between(sem(v), sem(upper))
    }

    /** `~1.2.3` → [1.2.3, 1.3). `~1` → [1, 2). */
    private fun tilde(v: String): VersionSet<Ver> {
        val lv = LooseVersion(v)
        val segs = lv.segments.map { it.toIntOrNull() ?: return Range.higherThan(sem(v)) }
        val upper = if (segs.size >= 2) "${segs[0]}.${segs[1] + 1}" else "${segs.getOrElse(0) { 0 } + 1}"
        return Range.between(sem(v), sem(upper))
    }

    /** `1.2.x` → [1.2, 1.3). `1.x` → [1, 2). */
    private fun wildcard(prefix: String): VersionSet<Ver> {
        val segs = prefix.split('.').map { it.toIntOrNull() ?: return Range.full() }
        if (segs.isEmpty()) return Range.full()
        val upper = (segs.dropLast(1) + (segs.last() + 1)).joinToString(".")
        return Range.between(sem(prefix), sem(upper))
    }
}
