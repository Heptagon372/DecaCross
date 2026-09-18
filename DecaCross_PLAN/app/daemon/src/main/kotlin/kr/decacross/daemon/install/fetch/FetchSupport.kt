package kr.decacross.daemon.install.fetch

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kr.decacross.daemon.install.RetryPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.channels.UnsupportedAddressTypeException
import java.nio.file.Path
import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/*
 * 다운로드 보조 순수 함수 모음 (SCP-F4 / D-I25).
 *
 * 수집기의 `app/collector/src/main/kotlin/kr/decacross/collector/http/RetryAfter.kt` 와
 * `KtorHttp.Companion`(`isRetryableException`, `retryDelayMillis`, `parseContentRangeTotal`)에서 옮겨 적었다.
 * 공용 네트워크 모듈을 새로 만들지 않은 것은 모듈 소유권 때문이고, 수집기 쪽 원본은 손대지 않는다.
 * 설치 다운로드는 "큰 파일 몇 개"라 수집기의 호스트 페이서·서킷 브레이커는 가져오지 않았다.
 */

/** 재시도해도 되는 상태 코드 (AE-20, RFC 9110). */
internal val RETRYABLE_STATUSES: Set<Int> = setOf(408, 429, 500, 502, 503, 504)

/** 8 MiB 마다 `force` + 메타 갱신 (AE-21). */
internal const val CHECKPOINT_BYTES: Long = 8L * 1024 * 1024

/** 스트리밍 버퍼 크기. */
internal const val STREAM_BUFFER_BYTES: Int = 64 * 1024

/** 부분 파일 보관 기간 (AE-21: 7일). */
internal const val PARTIAL_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** 같은 sha256 을 다른 프로세스가 받는 중일 때 기다리는 한계와 폴링 간격. */
internal const val CROSS_PROCESS_LOCK_WAIT_MS: Long = 60_000

internal const val CROSS_PROCESS_LOCK_POLL_MS: Long = 500

/** 로컬 디스크 오류 표시용. `IOException` 이 아니어야 네트워크 오류로 섞이지 않는다 (수집기와 같은 수법). */
internal class LocalIoException(val path: Path, cause: Exception) : RuntimeException(cause)

/**
 * `Retry-After` 헤더 → 기다릴 밀리초.
 *
 * - delta-seconds (`120`) 와 HTTP-date (`Wed, 21 Oct 2015 07:28:00 GMT`, RFC 1123) 둘 다 받는다.
 * - 결과는 0 이상으로 자른다 (과거 날짜 → 0).
 * - 해석할 수 없으면 null.
 */
internal fun parseRetryAfterMillis(value: String, nowEpochMs: Long): Long? {
    val v = value.trim()
    if (v.isEmpty()) return null
    val seconds = v.toLongOrNull()
    if (seconds != null) return secondsToMillis(seconds)
    val at = parseHttpDateMillis(v) ?: return null
    return (at - nowEpochMs).coerceAtLeast(0L)
}

private fun secondsToMillis(seconds: Long): Long = when {
    seconds <= 0L -> 0L
    seconds >= Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
    else -> seconds * 1_000L
}

/** RFC 1123 HTTP-date → epoch ms. 해석할 수 없으면 null. */
internal fun parseHttpDateMillis(value: String): Long? = try {
    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (e: DateTimeException) {
    null
}

/**
 * 다시 시도해도 되는 예외인가 (수집기 `isRetryableException` + 설계 §2.11 표).
 *
 * `io.ktor.utils.io.ClosedByteChannelException` 과 Ktor 의 소켓 타임아웃은 모두 `IOException` 계열이라 첫 줄에서 걸린다.
 * `UnresolvedAddressException`/`UnsupportedAddressTypeException` 은 `IllegalArgumentException` 계열이라 따로 적는다.
 */
internal fun isRetryableFetchException(cause: Throwable): Boolean = when (cause) {
    is IOException,
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException,
    is UnresolvedAddressException,
    is UnsupportedAddressTypeException,
    -> true

    else -> false
}

/**
 * 재시도 지연 (D-I23): `min(base·2^(failures-1), maxDelay) + jitter`.
 * 새 바이트를 받은 시도([progressed])는 실패로 세지 않으므로 기본 지연만 준다.
 *
 * @param failures 이 소스에서 연속 실패한 횟수 (1 부터)
 */
internal fun backoffDelayMs(
    failures: Int,
    policy: RetryPolicy,
    progressed: Boolean,
    jitterMs: Long = randomJitterMs(policy.maxJitterMs),
): Long {
    if (progressed) return policy.baseDelayMs
    val shift = (failures - 1).coerceIn(0, 20)
    val exponential = if (policy.baseDelayMs <= 0) 0L else minOf(policy.baseDelayMs shl shift, policy.maxDelayMs)
    return (exponential + jitterMs).coerceAtLeast(0L)
}

internal fun randomJitterMs(maxJitterMs: Long): Long = if (maxJitterMs <= 0L) 0L else Random.nextLong(0L, maxJitterMs)

/** `Content-Range: bytes a-b/T` 의 조각. [total] 이 `*` 이면 null. */
internal data class ContentRangeParts(val start: Long, val endInclusive: Long, val total: Long?)

private val CONTENT_RANGE_RE = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

/** `bytes a-b/T` 파싱. 형식이 틀리면 null (416 의 별표 범위는 [parseContentRangeTotal] 로 읽는다). */
internal fun parseContentRange(value: String): ContentRangeParts? {
    val m = CONTENT_RANGE_RE.matchEntire(value.trim()) ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: return null
    val end = m.groupValues[2].toLongOrNull() ?: return null
    val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
    return ContentRangeParts(start, end, total)
}

/** 416 응답의 `Content-Range` 에서 마지막 슬래시 뒤 TOTAL 만 읽는다. 별표거나 형식이 틀리면 null. */
internal fun parseContentRangeTotal(value: String): Long? = value.substringAfterLast('/', "").trim().toLongOrNull()

/** 강한 ETag 만 돌려준다 (`W/` 로 시작하면 If-Range 에 쓸 수 없다 — RFC 9110 §13.1.5). */
internal fun strongEtag(raw: String?): String? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty() || v.startsWith("W/")) return null
    return v
}

/**
 * `Last-Modified` 를 If-Range 검증자로 써도 되는가 (RFC 9110 §8.8.2.2).
 * 원 서버의 `Date` 와 1초 이상 차이가 나야 "강한" 검증자로 인정된다.
 */
internal fun lastModifiedValidator(lastModified: String?, date: String?): String? {
    val lm = lastModified?.trim().orEmpty()
    if (lm.isEmpty() || date == null) return null
    val lmMs = parseHttpDateMillis(lm) ?: return null
    val dateMs = parseHttpDateMillis(date) ?: return null
    return if (dateMs - lmMs >= 1_000L) lm else null
}

/** 응답 헤더 → If-Range 검증자. 강한 ETag 우선, 없으면 조건을 만족하는 `Last-Modified`. */
internal fun chooseValidator(etag: String?, lastModified: String?, date: String?): String? =
    strongEtag(etag) ?: lastModifiedValidator(lastModified, date)

/** `Content-Type` 이 `text/...` 인가 (포털·프록시가 끼워 넣은 HTML). */
internal fun isTextContentType(contentType: String?): Boolean =
    contentType?.trim()?.startsWith("text/", ignoreCase = true) == true

/** `Content-Encoding` 이 identity 가 아닌가 (Range 오프셋이 깨진다). */
internal fun isTransformingEncoding(contentEncoding: String?): Boolean {
    val v = contentEncoding?.trim().orEmpty()
    return v.isNotEmpty() && !v.equals("identity", ignoreCase = true)
}
