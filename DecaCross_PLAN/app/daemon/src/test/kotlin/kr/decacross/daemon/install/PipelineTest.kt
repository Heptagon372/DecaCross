package kr.decacross.daemon.install

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.runtime.Cas
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class PipelineTest {
    private val fakeJar: ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            z.write("Manifest-Version: 1.0\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("hello.txt"))
            z.write("paper".toByteArray())
            z.closeEntry()
        }
    }.toByteArray()
    private val fakeSha = MessageDigest.getInstance("SHA-256").digest(fakeJar).joinToString("") { "%02x".format(it) }

    private val mc = McVersion(McOrdinal(1940), "1.21.8", Instant.parse("2025-07-17T00:00:00Z"), false, 21, 21, null, null)

    private fun build(sha: String = fakeSha, url: String = "https://example.test/paper.jar") =
        CoreBuild(CoreKey.PAPER, mc.ordinal, "60", Channel.STABLE, url, sha, fakeJar.size.toLong())

    /** 경로에 공백을 넣어 인용 처리를 검증한다. */
    private fun tmpPaths(): DecaPaths {
        val root = Files.createTempDirectory("decacross test ")
        return DecaPaths(root.resolve("app data"), root.resolve("my servers"))
    }

    private fun clientServing(bytes: ByteArray?, failWith: HttpStatusCode? = null) = HttpClient(
        MockEngine { req ->
            when {
                failWith != null -> respondError(failWith)
                bytes == null -> respondError(HttpStatusCode.NotFound)
                else -> respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
            }
        },
    )

    private fun spec(paths: DecaPaths, name: String = "demo", eula: Boolean = true, core: CoreBuild = build()) =
        InstallSpec(
            name = name,
            mc = mc,
            core = core,
            javaExe = Path.of("C:\\Program Files\\Java\\jdk-25.0.2\\bin\\java.exe"),
            ramMb = 2048,
            acceptEula = eula,
        )

    @Test
    fun happyPath_createsServerAtomically_andLeavesNoStaging() = runTest {
        val paths = tmpPaths()
        val pipeline = InstallPipeline(paths, Fetcher(clientServing(fakeJar), maxRetries = 0, backoffMs = listOf(0)))
        val events = pipeline.run(spec(paths)).toList()

        val done = events.last()
        assertIs<InstallEvent.Completed>(done, "events=$events")
        val dir = paths.server("demo")
        assertTrue(Files.isRegularFile(dir.resolve("paper-1.21.8-60.jar")))
        assertTrue(Files.isRegularFile(dir.resolve("server.properties")))
        assertTrue(Files.isRegularFile(dir.resolve("eula.txt")))
        assertContains(dir.resolve("eula.txt").readText(), "eula=true")
        assertTrue(Files.isRegularFile(dir.resolve(".decacross/server.json")))
        assertTrue(Files.isRegularFile(dir.resolve(".decacross/manifest.json")))
        assertEquals(emptyList(), Files.list(paths.staging).use { it.toList() }, "스테이징 잔여물 없음")
        assertEquals(
            InstallStage.entries.map { InstallEvent.StageChanged(it) },
            events.filterIsInstance<InstallEvent.StageChanged>(),
            "단계를 순서대로 전부 거친다",
        )
        // CAS 에 blob 1개, 서버 파일은 그 blob 과 같은 내용
        assertEquals(1, Cas(paths.cacheBlobs).stats().blobCount)
    }

    @Test
    fun startScripts_useAbsoluteQuotedJava_andRunWithoutLauncher() = runTest {
        val paths = tmpPaths()
        InstallPipeline(paths, Fetcher(clientServing(fakeJar), 0, listOf(0))).run(spec(paths)).toList()
        val bat = paths.server("demo").resolve("start.bat").readText()
        assertTrue(bat.contains("\"C:\\Program Files\\Java\\jdk-25.0.2\\bin\\java.exe\""), "공백 포함 경로는 인용된다")
        assertContains(bat, "-Xmx2048M")
        assertContains(bat, "-Dfile.encoding=UTF-8")
        assertContains(bat, "-jar \"paper-1.21.8-60.jar\" nogui")
        assertFalse(bat.contains("decacross", ignoreCase = true) && bat.contains("daemon"), "런처/데몬 의존 없음")
        assertFalse(bat.contains(paths.bundledJre.toString()), "★ 번들 JRE 경로는 절대 스크립트에 들어가지 않는다")
        assertFalse(bat.contains("JAVA_HOME"), "JAVA_HOME 에 의존하지 않는다")
    }

    @Test
    fun hashMismatch_abortsImmediately_andCleansUp() = runTest {
        val paths = tmpPaths()
        val wrong = "0".repeat(64)
        val events = InstallPipeline(paths, Fetcher(clientServing(fakeJar), 0, listOf(0)))
            .run(spec(paths, core = build(sha = wrong))).toList()
        val failed = assertIs<InstallEvent.Failed>(events.last())
        assertIs<InstallError.HashMismatch>(failed.error)
        assertFalse(Files.exists(paths.server("demo")), "사용자 폴더 무오염")
        assertEquals(emptyList(), Files.list(paths.staging).use { it.toList() })
        assertEquals(0, Cas(paths.cacheBlobs).stats().blobCount, "변조 의심 파일은 캐시에 들어가지 않는다")
    }

    @Test
    fun networkFailure_reportsNetworkError_andCleansUp() = runTest {
        val paths = tmpPaths()
        val events = InstallPipeline(paths, Fetcher(clientServing(null, HttpStatusCode.InternalServerError), 1, listOf(0, 0)))
            .run(spec(paths)).toList()
        val failed = assertIs<InstallEvent.Failed>(events.last())
        assertIs<InstallError.Network>(failed.error)
        assertEquals(InstallStage.FETCH, failed.stage)
        assertFalse(Files.exists(paths.server("demo")))
    }

    @Test
    fun eulaRefused_neverProducesServer_norEulaTrue() = runTest {
        val paths = tmpPaths()
        val events = InstallPipeline(paths, Fetcher(clientServing(fakeJar), 0, listOf(0)))
            .run(spec(paths, eula = false)).toList()
        val failed = assertIs<InstallEvent.Failed>(events.last())
        assertEquals(InstallError.EulaNotAccepted, failed.error)
        assertEquals(InstallStage.EULA, failed.stage)
        assertFalse(Files.exists(paths.server("demo")))
        assertEquals(emptyList(), Files.list(paths.staging).use { it.toList() })
    }

    @Test
    fun duplicateName_isRejectedBeforeAnyDownload() = runTest {
        val paths = tmpPaths()
        Files.createDirectories(paths.server("demo"))
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(fakeJar)
        })
        val events = InstallPipeline(paths, Fetcher(client, 0, listOf(0))).run(spec(paths)).toList()
        assertIs<InstallError.AlreadyExists>(assertIs<InstallEvent.Failed>(events.last()).error)
        assertEquals(0, requests)
    }

    @Test
    fun secondInstall_hitsCache_andDoesNotDownloadAgain() = runTest {
        val paths = tmpPaths()
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond(fakeJar, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, fakeJar.size.toString()))
        })
        val pipeline = InstallPipeline(paths, Fetcher(client, 0, listOf(0)))
        assertIs<InstallEvent.Completed>(pipeline.run(spec(paths, "a")).toList().last())
        assertIs<InstallEvent.Completed>(pipeline.run(spec(paths, "b")).toList().last())
        assertEquals(1, requests, "같은 jar 는 한 번만 받는다")
        assertEquals(1, Cas(paths.cacheBlobs).stats().blobCount, "디스크에 1벌")
    }

    @Test
    fun invalidNames_areRejected() {
        listOf("", " ", ".", "..", ".hidden", "bad/name", "bad\\name", "ends.", "a".repeat(65), "co:lon").forEach {
            assertTrue(InstallPipeline.validateName(it) != null, "'$it' 는 거부돼야 한다")
        }
        listOf("demo", "우리 서버", "S.OWL_survival-2026", "a b").forEach {
            assertEquals(null, InstallPipeline.validateName(it), "'$it' 는 허용")
        }
    }

    @Test
    fun atomicMove_fallsBackWhenAtomicUnsupported() {
        val root = Files.createTempDirectory("atomic")
        val staging = Files.createDirectories(root.resolve("stg"))
        Files.writeString(staging.resolve("f.txt"), "x")
        val target = root.resolve("out")
        var atomicTried = false
        Atomic.moveInto(staging, target) { from, to ->
            atomicTried = true
            // ATOMIC_MOVE 미지원 파일시스템을 흉내: 일반 이동으로 처리
            Files.move(from, to)
        }
        assertTrue(atomicTried)
        assertEquals("x", target.resolve("f.txt").readText())
        assertFalse(Files.exists(staging))
    }
}
