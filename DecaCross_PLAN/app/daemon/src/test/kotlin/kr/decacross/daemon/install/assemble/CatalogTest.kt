package kr.decacross.daemon.install.assemble

import kotlinx.serialization.json.Json
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.FileOrigin
import kr.decacross.daemon.install.INCOMPLETE_MARKER_NAME
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.MANIFEST_FILE_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.ManifestFile
import kr.decacross.daemon.install.findServer
import kr.decacross.daemon.install.isEulaAccepted
import kr.decacross.daemon.install.listServers
import kr.decacross.daemon.install.writeEulaAccepted
import kr.decacross.daemon.testkit.FakeServerJar
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 서버 목록·EULA 상태 (DESIGN2 §2.1 ServerCatalog). 전부 임시 디렉터리에서만 돈다. */
class CatalogTest {
    private val root: Path = Files.createTempDirectory("dcx-catalog")
    private val servers: Path = Files.createDirectories(root.resolve("servers"))

    init {
        // §4.2 규칙 8: eula.txt 를 쓰는 테스트는 FakeServerJar 가 있는 임시 루트 안에서만 돈다
        FakeServerJar.write(root.resolve("paper-1.21.8.jar"))
    }

    private val launch = LaunchSpec(
        javaPath = "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
        javaFeature = 21,
        xmsMb = 4096,
        xmxMb = 4096,
        flagProfileId = "aikar-base",
        jvmFlags = listOf("-XX:+UseG1GC"),
        jarFileName = "paper-1.21.8.jar",
    )

    private fun manifest(name: String) = InstallManifest(
        installId = "t-1",
        serverName = name,
        createdAt = "2026-09-17T13:05:00Z",
        mcLabel = "1.21.8",
        mcOrdinal = 1940,
        core = "PAPER",
        coreBuild = "60",
        coreChannel = "STABLE",
        files = listOf(ManifestFile("paper-1.21.8.jar", "a".repeat(64), 10, FileOrigin.DOWNLOADED)),
    )

    private fun seedServer(name: String, launchJson: String? = null, incomplete: Boolean = false): Path {
        val dir = Files.createDirectories(servers.resolve(name))
        val meta = Files.createDirectories(dir.resolve(META_DIR_NAME))
        Files.writeString(meta.resolve(LAUNCH_FILE_NAME), launchJson ?: Json.encodeToString(LaunchSpec.serializer(), launch))
        Files.writeString(meta.resolve(MANIFEST_FILE_NAME), Json.encodeToString(InstallManifest.serializer(), manifest(name)))
        if (incomplete) Files.writeString(meta.resolve(INCOMPLETE_MARKER_NAME), "t-9")
        return dir
    }

    @Test
    fun listServers_skipsDotDirsPlainDirsAndIncompleteDirs() {
        seedServer("demo")
        seedServer("Beta")
        seedServer("half", incomplete = true)
        Files.createDirectories(servers.resolve(".staging").resolve("t-1"))
        Files.createDirectories(servers.resolve("그냥 폴더"))
        Files.writeString(servers.resolve("readme.txt"), "x")

        val entries = listServers(servers)
        assertEquals(listOf("Beta", "demo"), entries.map { it.name }, "이름순(대소문자 무시)")
        assertTrue(entries.all { it.problemKo == null }, entries.map { it.problemKo }.toString())
        assertEquals(launch, entries.first { it.name == "demo" }.launch)
        assertEquals(1940, assertNotNull(entries.first { it.name == "demo" }.manifest).mcOrdinal)
    }

    @Test
    fun listServers_missingRoot_isEmpty() {
        assertEquals(emptyList(), listServers(root.resolve("nope")))
    }

    @Test
    fun corruptLaunchJson_becomesProblemEntry() {
        seedServer("broken", launchJson = "{ 이건 JSON 이 아니다 }")
        val entry = assertNotNull(listServers(servers).firstOrNull { it.name == "broken" })
        assertNull(entry.launch)
        val problem = assertNotNull(entry.problemKo)
        assertTrue(problem.contains(LAUNCH_FILE_NAME), problem)
    }

    @Test
    fun findServer_isCaseInsensitive() {
        seedServer("Demo")
        assertEquals("Demo", assertNotNull(findServer(servers, "demo")).name)
        assertEquals("Demo", assertNotNull(findServer(servers, "DEMO")).name)
        assertNull(findServer(servers, "other"))
    }

    @Test
    fun isEulaAccepted_readsPropertiesRules() {
        val dir = seedServer("eula-cases")
        assertFalse(isEulaAccepted(dir), "파일이 없으면 false")

        Files.writeString(dir.resolve("eula.txt"), "#주석\neula = TRUE\n")
        assertTrue(isEulaAccepted(dir), "값은 대소문자 무시, 공백 허용")

        Files.writeString(dir.resolve("eula.txt"), "eula=false\n")
        assertFalse(isEulaAccepted(dir))

        Files.writeString(dir.resolve("eula.txt"), "#eula=true\n")
        assertFalse(isEulaAccepted(dir), "주석은 값이 아니다")
    }

    @Test
    fun writeEulaAccepted_thenIsEulaAccepted() {
        val dir = seedServer("consented")
        val result = writeEulaAccepted(dir, ConsentChannel.INTERACTIVE_PROMPT, Instant.parse("2026-09-17T13:05:00Z"))
        assertTrue(result is LayoutIoResult.Ok, "$result")
        assertTrue(isEulaAccepted(dir))
        val text = Files.readString(dir.resolve("eula.txt"))
        assertTrue(text.contains("#EULA accepted via DecaCross (INTERACTIVE_PROMPT)"), text)
        assertTrue(text.contains("#2026-09-17T13:05:00Z"), text)
    }
}
