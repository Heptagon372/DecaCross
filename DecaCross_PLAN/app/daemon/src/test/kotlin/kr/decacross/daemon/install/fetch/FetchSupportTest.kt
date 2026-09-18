package kr.decacross.daemon.install.fetch

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.ClosedByteChannelException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 설계 §2.11 의 보조 함수 (수집기에서 옮겨 온 부분 포함). */
class FetchSupportTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `Retry-After 는 초와 HTTP-date 를 모두 읽는다`() {
        assertEquals(0L, parseRetryAfterMillis("0", now))
        assertEquals(120_000L, parseRetryAfterMillis("120", now))
        assertEquals(3_600_000L, parseRetryAfterMillis("3600", now))
        // 과거 날짜는 0 으로 자른다
        assertEquals(0L, parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
        val future = parseRetryAfterMillis("Wed, 21 Oct 2099 07:28:00 GMT", now)
        assertTrue(future != null && future > 0L, "미래 날짜는 양수여야 한다")
        assertNull(parseRetryAfterMillis("", now))
        assertNull(parseRetryAfterMillis("곧", now))
    }

    @Test
    fun `Content-Range 파싱`() {
        assertEquals(ContentRangeParts(100, 199, 1000), parseContentRange("bytes 100-199/1000"))
        assertEquals(ContentRangeParts(0, 9, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange("bytes */1000"))
        assertNull(parseContentRange("엉터리"))
        assertEquals(1000L, parseContentRangeTotal("bytes */1000"))
        assertNull(parseContentRangeTotal("bytes */*"))
    }

    @Test
    fun `약한 ETag 는 검증자가 아니다`() {
        assertEquals("\"v1\"", strongEtag("\"v1\""))
        assertNull(strongEtag("W/\"v1\""))
        assertNull(strongEtag(null))
        assertNull(strongEtag("  "))
    }

    @Test
    fun `Last-Modified 는 Date 와 1초 이상 차이날 때만 검증자다`() {
        val lm = "Wed, 21 Oct 2015 07:28:00 GMT"
        assertEquals(lm, lastModifiedValidator(lm, "Wed, 21 Oct 2015 07:28:10 GMT"))
        assertNull(lastModifiedValidator(lm, "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(lastModifiedValidator(lm, null))
        // 강한 ETag 가 있으면 그쪽이 이긴다
        assertEquals("\"v1\"", chooseValidator("\"v1\"", lm, "Wed, 21 Oct 2015 07:28:10 GMT"))
        assertEquals(lm, chooseValidator("W/\"v1\"", lm, "Wed, 21 Oct 2015 07:28:10 GMT"))
        assertNull(chooseValidator("W/\"v1\"", lm, null))
    }

    @Test
    fun `백오프는 지수로 늘고 상한에서 멈춘다`() {
        val policy = fastPolicy()
        assertEquals(5L, backoffDelayMs(1, policy, progressed = false, jitterMs = 0))
        assertEquals(10L, backoffDelayMs(2, policy, progressed = false, jitterMs = 0))
        assertEquals(20L, backoffDelayMs(3, policy, progressed = false, jitterMs = 0))
        assertEquals(20L, backoffDelayMs(9, policy, progressed = false, jitterMs = 0))
        // 새 바이트를 받은 시도는 기본 지연만
        assertEquals(5L, backoffDelayMs(9, policy, progressed = true, jitterMs = 0))
        assertEquals(21L, backoffDelayMs(3, policy, progressed = false, jitterMs = 1))
    }

    @Test
    fun `재시도 가능한 예외 분류`() {
        assertTrue(isRetryableFetchException(IOException("boom")))
        assertTrue(isRetryableFetchException(ClosedByteChannelException()))
        assertTrue(isRetryableFetchException(HttpRequestTimeoutException("http://x", 1)))
        assertTrue(isRetryableFetchException(UnresolvedAddressException()))
        assertFalse(isRetryableFetchException(IllegalStateException("bug")))
    }

    @Test
    fun `엉뚱한 본문과 변환 인코딩 판정`() {
        assertTrue(isTextContentType("text/html; charset=utf-8"))
        assertTrue(isTextContentType("TEXT/plain"))
        assertFalse(isTextContentType("application/java-archive"))
        assertFalse(isTextContentType(null))
        assertTrue(isTransformingEncoding("gzip"))
        assertFalse(isTransformingEncoding("identity"))
        assertFalse(isTransformingEncoding(null))
        assertFalse(isTransformingEncoding(""))
    }
}
