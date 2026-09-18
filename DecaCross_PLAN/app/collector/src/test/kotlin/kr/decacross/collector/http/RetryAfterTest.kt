package kr.decacross.collector.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RetryAfterTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")

    @Test
    fun seconds() {
        assertEquals(120_000L, parseRetryAfterMillis("120", now))
        assertEquals(0L, parseRetryAfterMillis("0", now))
        assertEquals(5_000L, parseRetryAfterMillis(" 5 ", now))
        assertEquals(0L, parseRetryAfterMillis("-3", now), "음수는 0 으로 자른다")
        assertEquals(30_000L, parseRateLimitResetMillis("30"))
    }

    @Test
    fun httpDate_future() {
        assertEquals(90_000L, parseRetryAfterMillis("Thu, 17 Sep 2026 12:01:30 GMT", now))
    }

    @Test
    fun httpDate_past_isZero() {
        assertEquals(0L, parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
    }

    @Test
    fun garbage_null() {
        assertNull(parseRetryAfterMillis("soon", now))
        assertNull(parseRetryAfterMillis("", now))
        assertNull(parseRetryAfterMillis("2026-09-17T12:01:30Z", now))
        assertNull(parseRateLimitResetMillis("x"))
    }
}
