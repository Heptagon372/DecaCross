package kr.decacross.collector.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.HostPolicy
import kr.decacross.collector.config.HttpSettings
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.countFiles
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testSettings
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class KtorHttpTest {
    private val dir = newTempDir("decacross-ktorhttp-")
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        background.cancel()
        deleteTree(dir)
    }

    private fun settings(
        maxRetryDelayMs: Long = 50,
        maxRetries: Int = 4,
        policies: Map<String, HostPolicy> = emptyMap(),
        defaultPolicy: HostPolicy = HostPolicy(minIntervalMs = 0, maxConcurrent = 8),
        circuitBreakerFailures: Int = 5,
    ): CollectorSettings = testSettings(dir).copy(
        http = HttpSettings(
            maxRetries = maxRetries,
            maxRetryDelayMs = maxRetryDelayMs,
            hostPolicies = policies,
            defaultHostPolicy = defaultPolicy,
            circuitBreakerFailures = circuitBreakerFailures,
        ),
    )

    private fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): MockEngine = MockEngine(handler)

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    private fun MockRequestHandleScope.ok(body: ByteArray, withLength: Boolean = true, extra: Map<String, String> = emptyMap()): HttpResponseData {
        val pairs = (if (withLength) listOf(HttpHeaders.ContentLength to body.size.toString()) else emptyList()) + extra.toList()
        return respond(body, HttpStatusCode.OK, headersOf(*pairs.map { (k, v) -> k to listOf(v) }.toTypedArray()))
    }

    /** 앞부분 [prefix] 바이트를 보낸 뒤 IOException 으로 끊기는 본문. */
    private fun brokenBody(prefix: ByteArray): ByteReadChannel {
        val ch = ByteChannel()
        background.launch {
            ch.writeFully(prefix)
            ch.flush()
            delay(20)
            ch.close(IOException("연결 끊김 (테스트)"))
        }
        return ch
    }

    @Test
    fun ua_sentOnEveryRequest(): Unit = runBlocking {
        val s = settings()
        val body = bytes(64)
        val calls = AtomicInteger()
        val mock = engine { req ->
            when {
                req.url.encodedPath.endsWith("/flaky") && calls.incrementAndGet() == 1 -> respond("busy", HttpStatusCode.ServiceUnavailable)
                req.headers[HttpHeaders.Range] != null -> respond(body.copyOfRange(0, 8), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-7/64"))
                else -> ok(body)
            }
        }
        KtorHttp(s, mock).use { http ->
            assertIs<HttpResult.Ok<*>>(http.get("https://a.test/flaky"))
            assertIs<HttpResult.Ok<*>>(http.getRange("https://a.test/r", 0, 8))
            assertIs<HttpResult.Ok<*>>(http.head("https://a.test/h"))
            val dl = http.download("https://a.test/d", DownloadRequest(maxBytes = 1024))
            assertIs<HttpResult.Ok<TempDownload>>(dl).value.close()
            assertIs<HttpResult.Ok<*>>(http.digest("https://a.test/g", DownloadRequest(maxBytes = 1024)))
        }
        assertEquals(6, mock.requestHistory.size, "재시도 1회 포함")
        for (r in mock.requestHistory) assertEquals(s.userAgent, r.headers[HttpHeaders.UserAgent], "UA 누락: ${r.url}")
        assertTrue(s.userAgent.startsWith("DecaCross/"))
    }

    @Test
    fun retry_429_503_200_succeeds_threeAttempts(): Unit = runBlocking {
        val n = AtomicInteger()
        val mock = engine {
            when (n.incrementAndGet()) {
                1 -> respond("slow down", HttpStatusCode.TooManyRequests)
                2 -> respond("oops", HttpStatusCode.ServiceUnavailable)
                else -> ok("done".encodeToByteArray())
            }
        }
        val result = KtorHttp(settings(), mock).use { it.get("https://api.test/x") }
        val okResult = assertIs<HttpResult.Ok<ByteArray>>(result)
        assertEquals("done", okResult.value.decodeToString())
        assertEquals(3, mock.requestHistory.size)
    }

    @Test
    fun noRetry_404_403(): Unit = runBlocking {
        val mock = engine { req ->
            if (req.url.encodedPath.endsWith("404")) respond("nope", HttpStatusCode.NotFound) else respond("denied", HttpStatusCode.Forbidden)
        }
        KtorHttp(settings(), mock).use { http ->
            val a = assertIs<HttpResult.Status>(http.get("https://api.test/404"))
            assertEquals(404, a.meta.status)
            assertEquals("nope", a.bodySnippet)
            val b = assertIs<HttpResult.Status>(http.get("https://api.test/403"))
            assertEquals(403, b.meta.status)
        }
        assertEquals(2, mock.requestHistory.size)
    }

    @Test
    fun retryAfter_capped_byMaxRetryDelay(): Unit = runBlocking {
        val n = AtomicInteger()
        val mock = engine {
            if (n.incrementAndGet() == 1) {
                respond("later", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
            } else {
                ok("ok".encodeToByteArray())
            }
        }
        val started = TimeSource.Monotonic.markNow()
        val result = KtorHttp(settings(maxRetryDelayMs = 50), mock).use { it.get("https://api.test/limited") }
        val took = started.elapsedNow()
        assertIs<HttpResult.Ok<ByteArray>>(result)
        assertEquals(2, mock.requestHistory.size)
        assertTrue(took < 5.seconds, "Retry-After 3600 초가 maxRetryDelayMs 로 잘려야 한다: $took")
    }

    @Test
    fun xRatelimitReset_penalizesHost(): Unit = runBlocking {
        val arrivals = CopyOnWriteArrayList<ComparableTimeMark>()
        val mock = engine { req ->
            arrivals += TimeSource.Monotonic.markNow()
            if (req.url.encodedPath.endsWith("/first")) {
                ok("a".encodeToByteArray(), extra = mapOf("X-Ratelimit-Remaining" to "0", "X-Ratelimit-Reset" to "1"))
            } else {
                ok("b".encodeToByteArray())
            }
        }
        KtorHttp(settings(maxRetryDelayMs = 400), mock).use { http ->
            assertIs<HttpResult.Ok<*>>(http.get("https://api.modrinth.test/first"))
            assertTrue(http.pacer.remainingPause("api.modrinth.test") > 200.milliseconds, "Remaining: 0 이면 호스트를 멈춘다")
            assertIs<HttpResult.Ok<*>>(http.get("https://api.modrinth.test/second"))
            // 다른 호스트는 영향 없음
            assertEquals(kotlin.time.Duration.ZERO, http.pacer.remainingPause("other.test"))
        }
        val gap = arrivals[1] - arrivals[0]
        assertTrue(gap >= 300.milliseconds, "X-Ratelimit-Reset(1 s, 상한 400 ms) 만큼 기다려야 한다: $gap")
    }

    @Test
    fun get_notModified_sendsConditionalHeadersVerbatim(): Unit = runBlocking {
        val etag = "0x8DF1A2B3C4D5E6F"
        val lastModified = "Wed, 16 Sep 2026 10:00:00 GMT"
        val mock = engine { req ->
            if (req.headers[HttpHeaders.IfNoneMatch] == etag && req.headers[HttpHeaders.IfModifiedSince] == lastModified) {
                respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, etag))
            } else {
                respond("headers mangled: ${req.headers[HttpHeaders.IfNoneMatch]}", HttpStatusCode.BadRequest)
            }
        }
        val result = KtorHttp(settings(), mock).use { it.get("https://piston-meta.test/manifest.json", Conditional(etag, lastModified)) }
        val nm = assertIs<HttpResult.NotModified>(result)
        assertEquals(304, nm.meta.status)
        assertEquals(etag, nm.meta.etag)
    }

    @Test
    fun get_maxBytes_tooLarge(): Unit = runBlocking {
        val mock = engine { req -> ok(bytes(100), withLength = req.url.encodedPath.endsWith("/declared")) }
        KtorHttp(settings(), mock).use { http ->
            assertEquals(FailureKind.TOO_LARGE, assertIs<HttpResult.Failure>(http.get("https://x.test/declared", maxBytes = 10)).kind)
            assertEquals(FailureKind.TOO_LARGE, assertIs<HttpResult.Failure>(http.get("https://x.test/stream", maxBytes = 10)).kind)
            val fits = assertIs<HttpResult.Ok<ByteArray>>(http.get("https://x.test/stream", maxBytes = 100))
            assertContentEquals(bytes(100), fits.value)
            assertEquals(200, fits.meta.status)
        }
    }

    @Test
    fun get_shortBody_vsContentLength_network(): Unit = runBlocking {
        val mock = engine { respond(bytes(5), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "10")) }
        val result = KtorHttp(settings(), mock).use { it.get("https://x.test/short") }
        assertEquals(FailureKind.NETWORK, assertIs<HttpResult.Failure>(result).kind)
    }

    @Test
    fun getRange_206_parsesTotal(): Unit = runBlocking {
        val mock = engine { req ->
            if (req.headers[HttpHeaders.Range] == "bytes=10-19") {
                respond(bytes(10), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 10-19/1234"))
            } else {
                respond("bad range header ${req.headers[HttpHeaders.Range]}", HttpStatusCode.BadRequest)
            }
        }
        KtorHttp(settings(), mock).use { http ->
            val ok = assertIs<HttpResult.Ok<ByteArray>>(http.getRange("https://x.test/client.jar", 10, 10))
            assertEquals(1234L, ok.meta.totalLength)
            assertEquals(206, ok.meta.status)
            assertContentEquals(bytes(10), ok.value)
            assertEquals(FailureKind.BAD_RANGE, assertIs<HttpResult.Failure>(http.getRange("https://x.test/client.jar", -1, 10)).kind)
            assertEquals(FailureKind.BAD_RANGE, assertIs<HttpResult.Failure>(http.getRange("https://x.test/client.jar", 0, 0)).kind)
        }
        assertEquals(1, mock.requestHistory.size, "잘못된 범위는 요청을 보내지 않는다")
    }

    @Test
    fun getRange_200_isBadRange(): Unit = runBlocking {
        val mock = engine { ok(bytes(1000)) }
        val result = KtorHttp(settings(), mock).use { it.getRange("https://x.test/ignores-range.jar", 0, 16) }
        val failure = assertIs<HttpResult.Failure>(result)
        assertEquals(FailureKind.BAD_RANGE, failure.kind)
        assertTrue(failure.message.contains("ignored Range"))
    }

    @Test
    fun download_sha256Ok_writesTemp_closeDeletes(): Unit = runBlocking {
        val body = bytes(200_000)
        val sha = FakeHttp.hex(DigestAlgo.SHA256, body)
        val mock = engine { ok(body) }
        val result = KtorHttp(settings(), mock).use {
            it.download("https://cdn.test/plugin.jar", DownloadRequest(maxBytes = 1_000_000, expected = mapOf(DigestAlgo.SHA256 to sha.uppercase())))
        }
        val dl = assertIs<HttpResult.Ok<TempDownload>>(result).value
        assertTrue(dl.path.startsWith(dir), "tempDir 아래에 쓴다: ${dl.path}")
        assertTrue(dl.path.fileName.toString().startsWith("dl-") && dl.path.fileName.toString().endsWith(".part"))
        assertContentEquals(body, Files.readAllBytes(dl.path))
        assertEquals(body.size.toLong(), dl.size)
        assertEquals(sha, dl.digests[DigestAlgo.SHA256])
        assertEquals(1, countFiles(dir))
        dl.close()
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun download_mismatch_deletes_noRetry(): Unit = runBlocking {
        val body = bytes(1000)
        val withLength = engine { ok(body) }
        val request = DownloadRequest(maxBytes = 10_000, expected = mapOf(DigestAlgo.SHA256 to "00".repeat(32)))
        val a = KtorHttp(settings(), withLength).use { it.download("https://cdn.test/a.jar", request) }
        assertEquals(FailureKind.DIGEST_MISMATCH, assertIs<HttpResult.Failure>(a).kind)
        assertEquals(1, withLength.requestHistory.size, "Content-Length 가 있으면 재시도하지 않는다")
        assertEquals(0, countFiles(dir))

        // Content-Length 가 없으면 잘린 전송일 수 있어 딱 한 번 더 받는다
        val noLength = engine { ok(body, withLength = false) }
        val b = KtorHttp(settings(), noLength).use { it.download("https://cdn.test/b.jar", request) }
        assertEquals(FailureKind.DIGEST_MISMATCH, assertIs<HttpResult.Failure>(b).kind)
        assertEquals(2, noLength.requestHistory.size)
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun download_midStreamFailure_retriedThenOk(): Unit = runBlocking {
        val body = bytes(100_000)
        val n = AtomicInteger()
        val mock = engine {
            if (n.incrementAndGet() == 1) {
                respond(brokenBody(body.copyOf(30_000)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            } else {
                ok(body)
            }
        }
        val result = KtorHttp(settings(), mock).use { it.download("https://cdn.test/flaky.jar", DownloadRequest(maxBytes = 1_000_000)) }
        val dl = assertIs<HttpResult.Ok<TempDownload>>(result).value
        assertEquals(2, mock.requestHistory.size)
        assertContentEquals(body, Files.readAllBytes(dl.path))
        assertEquals(1, countFiles(dir), "실패한 시도의 임시 파일은 지워졌다")
        dl.close()
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun download_shortBody_retriedAsNetwork(): Unit = runBlocking {
        val body = bytes(5_000)
        val n = AtomicInteger()
        val mock = engine {
            if (n.incrementAndGet() == 1) {
                respond(body.copyOf(1_000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            } else {
                ok(body)
            }
        }
        KtorHttp(settings(), mock).use { http ->
            val dl = assertIs<HttpResult.Ok<TempDownload>>(http.download("https://cdn.test/short.jar", DownloadRequest(maxBytes = 1_000_000))).value
            assertEquals(5_000L, dl.size)
            dl.close()
        }
        assertEquals(2, mock.requestHistory.size)

        val alwaysShort = engine { respond(body.copyOf(1_000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString())) }
        val failed = KtorHttp(settings(), alwaysShort).use { it.download("https://cdn.test/short.jar", DownloadRequest(maxBytes = 1_000_000)) }
        assertEquals(FailureKind.NETWORK, assertIs<HttpResult.Failure>(failed).kind)
        assertEquals(HttpSettings().downloadAttempts, alwaysShort.requestHistory.size)
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun download_tooLarge_deletes(): Unit = runBlocking {
        val mock = engine { req -> ok(bytes(100_000), withLength = req.url.encodedPath.endsWith("declared.jar")) }
        KtorHttp(settings(), mock).use { http ->
            val a = http.download("https://cdn.test/stream.jar", DownloadRequest(maxBytes = 10_000))
            assertEquals(FailureKind.TOO_LARGE, assertIs<HttpResult.Failure>(a).kind)
            val b = http.download("https://cdn.test/declared.jar", DownloadRequest(maxBytes = 10_000))
            assertEquals(FailureKind.TOO_LARGE, assertIs<HttpResult.Failure>(b).kind)
        }
        assertEquals(2, mock.requestHistory.size, "TOO_LARGE 는 재시도하지 않는다")
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun download_cancelledMidStream_noTempLeft(): Unit = runBlocking {
        val firstChunkSent = CompletableDeferred<Unit>()
        val mock = engine {
            val ch = ByteChannel()
            background.launch {
                ch.writeFully(bytes(64 * 1024))
                ch.flush()
                firstChunkSent.complete(Unit)
                awaitCancellation()
            }
            respond(ch, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "10000000"))
        }
        KtorHttp(settings(), mock).use { http ->
            val job = launch(Dispatchers.Default) { http.download("https://cdn.test/slow.jar", DownloadRequest(maxBytes = 100_000_000)) }
            withTimeout(10.seconds) { firstChunkSent.await() }
            withTimeout(10.seconds) { while (countFiles(dir) == 0) delay(10) }
            delay(200)
            job.cancelAndJoin()
        }
        assertEquals(0, countFiles(dir), "취소돼도 임시 파일이 남지 않는다")
    }

    @Test
    fun digest_writesNothing(): Unit = runBlocking {
        val body = bytes(300_000)
        val mock = engine { ok(body) }
        Files.createDirectories(dir)
        val result = KtorHttp(settings(), mock).use { it.digest("https://api.purpur.test/download", DownloadRequest(maxBytes = 1_000_000)) }
        val d = assertIs<HttpResult.Ok<StreamDigest>>(result).value
        assertEquals(body.size.toLong(), d.size)
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, body), d.digests[DigestAlgo.SHA256])
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun digest_computesAlgorithmsUnionExpected(): Unit = runBlocking {
        val body = bytes(4_096)
        val md5 = FakeHttp.hex(DigestAlgo.MD5, body)
        val mock = engine { ok(body) }
        val request = DownloadRequest(maxBytes = 1_000_000, algorithms = setOf(DigestAlgo.SHA256), expected = mapOf(DigestAlgo.MD5 to md5))
        val result = KtorHttp(settings(), mock).use { it.digest("https://api.purpur.test/download", request) }
        val d = assertIs<HttpResult.Ok<StreamDigest>>(result).value
        assertEquals(setOf(DigestAlgo.SHA256, DigestAlgo.MD5), d.digests.keys)
        assertEquals(md5, d.digests[DigestAlgo.MD5])
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, body), d.digests[DigestAlgo.SHA256])

        val wrong = KtorHttp(settings(), engine { ok(body) }).use {
            it.digest("https://api.purpur.test/download", request.copy(expected = mapOf(DigestAlgo.MD5 to "0".repeat(32))))
        }
        assertEquals(FailureKind.DIGEST_MISMATCH, assertIs<HttpResult.Failure>(wrong).kind)
    }

    @Test
    fun unresolvedAddress_retriedThenNetworkFailure(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val mock = engine {
            attempts.incrementAndGet()
            throw UnresolvedAddressException()
        }
        val result = KtorHttp(settings(maxRetries = 2), mock).use { it.get("https://no-such-host.test/x") }
        assertEquals(FailureKind.NETWORK, assertIs<HttpResult.Failure>(result).kind)
        assertEquals(3, attempts.get(), "maxRetries=2 → 3 번 시도")
    }

    @Test
    fun unexpectedException_mappedToNetwork_notThrown(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val mock = engine {
            attempts.incrementAndGet()
            throw IllegalStateException("예상 못 한 오류 (테스트)")
        }
        val result = KtorHttp(settings(), mock).use { it.get("https://x.test/boom") }
        val failure = assertIs<HttpResult.Failure>(result)
        assertEquals(FailureKind.NETWORK, failure.kind)
        assertTrue(failure.message.contains("예상 못 한 오류"), failure.message)
        assertEquals(1, attempts.get(), "분류되지 않은 예외는 재시도하지 않는다")
    }

    @Test
    fun url_percentEncoding_isPreserved(): Unit = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val mock = engine { req ->
            paths += req.url.encodedPath
            ok("{}".encodeToByteArray())
        }
        KtorHttp(settings(), mock).use { http ->
            http.get("https://piston-meta.mojang.com/v1/packages/0123abcd/1.14.2%20Pre-Release%204.json")
            http.get("https://hangar.papermc.io/api/v1/projects/ViaVersion/versions/5.12.0-SNAPSHOT%2B626/PAPER/download")
            http.get("https://hangar.papermc.io/api/v1/projects/ViaVersion/versions/5.12.0-SNAPSHOT+626/PAPER/download")
        }
        assertTrue(paths[0].endsWith("/1.14.2%20Pre-Release%204.json"), paths[0])
        assertTrue(paths[1].contains("/5.12.0-SNAPSHOT%2B626/"), paths[1])
        assertTrue(paths[2].contains("/5.12.0-SNAPSHOT+626/"), paths[2])
    }
}
