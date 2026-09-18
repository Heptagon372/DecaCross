package kr.decacross.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.decacross.cli.testkit.PipedIo
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.EULA_NOTICE_LINES
import kr.decacross.daemon.install.EULA_QUESTION
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.EulaNotice
import kr.decacross.daemon.install.MINECRAFT_EULA_URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CliInteractionTest {
    private val notice = EulaNotice(MINECRAFT_EULA_URL, "paper-1.21.8", "1.21.8")

    /** 질문이 출력된 **뒤에** [answer] 를 넣는다. null 이면 답 없이 입력을 끝낸다(EOF). */
    private fun answerAfterQuestion(answer: String?): Pair<EulaAnswer, List<String>> = runBlocking {
        PipedIo().use { piped ->
            val interaction = CliInteraction(acceptEulaFlag = false, io = piped.io)
            val asked = async { interaction.requestEulaConsent(notice) }
            withTimeout(10.seconds) {
                while (piped.lines.none { it == EULA_QUESTION }) delay(5)
            }
            // 질문이 나오기 전에는 읽지 않았다는 증거: 답은 지금 처음 들어간다
            if (answer == null) piped.endInput() else piped.writeLine(answer)
            val result = withTimeout(10.seconds) { asked.await() }
            result to piped.lines.toList()
        }
    }

    @Test
    fun `명령줄 플래그는 중립 문구와 함께 CLI_FLAG 동의가 된다`() = runBlocking {
        val recording = RecordingIo("y\n")
        val answer = CliInteraction(acceptEulaFlag = true, io = recording.io).requestEulaConsent(notice)
        assertEquals(EulaAnswer.Accepted(ConsentChannel.CLI_FLAG), answer)
        assertTrue(recording.text().contains("명령줄 --accept-eula 로 동의가 전달됐습니다"), recording.text())
        // 입력을 읽지 않았다 (읽었다면 채널이 비었을 것이다)
        assertTrue(recording.io.awaitInputEnd(5_000))
        assertEquals(1, recording.io.drainBuffered(), "플래그 경로는 표준입력을 읽지 않아야 한다")
    }

    @Test
    fun `안내와 질문을 먼저 출력한 뒤 답을 읽는다`() {
        val (answer, lines) = answerAfterQuestion("y")
        assertEquals(EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT), answer)
        for (noticeLine in EULA_NOTICE_LINES) {
            assertTrue(lines.any { it.contains(noticeLine.trim()) }, "안내 줄이 없다: $noticeLine")
        }
        assertTrue(lines.indexOf(EULA_QUESTION) >= 0)
    }

    @Test
    fun `예 도 명시적 동의다`() {
        val (answer, _) = answerAfterQuestion("예")
        assertEquals(EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT), answer)
    }

    @Test
    fun `yes 와 대문자도 동의다`() {
        assertEquals(EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT), answerAfterQuestion("YES").first)
        assertEquals(EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT), answerAfterQuestion(" Y ").first)
    }

    @Test
    fun `그 밖의 답과 빈 줄과 EOF 는 거부다`() {
        for (text in listOf("no", "n", "", "   ", "yy", "동의")) {
            val (answer, _) = answerAfterQuestion(text)
            val declined = assertNotNull(answer as? EulaAnswer.Declined, "'$text' 은 거부여야 한다: $answer")
            assertTrue(declined.reasonKo.isNotBlank())
        }
        val (eof, _) = answerAfterQuestion(null)
        val declinedEof = assertNotNull(eof as? EulaAnswer.Declined, "EOF 는 거부여야 한다: $eof")
        assertTrue(declinedEof.reasonKo.contains("EOF"), declinedEof.reasonKo)
    }

    @Test
    fun `질문이 나오기 전에 파이프로 들어온 y 는 동의가 아니다`() = runBlocking {
        // ★ 회귀: 비우기(drainBuffered)와 질문 출력 사이에 읽힌 줄이 동의가 됐다.
        //   `echo y | decacross create …` 의 결과가 스레드 타이밍에 따라 달라지면 안 된다.
        PipedIo().use { piped ->
            val interaction = CliInteraction(acceptEulaFlag = false, io = piped.io)
            val asked = async { interaction.requestEulaConsent(notice) }
            piped.writeLine("y") // 질문이 나오기 전 (파이프에 이미 들어와 있던 입력과 같은 모양)
            withTimeout(10.seconds) {
                while (piped.lines.none { it == EULA_QUESTION }) delay(5)
            }
            piped.endInput()
            val answer = withTimeout(10.seconds) { asked.await() }
            val declined = assertNotNull(answer as? EulaAnswer.Declined, "질문 전 입력은 동의가 아니다: $answer")
            assertTrue(declined.reasonKo.contains("EOF"), declined.reasonKo)
            assertTrue(piped.lines.any { it.contains("질문 전에 입력된 줄") }, piped.lines.toString())
        }
        Unit
    }

    @Test
    fun `질문 전에 입력된 y 는 버려지고 그 뒤 EOF 는 거부다`() = runBlocking {
        val recording = RecordingIo("y\n")
        assertTrue(recording.io.awaitInputEnd(5_000), "입력이 모두 들어와 있어야 한다")
        val answer = CliInteraction(acceptEulaFlag = false, io = recording.io).requestEulaConsent(notice)
        val declined = assertNotNull(answer as? EulaAnswer.Declined, "질문 전에 친 줄은 동의가 아니다: $answer")
        assertTrue(declined.reasonKo.contains("EOF"), declined.reasonKo)
        assertTrue(recording.text().contains("질문 전에 입력된 줄 1개는 무시했습니다"), recording.text())
    }
}
