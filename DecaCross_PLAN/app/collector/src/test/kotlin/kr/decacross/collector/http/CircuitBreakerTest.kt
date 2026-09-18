package kr.decacross.collector.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.HostPolicy
import kr.decacross.collector.config.HttpSettings
import kr.decacross.collector.http.CircuitBreaker.Admission
import kr.decacross.collector.http.CircuitBreaker.Outcome
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CircuitBreakerTest {
    private val dir = newTempDir("decacross-circuit-")

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun settings(failures: Int = 3, openMs: Long = 1_000, tempDir: Path = dir): CollectorSettings = CollectorSettings(
        tempDir = tempDir,
        http = HttpSettings(
            circuitBreakerFailures = failures,
            circuitOpenMs = openMs,
            maxRetryDelayMs = 20,
            hostPolicies = emptyMap(),
            defaultHostPolicy = HostPolicy(0, 8),
        ),
    )

    @Test
    fun circuitBreaker_opensAfterN_andFailsFast() {
        val time = TestTimeSource()
        val cb = CircuitBreaker(settings(failures = 3), time)
        repeat(2) {
            assertEquals(Admission.ALLOWED, cb.acquire("h.test"))
            cb.record("h.test", Admission.ALLOWED, Outcome.FAILURE)
        }
        assertFalse(cb.isOpen("h.test"))
        // 성공은 카운터를 0 으로
        cb.record("h.test", cb.acquire("h.test"), Outcome.SUCCESS)
        repeat(2) { cb.record("h.test", cb.acquire("h.test"), Outcome.FAILURE) }
        // 중립(404 등)은 카운터를 바꾸지 않는다
        cb.record("h.test", cb.acquire("h.test"), Outcome.NEUTRAL)
        assertFalse(cb.isOpen("h.test"))
        cb.record("h.test", cb.acquire("h.test"), Outcome.FAILURE)
        assertTrue(cb.isOpen("h.test"))
        assertEquals(Admission.REJECTED, cb.acquire("h.test"))
        time += 999.milliseconds
        assertEquals(Admission.REJECTED, cb.acquire("h.test"))
        // 다른 호스트는 영향 없음
        assertEquals(Admission.ALLOWED, cb.acquire("other.test"))
    }

    @Test
    fun circuitBreaker_halfOpen_singleProbe() {
        val time = TestTimeSource()
        val cb = CircuitBreaker(settings(failures = 1, openMs = 1_000), time)
        cb.record("h.test", cb.acquire("h.test"), Outcome.FAILURE)
        assertEquals(Admission.REJECTED, cb.acquire("h.test"))

        time += 1_000.milliseconds
        assertEquals(Admission.PROBE, cb.acquire("h.test"), "만료 후 첫 호출은 탐침")
        assertEquals(Admission.REJECTED, cb.acquire("h.test"), "탐침이 도는 동안 다른 호출은 즉시 실패")
        cb.record("h.test", Admission.PROBE, Outcome.FAILURE)
        assertEquals(Admission.REJECTED, cb.acquire("h.test"), "탐침 실패 → 다시 OPEN")

        time += 1_000.milliseconds
        assertEquals(Admission.PROBE, cb.acquire("h.test"))
        // 결과 없이 끝난 탐침(취소)은 다음 호출이 다시 탐침이 되게 한다
        cb.record("h.test", Admission.PROBE, null)
        assertEquals(Admission.PROBE, cb.acquire("h.test"))
        cb.record("h.test", Admission.PROBE, Outcome.SUCCESS)
        assertFalse(cb.isOpen("h.test"))
        assertEquals(Admission.ALLOWED, cb.acquire("h.test"))
        assertEquals(Admission.ALLOWED, cb.acquire("h.test"))
    }

    @Test
    fun circuitBreaker_countsLogicalCalls_notAttempts(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val mock = MockEngine {
            attempts.incrementAndGet()
            respond("down", HttpStatusCode.ServiceUnavailable)
        }
        val s = settings(failures = 2, openMs = 60_000).let { it.copy(http = it.http.copy(maxRetries = 4)) }
        KtorHttp(s, mock).use { http ->
            val first = assertIs<HttpResult.Status>(http.get("https://down.test/a"))
            assertEquals(503, first.meta.status)
            assertEquals(5, attempts.get(), "재시도 4회 + 1")
            assertFalse(http.breaker.isOpen("down.test"), "재시도 5번은 실패 1번으로 센다")
            assertIs<HttpResult.Status>(http.get("https://down.test/b"))
            assertEquals(10, attempts.get())
            assertTrue(http.breaker.isOpen("down.test"))
            val third = assertIs<HttpResult.Failure>(http.get("https://down.test/c"))
            assertEquals(FailureKind.CIRCUIT_OPEN, third.kind)
            assertEquals(10, attempts.get(), "OPEN 이면 요청을 보내지 않는다")
            // 다운로드도 같은 차단기를 쓴다
            assertEquals(FailureKind.CIRCUIT_OPEN, assertIs<HttpResult.Failure>(http.download("https://down.test/x.jar", DownloadRequest(1024))).kind)
            assertEquals(10, attempts.get())
        }
    }

    @Test
    fun classify_mapsResults() {
        assertEquals(Outcome.SUCCESS, CircuitBreaker.classify(HttpResult.Ok(Unit, ResponseMeta(206))))
        assertEquals(Outcome.SUCCESS, CircuitBreaker.classify(HttpResult.NotModified(ResponseMeta(304))))
        assertEquals(Outcome.FAILURE, CircuitBreaker.classify(HttpResult.Status(ResponseMeta(429), "")))
        assertEquals(Outcome.FAILURE, CircuitBreaker.classify(HttpResult.Status(ResponseMeta(502), "")))
        assertEquals(Outcome.NEUTRAL, CircuitBreaker.classify(HttpResult.Status(ResponseMeta(404), "")))
        assertEquals(Outcome.FAILURE, CircuitBreaker.classify(HttpResult.Failure(FailureKind.TIMEOUT, "")))
        assertEquals(Outcome.FAILURE, CircuitBreaker.classify(HttpResult.Failure(FailureKind.NETWORK, "")))
        assertEquals(Outcome.NEUTRAL, CircuitBreaker.classify(HttpResult.Failure(FailureKind.DIGEST_MISMATCH, "")))
    }
}
