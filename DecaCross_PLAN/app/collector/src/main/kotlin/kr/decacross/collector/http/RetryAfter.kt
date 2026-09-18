package kr.decacross.collector.http

import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/**
 * `Retry-After` 헤더 → 기다릴 밀리초.
 *
 * - delta-seconds (`120`) 와 HTTP-date (`Wed, 21 Oct 2015 07:28:00 GMT`, RFC 1123) 둘 다 받는다.
 * - 결과는 0 이상으로 자른다 (과거 날짜 → 0).
 * - 해석할 수 없으면 null.
 *
 * Ktor 기본 처리(`respectRetryAfterHeader`)는 초 단위만 읽고 상한이 없어서 쓰지 않는다 (D22).
 */
fun parseRetryAfterMillis(value: String, now: Instant): Long? {
    val v = value.trim()
    if (v.isEmpty()) return null
    val seconds = v.toLongOrNull()
    if (seconds != null) return secondsToMillis(seconds)
    return try {
        val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - now.toEpochMilliseconds()).coerceAtLeast(0L)
    } catch (e: DateTimeException) {
        null
    }
}

/** `X-Ratelimit-Reset` (Modrinth: 초) → 밀리초. 해석할 수 없으면 null. */
fun parseRateLimitResetMillis(value: String): Long? = value.trim().toLongOrNull()?.let(::secondsToMillis)

private fun secondsToMillis(seconds: Long): Long = when {
    seconds <= 0L -> 0L
    seconds >= Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
    else -> seconds * 1_000L
}
