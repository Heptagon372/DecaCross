package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import kr.decacross.compat.model.Os
import kr.decacross.daemon.paths.currentOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 실제 가짜 서버로 도는 종료 프로토콜 (DESIGN2 §2.9 · §4.7). */
class ServerProtocolTest {
    private val onWindows = currentOs() == Os.WINDOWS

    private fun start(vararg flags: String, interrupter: ProcessInterrupter? = null): Pair<java.nio.file.Path, ServerProcess> {
        val dir = ServerFixture.createServerDir()
        val result = if (interrupter == null) {
            launchServer(dir, ServerFixture.spec(*flags), ServerFixture.patterns, outputLine = { })
        } else {
            launchServer(dir, ServerFixture.spec(*flags), ServerFixture.patterns, outputLine = { }, interrupter = interrupter)
        }
        val process = (result as? LaunchResult.Started)?.process ?: fail("기동 실패: $result")
        runBlocking { assertEquals(ReadyState.READY, process.awaitReady(30.seconds), "준비 완료를 기다린다") }
        return dir to process
    }

    @Test
    fun normalRunStopsWithSaveAllThenStop() {
        val (dir, process) = start()
        try {
            val report = runBlocking {
                runShutdownProtocol(
                    process,
                    ServerFixture.patterns.saved,
                    ShutdownTimeouts(saveAll = 10.seconds, stop = 20.seconds, terminate = 10.seconds, kill = 5.seconds),
                )
            }
            assertEquals(ShutdownOutcome.STOPPED, report.outcome, "단계: ${report.steps}")
            assertEquals(0, report.exitCode)
            assertEquals(
                listOf(
                    ShutdownStep.SaveAll(SendResult.MATCHED),
                    ShutdownStep.Stop(SendResult.SENT),
                    ShutdownStep.StopWait(0),
                ),
                report.steps,
            )
            val events = ServerFixture.awaitEvent(dir, "stop-exit")
            assertTrue("stdin:save-all" in events, "사건: $events")
            assertTrue("stdin:stop" in events, "사건: $events")
            assertTrue("stop-exit" in events, "사건: $events")
        } finally {
            process.kill()
        }
    }

    @Test
    fun serverIgnoringStopIsInterruptedGracefully() {
        // fake.ignoreStop: stop 을 무시한다 → ④ 정상 종료 신호로 셧다운 훅(월드 저장)을 돌린다
        val (dir, process) = start("-Dfake.ignoreStop=true")
        try {
            val report = runBlocking {
                runShutdownProtocol(
                    process,
                    ServerFixture.patterns.saved,
                    ShutdownTimeouts(saveAll = 5.seconds, stop = 2.seconds, terminate = 20.seconds, kill = 5.seconds),
                )
            }
            assertEquals(ShutdownOutcome.INTERRUPTED, report.outcome, "단계: ${report.steps}")
            assertEquals(
                listOf(
                    ShutdownStep.SaveAll(SendResult.MATCHED),
                    ShutdownStep.Stop(SendResult.SENT),
                    ShutdownStep.StopWait(null),
                    ShutdownStep.Interrupt(InterruptResult.Sent),
                ),
                report.steps.dropLast(1),
            )
            // Windows: CTRL_C → 130, Unix: SIGTERM → 143 (둘 다 JVM 기본 종료 코드)
            assertEquals(if (onWindows) 130 else 143, report.exitCode)
            val events = ServerFixture.awaitEvent(dir, "hook-end")
            assertTrue("hook-start" in events, "셧다운 훅이 돌았다: $events")
            assertTrue("hook-end" in events, "월드 저장(훅)이 끝났다: $events")
        } finally {
            process.kill()
        }
    }

    @Test
    fun unsupportedInterrupterEndsInKill() {
        val (dir, process) = start(
            "-Dfake.ignoreStop=true",
            interrupter = ProcessInterrupter { InterruptResult.Unsupported("이 환경에서는 못 보냄") },
        )
        try {
            val report = runBlocking {
                runShutdownProtocol(
                    process,
                    ServerFixture.patterns.saved,
                    ShutdownTimeouts(
                        saveAll = 5.seconds,
                        stop = 1.seconds,
                        terminate = 300.milliseconds,
                        kill = 10.seconds,
                    ),
                )
            }
            assertEquals(ShutdownOutcome.KILLED, report.outcome, "단계: ${report.steps}")
            assertNotNull(report.exitCode)
            assertEquals(ShutdownStep.Interrupt(InterruptResult.Unsupported("이 환경에서는 못 보냄")), report.steps[3])
            assertTrue(ServerFixture.awaitEvent(dir, "stdin:stop", timeoutMs = 2_000).contains("stdin:stop"))
            runBlocking { assertEquals(false, process.isAlive) }
        } finally {
            process.kill()
        }
    }
}
