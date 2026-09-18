package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [runShutdownProtocol] 단계 순서 (DESIGN2 §2.9, 불변식 13 · SCP-I19). */
class ShutdownProtocolTest {
    private val saved = Regex("Saved the game")
    private val fast = ShutdownTimeouts(
        saveAll = 120.milliseconds,
        stop = 120.milliseconds,
        terminate = 120.milliseconds,
        kill = 120.milliseconds,
    )

    private fun run(console: FakeConsole, timeouts: ShutdownTimeouts = fast): Pair<ShutdownReport, List<ShutdownStep>> {
        val reported = CopyOnWriteArrayList<ShutdownStep>()
        val report = runBlocking { runShutdownProtocol(console, saved, timeouts) { reported.add(it) } }
        // onStep 은 report.steps 와 같은 순서·같은 내용이어야 한다 (CLI 가 그대로 출력한다)
        assertEquals(report.steps, reported.toList())
        return report to reported.toList()
    }

    @Test
    fun alreadyExited() {
        val console = FakeConsole().apply { exitNow() }
        val (report, _) = run(console)
        assertEquals(listOf(ShutdownStep.AlreadyExited), report.steps)
        assertEquals(ShutdownOutcome.ALREADY_EXITED, report.outcome)
        assertEquals(0, report.exitCode)
        assertTrue(console.sent.isEmpty(), "끝난 프로세스에는 아무것도 쓰지 않는다")
    }

    @Test
    fun savedMatchedThenStopThenExit() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 5.milliseconds)
        val (report, _) = run(console)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.MATCHED),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(0),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.STOPPED, report.outcome)
        assertEquals(listOf("save-all", "stop"), console.sent.map { it.line })
        assertEquals(saved, console.sent.first().expect)
        assertFalse(console.killed)
    }

    @Test
    fun savedTimedOutStillStops() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.seconds)
        val (report, _) = run(console)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.TIMED_OUT),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(0),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.STOPPED, report.outcome)
    }

    @Test
    fun exitDuringSaveAllStopsWithoutSendingStop() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.SAVE_ALL, savedReplyAfter = 5.milliseconds)
        val (report, _) = run(console)
        assertEquals(listOf(ShutdownStep.SaveAll(SendResult.MATCHED)), report.steps)
        assertEquals(ShutdownOutcome.STOPPED, report.outcome)
        assertEquals(0, report.exitCode)
        assertEquals(listOf("save-all"), console.sent.map { it.line })
    }

    @Test
    fun stdinClosedSkipsToInterrupt() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.INTERRUPT)
        console.stdinClosedFor.add("save-all")
        val (report, _) = run(console)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.STDIN_CLOSED),
                ShutdownStep.Interrupt(InterruptResult.Sent),
                ShutdownStep.InterruptWait(0),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.INTERRUPTED, report.outcome)
        assertEquals(listOf("save-all"), console.sent.map { it.line }, "쓸 수 없으면 stop 도 쓰지 않는다 (SCP-I19)")
    }

    @Test
    fun stdinClosedOnStopAlsoSkipsTheStopWait() {
        // ① 은 썼지만 ② 를 쓰지 못한 경우: 쓰지 못한 명령의 결과를 기다리지 않는다 (SCP-I19)
        val timeouts = fast.copy(stop = 9.seconds)
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.INTERRUPT, savedReplyAfter = 5.milliseconds)
        console.stdinClosedFor.add("stop")
        val (report, _) = run(console, timeouts)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.MATCHED),
                ShutdownStep.Stop(SendResult.STDIN_CLOSED),
                ShutdownStep.Interrupt(InterruptResult.Sent),
                ShutdownStep.InterruptWait(0),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.INTERRUPTED, report.outcome)
        assertEquals(listOf(timeouts.terminate), console.awaited, "③ 의 stop 대기는 건너뛴다")
        assertFalse(console.killed)
    }

    @Test
    fun ignoredStopGoesToInterrupt() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.INTERRUPT, savedReplyAfter = 5.milliseconds)
        val (report, _) = run(console)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.MATCHED),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(null),
                ShutdownStep.Interrupt(InterruptResult.Sent),
                ShutdownStep.InterruptWait(0),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.INTERRUPTED, report.outcome)
    }

    @Test
    fun ignoredInterruptIsKilled() {
        val console = FakeConsole(
            exitsAfter = FakeConsole.Stage.KILL,
            savedReplyAfter = 5.milliseconds,
            interruptResult = InterruptResult.Unsupported("이 환경에서는 못 보냄"),
            exitCode = 1,
        )
        val (report, _) = run(console)
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.MATCHED),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(null),
                ShutdownStep.Interrupt(InterruptResult.Unsupported("이 환경에서는 못 보냄")),
                ShutdownStep.InterruptWait(null),
                ShutdownStep.Killed(1),
            ),
            report.steps,
        )
        assertEquals(ShutdownOutcome.KILLED, report.outcome)
        assertTrue(console.killed)
    }

    @Test
    fun killUnconfirmed() {
        val console = FakeConsole(exitsAfter = null, savedReplyAfter = 5.milliseconds)
        val (report, _) = run(console)
        assertEquals(ShutdownStep.Killed(null), report.steps.last())
        assertEquals(ShutdownOutcome.KILL_UNCONFIRMED, report.outcome)
        assertNull(report.exitCode)
        assertTrue(console.killed)
    }

    @Test
    fun urgentWritesSaveAllAndStopWithoutWaiting() {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.seconds)
        val started = System.nanoTime()
        val (report, _) = run(console, ShutdownTimeouts.URGENT)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.SENT),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(0),
            ),
            report.steps,
        )
        assertNull(console.sent.first().expect, "URGENT 는 'Saved the game' 을 기다리지 않는다")
        assertTrue(elapsedMs < 1_000, "기다리지 않고 바로 stop 까지 간다 (${elapsedMs}ms)")
    }
}
