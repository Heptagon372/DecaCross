package kr.decacross.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kr.decacross.cli.testkit.FakeHooks
import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.Difficulty
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaRequirement
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliParsingTest {
    /** 파이프라인을 돌리지 않고 요청만 붙잡는 설치 환경. */
    private class CapturingEnvironment(
        private val requests: MutableList<InstallRequest>,
        private val serversRoot: Path,
    ) : InstallEnvironment {
        var closed: Boolean = false
            private set

        override fun flow(
            db: CompatDb,
            javaLocator: JavaLocator,
            interaction: InstallInteraction,
            profiles: LaunchProfiles,
        ): InstallFlow = InstallFlow { request ->
            requests.add(request)
            readyFlow(request, serversRoot)
        }

        override fun close() {
            closed = true
        }

        private fun readyFlow(request: InstallRequest, root: Path): Flow<InstallEvent> = flowOf(
            InstallEvent.Ready(
                InstalledServer(
                    request.serverName,
                    root.resolve(request.serverName),
                    Fixtures.spec,
                    Fixtures.manifest(request.serverName),
                ),
            ),
        )
    }

    private fun createCommand(
        requests: MutableList<InstallRequest>,
        recording: RecordingIo,
        ran: MutableList<Path> = CopyOnWriteArrayList(),
    ): CreateCommand {
        val tmp = Files.createTempDirectory("dcx-cli-create")
        tmp.toFile().deleteOnExit()
        val root = tmp.resolve("servers")
        val paths = DecaPaths(tmp.resolve("internal"), ServersRoot(root, ServersRootSource.OPTION, null))
        return CreateCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            environmentFactory = { _, _ -> CapturingEnvironment(requests, root) },
            javaLocatorFactory = { _, _, _ ->
                JavaLocator { requirement: JavaRequirement, _: Path? ->
                    JavaLocateResult.NotFound(requirement, emptyList())
                }
            },
            runner = { dir, _, _ ->
                ran.add(dir)
                ExitCodes.OK
            },
            hooks = FakeHooks(),
        )
    }

    @Test
    fun `명세 12 의 명령줄이 그대로 파싱되고 기본 이름이 붙는다`() {
        val requests = CopyOnWriteArrayList<InstallRequest>()
        val recording = RecordingIo()
        val result = createCommand(requests, recording)
            .test(listOf("--mc", "1.21.8", "--core", "paper", "--ram", "4G"))
        assertEquals(ExitCodes.OK, result.statusCode, recording.text())
        val request = assertNotNull(requests.firstOrNull())
        assertEquals("paper-1.21.8", request.serverName)
        assertEquals("1.21.8", request.mcLabel)
        assertEquals(CoreKey.PAPER, request.core)
        assertEquals(4096, request.ramMb)
        assertTrue(request.preTouch)
        assertEquals(20, request.settings.maxPlayers)
        assertEquals(Difficulty.EASY, request.settings.difficulty)
    }

    @Test
    fun `옵션이 요청으로 그대로 들어간다`() {
        val requests = CopyOnWriteArrayList<InstallRequest>()
        val recording = RecordingIo()
        val result = createCommand(requests, recording).test(
            listOf(
                "--mc", "1.21.8",
                "--core", "purpur",
                "--ram", "2048M",
                "--name", "내 서버 1",
                "--motd", "안녕",
                "--max-players", "7",
                "--difficulty", "hard",
                "--no-pretouch",
                "--experimental",
            ),
        )
        assertEquals(ExitCodes.OK, result.statusCode, recording.text())
        val request = assertNotNull(requests.firstOrNull())
        assertEquals("내 서버 1", request.serverName)
        assertEquals(CoreKey.PURPUR, request.core)
        assertEquals(2048, request.ramMb)
        assertEquals("안녕", request.settings.motd)
        assertEquals(7, request.settings.maxPlayers)
        assertEquals(Difficulty.HARD, request.settings.difficulty)
        assertTrue(request.allowExperimental)
        assertTrue(!request.preTouch)
    }

    @Test
    fun `--start 는 설치 뒤 서버를 띄운다`() {
        val requests = CopyOnWriteArrayList<InstallRequest>()
        val ran = CopyOnWriteArrayList<Path>()
        val recording = RecordingIo()
        val result = createCommand(requests, recording, ran)
            .test(listOf("--mc", "1.21.8", "--core", "paper", "--ram", "4G", "--start"))
        assertEquals(ExitCodes.OK, result.statusCode, recording.text())
        assertEquals(1, ran.size)
        assertTrue(ran.single().toString().endsWith("paper-1.21.8"), ran.single().toString())
    }

    @Test
    fun `--mc 가 없으면 사용법 오류다`() {
        val result = CreateCommand().test(listOf("--core", "paper", "--ram", "4G"))
        assertTrue(result.statusCode != 0)
        assertTrue(result.output.contains("--mc"), result.output)
    }

    @Test
    fun `--ram 형식이 틀리면 사용법 오류다`() {
        val result = CreateCommand().test(listOf("--mc", "1.21.8", "--ram", "4T"))
        assertTrue(result.statusCode != 0)
        assertTrue(result.output.contains("--ram"), result.output)
    }

    @Test
    fun `create --help 에 accept-eula 와 no-pretouch 가 보인다`() {
        val result = CreateCommand().test(listOf("--help"))
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("--accept-eula"), result.output)
        assertTrue(result.output.contains("--no-pretouch"), result.output)
        assertTrue(result.output.contains("--servers-dir"), result.output)
    }

    @Test
    fun `start 는 이름이 없으면 사용법 오류다`() {
        val result = StartCommand().test(emptyList())
        assertTrue(result.statusCode != 0)
    }

    @Test
    fun `stop 은 이름이 없으면 사용법 오류다`() {
        val result = StopCommand().test(emptyList())
        assertTrue(result.statusCode != 0)
    }

    @Test
    fun `start --help 에 save 와 java 가 보인다`() {
        val result = StartCommand().test(listOf("--help"))
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("--save"), result.output)
        assertTrue(result.output.contains("--java"), result.output)
        assertTrue(result.output.contains("--accept-eula"), result.output)
    }
}
