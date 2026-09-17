package kr.decacross.collector.testkit

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.config.buildUserAgent
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.DownloadRequest
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.http.HttpResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** WP0 스모크: JUnit Platform·coroutines-test 배선과 testkit 기본 동작. */
class TestkitSmokeTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanup() = deleteTree(dir)

    @Test
    fun fakeHttp_downloadVerifiesDigest_andCloseDeletesFile() = runTest {
        val bytes = "hello".encodeToByteArray()
        val http = FakeHttp(dir).on("https://x.test/a.jar", FakeResponse.Body(bytes))
        val sha = FakeHttp.hex(DigestAlgo.SHA256, bytes)
        val ok = http.download("https://x.test/a.jar", DownloadRequest(1024, expected = mapOf(DigestAlgo.SHA256 to sha)))
        assertIs<HttpResult.Ok<*>>(ok)
        (ok.value as AutoCloseable).close()
        assertEquals(0, countFiles(dir))
        val bad = http.download("https://x.test/a.jar", DownloadRequest(1024, expected = mapOf(DigestAlgo.SHA256 to "00")))
        assertEquals(FailureKind.DIGEST_MISMATCH, (bad as HttpResult.Failure).kind)
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun userAgent_hasContact_andNoEmail() {
        val ua = buildUserAgent()
        assertTrue(ua.startsWith("DecaCross/") && ua.contains("(+https://") && !ua.contains("@"))
    }
}
