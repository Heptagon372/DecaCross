package kr.decacross.daemon.install.assemble

/**
 * `java.util.Properties.store` 가 값에 쓰는 이스케이프 규칙 (`saveConvert(escapeSpace = false, escapeUnicode = true)`).
 * 우리가 직접 쓰는 이유: `Properties.store` 는 매번 달라지는 타임스탬프 주석을 넣어 결정적 바이트가 안 나온다.
 *
 * # 불변식
 * - 결과는 항상 ASCII (0x20 미만·0x7E 초과는 `\uXXXX`, 대문자 hex).
 * - `Properties().load(...)` 로 다시 읽으면 원래 문자열이 나온다 (테스트로 강제).
 */
internal fun escapePropertiesValue(value: String): String {
    val out = StringBuilder(value.length * 2)
    for ((index, ch) in value.withIndex()) {
        if (ch.code in 62..126) {
            if (ch == '\\') out.append("\\\\") else out.append(ch)
            continue
        }
        when (ch) {
            ' ' -> {
                // escapeSpace = false: 맨 앞 공백만 이스케이프한다 (키와 값의 경계가 흐려지므로)
                if (index == 0) out.append('\\')
                out.append(' ')
            }

            '\t' -> out.append("\\t")

            '\n' -> out.append("\\n")

            '\r' -> out.append("\\r")

            '' -> out.append("\\f")

            '=', ':', '#', '!' -> out.append('\\').append(ch)

            else -> {
                if (ch.code < 0x0020 || ch.code > 0x007E) appendUnicodeEscape(out, ch) else out.append(ch)
            }
        }
    }
    return out.toString()
}

private fun appendUnicodeEscape(out: StringBuilder, ch: Char) {
    out.append("\\u")
    for (shift in intArrayOf(12, 8, 4, 0)) out.append(HEX_DIGITS[(ch.code shr shift) and 0xF])
}

/** `Properties` 와 같은 대문자 hex. */
private const val HEX_DIGITS: String = "0123456789ABCDEF"
