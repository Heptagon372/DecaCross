package kr.decacross.daemon.process

import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * 종료 프로토콜용 대본 콘솔. 제한 시간은 ms 단위로 짧게 쓴다 (실제 프로세스는 `ServerConsoleTest` 가 본다).
 *
 * # 불변식
 * - 프로세스는 [exitsAfter] 가 가리키는 동작 뒤에만 끝난다 (null 이면 끝까지 살아 있다).
 * - [send] 는 기대 패턴이 있으면 [savedReplyAfter] 만큼 기다린다 — 제한 시간보다 길면 `TIMED_OUT`.
 */
internal class FakeConsole(
    var exitsAfter: Stage? = Stage.STOP,
    var savedReplyAfter: Duration = Duration.ZERO,
    var interruptResult: InterruptResult = InterruptResult.Sent,
    var exitCode: Int = 0,
) : ServerConsole {
    /** 프로세스가 끝나는 지점. */
    enum class Stage { SAVE_ALL, STOP, INTERRUPT, KILL }

    /** 쓴 줄 (기대 패턴·제한 시간 포함). */
    data class SentLine(val line: String, val expect: Regex?, val timeout: Duration)

    val sent: MutableList<SentLine> = CopyOnWriteArrayList()
    val awaited: MutableList<Duration> = CopyOnWriteArrayList()

    /** stdin 이 닫혀 쓸 수 없는 명령. */
    val stdinClosedFor: MutableSet<String> = HashSet()

    @Volatile
    var killed: Boolean = false
        private set

    @Volatile
    private var exited: Boolean = false

    /** `save-all` 을 몇 번 썼는가 (조정자 테스트: 프로토콜은 한 번만 돈다). */
    val saveAllCount: Int get() = sent.count { it.line == "save-all" }

    /** 프로세스가 이미 끝난 상태로 만든다. */
    fun exitNow() {
        exited = true
    }

    override val isAlive: Boolean get() = !exited

    override suspend fun send(line: String, expect: Regex?, timeout: Duration): SendResult {
        sent.add(SentLine(line, expect, timeout))
        if (line in stdinClosedFor) return SendResult.STDIN_CLOSED
        val result = when {
            expect == null -> SendResult.SENT

            savedReplyAfter < timeout -> {
                delay(savedReplyAfter)
                SendResult.MATCHED
            }

            else -> {
                delay(timeout)
                SendResult.TIMED_OUT
            }
        }
        val stage = if (line == "save-all") {
            Stage.SAVE_ALL
        } else if (line == "stop") {
            Stage.STOP
        } else {
            null
        }
        if (stage != null && stage == exitsAfter) exited = true
        return result
    }

    override suspend fun awaitExit(timeout: Duration): Int? {
        awaited.add(timeout)
        if (exited) return exitCode
        if (timeout > Duration.ZERO) delay(timeout)
        return if (exited) exitCode else null
    }

    override suspend fun interrupt(): InterruptResult {
        val result = interruptResult
        if (result is InterruptResult.Sent && exitsAfter == Stage.INTERRUPT) exited = true
        return result
    }

    override fun kill() {
        killed = true
        if (exitsAfter == Stage.KILL) exited = true
    }
}
