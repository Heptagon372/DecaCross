package kr.decacross.collector.sources.mojang

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.config.MojangSettings
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.compat.model.PackFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §9.2 JarMetaReader: server/client jar 중앙 디렉터리 선택 순서와 결과 상태. */
class JarMetaReaderTest {
    private val dir = newTempDir()
    private val serverUrl = "https://data.test/v/server.jar"
    private val clientUrl = "https://data.test/v/client.jar"

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun artifact(url: String, bytes: ByteArray) = VersionJson.Artifact(FakeHttp.hex(DigestAlgo.SHA1, bytes), bytes.size.toLong(), url)

    private fun filler(count: Int, prefix: String) = (0 until count).map { ZipItem("$prefix/c$it.class", ByteArray(4) { i -> i.toByte() }) }

    private val e3VersionJson = """{"id":"1.21","protocol_version":767,"pack_version":{"resource":34,"data":48}}""".encodeToByteArray()
    private val e1PackMcmeta = """{"pack":{"description":"The default data for Minecraft","pack_format":4}}""".encodeToByteArray()

    @Test
    fun bundlerServer_fitsTail_used() = runTest {
        val server = buildZip(listOf(ZipItem("version.json", e3VersionJson), ZipItem("META-INF/main-class", "x".encodeToByteArray())))
        val client = buildZip(filler(10, "a") + ZipItem("version.json", """{"pack_version":1}""".encodeToByteArray()))
        val http = FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)).on(clientUrl, FakeResponse.Body(client))

        val out = readJarMeta(http, MojangSettings(), artifact(serverUrl, server), artifact(clientUrl, client))

        assertEquals(JarMetaStatus.FOUND, out.status)
        assertEquals("server", out.jar)
        assertEquals("E3", out.facts?.era)
        assertEquals(PackFormat(34), out.facts?.rp)
        assertEquals(PackFormat(48), out.facts?.dp)
        assertEquals(767, out.facts?.protocol)
        assertTrue(http.requests.none { it.url == clientUrl }, "client jar 는 읽지 않는다")
        assertEquals(http.requests.size, out.requests)
        assertTrue(out.bytes > 0)
    }

    @Test
    fun smallerCdChosen_largerNeverFetchedWhenFound() = runTest {
        // 둘 다 꼬리(1 KiB)에 안 들어간다. server 디렉터리가 훨씬 크다
        val server = buildZip(filler(3_000, "net/minecraft/server"))
        val client = buildZip(filler(100, "net/minecraft/client") + ZipItem("pack.mcmeta", e1PackMcmeta) + ZipItem("data/minecraft/recipes/a.json", "{}".encodeToByteArray()))
        val http = FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)).on(clientUrl, FakeResponse.Body(client))
        val settings = MojangSettings(tailBytes = 1_024)

        val serverEocd = (readEocd(FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)), serverUrl, server.size.toLong(), 1_024) as EocdResult.Ok).eocd
        assertFalse(serverEocd.fitsInTail)

        val out = readJarMeta(http, settings, artifact(serverUrl, server), artifact(clientUrl, client))

        assertEquals(JarMetaStatus.FOUND, out.status)
        assertEquals("client", out.jar)
        assertEquals("E1", out.facts?.era)
        assertEquals(PackFormat(4), out.facts?.rp)
        val serverRequests = http.requests.filter { it.url == serverUrl }
        assertEquals(1, serverRequests.size, "server 는 꼬리 한 번만")
        assertTrue(serverRequests.none { it.range?.first == serverEocd.cdOffset }, "큰 디렉터리 Range 요청이 없어야 한다")
    }

    @Test
    fun bothTooLarge_tooLargeOutcome() = runTest {
        val server = buildZip(filler(200, "s"))
        val client = buildZip(filler(200, "c") + ZipItem("pack.mcmeta", e1PackMcmeta))
        val http = FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)).on(clientUrl, FakeResponse.Body(client))

        val out = readJarMeta(http, MojangSettings(maxCentralDirectoryBytes = 512), artifact(serverUrl, server), artifact(clientUrl, client))

        assertEquals(JarMetaStatus.TOO_LARGE, out.status)
        assertNull(out.facts)
    }

    @Test
    fun fallbackToOtherJar_whenNoMetadata() = runTest {
        // server 는 꼬리에 들어가지만 메타 파일이 없다 → client 로 넘어간다
        val server = buildZip(filler(5, "net/minecraft/server"))
        val client = buildZip(filler(5, "net/minecraft/client") + ZipItem("pack.mcmeta", e1PackMcmeta) + ZipItem("data/minecraft/a.json", "{}".encodeToByteArray()))
        val http = FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)).on(clientUrl, FakeResponse.Body(client))

        val out = readJarMeta(http, MojangSettings(), artifact(serverUrl, server), artifact(clientUrl, client))

        assertEquals(JarMetaStatus.FOUND, out.status)
        assertEquals("client", out.jar)
        assertEquals(PackFormat(4), out.facts?.dp)
    }

    @Test
    fun e0_none() = runTest {
        // 1.6.x: client 에 pack.mcmeta(pack_format 1)가 있지만 data/ 가 없다. server 에는 둘 다 없다
        val server = buildZip(filler(3, "net/minecraft/server"))
        val client = buildZip(filler(3, "net/minecraft/client") + ZipItem("pack.mcmeta", """{"pack":{"pack_format":1,"description":"The default look of Minecraft"}}""".encodeToByteArray()))
        val http = FakeHttp(dir).on(serverUrl, FakeResponse.Body(server)).on(clientUrl, FakeResponse.Body(client))

        val out = readJarMeta(http, MojangSettings(), artifact(serverUrl, server), artifact(clientUrl, client))

        assertEquals(JarMetaStatus.NONE, out.status)
        assertEquals("E0", out.facts?.era)
        assertNull(out.facts?.rp)

        // 어느 jar 에도 메타 파일이 없으면 역시 NONE (facts 없음)
        val bare = buildZip(filler(3, "x"))
        val http2 = FakeHttp(dir).on(clientUrl, FakeResponse.Body(bare))
        val none = readJarMeta(http2, MojangSettings(), null, artifact(clientUrl, bare))
        assertEquals(JarMetaStatus.NONE, none.status)
        assertNull(none.facts)

        // 네트워크 실패는 FAILED
        val http3 = FakeHttp(dir).on(clientUrl, FakeResponse.Fail(FailureKind.NETWORK))
        assertEquals(JarMetaStatus.FAILED, readJarMeta(http3, MojangSettings(), null, artifact(clientUrl, bare)).status)
    }
}
