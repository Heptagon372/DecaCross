package kr.decacross.daemon.process

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 종료 프로토콜 제한 시간 (불변식 13: `save-all` → `stop` → 60초 → SIGTERM → 30초 → SIGKILL).
 * [saveAll] 은 명세에 값이 없어 30초로 정했다 (DESIGN2 D-I14). [kill] 은 강제 종료 후 종료 확인 대기.
 * [saveAll] 이 0 이면 "Saved the game" 을 기다리지 않고 save-all 을 쓴 뒤 바로 stop 을 쓴다 ([URGENT]).
 */
data class ShutdownTimeouts(
    val saveAll: Duration = 30.seconds,
    val stop: Duration = 60.seconds,
    val terminate: Duration = 30.seconds,
    val kill: Duration = 10.seconds,
) {
    companion object {
        /** 창 닫힘·셧다운 훅처럼 시간이 짧을 때: save-all 대기만 없앤다 (순서는 그대로, Paper 의 stop 도 저장한다). */
        val URGENT: ShutdownTimeouts = ShutdownTimeouts(saveAll = Duration.ZERO)
    }
}

/** 진행 단계 보고 (CLI 가 한 줄씩 출력, 05 는 WS). */
sealed interface ShutdownStep {
    /** 시작할 때 이미 끝나 있었다. */
    data object AlreadyExited : ShutdownStep

    /** ① `save-all` 전송 + "Saved the game" 대기 결과. */
    data class SaveAll(val result: SendResult) : ShutdownStep

    /** ② `stop` 전송 결과. */
    data class Stop(val result: SendResult) : ShutdownStep

    /** ③ 최대 60초 대기 결과 (null = 시간 초과). */
    data class StopWait(val exitCode: Int?) : ShutdownStep

    /** ④ 정상 종료 신호. */
    data class Interrupt(val result: InterruptResult) : ShutdownStep

    /** ⑤ 추가 30초 대기 결과. */
    data class InterruptWait(val exitCode: Int?) : ShutdownStep

    /** ⑥ 강제 종료. ★ 사용자에게 "월드 손상 가능" 을 명시한다. */
    data class Killed(val exitCode: Int?) : ShutdownStep
}

/** 프로토콜 결과 분류. */
enum class ShutdownOutcome {
    /** 시작 전에 이미 종료 */
    ALREADY_EXITED,

    /** ①② 로 정상 종료 */
    STOPPED,

    /** ④ 신호로 종료 (서버 셧다운 훅이 저장) */
    INTERRUPTED,

    /** ⑥ 강제 종료 — 월드 손상 가능 */
    KILLED,

    /** ⑥ 후에도 종료 확인 실패 */
    KILL_UNCONFIRMED,
}

/** 프로토콜 기록. [steps] 는 실제로 거친 단계 순서, [exitCode] 는 확인된 종료 코드(모르면 null). */
data class ShutdownReport(val steps: List<ShutdownStep>, val exitCode: Int?, val outcome: ShutdownOutcome)

/**
 * 종료 프로토콜 (설계서 §5.2, 불변식 13). 순수 로직 — [ServerConsole] 만 본다 (05 데몬이 그대로 재사용).
 *
 * ```
 * 살아 있지 않음 → AlreadyExited
 * ① send("save-all", expect = saved, timeouts.saveAll)      (saveAll = 0 이면 expect 없이 SENT) STDIN_CLOSED 면 ④로
 * ② send("stop")                                            STDIN_CLOSED 면 ④로
 * ③ awaitExit(timeouts.stop)          → 끝나면 STOPPED
 * ④ interrupt()
 * ⑤ awaitExit(timeouts.terminate)     → 끝나면 INTERRUPTED
 * ⑥ kill(); awaitExit(timeouts.kill)  → KILLED / KILL_UNCONFIRMED
 * ```
 * 각 단계 사이에 프로세스가 끝났으면 즉시 멈춘다. 순서를 건너뛰는 유일한 경우는 stdin 이 닫혀 ①/② 를 **쓸 수 없을** 때이며
 * 그때 ④ 로 간다 (SCP-I19: 쓸 수 없는 명령을 "보냈다" 고 할 수 없다).
 */
suspend fun runShutdownProtocol(
    console: ServerConsole,
    savedPattern: Regex,
    timeouts: ShutdownTimeouts = ShutdownTimeouts(),
    onStep: suspend (ShutdownStep) -> Unit = {},
): ShutdownReport {
    val steps = ArrayList<ShutdownStep>()
    suspend fun record(step: ShutdownStep) {
        steps.add(step)
        onStep(step)
    }

    if (!console.isAlive) {
        record(ShutdownStep.AlreadyExited)
        return ShutdownReport(steps.toList(), console.awaitExit(Duration.ZERO), ShutdownOutcome.ALREADY_EXITED)
    }

    // ① save-all — URGENT(대기 0)면 응답을 기다리지 않고 바로 ② 로 간다
    val saveResult = if (timeouts.saveAll == Duration.ZERO) {
        console.send("save-all")
    } else {
        console.send("save-all", savedPattern, timeouts.saveAll)
    }
    record(ShutdownStep.SaveAll(saveResult))
    if (!console.isAlive) {
        return ShutdownReport(steps.toList(), console.awaitExit(Duration.ZERO), ShutdownOutcome.STOPPED)
    }

    // ② stop — stdin 이 닫혀 ① 을 쓰지 못했으면 ② 도 쓸 수 없다 (SCP-I19)
    var stopResult: SendResult? = null
    if (saveResult != SendResult.STDIN_CLOSED) {
        stopResult = console.send("stop")
        record(ShutdownStep.Stop(stopResult))
    }

    // ③ 종료 대기
    if (saveResult != SendResult.STDIN_CLOSED && stopResult != SendResult.STDIN_CLOSED) {
        val code = console.awaitExit(timeouts.stop)
        record(ShutdownStep.StopWait(code))
        if (code != null) return ShutdownReport(steps.toList(), code, ShutdownOutcome.STOPPED)
    }

    // ④ 정상 종료 신호 + ⑤ 대기
    record(ShutdownStep.Interrupt(console.interrupt()))
    val afterInterrupt = console.awaitExit(timeouts.terminate)
    record(ShutdownStep.InterruptWait(afterInterrupt))
    if (afterInterrupt != null) return ShutdownReport(steps.toList(), afterInterrupt, ShutdownOutcome.INTERRUPTED)

    // ⑥ 강제 종료 — 월드 손상 가능
    console.kill()
    val afterKill = console.awaitExit(timeouts.kill)
    record(ShutdownStep.Killed(afterKill))
    val outcome = if (afterKill != null) ShutdownOutcome.KILLED else ShutdownOutcome.KILL_UNCONFIRMED
    return ShutdownReport(steps.toList(), afterKill, outcome)
}

/**
 * 종료 프로토콜을 **한 번만** 돌리고 모든 호출자(CLI 입력 EOF, Ctrl+C·창 닫힘 셧다운 훅, 오류 경로)가 같은 실행을 기다리게 한다
 * (critique windows #4: 따로 돌면 늦게 온 쪽이 먼저 반환해 JVM 이 프로토콜 도중 멈추고 서버가 고아가 될 수 있다).
 *
 * # 불변식
 * - 프로토콜은 최대 한 번 시작된다. [start]·[startUrgent] 는 같은 [Deferred] 를 돌려준다.
 * - [startUrgent]: 시작 전이면 [ShutdownTimeouts.URGENT] 로 시작한다. 이미 ① save-all 응답을 기다리는 중이면 그 대기를 끊고
 *   ② stop 으로 넘어가게 한다. 어느 경우든 save-all → stop 순서는 유지된다.
 * - 시작한 프로토콜은 [scope] 취소와 무관하게 끝까지 간다 (`NonCancellable`, ⑥ 강제 종료 포함).
 */
class ShutdownCoordinator(
    private val console: ServerConsole,
    private val savedPattern: Regex,
    private val scope: CoroutineScope,
    private val timeouts: ShutdownTimeouts = ShutdownTimeouts(),
    private val onStep: suspend (ShutdownStep) -> Unit = {},
) {
    private val running = AtomicReference<Deferred<ShutdownReport>?>(null)

    /** 급한 종료 요청. ① 의 `Saved the game` 대기를 끊는 신호로도 쓴다. */
    private val urgent = CompletableDeferred<Unit>()

    /**
     * [scope] 의 디스패처만 물려받고 Job 은 따로 둔다 — 한 번 시작한 프로토콜은 [scope] 가 취소돼도 ⑥ 까지 끝내야 하고
     * (D-I43), 그래야 늦게 온 호출자도 [ShutdownReport] 를 받는다. `async(NonCancellable)` 은 폐기 예정이라 쓰지 않는다.
     */
    private val protocolScope: CoroutineScope =
        CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    /** ① 대기 중에 [startUrgent] 가 오면 그 대기만 끊어 주는 감싼 콘솔. */
    private val wrapped: ServerConsole = UrgentAwareConsole(console, urgent, protocolScope)

    /** 프로토콜이 이미 시작됐는가. */
    val isStarted: Boolean get() = running.get() != null

    /** 아직이면 [timeouts] 로 시작, 이미 시작했으면 그 실행. */
    fun start(): Deferred<ShutdownReport> = install(timeouts)

    /** 급한 종료 (셧다운 훅). 위 불변식 참고. */
    fun startUrgent(): Deferred<ShutdownReport> {
        urgent.complete(Unit)
        return install(ShutdownTimeouts.URGENT)
    }

    private fun install(chosen: ShutdownTimeouts): Deferred<ShutdownReport> {
        running.get()?.let { return it }
        val created = protocolScope.async(start = CoroutineStart.LAZY) {
            runShutdownProtocol(wrapped, savedPattern, chosen, onStep)
        }
        if (running.compareAndSet(null, created)) {
            created.start()
            return created
        }
        created.cancel()
        return running.get() ?: created
    }
}

/**
 * [ShutdownCoordinator] 전용 래퍼. 기대 패턴 대기 중에 급한 종료가 오면 **그 대기만** [SendResult.TIMED_OUT] 로 끝낸다
 * (줄은 이미 썼으므로 프로토콜은 ② `stop` 으로 넘어간다).
 *
 * # 불변식
 * - 어느 경로로 가든 [line] 은 반드시 stdin 에 쓴다. 쓰기를 건너뛰면 `save-all` 이 서버에 닿지 않는데도
 *   단계 기록에는 남아 불변식 13("순서 생략 금지")이 깨진다.
 * - 그래서 (a) 들어올 때 이미 급하면 기대 패턴 없이 쓰기만 하고 (URGENT 와 같은 의미), (b) 대기 중에 급해지면
 *   경쟁 중인 호출을 **취소하지 않는다** — 아직 쓰지 못한 줄이 취소와 함께 사라질 수 있기 때문이다.
 *   버려진 호출은 자기 제한 시간에 스스로 끝나고, 결과만 버린다 (실제 콘솔은 stdin 뮤텍스로 쓰기 순서를 지킨다).
 */
private class UrgentAwareConsole(
    private val inner: ServerConsole,
    private val urgent: CompletableDeferred<Unit>,
    private val raceScope: CoroutineScope,
) : ServerConsole {
    override val isAlive: Boolean get() = inner.isAlive

    override suspend fun send(line: String, expect: Regex?, timeout: Duration): SendResult {
        if (expect == null) return inner.send(line, null, timeout)
        // 이미 급한 상태 — 기다리지 않고 쓰기만 한다 (셧다운 훅이 EOF 경로보다 먼저 이긴 경우)
        if (urgent.isCompleted) return inner.send(line, null, Duration.ZERO)
        val call = raceScope.async(start = CoroutineStart.UNDISPATCHED) { inner.send(line, expect, timeout) }
        return select {
            call.onAwait { it }
            urgent.onAwait { SendResult.TIMED_OUT }
        }
    }

    override suspend fun awaitExit(timeout: Duration): Int? = inner.awaitExit(timeout)

    override suspend fun interrupt(): InterruptResult = inner.interrupt()

    override fun kill() = inner.kill()
}
