package kr.decacross.daemon.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `java -version` 출력 해석 (DESIGN2 §2.14 벡터). */
class JavaVersionParseTest {
    private fun parse(raw: String): ParsedJavaVersion? = parseJavaVersionOutput(versionOutput(raw))

    private fun versionOutput(raw: String): String =
        """
        openjdk version "$raw" 2024-07-16
        OpenJDK Runtime Environment (build $raw)
        OpenJDK 64-Bit Server VM (build $raw, mixed mode, sharing)
        """.trimIndent()

    @Test
    fun featureVectors() {
        val expected = mapOf(
            "1.8.0_392" to 8,
            "21.0.4" to 21,
            "22-ea" to 22,
            "25.0.4.1" to 25,
            "17" to 17,
            "11.0.2+9" to 11,
            "1.7.0_80" to 7,
        )
        for ((raw, feature) in expected) {
            val parsed = parse(raw)
            assertEquals(feature, parsed?.feature, "'$raw' 의 feature")
            assertEquals(raw, parsed?.raw)
        }
    }

    @Test
    fun preReleaseIsMarked() {
        assertTrue(parse("22-ea")?.preRelease == true)
        assertFalse(parse("21.0.4")?.preRelease == true)
        assertFalse(parse("11.0.2+9")?.preRelease == true)
    }

    @Test
    fun javaToolOptionsNoticeBeforeVersionLine() {
        val output = "Picked up JAVA_TOOL_OPTIONS: -Dfoo=bar\r\nopenjdk version \"21.0.4\" 2024-07-16\r\n"
        val parsed = parseJavaVersionOutput(output)
        assertEquals(21, parsed?.feature)
        assertEquals("21.0.4", parsed?.raw)
    }

    @Test
    fun oracleStyleJavaVersionLine() {
        assertEquals(8, parseJavaVersionOutput("java version \"1.8.0_392\"\n")?.feature)
    }

    @Test
    fun garbageOutputIsNull() {
        assertNull(parseJavaVersionOutput(""))
        assertNull(parseJavaVersionOutput("bash: java: command not found"))
        assertNull(parseJavaVersionOutput("Error occurred during initialization of VM"))
        // 줄 첫머리가 아닌 곳의 version 문자열은 받지 않는다
        assertNull(parseJavaVersionOutput("see: openjdk version \"21.0.4\""))
        // 따옴표 안이 비면 feature 를 알 수 없다
        assertNull(parseJavaVersionOutput("openjdk version \"\"\n"))
        assertNull(parseJavaVersionOutput("openjdk version \"ea\"\n"))
    }
}
