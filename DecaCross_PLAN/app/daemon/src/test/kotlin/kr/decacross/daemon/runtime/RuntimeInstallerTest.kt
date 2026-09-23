package kr.decacross.daemon.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.paths.DecaPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeInstallerTest {
    private fun paths(): DecaPaths {
        val root = Files.createTempDirectory("rt")
        return DecaPaths(root.resolve("home"), root.resolve("servers"))
    }

    @Test
    fun fallbackChain_jreToJdk_thenAarch64ToX64() = runTest {
        val asked = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { req ->
                asked += req.url.encodedQuery
                // aarch64 는 전부 404, x64 jre 도 404, x64 jdk 만 존재 (빈 배열 대신 404 로 응답하는 케이스)
                if (req.url.encodedQuery.contains("architecture=x64") && req.url.encodedQuery.contains("image_type=jdk")) {
                    respond("""[{"release_name":"jdk-21.0.4+7","binary":{"package":{"name":"x.zip","link":"https://dl/x.zip","checksum":"ab","size":1}}}]""")
                } else {
                    respondError(HttpStatusCode.NotFound)
                }
            },
        )
        val inst = RuntimeInstaller(paths(), client, Fetcher(client, 0, listOf(0)))
        assertNull(inst.latestAsset(21, Os.MAC, Arch.AARCH64, ImageType.JRE))
        val asset = inst.latestAsset(21, Os.MAC, Arch.X64, ImageType.JDK)
        assertEquals("x.zip", asset?.name)
        assertEquals("ab", asset?.sha256)
    }

    @Test
    fun ensure_reportsFailure_whenNothingAvailable() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val inst = RuntimeInstaller(paths(), client, Fetcher(client, 0, listOf(0)))
        val r = inst.ensureRuntime(99, Os.WINDOWS, Arch.AARCH64)
        val f = assertIs<EnsureResult.Failed>(r)
        assertTrue(f.messageKo.contains("aarch64") && f.messageKo.contains("x64"), "폴백 체인을 전부 시도했다: ${f.messageKo}")
    }

    @Test
    fun runtimeDir_isSeparateFromBundledJre() {
        val p = paths()
        val inst = RuntimeInstaller(p, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), Fetcher(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), 0, listOf(0)))
        val dir = inst.runtimeDir(21)
        assertTrue(dir.startsWith(p.runtimes))
        assertTrue(!dir.startsWith(p.bundledJre), "★ 서버 런타임은 번들 JRE 와 다른 트리")
    }
}
