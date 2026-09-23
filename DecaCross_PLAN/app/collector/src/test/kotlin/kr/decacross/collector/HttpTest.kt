package kr.decacross.collector

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpTest {
    @Test
    fun rateLimiter_sameHostWaitsBetweenRequests() = runTest {
        val sleeper = RecordingSleeper()
        val engine = MockEngine { respond("ok") }
        testHttp(engine, sleeper).use { http ->
            http.getText("https://a.example/1")
            http.getText("https://a.example/2")
            http.getText("https://b.example/1")
        }
        // 같은 호스트 2번째 요청만 500ms 대기, 다른 호스트는 대기 없음
        assertEquals(listOf(500L), sleeper.sleeps)
    }

    @Test
    fun userAgent_isSentOnEveryRequest() = runTest {
        var seen: String? = null
        val engine = MockEngine { req ->
            seen = req.headers[HttpHeaders.UserAgent]
            respond("ok")
        }
        testHttp(engine).use { it.getText("https://a.example/") }
        assertEquals("DecaCross-test/0 (+test)", seen)
    }

    @Test
    fun backoff_retriesOn5xxThenSucceeds() = runTest {
        val sleeper = RecordingSleeper()
        var n = 0
        val engine = MockEngine {
            n += 1
            if (n < 3) respond("boom", HttpStatusCode.ServiceUnavailable) else respond("ok")
        }
        val r = testHttp(engine, sleeper).use { it.getText("https://a.example/") }
        assertIs<HttpOutcome.Ok<String>>(r)
        assertEquals("ok", r.value)
        assertEquals(3, n)
        // 백오프 500 → 1500 (요청 간 레이트리밋 대기는 시계가 백오프만큼 흘러 0)
        assertEquals(listOf(500L, 1500L), sleeper.sleeps)
    }

    @Test
    fun backoff_honorsRetryAfter() = runTest {
        val sleeper = RecordingSleeper()
        var n = 0
        val engine = MockEngine {
            n += 1
            if (n == 1) respond("slow", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7")) else respond("ok")
        }
        val r = testHttp(engine, sleeper).use { it.getText("https://a.example/") }
        assertIs<HttpOutcome.Ok<String>>(r)
        assertEquals(listOf(7_000L), sleeper.sleeps)
    }

    @Test
    fun backoff_givesUpAfterThreeTries() = runTest {
        val sleeper = RecordingSleeper()
        var n = 0
        val engine = MockEngine {
            n += 1
            respond("boom", HttpStatusCode.BadGateway)
        }
        val r = testHttp(engine, sleeper).use { it.getText("https://a.example/") }
        assertIs<HttpOutcome.Failed>(r)
        assertEquals(502, r.status)
        assertEquals(3, n)
        assertEquals(listOf(500L, 1500L), sleeper.sleeps)
    }

    @Test
    fun clientError_doesNotRetry() = runTest {
        var n = 0
        val engine = MockEngine {
            n += 1
            respond("nope", HttpStatusCode.NotFound)
        }
        val r = testHttp(engine).use { it.getText("https://a.example/") }
        assertIs<HttpOutcome.Failed>(r)
        assertEquals(404, r.status)
        assertEquals(1, n)
    }

    @kotlinx.serialization.Serializable
    data class Small(val a: Int)

    @Test
    fun getJson_decodesWithUnknownKeysIgnored() = runTest {
        val engine = MockEngine { respond("""{"a":1,"zzz":true}""") }
        val r = testHttp(engine).use { it.getJson<Small>("https://a.example/") }
        assertIs<HttpOutcome.Ok<Small>>(r)
        assertTrue(r.value.a == 1)
    }
}
