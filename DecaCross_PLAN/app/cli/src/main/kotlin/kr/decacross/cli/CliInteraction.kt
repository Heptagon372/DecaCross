package kr.decacross.cli

import kotlinx.coroutines.delay
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.EULA_NOTICE_LINES
import kr.decacross.daemon.install.EULA_QUESTION
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.EulaNotice
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallPlan
import kr.decacross.daemon.install.isExplicitEulaAgreement

/**
 * CLI 의 [InstallInteraction] (DESIGN2 §2.15).
 *
 * # 불변식
 * - ★ 사용자의 명시적 동의 없이 [EulaAnswer.Accepted] 를 돌려주지 않는다. 질문이 출력된 **뒤에** 들어온 `y`/`yes`/`예`,
 *   또는 사용자가 직접 붙인 `--accept-eula` 만 동의다. 입력 없음(EOF)·빈 줄·그 밖의 답은 전부 [EulaAnswer.Declined].
 * - ★ 표준입력이 터미널이 아니면 **묻지 않고** 거부한다 ([interactive]). 순번 검사는 질문 *전에* 들어온 입력만
 *   막는다 — `(sleep 60; echo y) | decacross create …` 처럼 질문을 보고 나서 `y` 를 넣는 래퍼는 통과해 버리고,
 *   그러면 eula.txt 에 `INTERACTIVE_PROMPT`(= 사람이 쳤다)가 찍힌다. 비대화형은 `--accept-eula` 뿐이다.
 * - ★ 질문 전에 들어온 줄은 **읽힌 순번**으로 가려내 버린다 (몇 줄 버렸는지 알린다 — critique m7).
 *   비우기와 질문 출력 사이에 읽힌 줄도 답이 아니다: `echo y | decacross create …` 의 `y` 가 스레드 타이밍에 따라
 *   동의가 되면 안 된다 ([ConsoleIo.inputMark]).
 * - 계획 확인은 명령줄 자체가 확인이다 (SCP-I4): [confirmPlan] 은 항상 true.
 *
 * @param interactive 표준입력이 사람이 보는 터미널인가 ([SYSTEM_INTERACTIVE_STDIN]). 테스트는 파이프를 쓰므로 직접 준다.
 */
class CliInteraction(
    private val acceptEulaFlag: Boolean,
    private val io: ConsoleIo,
    private val interactive: () -> Boolean = SYSTEM_INTERACTIVE_STDIN,
) : InstallInteraction {
    override suspend fun confirmPlan(plan: InstallPlan): Boolean = true

    override suspend fun requestEulaConsent(notice: EulaNotice): EulaAnswer {
        io.out("---- Minecraft EULA ----")
        for (line in EULA_NOTICE_LINES) io.out(line)
        io.out("대상: ${notice.serverName} (Minecraft ${notice.mcLabel})")
        if (acceptEulaFlag) {
            // 중립 문구: 누가 입력했는지 주장하지 않는다 (critique M1)
            io.out("-> 명령줄 --accept-eula 로 동의가 전달됐습니다")
            return EulaAnswer.Accepted(ConsentChannel.CLI_FLAG)
        }
        if (!interactive()) {
            // ★ 한 줄도 읽지 않는다: 읽으면 그 답이 "사람이 친 것"(INTERACTIVE_PROMPT) 으로 기록될 수 있다
            io.out("-> 표준입력이 터미널이 아니라 EULA 동의를 묻지 않습니다 (Gradle run·파이프·리다이렉트)")
            io.out("   진짜 콘솔에서 실행하거나, EULA 를 읽고 동의한다면 사용자 본인이 --accept-eula 를 붙이세요")
            return EulaAnswer.Declined("비대화형 입력 — 동의로 보지 않습니다 (사용자 본인이 --accept-eula 를 붙여 다시 실행하세요)")
        }
        // 읽기 스레드를 띄우고 이미 들어와 있던 줄을 버린다. 파이프로 들어온 입력은 스레드가 뜬 직후에야 읽히므로
        // 잠깐 기다렸다 한 번 더 버린다 (사람에게는 보이지 않는 시간, `echo y | …` 는 확실히 버려진다).
        var discarded = io.drainBuffered()
        delay(PRE_QUESTION_SETTLE_MS)
        discarded += io.drainBuffered()
        // ★ 질문은 한 줄 통째로 먼저 출력한다 (Gradle 은 출력을 줄 단위로 넘긴다)
        io.out(EULA_QUESTION)
        // ★ 기준선은 질문을 **쓴 뒤에** 찍는다 — 이보다 앞서 읽힌 줄은 이 질문의 답일 수 없다
        val questionMark = io.inputMark()
        var answer: String? = null
        var answered = false
        while (true) {
            val line = io.readLineStamped() ?: break
            if (line.sequence <= questionMark) {
                discarded++
                continue
            }
            answer = line.text
            answered = true
            break
        }
        if (discarded > 0) io.out("(질문 전에 입력된 줄 ${discarded}개는 무시했습니다)")
        return when {
            !answered -> EulaAnswer.Declined("입력 없음(EOF) — 동의로 보지 않습니다")
            isExplicitEulaAgreement(answer) -> EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT)
            else -> EulaAnswer.Declined("답변 '$answer' — 동의로 보지 않습니다")
        }
    }
}

/** 질문을 내기 전에 "이미 들어온 입력" 이 읽히기를 기다리는 시간. 동의를 더 쉽게 만들지 않는다 — 버리는 쪽으로만 작용한다. */
private const val PRE_QUESTION_SETTLE_MS: Long = 150
