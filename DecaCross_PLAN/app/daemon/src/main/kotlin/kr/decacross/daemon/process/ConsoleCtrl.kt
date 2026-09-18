package kr.decacross.daemon.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.compat.model.Os
import kr.decacross.daemon.paths.currentOs
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 헬퍼 진입점 클래스 이름 (`java -cp <java.class.path> <이 이름> <pid>`). */
const val CONSOLE_CTRL_HELPER_MAIN: String = "kr.decacross.daemon.process.ConsoleCtrlHelper"

/**
 * Windows 콘솔 제어 (SCP-I3). JDK `Process.destroy()` 는 Windows 에서 `TerminateProcess` 라 셧다운 훅이 돌지 않고,
 * CTRL_BREAK 는 스레드 덤프만 찍는다. 파이프로 띄운 서버는 숨은 자기 콘솔을 가지므로, 짧게 사는 **별도 헬퍼 프로세스**가
 * 그 콘솔에 붙어 CTRL_C_EVENT 를 보내면 서버 JVM 의 셧다운 훅(월드 저장)이 돈다 (research codebase-windows §5 S5 실측).
 *
 * # 불변식
 * - 런처(CLI·데몬) 프로세스 안에서 `FreeConsole`/`AttachConsole`/`SetConsoleCtrlHandler(NULL, TRUE)` 를 부르지 않는다
 *   (CLI 출력이 끊기고, 무시 플래그가 이후 자식에게 상속된다). 그 호출은 [ConsoleCtrlHelper] 에서만 한다.
 */
object WindowsConsoleCtrl {
    /**
     * 이 프로세스가 부모에게서 물려받았을 수 있는 "Ctrl+C 무시" 플래그를 끈다 (`SetConsoleCtrlHandler(NULL, FALSE)`).
     * 서버를 띄우기 전에 부른다 — 켜진 채로 상속되면 ④ 신호가 무시된다. Windows 가 아니거나 실패하면 false.
     */
    fun clearInheritedCtrlCIgnore(): Boolean {
        if (currentOs() != Os.WINDOWS) return false
        val handle = kernel32Handle("SetConsoleCtrlHandler", ValueLayout.ADDRESS, ValueLayout.JAVA_INT) ?: return false
        return callInt(handle, MemorySegment.NULL, 0) != 0
    }

    /**
     * 헬퍼 프로세스를 띄워 [pid] 의 콘솔에 CTRL_C_EVENT 를 보낸다.
     * 헬퍼 JVM = `System.getProperty("java.home")/bin/java(.exe)` 가 있으면 그것, 없으면 `ProcessHandle.current().info().command()` 의
     * 파일 이름이 `java`/`java.exe` 일 때만 그것 (jpackage 런처 `.exe` 는 Java 가 아니다 — 05 에서 다시 본다), 둘 다 아니면 [InterruptResult.Failed].
     * 클래스패스 = `System.getProperty("java.class.path")` 전체 (installDist ≈ 6.6K 자, CreateProcess 한도 32,767 이하 — critique windows #10).
     * 인자 `--enable-native-access=ALL-UNNAMED -Xmx32m`, 표준입출력은 버림(헬퍼가 자기 숨은 콘솔을 갖게). 헬퍼 종료 코드 0 → [InterruptResult.Sent].
     * 이 헬퍼 JVM 은 서버가 아니므로 런처 런타임을 써도 불변식 9 와 무관하다.
     */
    suspend fun sendCtrlC(pid: Long, timeout: Duration = 10.seconds): InterruptResult {
        val java = helperJavaExecutable() ?: return InterruptResult.Failed("헬퍼 Java 없음")
        val classPath = System.getProperty("java.class.path").orEmpty()
        val command = listOf(
            java.toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx32m",
            "-cp",
            classPath,
            CONSOLE_CTRL_HELPER_MAIN,
            pid.toString(),
        )
        val process = withContext(Dispatchers.IO) {
            try {
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        } ?: return InterruptResult.Failed("헬퍼를 띄우지 못했습니다")
        withContext(Dispatchers.IO) {
            try {
                process.outputStream.close()
            } catch (e: IOException) {
                // 헬퍼는 stdin 을 읽지 않는다 — 닫기 실패는 무시한다
            }
        }
        val exited = withTimeoutOrNull(timeout) { process.onExit().await() }
        if (exited == null) {
            process.destroyForcibly()
            return InterruptResult.Failed("헬퍼 시간 초과")
        }
        val code = exited.exitValue()
        if (code == 0) return InterruptResult.Sent
        val reason = HELPER_EXIT_REASONS[code]
        return InterruptResult.Failed(if (reason == null) "헬퍼 종료 코드 $code" else "헬퍼 종료 코드 $code — $reason")
    }
}

/** [ConsoleCtrlHelper] 종료 코드 → 사람이 읽을 이유. */
private val HELPER_EXIT_REASONS: Map<Int, String> = mapOf(
    2 to "헬퍼 인자 오류",
    3 to "대상 콘솔에 붙지 못했습니다 (이미 끝난 프로세스)",
    4 to "CTRL_C 이벤트 전송 실패",
    5 to "Windows 가 아닙니다",
    6 to "네이티브 호출(FFM)을 쓸 수 없습니다",
)

/**
 * 헬퍼 JVM 경로. `java.home/bin/java(.exe)` 가 있으면 그것, 없으면 현재 프로세스의 명령이 `java`/`java.exe` 일 때만 그것.
 * (jpackage 런처 `.exe` 로는 `-cp` 를 줄 수 없다.)
 */
internal fun helperJavaExecutable(): Path? {
    val exe = if (currentOs() == Os.WINDOWS) "java.exe" else "java"
    val fromHome = try {
        Path.of(System.getProperty("java.home").orEmpty(), "bin", exe)
    } catch (e: InvalidPathException) {
        null
    }
    if (fromHome != null && Files.isRegularFile(fromHome)) return fromHome
    val current = ProcessHandle.current().info().command().orElse(null) ?: return null
    val path = try {
        Path.of(current)
    } catch (e: InvalidPathException) {
        return null
    }
    val name = path.fileName?.toString().orEmpty()
    val isJava = name.equals("java", ignoreCase = true) || name.equals("java.exe", ignoreCase = true)
    return if (isJava && Files.isRegularFile(path)) path else null
}

/** `GenerateConsoleCtrlEvent` 의 `CTRL_C_EVENT`. */
private const val CTRL_C_EVENT = 0

/** kernel32 함수 하나를 `int` 반환으로 묶는다. 실패하면 null (FFM 이 막힌 환경 등). */
private fun kernel32Handle(name: String, vararg args: MemoryLayout): MethodHandle? =
    try {
        val lookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val address = lookup.find(name).orElse(null)
        if (address == null) {
            null
        } else {
            Linker.nativeLinker().downcallHandle(address, FunctionDescriptor.of(ValueLayout.JAVA_INT, *args))
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: UnsatisfiedLinkError) {
        null
    } catch (e: IllegalCallerException) {
        null
    }

/**
 * `int` 를 돌려주는 kernel32 호출. `invokeWithArguments` 는 인자·반환 변환을 스스로 하므로 Kotlin 에서
 * `invokeExact` 의 서명 다형성에 기대지 않는다 (호출 횟수가 프로세스당 한 자리라 성능은 문제되지 않는다).
 */
private fun callInt(handle: MethodHandle, vararg args: Any): Int =
    try {
        handle.invokeWithArguments(args.toList()) as? Int ?: 0
    } catch (e: RuntimeException) {
        // WrongMethodTypeException·ClassCastException·IllegalArgumentException·IllegalCallerException 등
        // "이 환경에서는 못 부른다" 는 실패만 0(실패)으로 본다. Error 는 삼키지 않는다.
        0
    } catch (e: UnsatisfiedLinkError) {
        0
    }

/**
 * 헬퍼 프로세스 진입점. `FreeConsole` → `AttachConsole(pid)` → `SetConsoleCtrlHandler(NULL, TRUE)` →
 * `GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)` (FFM, kernel32).
 * 종료 코드: 0 전송, 2 인자 오류, 3 AttachConsole 실패, 4 GenerateConsoleCtrlEvent 실패, 5 Windows 아님,
 * 6 FFM(네이티브 호출) 사용 불가 — 5 와 섞으면 Windows 사용자에게 "Windows 아님" 이라고 말하게 된다.
 */
object ConsoleCtrlHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = args.singleOrNull()?.toLongOrNull()
        if (pid == null || pid <= 0) exitProcess(2)
        if (currentOs() != Os.WINDOWS) exitProcess(5)
        val freeConsole = kernel32Handle("FreeConsole")
        val attachConsole = kernel32Handle("AttachConsole", ValueLayout.JAVA_INT)
        val setHandler = kernel32Handle("SetConsoleCtrlHandler", ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        val generateEvent = kernel32Handle("GenerateConsoleCtrlEvent", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        if (freeConsole == null || attachConsole == null || setHandler == null || generateEvent == null) exitProcess(6)
        callInt(freeConsole)
        if (callInt(attachConsole, pid.toInt()) == 0) exitProcess(3)
        // 이 헬퍼 자신은 방금 만든 이벤트를 무시한다 (서버만 받게)
        callInt(setHandler, MemorySegment.NULL, 1)
        if (callInt(generateEvent, CTRL_C_EVENT, 0) == 0) exitProcess(4)
        // 대상 프로세스가 이벤트를 받을 틈을 준다 (research codebase-windows S5 실측)
        Thread.sleep(HELPER_SETTLE_MS)
        exitProcess(0)
    }
}

/** 이벤트 전달 대기 (ms). */
private const val HELPER_SETTLE_MS = 500L
