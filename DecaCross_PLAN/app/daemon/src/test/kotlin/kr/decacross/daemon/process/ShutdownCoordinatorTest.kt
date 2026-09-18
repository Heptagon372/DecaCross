package kr.decacross.daemon.process

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [ShutdownCoordinator] — 프로토콜은 최대 한 번 (D-I43, critique W4/W5). */
class ShutdownCoordinatorTest {
    private val saved = Regex("Saved the game")
    private val fast = ShutdownTimeouts(
        saveAll = 2.seconds,
        stop = 2.seconds,
        terminate = 2.seconds,
        kill = 2.seconds,
    )

    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    @Test
    fun twoConcurrentStartsRunOneProtocol() = runBlocking {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 50.milliseconds)
        val scope = scope()
        val coordinator = ShutdownCoordinator(console, saved, scope, fast)
        assertFalse(coordinator.isStarted)
        val first = async { coordinator.start().await() }
        val second = async { coordinator.start().await() }
        val a = first.await()
        val b = second.await()
        assertSame(a, b, "같은 실행의 같은 보고서")
        assertEquals(1, console.saveAllCount)
        assertEquals(ShutdownOutcome.STOPPED, a.outcome)
        assertTrue(coordinator.isStarted)
        scope.cancel()
    }

    @Test
    fun urgentCutsTheSaveAllWaitAndGoesToStop() = runBlocking {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.seconds)
        val scope = scope()
        val coordinator = ShutdownCoordinator(console, saved, scope, ShutdownTimeouts())
        val deferred = coordinator.start()
        withTimeout(5.seconds) {
            while (console.sent.isEmpty()) delay(5)
        }
        val started = System.nanoTime()
        assertSame(deferred, coordinator.startUrgent(), "이미 시작한 실행을 그대로 쓴다")
        val report = withTimeout(5.seconds) { deferred.await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.TIMED_OUT),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(0),
            ),
            report.steps,
        )
        assertTrue(elapsedMs < 1_000, "30초 대기를 끊고 곧바로 stop 을 쓴다 (${elapsedMs}ms)")
        assertEquals(1, console.saveAllCount)
        scope.cancel()
    }

    @Test
    fun urgentRightAfterStartStillWritesSaveAllBeforeStop() {
        // start() 직후 셧다운 훅이 startUrgent() 를 부르는 경쟁 (WP-CLI §2.9 (5)+(6)).
        // 수동 디스패처로 "훅이 먼저 이긴" 순간을 고정한다: 프로토콜 코루틴은 아직 한 줄도 돌지 않았는데 urgent 가 완료됐다.
        // ① 대기를 끊는 것은 맞지만 save-all 쓰기 자체를 건너뛰면 안 된다 (불변식 13 "순서 생략 금지").
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.seconds)
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val coordinator = ShutdownCoordinator(console, saved, scope, ShutdownTimeouts())
        val deferred = coordinator.start()
        assertSame(deferred, coordinator.startUrgent(), "이미 시작한 실행을 그대로 쓴다")
        val report = runBlocking { withTimeout(5.seconds) { dispatcher.drain(deferred) } }
        assertEquals(listOf("save-all", "stop"), console.sent.map { it.line }, "급해도 save-all 을 먼저 쓴다")
        assertEquals(1, console.saveAllCount)
        assertTrue(report.steps.first() is ShutdownStep.SaveAll, "① 단계는 언제나 보고된다: ${report.steps}")
        assertEquals(ShutdownStep.Stop(SendResult.SENT), report.steps[1])
        assertEquals(ShutdownOutcome.STOPPED, report.outcome)
        scope.cancel()
    }

    @Test
    fun urgentBeforeStartSendsBothImmediately() = runBlocking {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.seconds)
        val scope = scope()
        val coordinator = ShutdownCoordinator(console, saved, scope, ShutdownTimeouts())
        val started = System.nanoTime()
        val report = withTimeout(5.seconds) { coordinator.startUrgent().await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(
            listOf(
                ShutdownStep.SaveAll(SendResult.SENT),
                ShutdownStep.Stop(SendResult.SENT),
                ShutdownStep.StopWait(0),
            ),
            report.steps,
        )
        assertTrue(elapsedMs < 1_000, "URGENT 는 기다리지 않는다 (${elapsedMs}ms)")
        scope.cancel()
    }

    @Test
    fun lateCallerGetsTheSameFinishedReport() = runBlocking {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 10.milliseconds)
        val scope = scope()
        val coordinator = ShutdownCoordinator(console, saved, scope, fast)
        val report = coordinator.start().await()
        assertTrue(report.steps.isNotEmpty(), "보고서 없이 돌아오지 않는다")
        val again = coordinator.startUrgent().await()
        assertSame(report, again)
        assertEquals(1, console.saveAllCount)
        scope.cancel()
    }

    @Test
    fun cancellingTheScopeDoesNotStopTheProtocol() = runBlocking {
        val console = FakeConsole(exitsAfter = FakeConsole.Stage.STOP, savedReplyAfter = 300.milliseconds)
        val scope = scope()
        val coordinator = ShutdownCoordinator(console, saved, scope, fast)
        val deferred = coordinator.start()
        delay(30)
        scope.cancel()
        val report = withTimeout(5.seconds) { deferred.await() }
        assertEquals(ShutdownOutcome.STOPPED, report.outcome)
        assertEquals(1, console.saveAllCount)
        assertEquals(listOf("save-all", "stop"), console.sent.map { it.line })
    }
}

/**
 * 넘겨받은 작업을 [drain] 을 부를 때만 실행하는 디스패처. 훅(`startUrgent`)과 EOF 경로(`start`)의 순서를 고정한다
 * — 진짜 디스패처로는 프로토콜 코루틴이 먼저 돌아 버려 경쟁 순간을 재현할 수 없다.
 */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }

    /** [deferred] 가 끝날 때까지 큐를 이 스레드에서 돌린다. */
    suspend fun drain(deferred: Deferred<ShutdownReport>): ShutdownReport {
        while (!deferred.isCompleted) {
            while (true) {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                next.run()
            }
            if (!deferred.isCompleted) delay(1)
        }
        return deferred.await()
    }
}
