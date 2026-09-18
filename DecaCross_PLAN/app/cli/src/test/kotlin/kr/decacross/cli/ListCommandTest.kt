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
import kr.decacross.daemon.process.PidRecord
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListCommandTest {
    private val root: Path = Path.of("servers").toAbsolutePath()
    private val paths = DecaPaths(root.resolve(".internal"), ServersRoot(root, ServersRootSource.OPTION, "안내 한 줄"))

    private fun entry(name: String, problem: String? = null) =
        ServerEntry(name, root.resolve(name), Fixtures.manifest(name), Fixtures.spec, problem)

    private fun command(
        recording: RecordingIo,
        entries: List<ServerEntry>,
        live: ProcessHandle? = null,
        pid: PidRecord? = null,
        launcherAlive: Boolean = false,
    ): ListCommand = ListCommand(
        io = recording.io,
        hostOs = Os.LINUX,
        envProvider = { emptyMap() },
        pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
        listProvider = { entries },
        liveCheck = { live },
        pidReader = { pid },
        launcherAlive = { launcherAlive },
    )

    @Test
    fun `서버가 없으면 만들라고 안내한다`() {
        val recording = RecordingIo()
        assertEquals(0, command(recording, emptyList()).test("").statusCode)
        val text = recording.text()
        assertTrue(text.contains("서버 루트: $root"), text)
        assertTrue(text.contains("안내 한 줄"), text)
        assertTrue(text.contains("서버가 없습니다"), text)
    }

    @Test
    fun `정지 상태와 실행 중과 콘솔 없이 실행 중을 구분한다`() {
        val stopped = RecordingIo()
        command(stopped, listOf(entry("demo"))).test("")
        assertTrue(stopped.text().contains("| 정지 |"), stopped.text())

        val running = RecordingIo()
        command(
            running,
            listOf(entry("demo")),
            live = ProcessHandle.current(),
            pid = PidRecord(1L, null, null, launcherPid = 2L),
            launcherAlive = true,
        ).test("")
        assertTrue(running.text().contains("실행 중 pid"), running.text())

        val orphan = RecordingIo()
        command(
            orphan,
            listOf(entry("demo")),
            live = ProcessHandle.current(),
            pid = PidRecord(1L, null, null, launcherPid = 2L),
            launcherAlive = false,
        ).test("")
        assertTrue(orphan.text().contains("콘솔 없이 실행 중 pid"), orphan.text())
        assertTrue(orphan.text().contains("(stop demo)"), orphan.text())
    }

    @Test
    fun `문제가 있는 서버는 이유를 보여 준다`() {
        val recording = RecordingIo()
        command(recording, listOf(entry("demo", problem = "launch.json 손상"))).test("")
        assertTrue(recording.text().contains("문제: launch.json 손상"), recording.text())
    }
}
