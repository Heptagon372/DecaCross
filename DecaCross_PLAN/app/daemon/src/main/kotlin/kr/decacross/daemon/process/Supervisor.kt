package kr.decacross.daemon.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 마크 서버 프로세스 하나 (설계서 §5.1).
 *
 * - 항상 `nogui`. 바닐라 Swing 창이 같이 뜨면 안 된다.
 * - stdout/stderr 는 UTF-8 로 읽어 [lines] 로 흘린다. 한글 채팅이 `???` 로 깨지면 사용자는 즉시 이탈한다.
 * - stdin 에 명령을 쓴다 ([send]).
 */
class ServerProcess private constructor(
    val dir: Path,
    private val process: Process,
    private val stdin: BufferedWriter,
    val startedAt: Instant,
) {
    private val _lines = MutableSharedFlow<String>(replay = 256, extraBufferCapacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 서버 출력 라인 (stdout+stderr 병합). 배칭은 WS 계층(05)에서. */
    val lines: SharedFlow<String> = _lines

    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive

    fun send(command: String) {
        synchronized(stdin) {
            stdin.write(command)
            stdin.newLine()
            stdin.flush()
        }
    }

    /** 최대 [timeout] 동안 종료를 기다린다. 종료했으면 true. */
    suspend fun waitFor(timeout: Long, unit: TimeUnit): Boolean = withContext(Dispatchers.IO) { process.waitFor(timeout, unit) }

    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /** SIGTERM 상당. Windows 는 TerminateProcess 라 강제 종료와 같다 — 그래서 `stop` 명령이 먼저다. */
    fun terminate() {
        process.destroy()
    }

    fun kill() {
        process.destroyForcibly()
    }

    companion object {
        fun start(scope: CoroutineScope, dir: Path, javaExe: Path, jvmArgs: List<String>, jar: String): ServerProcess {
            val cmd = listOf(javaExe.toAbsolutePath().toString()) + jvmArgs + listOf("-jar", jar, "nogui")
            val process = ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
            val sp = ServerProcess(dir, process, stdin, Instant.now())
            scope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { seq ->
                    for (line in seq) sp._lines.tryEmit(line)
                }
            }
            return sp
        }
    }
}
