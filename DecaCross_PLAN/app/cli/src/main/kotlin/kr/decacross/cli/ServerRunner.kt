package kr.decacross.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.PID_FILE_NAME
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.process.InterruptResult
import kr.decacross.daemon.process.LaunchResult
import kr.decacross.daemon.process.ReadyState
import kr.decacross.daemon.process.SendResult
import kr.decacross.daemon.process.ServerProcess
import kr.decacross.daemon.process.ShutdownCoordinator
import kr.decacross.daemon.process.ShutdownOutcome
import kr.decacross.daemon.process.ShutdownReport
import kr.decacross.daemon.process.ShutdownStep
import kr.decacross.daemon.process.ShutdownTimeouts
import kr.decacross.daemon.process.deletePidFile
import kr.decacross.daemon.process.isWorldSessionLocked
import kr.decacross.daemon.process.launchServer
import kr.decacross.daemon.process.liveServerProcess
import kr.decacross.daemon.process.writePidFile
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 종료 프로토콜 조정자 경계. 운영 구현은 [ShutdownCoordinator] (D-I43), 테스트는 가짜.
 *
 * # 불변식
 * - [start] 와 [startUrgent] 는 같은 실행을 가리키는 [Deferred] 를 돌려준다 (프로토콜은 서버당 한 번).
 */
interface ShutdownControl {
    val isStarted: Boolean

    fun start(): Deferred<ShutdownReport>

    fun startUrgent(): Deferred<ShutdownReport>
}

/** [ShutdownCoordinator] 를 [ShutdownControl] 로 감싼다. */
private class CoordinatorControl(private val coordinator: ShutdownCoordinator) : ShutdownControl {
    override val isStarted: Boolean get() = coordinator.isStarted

    override fun start(): Deferred<ShutdownReport> = coordinator.start()

    override fun startUrgent(): Deferred<ShutdownReport> = coordinator.startUrgent()
}

/**
 * 서버 실행·콘솔 중계·안전 종료 (DESIGN2 §2.9 CLI 부분).
 *
 * # 불변식
 * - ★ 이미 실행 중인 서버를 두 번 띄우지 않는다: pid 기록([liveServerProcess]) 또는 `world/session.lock` (DecaCross 밖에서 띄운 서버).
 * - ★ 종료 프로토콜은 한 번만 돈다. 입력 EOF 와 셧다운 훅이 같은 [ShutdownControl] 을 쓴다 (critique windows #4).
 * - 강제 종료(⑥)가 일어나면 반드시 사용자에게 월드 손상 가능성을 알린다.
 * - pid 기록에 실패하면 조용히 넘어가지 않는다 — 기록이 없으면 `decacross stop` 이 이 서버를 찾지 못한다.
 * - 모든 경계(기동·pid·조정자)는 주입할 수 있다 — 테스트는 실제 프로세스를 띄우지 않는다.
 */
class ServerRunner(
    private val io: ConsoleIo,
    private val launcher: (Path, LaunchSpec, CompiledConsolePatterns, (String) -> Unit) -> LaunchResult =
        { dir, spec, patterns, outputLine -> launchServer(dir, spec, patterns, outputLine) },
    private val liveCheck: (Path) -> ProcessHandle? = { dir -> liveServerProcess(dir) },
    private val sessionLockCheck: (Path) -> Boolean = { dir -> isWorldSessionLocked(dir) },
    /** pid 기록 경계. null = 기록할 프로세스가 이미 없다(기록 안 함), [LayoutIoResult.Failed] = 쓰지 못했다. */
    private val pidWriter: (Path, ServerProcess) -> LayoutIoResult? = { dir, process -> writeLauncherPid(dir, process) },
    private val pidDeleter: (Path) -> Unit = { dir -> deletePidFile(dir) },
    private val timeouts: ShutdownTimeouts = ShutdownTimeouts(),
    private val coordinatorFactory: (ServerProcess, Regex, CoroutineScope, suspend (ShutdownStep) -> Unit) -> ShutdownControl =
        { process, saved, scope, onStep -> CoordinatorControl(ShutdownCoordinator(process, saved, scope, timeouts, onStep)) },
    private val launcherProperty: () -> String? = { System.getProperty("decacross.launcher") },
    private val hooks: ShutdownHookRegistrar = ShutdownHookRegistrar.SYSTEM,
    /** 창 닫힘 때 Windows 가 주는 시간은 약 5 초지만, 콘솔 Ctrl+C 는 프로토콜 전체를 기다릴 수 있다. */
    private val urgentBound: Duration = 135.seconds,
) {
    /** 서버를 띄우고 끝날 때까지 콘솔을 중계한다. 돌려주는 값이 CLI 종료 코드다. */
    suspend fun run(serverDir: Path, spec: LaunchSpec, patterns: CompiledConsolePatterns): Int {
        // pid 파일 읽기·session.lock `tryLock`·프로세스 기동은 전부 막히는 I/O 다 (네트워크 드라이브면 수 초)
        val running = withContext(Dispatchers.IO) { liveCheck(serverDir) }
        if (running != null) {
            io.out("[실패] 이미 실행 중 (pid ${running.pid()})")
            return ExitCodes.SERVER_STATE
        }
        if (withContext(Dispatchers.IO) { sessionLockCheck(serverDir) }) {
            io.out("[실패] 이미 실행 중 (DecaCross 밖에서 시작됨 — 그 콘솔에서 stop)")
            return ExitCodes.SERVER_STATE
        }
        if (launcherProperty() == GRADLE_RUN_LAUNCHER) {
            io.out(
                "[주의] Gradle run 에서 Ctrl+C 는 서버를 강제 종료합니다(저장 안 됨, SCP-I17). " +
                    "종료하려면 콘솔에 stop 을 입력하세요.",
            )
        } else {
            io.out("종료: stop 입력 또는 Ctrl+C (save-all -> stop 순서로 안전 종료)")
        }
        val launched = withContext(Dispatchers.IO) { launcher(serverDir, spec, patterns, io::printRaw) }
        val process =
            when (launched) {
                is LaunchResult.Failed -> {
                    io.out("[실패] 서버를 기동하지 못했습니다: ${launched.detailKo}")
                    return ExitCodes.SERVER_STATE
                }

                is LaunchResult.Started -> launched.process
            }
        // ★ pid 기록에 실패하면 이 창을 닫았을 때 `decacross stop` 이 서버를 찾지 못한다 (고아 서버, SCP-I20).
        //    서버는 이미 떠 있으므로 멈추지는 않고, 사용자에게 무엇이 없는지 알린다.
        val pidWritten = withContext(Dispatchers.IO) { pidWriter(serverDir, process) }
        if (pidWritten is LayoutIoResult.Failed) {
            io.out(
                "[주의] 실행 기록($META_DIR_NAME/$PID_FILE_NAME)을 쓰지 못했습니다: ${pidWritten.detail} — " +
                    "이 창을 닫으면 decacross stop 으로 끌 수 없습니다(이 콘솔에서 stop 을 입력해 끄세요)",
            )
        }
        return supervise(serverDir, process, patterns)
    }

    private suspend fun supervise(serverDir: Path, process: ServerProcess, patterns: CompiledConsolePatterns): Int =
        coroutineScope {
            val coordinator = coordinatorFactory(process, patterns.saved, this) { step ->
                io.out("  " + shutdownStepLine(step))
            }
            // 창 닫힘·Ctrl+C: 급한 종료로 save-all → stop 을 연달아 쓰고 결과를 기다린다
            val hook = Thread({
                if (process.isAlive) {
                    runBlocking { withTimeoutOrNull(urgentBound) { coordinator.startUrgent().await() } }
                }
            }, "dcx-cli-shutdown")
            hooks.add(hook)
            val inputJob = launch { consumeInput(process, coordinator) }
            val readyJob = launch {
                if (process.awaitReady(null) == ReadyState.READY) io.out("[완료] 서버 준비 완료")
            }
            // ★ 정리(훅 해제·pid 삭제)는 반드시 돈다: 여기서 새어 나가면 죽은 서버의 pid 기록과 훅이 남는다
            try {
                val exitCode = process.awaitExit(Duration.INFINITE)
                if (coordinator.isStarted) {
                    val report = coordinator.start().await()
                    if (report.outcome == ShutdownOutcome.KILLED || report.outcome == ShutdownOutcome.KILL_UNCONFIRMED) {
                        io.out("[주의] 강제 종료했습니다 — 월드가 손상됐을 수 있습니다")
                    }
                }
                if (exitCode == 0) ExitCodes.OK else ExitCodes.SERVER_FAILED
            } finally {
                // 입력 스레드는 데몬이라 계속 막혀 있어도 JVM 종료를 막지 않는다 — 소비 코루틴만 끊는다
                inputJob.cancel()
                readyJob.cancel()
                hooks.remove(hook)
                withContext(NonCancellable + Dispatchers.IO) { pidDeleter(serverDir) }
            }
        }

    private suspend fun consumeInput(process: ServerProcess, coordinator: ShutdownControl) {
        while (true) {
            val line = io.readLine() ?: break
            if (process.send(line) == SendResult.STDIN_CLOSED) return
        }
        // 입력 종료(EOF)도 안전 종료로 본다 (SCP-I7, 사용자 Q2) — 콘솔 없는 고아 서버를 남기지 않는다
        io.out("콘솔 입력이 끝났습니다(EOF). 서버가 준비되면 안전하게 종료합니다.")
        process.awaitReady(null)
        coordinator.start().await()
    }
}

/** 종료 단계 한 줄 (사용자가 무엇이 진행 중인지 보게). */
internal fun shutdownStepLine(step: ShutdownStep): String =
    when (step) {
        ShutdownStep.AlreadyExited -> "서버가 이미 종료돼 있었습니다"

        is ShutdownStep.SaveAll -> "① save-all (${sendResultKo(step.result)})"

        is ShutdownStep.Stop -> "② stop (${sendResultKo(step.result)})"

        is ShutdownStep.StopWait ->
            if (step.exitCode == null) "③ 대기 시간 초과" else "③ 종료 확인 (코드 ${step.exitCode})"

        is ShutdownStep.Interrupt -> "④ 정상 종료 신호 (${interruptResultKo(step.result)})"

        is ShutdownStep.InterruptWait ->
            if (step.exitCode == null) "⑤ 대기 시간 초과" else "⑤ 종료 확인 (코드 ${step.exitCode})"

        is ShutdownStep.Killed ->
            if (step.exitCode == null) "⑥ 강제 종료 (확인 실패)" else "⑥ 강제 종료 (코드 ${step.exitCode})"
    }

private fun sendResultKo(result: SendResult): String =
    when (result) {
        SendResult.SENT -> "보냄"
        SendResult.MATCHED -> "완료"
        SendResult.TIMED_OUT -> "응답 없음"
        SendResult.STDIN_CLOSED -> "입력이 닫힘"
    }

private fun interruptResultKo(result: InterruptResult): String =
    when (result) {
        InterruptResult.Sent -> "보냄"
        is InterruptResult.Unsupported -> "불가: ${result.reasonKo}"
        is InterruptResult.Failed -> "실패: ${result.detail}"
    }

/** pid 기록. 핸들을 못 찾으면(이미 종료) 기록하지 않고 null. */
private fun writeLauncherPid(serverDir: Path, process: ServerProcess): LayoutIoResult? {
    val handle = ProcessHandle.of(process.pid).orElse(null) ?: return null
    return writePidFile(serverDir, handle)
}
