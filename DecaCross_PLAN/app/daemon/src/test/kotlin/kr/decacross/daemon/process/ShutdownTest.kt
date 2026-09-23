package kr.decacross.daemon.process

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실제 JVM 프로세스([FakeServer])로 종료 프로토콜 순서를 검증한다. */
class ShutdownTest {
    private val javaExe = Paths.get(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
    private val classpath = System.getProperty("java.class.path")

    // ServerProcess.start 는 뒤에 `-jar dummy.jar nogui` 를 붙인다. FakeServer 는 그 인자를 무시한다.

    @Test
    fun graceful_saveAll_then_stop_inOrder() = runBlocking {
        coroutineScope {
            // FakeServer main 이 "-jar dummy.jar nogui" 를 인자로 받지만 무시한다
            val sp = ServerProcess.start(this, Files.createTempDirectory("fake"), javaExe, listOf("-cp", classpath, "kr.decacross.daemon.process.FakeServer"), "dummy.jar")
            withTimeout(20_000) { sp.lines.first { it.contains("Done (") } }
            val phases = mutableListOf<ShutdownPhase>()
            val result = withTimeout(30_000) { sp.shutdown(saveTimeoutSec = 5, stopTimeoutSec = 10) { phases += it } }
            assertIs<ShutdownResult.Graceful>(result)
            assertEquals(
                listOf(ShutdownPhase.SAVE_ALL, ShutdownPhase.STOP, ShutdownPhase.WAIT_STOP, ShutdownPhase.EXITED),
                phases,
                "순서 생략 금지: save-all → stop → 대기",
            )
        }
    }

    @Test
    fun ignoresStop_thenTerminated() = runBlocking {
        coroutineScope {
            val sp = ServerProcess.start(this, Files.createTempDirectory("fake"), javaExe, listOf("-cp", classpath, "kr.decacross.daemon.process.FakeServer", "ignore-stop"), "dummy.jar")
            withTimeout(20_000) { sp.lines.first { it.contains("Done (") } }
            val phases = mutableListOf<ShutdownPhase>()
            val result = withTimeout(30_000) { sp.shutdown(saveTimeoutSec = 2, stopTimeoutSec = 2, termTimeoutSec = 5) { phases += it } }
            assertIs<ShutdownResult.Terminated>(result)
            assertEquals(ShutdownPhase.TERMINATE, phases[phases.indexOf(ShutdownPhase.WAIT_STOP) + 1], "stop 무응답 → TERMINATE 로 넘어간다")
        }
    }
}
