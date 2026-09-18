package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import kr.decacross.compat.model.Os
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.testkit.FakeServerJar
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Windows 콘솔 제어 (SCP-I3, D-I15). FFM + 짧게 사는 헬퍼 프로세스. */
class ConsoleCtrlTest {
    private val onWindows = currentOs() == Os.WINDOWS

    @Test
    fun clearInheritedCtrlCIgnoreSucceedsOnWindows() {
        if (onWindows) {
            assertTrue(WindowsConsoleCtrl.clearInheritedCtrlCIgnore(), "SetConsoleCtrlHandler(NULL, FALSE) 성공")
        } else {
            assertFalse(WindowsConsoleCtrl.clearInheritedCtrlCIgnore(), "Windows 가 아니면 false")
        }
    }

    @Test
    fun helperJavaIsTheRunningJvmsLauncher() {
        val resolved = helperJavaExecutable() ?: fail("헬퍼 Java 를 못 찾았다")
        val exe = if (onWindows) "java.exe" else "java"
        assertEquals(Path.of(System.getProperty("java.home"), "bin", exe), resolved)
    }

    @Test
    fun sendCtrlCToAnExitedProcessFails() = runBlocking {
        assumeTrue(onWindows, "CTRL_C 헬퍼는 Windows 전용")
        val short = ProcessBuilder(FakeServerJar.testJava().toString(), "-Xmx64M", "-version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        val pid = short.pid()
        short.waitFor()
        val result = WindowsConsoleCtrl.sendCtrlC(pid, 20.seconds)
        val failed = result as? InterruptResult.Failed ?: fail("Failed 를 기대했다: $result")
        // AttachConsole 실패 = 종료 코드 3
        assertTrue(failed.detail.contains("3"), failed.detail)

        // Windows 의 ④ 는 헬퍼를 쓴다 — destroy()(TerminateProcess)를 부르면 셧다운 훅이 돌지 않는다
        val recording = RecordingProcess(pid)
        val viaDefault = ProcessInterrupter.platformDefault(Os.WINDOWS).interrupt(recording)
        assertTrue(viaDefault is InterruptResult.Failed, "이미 끝난 pid 라 헬퍼가 실패한다: $viaDefault")
        assertEquals(0, recording.destroyCalls, "Windows 에서는 destroy 를 쓰지 않는다")
        Unit
    }

    @Test
    fun platformDefaultSendsSigtermOutsideWindows() = runBlocking {
        // Windows 가 아니면 ④ = SIGTERM (`Process.destroy`) — Paper 의 셧다운 훅이 월드를 저장한다
        for (os in listOf(Os.LINUX, Os.MAC)) {
            val recording = RecordingProcess(1234)
            assertEquals(InterruptResult.Sent, ProcessInterrupter.platformDefault(os).interrupt(recording), "$os")
            assertEquals(1, recording.destroyCalls, "$os 는 destroy 한 번")
        }
        Unit
    }
}

/** `destroy()` 호출만 세는 가짜 프로세스 (신호 전략만 보는 테스트용). */
private class RecordingProcess(private val pid: Long) : Process() {
    var destroyCalls: Int = 0
        private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun isAlive(): Boolean = false

    override fun pid(): Long = pid

    override fun destroy() {
        destroyCalls++
    }
}
