package kr.decacross.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.decacross.cli.testkit.FakeHooks
import kr.decacross.cli.testkit.FakeServerProcess
import kr.decacross.cli.testkit.FakeShutdownControl
import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.PipedIo
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.process.InterruptResult
import kr.decacross.daemon.process.LaunchResult
import kr.decacross.daemon.process.ReadyState
import kr.decacross.daemon.process.SendResult
import kr.decacross.daemon.process.ServerProcess
import kr.decacross.daemon.process.ShutdownOutcome
import kr.decacross.daemon.process.ShutdownReport
import kr.decacross.daemon.process.ShutdownStep
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerRunnerTest {
    private val serverDir: Path = Path.of("servers", "demo").toAbsolutePath()

    private fun runner(
        io: ConsoleIo,
        process: ServerProcess,
        control: FakeShutdownControl,
        hooks: FakeHooks = FakeHooks(),
        live: ProcessHandle? = null,
        sessionLocked: Boolean = false,
        pidWritten: MutableList<Path> = CopyOnWriteArrayList(),
        pidDeleted: MutableList<Path> = CopyOnWriteArrayList(),
        launcher: String? = null,
        pidWriteResult: (Path) -> LayoutIoResult? = { dir -> LayoutIoResult.Ok(dir) },
    ): ServerRunner = ServerRunner(
        io = io,
        launcher = { _, _, _, _ -> LaunchResult.Started(process) },
        liveCheck = { live },
        sessionLockCheck = { sessionLocked },
        pidWriter = { dir, _ ->
            pidWritten.add(dir)
            pidWriteResult(dir)
        },
        pidDeleter = { dir -> pidDeleted.add(dir) },
        coordinatorFactory = { _, _, _, _ -> control },
        launcherProperty = { launcher },
        hooks = hooks,
    )

    @Test
    fun `입력 EOF 는 준비를 기다린 뒤 종료 프로토콜을 한 번 시작한다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl()
        val written = CopyOnWriteArrayList<Path>()
        val deleted = CopyOnWriteArrayList<Path>()
        val runner = runner(recording.io, process, control, pidWritten = written, pidDeleted = deleted)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(0)
        assertEquals(ExitCodes.OK, withTimeout(10.seconds) { job.await() })
        assertEquals("start", control.startedByKind)
        assertEquals(0, control.calls.count { it == "urgent" })
        assertEquals(listOf(serverDir), written.toList())
        assertEquals(listOf(serverDir), deleted.toList())
        assertTrue(recording.text().contains("콘솔 입력이 끝났습니다(EOF)"), recording.text())
        assertTrue(recording.text().contains("[완료] 서버 준비 완료"), recording.text())
    }

    @Test
    fun `EOF 뒤에도 준비가 될 때까지는 종료 프로토콜을 시작하지 않는다`() = runBlocking {
        val recording = RecordingIo("")
        val gate = CompletableDeferred<ReadyState>()
        val process = FakeServerProcess(readySignal = gate)
        val control = FakeShutdownControl()
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) {
            while (!recording.text().contains("콘솔 입력이 끝났습니다(EOF)")) delay(5)
        }
        delay(50)
        assertNull(control.startedByKind, "준비 전에는 프로토콜을 시작하지 않는다")
        gate.complete(ReadyState.READY)
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(0)
        assertEquals(ExitCodes.OK, withTimeout(10.seconds) { job.await() })
    }

    @Test
    fun `입력한 줄은 서버 표준입력으로 전달된다`() = runBlocking {
        val recording = RecordingIo("say 안녕\nlist\n")
        val process = FakeServerProcess()
        val control = FakeShutdownControl()
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(0)
        withTimeout(10.seconds) { job.await() }
        assertEquals(listOf("say 안녕", "list"), process.sent.toList())
    }

    @Test
    fun `이미 실행 중이면 기동하지 않고 5 로 끝낸다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val runner = runner(recording.io, process, FakeShutdownControl(), live = ProcessHandle.current())
        assertEquals(ExitCodes.SERVER_STATE, runner.run(serverDir, Fixtures.spec, Fixtures.patterns))
        assertTrue(recording.text().contains("이미 실행 중"), recording.text())
    }

    @Test
    fun `세션 잠금이 잡혀 있으면 5 로 끝낸다`() = runBlocking {
        val recording = RecordingIo("")
        val runner = runner(recording.io, FakeServerProcess(), FakeShutdownControl(), sessionLocked = true)
        assertEquals(ExitCodes.SERVER_STATE, runner.run(serverDir, Fixtures.spec, Fixtures.patterns))
        assertTrue(recording.text().contains("DecaCross 밖에서 시작됨"), recording.text())
    }

    @Test
    fun `기동 실패는 5 로 끝낸다`() = runBlocking {
        val recording = RecordingIo("")
        val runner = ServerRunner(
            io = recording.io,
            launcher = { _, _, _, _ -> LaunchResult.Failed("java 실행 파일 없음") },
            liveCheck = { null },
            sessionLockCheck = { false },
            pidWriter = { _, _ -> error("기동 실패면 pid 를 쓰지 않는다") },
            pidDeleter = { },
            coordinatorFactory = { _, _, _, _ -> FakeShutdownControl() },
            launcherProperty = { null },
            hooks = FakeHooks(),
        )
        assertEquals(ExitCodes.SERVER_STATE, runner.run(serverDir, Fixtures.spec, Fixtures.patterns))
        assertTrue(recording.text().contains("기동하지 못했습니다"), recording.text())
    }

    @Test
    fun `서버가 0 아닌 코드로 끝나면 6 이다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl()
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(1)
        assertEquals(ExitCodes.SERVER_FAILED, withTimeout(10.seconds) { job.await() })
    }

    @Test
    fun `Gradle run 에서만 강제 종료 안내를 띄운다`() = runBlocking {
        for (launcher in listOf(null, GRADLE_RUN_LAUNCHER)) {
            val recording = RecordingIo("")
            val process = FakeServerProcess()
            val control = FakeShutdownControl()
            val runner = runner(recording.io, process, control, launcher = launcher)
            val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
            withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
            process.finish(0)
            withTimeout(10.seconds) { job.await() }
            val text = recording.text()
            if (launcher == GRADLE_RUN_LAUNCHER) {
                assertTrue(text.contains("Gradle run 에서 Ctrl+C"), text)
            } else {
                assertTrue(text.contains("종료: stop 입력 또는 Ctrl+C"), text)
                assertTrue(!text.contains("Gradle run"), text)
            }
        }
    }

    @Test
    fun `셧다운 훅은 급한 종료를 시작하고 결과를 기다린다`() = runBlocking {
        PipedIo().use { piped ->
            val process = FakeServerProcess()
            // ★ 결과를 손으로 내놓는다 — 바로 완성해 주면 `startUrgent()` 만 부르고 마는 구현과 구분되지 않는다
            val control = FakeShutdownControl(manualRelease = true)
            val hooks = FakeHooks()
            val runner = runner(piped.io, process, control, hooks = hooks)
            val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
            withTimeout(10.seconds) { while (hooks.added.isEmpty()) delay(5) }
            val hook = hooks.added.single()
            hook.start()
            withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
            delay(200)
            assertTrue(hook.isAlive, "종료 프로토콜 결과를 기다리지 않고 훅이 끝났다 (프로토콜 도중에 JVM 이 죽는다)")
            control.release()
            hook.join(10_000)
            assertTrue(!hook.isAlive, "결과가 나왔는데도 훅이 끝나지 않았다")
            assertTrue(control.calls.contains("urgent"), control.calls.toList().toString())
            assertEquals("urgent", control.startedByKind)
            process.finish(0)
            withTimeout(10.seconds) { job.await() }
            assertEquals(listOf(hook), hooks.removed.toList())
        }
    }

    @Test
    fun `pid 기록에 실패하면 경고를 띄우고 계속 실행한다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl()
        val runner = runner(
            recording.io,
            process,
            control,
            pidWriteResult = { dir -> LayoutIoResult.Failed(dir, "액세스가 거부되었습니다") },
        )
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(0)
        assertEquals(ExitCodes.OK, withTimeout(10.seconds) { job.await() }, "서버는 이미 떠 있으니 멈추지 않는다")
        val text = recording.text()
        assertTrue(text.contains("실행 기록"), text)
        assertTrue(text.contains("액세스가 거부되었습니다"), text)
        assertTrue(text.contains("decacross stop"), text)
    }

    @Test
    fun `pid 기록이 성공하면 경고를 띄우지 않는다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl()
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(0)
        withTimeout(10.seconds) { job.await() }
        assertTrue(!recording.text().contains("실행 기록"), recording.text())
    }

    @Test
    fun `종료 프로토콜이 예외로 끝나도 pid 기록과 셧다운 훅을 정리한다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl(failure = IllegalStateException("종료 프로토콜 실패"))
        val hooks = FakeHooks()
        val deleted = CopyOnWriteArrayList<Path>()
        val runner = runner(recording.io, process, control, hooks = hooks, pidDeleted = deleted)
        val thrown =
            runCatching { withTimeout(10.seconds) { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) } }
                .exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "원인 예외가 그대로 나와야 한다: $thrown")
        assertEquals(listOf(serverDir), deleted.toList(), "낡은 server.pid 를 남기면 stop 이 엉뚱한 pid 를 본다")
        assertEquals(hooks.added.toList(), hooks.removed.toList(), "죽은 서버의 셧다운 훅이 남으면 안 된다")
    }

    @Test
    fun `강제 종료로 끝나면 월드 손상 경고를 띄운다`() = runBlocking {
        val recording = RecordingIo("")
        val process = FakeServerProcess()
        val control = FakeShutdownControl(ShutdownReport(listOf(ShutdownStep.Killed(137)), 137, ShutdownOutcome.KILLED))
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        withTimeout(10.seconds) { while (!control.isStarted) delay(5) }
        process.finish(137)
        assertEquals(ExitCodes.SERVER_FAILED, withTimeout(10.seconds) { job.await() })
        assertTrue(recording.text().contains("강제 종료했습니다"), recording.text())
    }

    @Test
    fun `표준입력이 닫히면 더 보내지 않는다`() = runBlocking {
        val recording = RecordingIo("a\nb\n")
        val process = FakeServerProcess()
        process.stdinClosed.set(true)
        val control = FakeShutdownControl()
        val runner = runner(recording.io, process, control)
        val job = async { runner.run(serverDir, Fixtures.spec, Fixtures.patterns) }
        // 프로토콜은 시작되지 않는다 (입력 소비가 STDIN_CLOSED 에서 멈춘다) — 서버 종료로 끝낸다
        delay(100)
        assertNull(control.startedByKind)
        process.finish(0)
        assertEquals(ExitCodes.OK, withTimeout(10.seconds) { job.await() })
        assertTrue(process.sent.isEmpty())
    }

    @Test
    fun `종료 단계 줄은 서로 다르고 MS949 로 인코딩된다`() {
        assumeTrue(Charset.isSupported("MS949"), "MS949 를 지원하지 않는 JVM")
        val encoder = Charset.forName("MS949").newEncoder()
        val lines = listOf(
            ShutdownStep.AlreadyExited,
            ShutdownStep.SaveAll(SendResult.MATCHED),
            ShutdownStep.SaveAll(SendResult.STDIN_CLOSED),
            ShutdownStep.Stop(SendResult.SENT),
            ShutdownStep.StopWait(null),
            ShutdownStep.StopWait(0),
            ShutdownStep.Interrupt(InterruptResult.Sent),
            ShutdownStep.Interrupt(InterruptResult.Unsupported("이 환경에서는 불가")),
            ShutdownStep.Interrupt(InterruptResult.Failed("헬퍼 종료 3")),
            ShutdownStep.InterruptWait(143),
            ShutdownStep.InterruptWait(null),
            ShutdownStep.Killed(null),
            ShutdownStep.Killed(137),
        ).map { shutdownStepLine(it) }
        assertTrue(lines.all { it.isNotBlank() })
        assertEquals(lines.size, lines.toSet().size, "단계 줄이 서로 달라야 한다: $lines")
        for (line in lines) assertTrue(encoder.canEncode(line), "MS949 로 인코딩할 수 없는 줄: $line")
    }
}
