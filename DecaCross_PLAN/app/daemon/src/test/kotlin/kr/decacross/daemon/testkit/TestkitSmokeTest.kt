package kr.decacross.daemon.testkit

import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.InstallFailure
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.coreJarFileName
import kr.decacross.daemon.install.defaultServerName
import kr.decacross.daemon.install.describeKo
import kr.decacross.daemon.install.isExplicitEulaAgreement
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.install.processCommand
import kr.decacross.daemon.install.writeFileAtomically
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.runtime.JavaCandidate
import kr.decacross.daemon.runtime.JavaOrigin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** WP0 테스트 킷 자체 검증 + WP0 에 완성본으로 들어간 작은 함수들. */
class TestkitSmokeTest {
    @Test
    fun launchProfilesResource_parses_andReadyPatternSeparatesDoneLines() {
        val loaded = loadLaunchProfiles()
        val profiles = (loaded as? LaunchProfilesLoad.Loaded)?.profiles ?: fail("리소스 로드 실패: $loaded")
        val ready = CompiledConsolePatterns.from(profiles.console).ready
        assertTrue(ready.containsMatchIn("[12:00:00 INFO]: Done (12.345s)! For help, type \"help\""))
        assertTrue(ready.containsMatchIn("[08:01:02] [Server thread/INFO]: Done (8,2s)! For help, type \"help\""))
        assertFalse(ready.containsMatchIn("[12:00:00 INFO]: Done preparing level \"world\" (0.001s)"))
        assertEquals(listOf("aikar-base", "aikar-12g"), profiles.jvmFlagProfiles.map { it.id })
        for (p in profiles.jvmFlagProfiles) {
            val unlock = p.flags.indexOf("-XX:+UnlockExperimentalVMOptions")
            val firstExperimental = p.flags.indexOfFirst { it.startsWith("-XX:G1NewSizePercent") }
            assertTrue(unlock in 0 until firstExperimental, "${p.id}: Unlock 가 실험 플래그보다 앞")
        }
    }

    @Test
    fun fakeServer_printsDone_savesAndStopsOnStdin() {
        val dir = Files.createTempDirectory("dcx-fake")
        val jar = FakeServerJar.write(dir.resolve("fake server 한글.jar"))
        val pb = ProcessBuilder(
            FakeServerJar.testJava().toString(),
            "-Xmx64M",
            "-Dstdout.encoding=UTF-8",
            "-jar",
            jar.fileName.toString(),
            "nogui",
        ).directory(dir.toFile()).redirectErrorStream(true)
        val p = pb.start()
        val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
        val seen = ArrayList<String>()
        fun readUntil(marker: String) {
            while (true) {
                val line = reader.readLine() ?: fail("EOF 전에 '$marker' 를 못 봄: $seen")
                seen.add(line)
                if (line.contains(marker)) return
            }
        }
        readUntil("For help, type")
        assertTrue(seen.any { it.contains("fake.korean=한글 출력 확인") }, "UTF-8 출력: $seen")
        p.outputStream.write("save-all\n".toByteArray())
        p.outputStream.flush()
        readUntil("Saved the game")
        p.outputStream.write("stop\n".toByteArray())
        p.outputStream.flush()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "stop 후 종료")
        assertEquals(0, p.exitValue())
        val events = FakeServerJar.events(dir)
        assertTrue("stop-exit" in events && "hook-end" in events, "사건: $events")
    }

    @Test
    fun localHttpServer_rangeIfRangeAnd416() {
        val body = TestJars.serverJar(seed = 7, randomEntryBytes = 64 * 1024)
        LocalHttpServer().use { server ->
            server.put("/a.jar", LocalHttpServer.Resource(body))
            val http = HttpClient.newHttpClient()
            fun get(vararg headers: String): HttpResponse<ByteArray> {
                val b = HttpRequest.newBuilder(URI.create(server.url("/a.jar")))
                headers.toList().chunked(2).forEach { (k, v) -> b.header(k, v) }
                return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            val full = get()
            assertEquals(200, full.statusCode())
            assertTrue(full.body().contentEquals(body))
            val part = get("Range", "bytes=100-", "If-Range", "\"v1\"")
            assertEquals(206, part.statusCode())
            assertEquals("bytes 100-${body.size - 1}/${body.size}", part.headers().firstValue("Content-Range").orElse(""))
            assertTrue(part.body().contentEquals(body.copyOfRange(100, body.size)))
            assertEquals(200, get("Range", "bytes=100-", "If-Range", "\"stale\"").statusCode())
            val beyond = get("Range", "bytes=${body.size}-")
            assertEquals(416, beyond.statusCode())
            assertEquals("bytes */${body.size}", beyond.headers().firstValue("Content-Range").orElse(""))
            assertEquals(4, server.requestsTo("/a.jar").size)
        }
    }

    @Test
    fun treeSnapshot_detectsChanges() {
        val dir = Files.createTempDirectory("dcx-tree")
        assertEquals(emptyMap(), TreeSnapshot.of(dir.resolve("missing")))
        Files.writeString(dir.resolve("a.txt"), "x")
        val before = TreeSnapshot.of(dir)
        Files.createDirectories(dir.resolve(".staging"))
        assertNotEquals(before, TreeSnapshot.of(dir))
    }

    @Test
    fun eulaAgreement_onlyExplicitAnswers() {
        for (yes in listOf("y", "Y", " yes ", "YES", "예")) assertTrue(isExplicitEulaAgreement(yes), yes)
        for (no in listOf(null, "", "n", "no", "ㅇ", "네", "yes please", "true", "1")) assertFalse(isExplicitEulaAgreement(no), "$no")
    }

    @Test
    fun launchSpec_processCommand_orderAndEncodingArgs() {
        val spec = LaunchSpec(
            javaPath = "C:\\Java\\bin\\java.exe",
            javaFeature = 21,
            xmsMb = 4096,
            xmxMb = 4096,
            flagProfileId = "aikar-base",
            jvmFlags = listOf("-XX:+UseG1GC"),
            jarFileName = "paper-1.21.8.jar",
        )
        assertEquals(
            listOf(
                "C:\\Java\\bin\\java.exe", "-Xms4096M", "-Xmx4096M", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", "paper-1.21.8.jar", "nogui",
            ),
            spec.processCommand(),
        )
        // ★ 서버 실행 명령에는 EULA 관련 인자(우회용 시스템 속성 등)가 절대 없다 (DESIGN2 critique B2)
        assertTrue(spec.processCommand().none { it.contains("eula", ignoreCase = true) })
    }

    @Test
    fun writeFileAtomically_createsParentsReplacesAndLeavesNoTemp() {
        val dir = Files.createTempDirectory("dcx-atomic")
        val target = dir.resolve("a b/한글/.decacross/launch.json")
        assertEquals(LayoutIoResult.Ok(target), writeFileAtomically(target, "one".toByteArray()))
        assertEquals(LayoutIoResult.Ok(target), writeFileAtomically(target, "two".toByteArray()))
        assertEquals("two", Files.readString(target))
        Files.list(target.parent).use { s -> assertEquals(listOf("launch.json"), s.map { it.fileName.toString() }.toList()) }
        val blocked = dir.resolve("file-not-dir")
        Files.writeString(blocked, "x")
        assertTrue(writeFileAtomically(blocked.resolve("child.json"), "y".toByteArray()) is LayoutIoResult.Failed)
    }

    @Test
    fun hostEnvironment_windowsIgnoresNameCase_andDefaultServerName() {
        val raw = mapOf("Path" to "C:\\a;C:\\b", "PROGRAMFILES" to "C:\\Program Files")
        val win = hostEnvironment(Os.WINDOWS, raw)
        assertEquals("C:\\a;C:\\b", win["PATH"])
        assertEquals("C:\\Program Files", win["ProgramFiles"])
        assertEquals(null, hostEnvironment(Os.LINUX, raw)["PATH"])
        assertEquals(Os.WINDOWS, currentOs("Windows 11"))
        assertEquals(Os.MAC, currentOs("Mac OS X"))
        assertEquals(Os.LINUX, currentOs("Linux"))
        assertEquals("paper-1.21.8", defaultServerName(CoreKey.PAPER, "1.21.8"))
        assertEquals("paper-1.14.2_Pre-Release_4.jar", coreJarFileName(CoreKey.PAPER, "1.14.2 Pre-Release 4"))
    }

    @Test
    fun everyFailure_hasAtLeastOneFix() {
        val p = Path.of("x")
        val samples: List<InstallFailure> = listOf(
            InstallFailure.UnknownMc("1.99", emptyList()),
            InstallFailure.UnknownMc("1.21", listOf("1.21.1")),
            InstallFailure.UnsupportedCore(CoreKey.FABRIC),
            InstallFailure.NoStableBuild(CoreKey.PAPER, "26.3", "6"),
            InstallFailure.NoBuildCollected(CoreKey.PAPER, "1.8"),
            InstallFailure.InvalidCatalogData("sha256"),
            InstallFailure.InvalidServerName("CON", "예약 이름"),
            InstallFailure.ServerExists(p),
            InstallFailure.NameBusy("demo"),
            InstallFailure.ServersRootUnusable(p, "!"),
            InstallFailure.InvalidRam(512, "작음"),
            InstallFailure.JavaNotFound(21, 21, listOf(JavaCandidate(p, JavaOrigin.PATH, "17.0.1", 17, "낮음"))),
            InstallFailure.NoFlagProfile("없음"),
            InstallFailure.InsufficientDisk(p, 1, 0),
            InstallFailure.PlanRejected,
            InstallFailure.DownloadFailed("core", FetchError.SourcesExhausted(emptyList())),
            InstallFailure.DownloadFailed("core", FetchError.SizeMismatch("u", 1, 2, "Content-Length")),
            InstallFailure.DownloadFailed("core", FetchError.UnexpectedContent("u", "text/html")),
            InstallFailure.DownloadFailed("core", FetchError.LocalIo(p, "full")),
            InstallFailure.DownloadFailed("core", FetchError.Aborted("other")),
            InstallFailure.IntegrityFailed("core", VerifyOutcome.HashMismatch("a".repeat(64), "b".repeat(64))),
            InstallFailure.LocalIo(null, "denied"),
            InstallFailure.EulaDeclined("EOF"),
            InstallFailure.CommitFailed("denied"),
            InstallFailure.Cancelled,
        )
        for (f in samples) {
            val d = f.describeKo()
            assertTrue(d.messageKo.isNotBlank() && d.fixesKo.isNotEmpty() && d.fixesKo.all { it.isNotBlank() }, "$f → $d")
        }
        // 동의 경로 이름은 eula.txt 주석에 그대로 쓰인다
        assertEquals(listOf("INTERACTIVE_PROMPT", "CLI_FLAG", "UI_DIALOG"), ConsentChannel.entries.map { it.name })
    }
}
