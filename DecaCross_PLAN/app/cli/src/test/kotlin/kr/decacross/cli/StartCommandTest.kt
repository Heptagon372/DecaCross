package kr.decacross.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.PRETOUCH_FLAG
import kr.decacross.daemon.install.ServerEntry
import kr.decacross.daemon.install.encodeLaunchJson
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaOrigin
import kr.decacross.daemon.runtime.JavaRequirement
import kr.decacross.daemon.runtime.JavaSelection
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StartCommandTest {
    /** 동의 여부·기록을 흉내 내는 서버 폴더 접근 (실제 eula.txt 를 만들지 않는다). */
    private class FakeAccess(
        private val entry: ServerEntry?,
        var accepted: Boolean,
        val writes: MutableList<ConsentChannel> = CopyOnWriteArrayList(),
        private val writeResult: LayoutIoResult = LayoutIoResult.Ok(Path.of("eula.txt")),
    ) : ServerAccess {
        override fun find(serversRoot: Path, name: String): ServerEntry? = entry

        override fun eulaAccepted(serverDir: Path): Boolean = accepted

        override fun acceptEula(serverDir: Path, channel: ConsentChannel, acceptedAt: Instant): LayoutIoResult {
            writes.add(channel)
            accepted = true
            return writeResult
        }
    }

    private fun tempServer(): Pair<DecaPaths, Path> {
        val tmp = Files.createTempDirectory("dcx-cli-start")
        tmp.toFile().deleteOnExit()
        val root = tmp.resolve("servers")
        val serverDir = root.resolve("demo")
        serverDir.resolve(META_DIR_NAME).createDirectories()
        return DecaPaths(tmp.resolve("internal"), ServersRoot(root, ServersRootSource.OPTION, null)) to serverDir
    }

    @Test
    fun `--java --save 는 launch json 과 실행 스크립트를 다시 쓴다`() {
        val (paths, serverDir) = tempServer()
        val newJava = serverDir.resolveSibling("jdk").resolve("bin").resolve("java")
        newJava.parent.createDirectories()
        newJava.writeText("")
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo()
        val ranSpec = CopyOnWriteArrayList<LaunchSpec>()
        val writer = LaunchScriptWriter(
            renderBat = { spec, _ -> "BAT ${spec.javaPath}".toByteArray(Charsets.UTF_8) },
            renderSh = { spec -> "SH ${spec.javaPath}".toByteArray(Charsets.UTF_8) },
        )
        val locator = JavaLocator { _: JavaRequirement, explicit: Path? ->
            JavaLocateResult.Found(
                JavaSelection(assertNotNull(explicit), 21, "21.0.8", JavaOrigin.EXPLICIT, listOf("테스트 경고")),
                emptyList(),
            )
        }
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(entry, accepted = true),
            scriptWriter = writer,
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            javaLocatorFactory = { _, _, _ -> locator },
            javaExists = { true },
            runner = { _, spec, _ ->
                ranSpec.add(spec)
                ExitCodes.OK
            },
        )
        val result = command.test(listOf("demo", "--java", newJava.toString(), "--save"))
        assertEquals(ExitCodes.OK, result.statusCode, recording.text())
        assertEquals("BAT $newJava", serverDir.resolve(START_BAT_FILE_NAME).readText())
        assertEquals("SH $newJava", serverDir.resolve(START_SH_FILE_NAME).readText())
        val launchText = serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME).readText()
        val stored = Json.decodeFromString(LaunchSpec.serializer(), launchText)
        assertEquals(newJava.toString(), stored.javaPath)
        assertEquals(21, stored.javaFeature)
        // 기본값과 같아도 빠지면 안 되는 필드 (스키마 표시·서버 인자) — 다시 읽어서는 잡히지 않으므로 원문을 본다
        assertTrue(launchText.contains("\"schema\""), launchText)
        assertTrue(launchText.contains("\"serverArgs\""), launchText)
        // ★ 회귀 (DESIGN2 §2.8): `--save` 가 다시 쓴 파일은 설치가 쓴 파일과 **바이트가 같아야** 한다.
        //   CLI 가 자기 Json 을 따로 만들면 들여쓰기가 4칸이 되고 끝 줄바꿈이 빠져 같은 내용의 다른 파일이 됐다.
        val launchBytes = Files.readAllBytes(serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME))
        assertContentEquals(encodeLaunchJson(stored), launchBytes, launchText)
        assertTrue(launchText.contains("\n  \"javaPath\""), "들여쓰기가 2칸이어야 한다: $launchText")
        assertTrue(launchText.endsWith("\n"), "끝에 줄바꿈이 있어야 한다: $launchText")
        assertEquals(newJava.toString(), ranSpec.single().javaPath)
        assertTrue(recording.text().contains("[주의] 테스트 경고"), recording.text())
    }

    /**
     * 회귀 (게이트 §5.1): 사용자가 `.decacross/launch.json` 을 고쳐 ASCII 밖 토큰이 들어가면 **진짜 렌더러**가
     * `IllegalArgumentException` 을 던진다 (daemon `install/Config.kt` 계약). CLI 는 스택 트레이스 대신
     * 고칠 방법을 알려 주고, 한 파일도 바꾸지 않는다.
     */
    @Test
    fun `--save 가 스크립트를 만들 수 없으면 스택 트레이스 대신 이유를 알린다`() {
        val (paths, serverDir) = tempServer()
        val newJava = serverDir.resolveSibling("jdk").resolve("bin").resolve("java")
        newJava.parent.createDirectories()
        newJava.writeText("")
        val brokenSpec = Fixtures.spec.copy(jarFileName = "서버.jar")
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), brokenSpec, null)
        val recording = RecordingIo()
        val ranSpec = CopyOnWriteArrayList<LaunchSpec>()
        val locator = JavaLocator { _: JavaRequirement, explicit: Path? ->
            JavaLocateResult.Found(
                JavaSelection(assertNotNull(explicit), 21, "21.0.8", JavaOrigin.EXPLICIT, emptyList()),
                emptyList(),
            )
        }
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(entry, accepted = true),
            // ★ 진짜 렌더러 (계약을 흉내 내지 않는다)
            scriptWriter = LaunchScriptWriter(),
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            javaLocatorFactory = { _, _, _ -> locator },
            javaExists = { true },
            runner = { _, spec, _ ->
                ranSpec.add(spec)
                ExitCodes.OK
            },
        )

        val result = command.test(listOf("demo", "--java", newJava.toString(), "--save"))

        assertEquals(ExitCodes.INPUT, result.statusCode, recording.text())
        assertTrue(recording.text().contains("[실패] 실행 스크립트를 만들 수 없습니다"), recording.text())
        assertTrue(recording.text().contains("해결:"), recording.text())
        assertTrue(ranSpec.isEmpty(), "서버를 띄우면 안 된다")
        assertFalse(Files.exists(serverDir.resolve(START_BAT_FILE_NAME)), "start.bat 을 쓰면 안 된다")
        assertFalse(Files.exists(serverDir.resolve(START_SH_FILE_NAME)), "start.sh 를 쓰면 안 된다")
        assertFalse(
            Files.exists(serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME)),
            "launch.json 만 새 Java 로 바뀌면 안 된다",
        )
    }

    @Test
    fun `--java 만 주면 파일을 바꾸지 않고 이번 실행에만 쓴다`() {
        val (paths, serverDir) = tempServer()
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo()
        val ranSpec = CopyOnWriteArrayList<LaunchSpec>()
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(entry, accepted = true),
            scriptWriter = LaunchScriptWriter(
                renderBat = { _, _ -> error("--save 없이 스크립트를 쓰면 안 된다") },
                renderSh = { error("--save 없이 스크립트를 쓰면 안 된다") },
            ),
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { true },
            runner = { _, spec, _ ->
                ranSpec.add(spec)
                ExitCodes.OK
            },
        )
        val other = Path.of("/opt/other/bin/java").toAbsolutePath()
        assertEquals(ExitCodes.OK, command.test(listOf("demo", "--java", other.toString())).statusCode)
        assertEquals(other.toString(), ranSpec.single().javaPath)
        assertFalse(Files.exists(serverDir.resolve(START_BAT_FILE_NAME)))
    }

    @Test
    fun `--no-pretouch 는 이번 실행만 플래그를 뺀다`() {
        val (paths, serverDir) = tempServer()
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo()
        val ranSpec = CopyOnWriteArrayList<LaunchSpec>()
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(entry, accepted = true),
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { true },
            runner = { _, spec, _ ->
                ranSpec.add(spec)
                ExitCodes.OK
            },
        )
        assertEquals(ExitCodes.OK, command.test(listOf("demo", "--no-pretouch")).statusCode, recording.text())
        assertTrue(Fixtures.spec.jvmFlags.contains(PRETOUCH_FLAG))
        assertFalse(ranSpec.single().jvmFlags.contains(PRETOUCH_FLAG))
    }

    @Test
    fun `EULA 동의가 없으면 묻고 거부하면 3 이다`() {
        val (paths, serverDir) = tempServer()
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo("no\n")
        val access = FakeAccess(entry, accepted = false)
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = access,
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { true },
            runner = { _, _, _ -> error("동의 없이 서버를 띄우면 안 된다") },
        )
        assertEquals(ExitCodes.EULA_DECLINED, command.test(listOf("demo")).statusCode)
        assertTrue(access.writes.isEmpty(), "거부했으면 eula.txt 를 쓰지 않는다")
    }

    @Test
    fun `--accept-eula 는 동의를 기록하고 실행한다`() {
        val (paths, serverDir) = tempServer()
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo()
        val access = FakeAccess(entry, accepted = false)
        val ran = CopyOnWriteArrayList<Path>()
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = access,
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { true },
            runner = { dir, _, _ ->
                ran.add(dir)
                ExitCodes.OK
            },
        )
        assertEquals(ExitCodes.OK, command.test(listOf("demo", "--accept-eula")).statusCode, recording.text())
        assertEquals(listOf(ConsentChannel.CLI_FLAG), access.writes.toList())
        assertEquals(listOf(serverDir), ran.toList())
    }

    @Test
    fun `서버가 없으면 5 이고 list 를 안내한다`() {
        val (paths, _) = tempServer()
        val recording = RecordingIo()
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(null, accepted = true),
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { true },
            runner = { _, _, _ -> error("서버가 없으면 띄우지 않는다") },
        )
        assertEquals(ExitCodes.SERVER_STATE, command.test(listOf("nope")).statusCode)
        assertTrue(recording.text().contains("decacross list"), recording.text())
    }

    @Test
    fun `java 실행 파일이 없으면 5 이고 저장 명령을 안내한다`() {
        val (paths, serverDir) = tempServer()
        val entry = ServerEntry("demo", serverDir, Fixtures.manifest("demo"), Fixtures.spec, null)
        val recording = RecordingIo()
        val command = StartCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            access = FakeAccess(entry, accepted = true),
            profilesLoader = { loadLaunchProfiles() },
            javaExists = { false },
            runner = { _, _, _ -> error("java 가 없으면 띄우지 않는다") },
        )
        assertEquals(ExitCodes.SERVER_STATE, command.test(listOf("demo")).statusCode)
        assertTrue(recording.text().contains("--java <java 실행 파일 경로> --save"), recording.text())
    }
}
