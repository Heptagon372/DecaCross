package kr.decacross.cli

import kr.decacross.daemon.install.MIN_RAM_MB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RamOptionTest {
    private fun mb(text: String): Int {
        val parsed = parseRamMb(text)
        return assertNotNull(parsed as? RamParse.Ok, "'$text' 은 통과해야 한다: $parsed").megabytes
    }

    private fun reason(text: String): String {
        val parsed = parseRamMb(text)
        return assertNotNull(parsed as? RamParse.Invalid, "'$text' 은 거부돼야 한다: $parsed").reasonKo
    }

    @Test
    fun `G 와 M 과 단위 없는 값을 MB 로 바꾼다`() {
        assertEquals(4096, mb("4G"))
        assertEquals(4096, mb("4g"))
        assertEquals(4096, mb("4096M"))
        assertEquals(4096, mb("4096m"))
        assertEquals(4096, mb("4096"))
        assertEquals(4096, mb("  4G  "))
    }

    @Test
    fun `하한 미만도 파서는 통과시킨다 - 거부는 PLAN 몫`() {
        assertEquals(512, mb("512M"))
        assertTrue(512 < MIN_RAM_MB, "512MB 는 설치 하한 미만이어야 이 테스트가 의미가 있다")
    }

    @Test
    fun `형식이 틀린 값은 이유와 함께 거부한다`() {
        for (text in listOf("", "   ", "0G", "0", "-1G", "-1", "4T", "1.5G", "G", "M", "4 G", "4GB")) {
            assertTrue(reason(text).isNotBlank(), "'$text' 의 이유 문구가 비었다")
        }
    }

    @Test
    fun `Int MB 로 담을 수 없는 값은 거부한다`() {
        assertTrue(reason("9999999G").isNotBlank())
    }

    @Test
    fun `Long 을 넘겨 감기는 값도 거부한다`() {
        // 18014398509481985 * 1024 는 Long 에서 1024 로 감긴다 — 곱한 뒤에 검사하면 4 엑사바이트 요청이 1GB 로 통과한다
        assertTrue(reason("18014398509481985G").isNotBlank())
        assertTrue(reason("${Long.MAX_VALUE}G").isNotBlank())
        assertTrue(reason("${Long.MAX_VALUE}M").isNotBlank())
        assertTrue(reason("9007199254740993G").isNotBlank())
    }
}
