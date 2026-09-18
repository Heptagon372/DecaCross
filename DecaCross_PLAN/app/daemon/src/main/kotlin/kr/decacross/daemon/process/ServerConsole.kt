package kr.decacross.daemon.process

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ConsolePatterns
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.processCommand
import kr.decacross.daemon.paths.currentOs
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/** [ServerConsole.send] 결과. */
enum class SendResult {
    /** 썼다 (기대 패턴 없음). */
    SENT,

    /** 썼고, 제한 시간 안에 기대 패턴 줄이 나왔다. */
    MATCHED,

    /** 썼지만 기대 패턴이 제한 시간 안에 안 나왔다. */
    TIMED_OUT,

    /** stdin 이 닫혀 쓸 수 없었다 (프로세스 종료 등). */
    STDIN_CLOSED,
}

/** 정상 종료 신호(④) 결과. */
sealed interface InterruptResult {
    /** 신호를 보냈다 (종료 여부는 다음 대기에서 본다). */
    data object Sent : InterruptResult

    /** 이 환경에서는 보낼 수 없다 → 프로토콜은 ⑤ 대기 후 ⑥ 으로 간다. */
    data class Unsupported(val reasonKo: String) : InterruptResult

    /** 보내려다 실패 (헬퍼 종료 코드 등). */
    data class Failed(val detail: String) : InterruptResult
}

/**
 * 종료 프로토콜이 보는 서버 콘솔 경계. 실제 구현은 [ServerProcess], 테스트는 가짜.
 *
 * # 불변식
 * - [send] 는 기대 패턴 대기를 **쓰기 전에** 건다 (쓰자마자 나온 줄을 놓치지 않게).
 * - [kill] 외의 어떤 메서드도 프로세스를 강제로 죽이지 않는다.
 */
interface ServerConsole {
    /** 프로세스가 아직 살아 있는가. */
    val isAlive: Boolean

    /** [line] + `\n` 을 UTF-8 로 쓴다. [expect] 가 있으면 이후 나온 줄(ANSI 제거)에서 [timeout] 까지 찾는다. */
    suspend fun send(line: String, expect: Regex? = null, timeout: Duration = Duration.ZERO): SendResult

    /** 종료 코드. [timeout] 안에 안 끝나면 null. */
    suspend fun awaitExit(timeout: Duration): Int?

    /** ④ 정상 종료 신호. Windows: 서버 콘솔에 CTRL_C_EVENT (헬퍼 프로세스), 그 외: SIGTERM (`Process.destroy`). */
    suspend fun interrupt(): InterruptResult

    /** ⑥ 강제 종료 (`destroyForcibly`). 월드 손상 가능. */
    fun kill()
}

/** [ServerProcess.awaitReady] 결과. */
enum class ReadyState {
    /** 준비 완료 줄을 봤다 */
    READY,

    /** 준비 전에 끝났다 */
    EXITED,

    /** 제한 시간 초과 */
    TIMED_OUT,
}

/** 기동된 서버 프로세스. */
interface ServerProcess : ServerConsole {
    /** OS 프로세스 id (pid 파일·헬퍼용). */
    val pid: Long

    /** 준비 완료(`Done (…)! For help`) 줄을 기다린다. [timeout] null 이면 무제한. 이미 봤으면 즉시 READY. */
    suspend fun awaitReady(timeout: Duration?): ReadyState
}

/** 컴파일된 콘솔 패턴 (준비 완료, save-all 완료). */
data class CompiledConsolePatterns(val ready: Regex, val saved: Regex) {
    companion object {
        /** 리소스 문자열을 정규식으로. 잘못된 정규식이면 `PatternSyntaxException` (리소스 테스트가 막는다). */
        fun from(patterns: ConsolePatterns): CompiledConsolePatterns = CompiledConsolePatterns(Regex(patterns.ready), Regex(patterns.saved))
    }
}

/** [launchServer] 결과. */
sealed interface LaunchResult {
    /** 기동됨 (준비 완료는 아직 모른다). */
    data class Started(val process: ServerProcess) : LaunchResult

    /** Java 실행 파일 없음, 작업 디렉터리 없음 등 `ProcessBuilder.start` 실패. */
    data class Failed(val detailKo: String) : LaunchResult
}

/** ANSI 이스케이프(CSI) 제거. */
fun stripAnsi(line: String): String = ANSI_CSI.replace(line, "")

/** CSI: `ESC [` + 매개변수 바이트 + 중간 바이트 + 최종 바이트. */
private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/**
 * 서버 기동 (설계서 §5.1, DESIGN2 §2.9).
 *
 * - 명령 = [LaunchSpec.processCommand], 작업 디렉터리 = [serverDir], `redirectErrorStream(true)`.
 * - **stdin/stdout 모두 파이프** (상속 금지): 콘솔 Ctrl+C 가 서버에 직접 가지 않게 해서 CLI 가 순서(save-all → stop)를 지킨다.
 * - 출력 펌프: 바이트를 UTF-8 로 줄 단위 디코드(`\r\n` 처리) → [outputLine] 호출 → 대기자 매칭(ANSI 제거 후).
 * - Windows 에서는 기동 전에 [WindowsConsoleCtrl.clearInheritedCtrlCIgnore] 를 한 번 부른다.
 */
fun launchServer(
    serverDir: Path,
    spec: LaunchSpec,
    patterns: CompiledConsolePatterns,
    outputLine: (String) -> Unit,
    interrupter: ProcessInterrupter = ProcessInterrupter.platformDefault(currentOs()),
): LaunchResult {
    // 성공했을 때만 "했다" 로 표시한다 — 실패(FFM 차단 등)를 한 번 겪었다고 영영 포기하면
    // 그 JVM 이 띄운 서버는 ④ 신호를 영영 못 받는다
    if (currentOs() == Os.WINDOWS && !ctrlCIgnoreCleared.get() && WindowsConsoleCtrl.clearInheritedCtrlCIgnore()) {
        ctrlCIgnoreCleared.set(true)
    }
    val java = try {
        Path.of(spec.javaPath)
    } catch (e: InvalidPathException) {
        return LaunchResult.Failed("Java 경로 형식 오류: ${spec.javaPath}")
    }
    if (!Files.isRegularFile(java)) return LaunchResult.Failed("Java 실행 파일이 없습니다: $java")
    if (!Files.isDirectory(serverDir)) return LaunchResult.Failed("서버 폴더가 없습니다: $serverDir")
    val jar = serverDir.resolve(spec.jarFileName)
    if (!Files.isRegularFile(jar)) return LaunchResult.Failed("서버 jar 가 없습니다: $jar")
    val process = try {
        ProcessBuilder(spec.processCommand())
            .directory(serverDir.toFile())
            .redirectErrorStream(true)
            .start()
    } catch (e: IOException) {
        return LaunchResult.Failed("서버를 시작하지 못했습니다: $e")
    } catch (e: SecurityException) {
        return LaunchResult.Failed("서버를 시작하지 못했습니다: $e")
    }
    return LaunchResult.Started(PipedServerProcess(process, patterns, outputLine, interrupter))
}

/** [WindowsConsoleCtrl.clearInheritedCtrlCIgnore] 는 JVM 당 한 번이면 된다. */
private val ctrlCIgnoreCleared = AtomicBoolean(false)

/**
 * 파이프로 붙은 서버 프로세스. 출력 펌프는 데몬 스레드 하나(`dcx-server-out-<pid>`)가 돌린다.
 *
 * # 불변식
 * - stdin 쓰기는 [stdinMutex] 로 직렬화한다 (동시 `send` 가 줄을 섞지 않게).
 * - [lines] 는 버퍼가 차면 오래된 줄을 버린다 (느린 대기자가 서버 출력을 막지 않게).
 * - [readyDeferred] 는 준비 완료면 true, 출력이 끝나면(EOF) false 로 **정상** 완료한다.
 */
internal class PipedServerProcess(
    private val process: Process,
    private val patterns: CompiledConsolePatterns,
    private val outputLine: (String) -> Unit,
    private val interrupter: ProcessInterrupter,
) : ServerProcess {
    private val lines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val readyDeferred = CompletableDeferred<Boolean>()
    private val stdinMutex = Mutex()
    private val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

    override val pid: Long = process.pid()

    init {
        val pump = Thread({ pumpOutput() }, "dcx-server-out-$pid")
        pump.isDaemon = true
        pump.start()
    }

    private fun pumpOutput() {
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    try {
                        outputLine(line)
                    } catch (e: RuntimeException) {
                        // 출력 콜백(CLI 출력·WS 전송)이 실패해도 펌프는 멈추지 않는다 —
                        // 멈추면 살아 있는 서버가 "끝났다" 로 보이고 강제 종료로 이어진다
                    }
                    val plain = stripAnsi(line)
                    lines.tryEmit(plain)
                    if (!readyDeferred.isCompleted && patterns.ready.containsMatchIn(plain)) {
                        readyDeferred.complete(true)
                    }
                }
            }
        } catch (e: IOException) {
            // 파이프가 끊긴 것은 종료로 본다 (종료 코드는 awaitExit 가 준다)
        } finally {
            readyDeferred.complete(false)
        }
    }

    override val isAlive: Boolean get() = process.isAlive

    override suspend fun send(line: String, expect: Regex?, timeout: Duration): SendResult {
        if (expect == null) return write(line)
        // `withTimeoutOrNull` 은 제한 시간이 0 이하면 블록을 **아예 실행하지 않는다** —
        // 그래도 줄은 써야 한다 (ServerConsole.send 계약). 기다리지 않을 뿐이다.
        if (timeout <= Duration.ZERO) {
            val written = write(line)
            return if (written == SendResult.STDIN_CLOSED) SendResult.STDIN_CLOSED else SendResult.TIMED_OUT
        }
        return try {
            val matched = withTimeoutOrNull(timeout) {
                lines
                    .onSubscription { if (write(line) == SendResult.STDIN_CLOSED) throw StdinClosed() }
                    .first { expect.containsMatchIn(it) }
            }
            if (matched == null) SendResult.TIMED_OUT else SendResult.MATCHED
        } catch (e: StdinClosed) {
            SendResult.STDIN_CLOSED
        }
    }

    private suspend fun write(line: String): SendResult =
        stdinMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                    SendResult.SENT
                } catch (e: IOException) {
                    SendResult.STDIN_CLOSED
                }
            }
        }

    override suspend fun awaitExit(timeout: Duration): Int? {
        if (!process.isAlive) return process.exitValue()
        if (timeout <= Duration.ZERO) return null
        return withTimeoutOrNull(timeout) { process.onExit().await() }?.exitValue()
    }

    override suspend fun awaitReady(timeout: Duration?): ReadyState {
        if (readyDeferred.isCompleted) return if (readyDeferred.await()) ReadyState.READY else ReadyState.EXITED
        val wait: suspend () -> ReadyState = {
            select {
                readyDeferred.onAwait { ready -> if (ready) ReadyState.READY else ReadyState.EXITED }
                process.onExit().asDeferred().onAwait { ReadyState.EXITED }
            }
        }
        return if (timeout == null) wait() else withTimeoutOrNull(timeout) { wait() } ?: ReadyState.TIMED_OUT
    }

    override suspend fun interrupt(): InterruptResult = interrupter.interrupt(process)

    override fun kill() {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}

/** stdin 이 닫혀 쓰지 못했음을 [PipedServerProcess.send] 안에서 빠져나오는 데만 쓴다. */
private class StdinClosed : RuntimeException(null, null, false, false)

/** ④ 신호 보내기 전략. */
fun interface ProcessInterrupter {
    /** [process] 에 ④ 정상 종료 신호. */
    suspend fun interrupt(process: Process): InterruptResult

    companion object {
        /** Windows → [WindowsConsoleCtrl.sendCtrlC], 그 외 → `process.destroy()` (SIGTERM). */
        fun platformDefault(os: Os): ProcessInterrupter =
            if (os == Os.WINDOWS) {
                ProcessInterrupter { process -> WindowsConsoleCtrl.sendCtrlC(process.pid()) }
            } else {
                ProcessInterrupter { process ->
                    process.destroy()
                    InterruptResult.Sent
                }
            }
    }
}
