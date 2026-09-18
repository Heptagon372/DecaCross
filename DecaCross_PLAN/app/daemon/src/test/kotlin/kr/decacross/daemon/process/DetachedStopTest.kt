package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.processCommand
import kr.decacross.daemon.paths.currentOs
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** 콘솔을 잃은 서버 정상 종료 — CLI `stop <이름>` (SCP-I20). 강제 종료는 하지 않는다. */
class DetachedStopTest {
    /** 런처 없이 돌아가는 서버 흉내: stdout 은 버리고, 준비가 끝나면 stdin 을 닫는다. */
    private fun startOrphan(vararg flags: String): Pair<Path, Process> {
        val dir = ServerFixture.createServerDir()
        // 서버를 띄우기 전에 물려받은 "Ctrl+C 무시" 플래그를 끈다 — launchServer 가 하는 일이며,
        // 이걸 빼면 자식이 무시 플래그를 그대로 물려받아 ④ 신호가 먹지 않는다 (실측).
        WindowsConsoleCtrl.clearInheritedCtrlCIgnore()
        val process = ProcessBuilder(ServerFixture.spec(*flags).processCommand())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        assertTrue(ServerFixture.awaitEvent(dir, "done", timeoutMs = 30_000).contains("done"), "가짜 서버가 준비되지 않았다")
        process.outputStream.close()
        val events = ServerFixture.awaitEvent(dir, "stdin-eof", timeoutMs = 10_000)
        assertTrue("stdin-eof" in events, "stdin 이 끝나도 서버는 계속 돈다 (Paper 와 같게): $events")
        assertTrue(process.isAlive)
        return dir to process
    }

    @Test
    fun orphanServerIsStoppedByTheShutdownHook() {
        val (dir, process) = startOrphan()
        try {
            val result = runBlocking { stopDetachedServer(process.toHandle(), currentOs(), wait = 30.seconds) }
            assertEquals(DetachedStopResult.Stopped, result)
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val events = ServerFixture.awaitEvent(dir, "hook-end")
            assertTrue("hook-start" in events, "사건: $events")
            assertTrue("hook-end" in events, "셧다운 훅이 월드를 저장했다: $events")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun slowShutdownIsReportedWithoutKilling() {
        // 훅이 5초 걸리는데 1초만 기다린다 → 강제 종료하지 않고 StillRunning
        val (_, process) = startOrphan("-Dfake.hookMs=5000")
        try {
            val result = runBlocking { stopDetachedServer(process.toHandle(), currentOs(), wait = 1.seconds) }
            assertEquals(DetachedStopResult.StillRunning, result)
            assertTrue(process.isAlive, "★ 강제 종료는 하지 않는다 (월드 손상 위험)")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun signalFailureIsReported() {
        val (_, process) = startOrphan()
        try {
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val result = runBlocking { stopDetachedServer(process.toHandle(), currentOs(), wait = 2.seconds) }
            // 이미 끝난 프로세스: Windows 는 헬퍼가 콘솔을 못 찾고(3), 그 밖에서는 destroy 가 false
            val failed = result as? DetachedStopResult.SignalFailed ?: fail("SignalFailed 를 기대했다: $result")
            assertTrue(failed.detail.isNotBlank())
        } finally {
            process.destroyForcibly()
        }
    }
}
