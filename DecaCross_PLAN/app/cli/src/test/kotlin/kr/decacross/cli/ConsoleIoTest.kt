package kr.decacross.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.decacross.cli.testkit.PipedIo
import kr.decacross.cli.testkit.RecordingIo
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConsoleIoTest {
    @Test
    fun `입력이 끝나면 readLine 이 null 을 돌려준다`() = runBlocking {
        val recording = RecordingIo("첫 줄\n둘째 줄\n")
        withTimeout(5.seconds) {
            assertEquals("첫 줄", recording.io.readLine())
            assertEquals("둘째 줄", recording.io.readLine())
            assertNull(recording.io.readLine())
            assertNull(recording.io.readLine())
        }
    }

    @Test
    fun `drainBuffered 는 버퍼에 있던 줄 수를 세고 EOF 는 남긴다`() = runBlocking {
        val recording = RecordingIo("a\nb\nc\n")
        assertTrue(recording.io.awaitInputEnd(5_000), "입력 스레드가 EOF 에 도달해야 한다")
        assertEquals(3, recording.io.drainBuffered())
        assertEquals(0, recording.io.drainBuffered())
        withTimeout(5.seconds) { assertNull(recording.io.readLine()) }
    }

    @Test
    fun `이미 읽힌 줄의 순번은 기준선보다 크지 않다`() = runBlocking {
        // 질문 전에 들어온 입력을 가려내는 근거 ([CliInteraction] 이 이 순번으로 판단한다)
        val recording = RecordingIo("y\n")
        assertTrue(recording.io.awaitInputEnd(5_000), "입력이 모두 들어와 있어야 한다")
        val mark = recording.io.inputMark()
        val stamped = assertNotNull(withTimeout(5.seconds) { recording.io.readLineStamped() })
        assertEquals("y", stamped.text)
        assertTrue(stamped.sequence <= mark, "질문 전에 읽힌 줄인데 순번이 기준선보다 크다: ${stamped.sequence} > $mark")
    }

    @Test
    fun `readLine 은 취소할 수 있다`() = runBlocking {
        PipedIo().use { piped ->
            val waiting = async { piped.io.readLine() }
            // 읽기 스레드가 떠서 실제로 막힐 때까지 잠깐 기다린다
            delay(50)
            waiting.cancel()
            withTimeout(5.seconds) { waiting.join() }
            assertTrue(waiting.isCancelled)
        }
    }

    @Test
    fun `out 은 MS949 에서 깨지는 문자를 바꾸고 printRaw 는 그대로 둔다`() {
        val recording = RecordingIo()
        recording.io.out("서버 — 종료")
        recording.io.printRaw("서버 — 종료")
        assertEquals("서버 - 종료", recording.lines[0])
        assertEquals("서버 — 종료", recording.lines[1])
    }

    @Test
    fun `일반 구두점의 대시와 공백은 모두 MS949 로 내려간다`() {
        assumeTrue(Charset.isSupported("MS949"), "MS949 를 지원하지 않는 JVM")
        val encoder = Charset.forName("MS949").newEncoder()
        // 다른 작업 패키지(WP-INSTALL·WP-PROC)의 한국어 문구가 어떤 대시·공백을 쓰더라도 `?` 로 깨지지 않아야 한다
        for (code in 0x2000..0x2015) {
            val line = "앞 ${code.toChar()} 뒤"
            val safe = consoleSafe(line)
            assertTrue(encoder.canEncode(safe), "MS949 로 인코딩할 수 없다: U+%04X -> '%s'".format(code, safe))
        }
    }
}
