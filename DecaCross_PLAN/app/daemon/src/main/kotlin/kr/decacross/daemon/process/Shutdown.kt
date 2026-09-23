package kr.decacross.daemon.process

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** 종료 프로토콜 단계 (설계서 §5.2). UI 는 이 값으로 진행 상황을 보여준다. */
enum class ShutdownPhase { SAVE_ALL, STOP, WAIT_STOP, TERMINATE, WAIT_TERMINATE, KILL, EXITED }

sealed interface ShutdownResult {
    /** `stop` 으로 정상 종료 */
    data class Graceful(val exitCode: Int) : ShutdownResult

    /** SIGTERM 상당으로 종료 */
    data class Terminated(val exitCode: Int) : ShutdownResult

    /** ★ 강제 종료 — 월드 손상 가능. UI 에 반드시 명시 경고. */
    data class Killed(val exitCode: Int?) : ShutdownResult
}

/**
 * 순서 생략 금지 (CLAUDE.md 불변식 13):
 * ① `save-all` → 저장 완료 로그 대기 ② `stop` ③ 최대 60초 ④ SIGTERM ⑤ 30초 ⑥ SIGKILL
 *
 * [force] 가 true 면 ④ 부터 시작한다 — 호출 전에 UI 가 경고 모달을 띄웠어야 한다.
 */
suspend fun ServerProcess.shutdown(
    force: Boolean = false,
    stopTimeoutSec: Long = 60,
    termTimeoutSec: Long = 30,
    saveTimeoutSec: Long = 15,
    onPhase: suspend (ShutdownPhase) -> Unit = {},
): ShutdownResult {
    if (!isAlive) return ShutdownResult.Graceful(exitCode ?: 0)
    if (!force) {
        onPhase(ShutdownPhase.SAVE_ALL)
        runCatching { send("save-all") }
        // Paper: "Saved the game" / 바닐라 구버전: "Saved the world"
        runCatching {
            withTimeout(saveTimeoutSec * 1000) {
                lines.first { it.contains("Saved the game") || it.contains("Saved the world") || it.contains("Saving chunks") }
            }
        }.onFailure { if (it !is TimeoutCancellationException) throw it }
        onPhase(ShutdownPhase.STOP)
        runCatching { send("stop") }
        onPhase(ShutdownPhase.WAIT_STOP)
        if (waitFor(stopTimeoutSec, TimeUnit.SECONDS)) {
            onPhase(ShutdownPhase.EXITED)
            return ShutdownResult.Graceful(exitCode ?: 0)
        }
    }
    onPhase(ShutdownPhase.TERMINATE)
    terminate()
    onPhase(ShutdownPhase.WAIT_TERMINATE)
    if (waitFor(termTimeoutSec, TimeUnit.SECONDS)) {
        onPhase(ShutdownPhase.EXITED)
        return ShutdownResult.Terminated(exitCode ?: -1)
    }
    onPhase(ShutdownPhase.KILL)
    kill()
    waitFor(10, TimeUnit.SECONDS)
    onPhase(ShutdownPhase.EXITED)
    return ShutdownResult.Killed(exitCode)
}
