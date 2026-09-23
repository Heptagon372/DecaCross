package kr.decacross.pubgrub.golden

/**
 * 테스트 전용 최소 JSON 파서. 골든 픽스처를 읽는 데만 쓴다 (새 의존성을 피하기 위해 직접 작성).
 * 객체 → `Map<String, Any?>`(삽입 순서 유지), 배열 → `List<Any?>`, 문자열, 숫자(Double), Boolean, null.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "JSON 끝에 남은 문자가 있습니다 (위치 ${p.pos})" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "JSON 이 예기치 않게 끝났습니다" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("예상치 못한 문자 '$c' (위치 $pos)")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "리터럴 오류 (위치 $pos)" }
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun str(): String {
            require(s[pos] == '"')
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "닫히지 않은 문자열" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()

                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')

                            '\\' -> sb.append('\\')

                            '/' -> sb.append('/')

                            'b' -> sb.append('\b')

                            'f' -> sb.append('')

                            'n' -> sb.append('\n')

                            'r' -> sb.append('\r')

                            't' -> sb.append('\t')

                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }

                            else -> error("잘못된 이스케이프 \\$e")
                        }
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            require(s[pos] == '{')
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                require(s[pos] == ':') { "':' 가 필요합니다 (위치 $pos)" }
                pos++
                out[key] = value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("',' 또는 '}' 가 필요합니다 (위치 ${pos - 1})")
                }
            }
        }

        private fun arr(): List<Any?> {
            require(s[pos] == '[')
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out += value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("',' 또는 ']' 가 필요합니다 (위치 ${pos - 1})")
                }
            }
        }
    }
}
