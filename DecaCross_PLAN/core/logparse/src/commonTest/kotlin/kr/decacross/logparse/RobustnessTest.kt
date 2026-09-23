package kr.decacross.logparse

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 파서·이벤트·시그니처는 어떤 입력에도 예외를 던지지 않는다. */
class RobustnessTest {
    private val ctx = EventContext(listOf("%1\$s was slain by %2\$s", "%1\$s drowned"))

    private fun exercise(text: String) {
        val lines = parseLog(text)
        lines.forEach {
            it.toEvent(ctx)
            matchAllSignatures(it, SeedSignatures.signatures)
            detectFormat(it.raw)
        }
    }

    @Test
    fun `빈 입력`() {
        assertEquals(emptyList(), parseLog(""))
        assertEquals(emptyList(), parseLog("\n\n\r\n"))
        assertEquals(emptyList(), LogParser().feed(""))
        assertEquals(emptyList(), LogParser().flush())
        exercise("")
    }

    @Test
    fun `무작위 쓰레기`() {
        val random = Random(20260923)
        val alphabet = "[]/:\t <>§%\$\\().!?\n\rabcXYZ019가나다"
        repeat(300) {
            val junk = buildString { repeat(random.nextInt(0, 200)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            exercise(junk)
            LogParser().apply {
                feed(junk)
                flush()
            }
        }
    }

    @Test
    fun `아주 긴 줄과 아주 긴 스택`() {
        val longMessage = "x".repeat(200_000)
        val lines = parseLog("[12:00:00] [Server thread/INFO]: $longMessage")
        assertEquals(longMessage, lines.single().message)
        exercise("[12:00:00] [Server thread/ERROR]: boom\n" + "\tat a.B(B.java:1)\n".repeat(20_000))
        exercise("[12:00:00] [Server thread/INFO]: <Steve> " + "§a".repeat(50_000))
        exercise("[" + "[".repeat(10_000) + "/INFO]: ]".repeat(1_000))
    }

    @Test
    fun `깨진 접두와 한국어`() {
        listOf(
            "[12:00:00]",
            "[12:00:00] [",
            "[12:00:00] [Server thread/]: x",
            "[12:00:00] [/INFO]: x",
            "[12:00:00] []: x",
            "[99:99:99] [Server thread/INFO]: x",
            "[12:00:00 ]: x",
            "[12:00:00] [Server thread/INFO]",
            "[12:00:00] [Server thread/INFO]:",
            "<철수> 안녕하세요",
            "[12:00:00] [Server thread/INFO]: <철수> 안녕하세요",
            "[12:00:00] [서버 스레드/정보]: 한국어 스레드",
            "Caused by:",
            "\tat ",
            "...",
        ).forEach { exercise(it) }
        val chat = parseLine("[12:00:00] [Server thread/INFO]: <철수> 안녕하세요").toEvent(ctx)
        assertEquals(LogEvent.Chat("철수", "안녕하세요"), chat)
        assertTrue(parseLine("[12:00:00] [Server thread/INFO]:").message.isEmpty())
    }
}
