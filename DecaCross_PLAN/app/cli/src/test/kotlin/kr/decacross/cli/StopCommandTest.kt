package kr.decacross.cli

import com.github.ajalt.clikt.testing.test
import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ServerEntry
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.process.DetachedStopResult
import kr.decacross.daemon.process.PidRecord
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopCommandTest {
    private val root: Path = Path.of("servers").toAbsolutePath()
    private val serverDir: Path = root.resolve("demo")
    private val paths = DecaPaths(root.resolve(".internal"), ServersRoot(root, ServersRootSource.OPTION, null))
    private val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)

    private fun command(
        recording: RecordingIo,
        live: ProcessHandle?,
        sessionLocked: Boolean = false,
        pid: PidRecord? = null,
        launcherAlive: Boolean = false,
        result: DetachedStopResult = DetachedStopResult.Stopped,
        signals: MutableList<Long> = CopyOnWriteArrayList(),
        deleted: MutableList<Path> = CopyOnWriteArrayList(),
        entryOrNull: ServerEntry? = entry,
    ): StopCommand = StopCommand(
        io = recording.io,
        hostOs = Os.LINUX,
        envProvider = { emptyMap() },
        pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
        serverFinder = { _, _ -> entryOrNull },
        liveCheck = { live },
        sessionLockCheck = { sessionLocked },
        pidReader = { pid },
        pidDeleter = { dir -> deleted.add(dir) },
        launcherAlive = { launcherAlive },
        detachedStop = { handle, _ ->
            signals.add(handle.pid())
            result
        },
    )

    @Test
    fun `서버가 없으면 5`() {
        val recording = RecordingIo()
        val result = command(recording, live = null, entryOrNull = null).test("demo")
        assertEquals(ExitCodes.SERVER_STATE, result.statusCode)
        assertTrue(recording.text().contains("서버를 찾을 수 없습니다"), recording.text())
    }

    @Test
    fun `실행 중이 아니면 5`() {
        val recording = RecordingIo()
        val signals = CopyOnWriteArrayList<Long>()
        val result = command(recording, live = null, signals = signals).test("demo")
        assertEquals(ExitCodes.SERVER_STATE, result.statusCode)
        assertTrue(recording.text().contains("실행 중이 아닙니다"), recording.text())
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `DecaCross 밖에서 시작된 서버는 그 창을 안내한다`() {
        val recording = RecordingIo()
        val result = command(recording, live = null, sessionLocked = true).test("demo")
        assertEquals(ExitCodes.SERVER_STATE, result.statusCode)
        assertTrue(recording.text().contains("그 창에서 stop"), recording.text())
    }

    @Test
    fun `콘솔이 있는 CLI 가 관리 중이면 신호를 보내지 않는다`() {
        val recording = RecordingIo()
        val signals = CopyOnWriteArrayList<Long>()
        val result = command(
            recording,
            live = ProcessHandle.current(),
            pid = PidRecord(ProcessHandle.current().pid(), null, null, launcherPid = 4321L),
            launcherAlive = true,
            signals = signals,
        ).test("demo")
        assertEquals(ExitCodes.SERVER_STATE, result.statusCode)
        assertTrue(recording.text().contains("그 콘솔에서 stop"), recording.text())
        assertTrue(signals.isEmpty(), "관리 중인 서버에는 신호를 보내지 않는다")
    }

    @Test
    fun `정상 종료되면 0 이고 pid 파일을 지운다`() {
        val recording = RecordingIo()
        val deleted = CopyOnWriteArrayList<Path>()
        val signals = CopyOnWriteArrayList<Long>()
        val result = command(
            recording,
            live = ProcessHandle.current(),
            pid = PidRecord(ProcessHandle.current().pid(), null, null, launcherPid = 4321L),
            launcherAlive = false,
            result = DetachedStopResult.Stopped,
            signals = signals,
            deleted = deleted,
        ).test("demo")
        assertEquals(ExitCodes.OK, result.statusCode)
        assertEquals(listOf(ProcessHandle.current().pid()), signals.toList())
        assertEquals(listOf(serverDir), deleted.toList())
        assertTrue(recording.text().contains("[완료] 종료했습니다"), recording.text())
    }

    @Test
    fun `제한 시간 안에 끝나지 않으면 6 이고 강제 종료하지 않는다`() {
        val recording = RecordingIo()
        val deleted = CopyOnWriteArrayList<Path>()
        val result = command(
            recording,
            live = ProcessHandle.current(),
            pid = null,
            result = DetachedStopResult.StillRunning,
            deleted = deleted,
        ).test("demo")
        assertEquals(ExitCodes.SERVER_FAILED, result.statusCode)
        assertTrue(recording.text().contains("강제 종료는 하지 않습니다"), recording.text())
        assertTrue(deleted.isEmpty(), "끝나지 않았으면 pid 기록을 지우지 않는다")
    }

    @Test
    fun `신호를 못 보내면 1`() {
        val recording = RecordingIo()
        val result = command(
            recording,
            live = ProcessHandle.current(),
            result = DetachedStopResult.SignalFailed("헬퍼 종료 3"),
        ).test("demo")
        assertEquals(ExitCodes.UNEXPECTED, result.statusCode)
        assertTrue(recording.text().contains("헬퍼 종료 3"), recording.text())
        // 신호를 보내기 전 줄이 "보냈습니다" 라고 하면 바로 아래 실패 줄과 앞뒤가 맞지 않는다
        assertFalse(recording.text().contains("종료 신호를 보냈습니다"), recording.text())
        assertTrue(recording.text().contains("종료 신호를 보냅니다"), recording.text())
    }
}
