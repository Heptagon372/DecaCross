package kr.decacross.collector.http

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 스트리밍 해시 알고리즘. */
enum class DigestAlgo(val jcaName: String) {
    SHA1("SHA-1"),
    SHA256("SHA-256"),
    SHA512("SHA-512"),
    MD5("MD5"),
}

/** 응답 메타데이터. 헤더 값은 원문 그대로 (ETag 따옴표를 벗기지 않는다 — Mojang 은 따옴표 없는 ETag 를 쓴다). */
data class ResponseMeta(
    val status: Int,
    val etag: String? = null,
    val lastModified: String? = null,
    val contentLength: Long? = null,
    /** `Content-Range: bytes a-b/TOTAL` 의 TOTAL. */
    val totalLength: Long? = null,
)

/** 조건부 요청. 둘 다 있으면 둘 다 보낸다 (Mojang 은 gzip 을 쓰지 않으므로 If-None-Match 가 동작한다). */
data class Conditional(val etag: String? = null, val lastModified: String? = null)

enum class FailureKind {
    /** 연결·DNS·본문 도중 끊김·Content-Length 보다 짧은 본문, 그 밖의 분류 안 된 예외 (재시도 후) */
    NETWORK,

    TIMEOUT,

    /** 본문이 maxBytes 초과 */
    TOO_LARGE,

    /** 기대 해시와 불일치 (임시 파일은 이미 삭제됨) */
    DIGEST_MISMATCH,

    /** Range 요청에 206 이 아닌 응답 / 요청 범위 밖 */
    BAD_RANGE,

    /** 로컬 디스크 I/O */
    IO,

    /** 호스트 연속 실패로 회로 차단 중 */
    CIRCUIT_OPEN,
}

/**
 * HTTP 결과. 예외 대신 sealed. 단, [kotlinx.coroutines.CancellationException] 은 그대로 전파된다.
 */
sealed interface HttpResult<out T> {
    data class Ok<out T>(val value: T, val meta: ResponseMeta) : HttpResult<T>

    /** 304. */
    data class NotModified(val meta: ResponseMeta) : HttpResult<Nothing>

    /** 재시도 후에도 2xx/304 가 아닌 상태 코드. [bodySnippet] 은 최대 512자. */
    data class Status(val meta: ResponseMeta, val bodySnippet: String) : HttpResult<Nothing>

    data class Failure(val kind: FailureKind, val message: String) : HttpResult<Nothing>
}

/** Ok 면 값, 아니면 null. */
fun <T> HttpResult<T>.valueOrNull(): T? = (this as? HttpResult.Ok<T>)?.value

/** 다운로드·스트리밍 해시 요청. */
data class DownloadRequest(
    /** 이 크기를 넘으면 즉시 중단하고 [FailureKind.TOO_LARGE]. */
    val maxBytes: Long,
    val algorithms: Set<DigestAlgo> = setOf(DigestAlgo.SHA256),
    /** 알고리즘 → 기대 hex(소문자 비교). 하나라도 다르면 [FailureKind.DIGEST_MISMATCH]. */
    val expected: Map<DigestAlgo, String> = emptyMap(),
)

/**
 * 임시 파일로 받은 다운로드. [close] 가 파일을 지운다.
 *
 * # 불변식
 * - 반드시 `use {}` 로 감싸라. 분석이 끝난 jar 는 즉시 폐기한다 (CLAUDE.md: jar 보관 금지).
 * - [close] 는 throw 하지 않는다. Windows 백신·인덱서가 방금 쓴 파일을 잡고 있을 수 있어 짧게 재시도하고,
 *   끝내 못 지우면 [onDeleteFailure] 로 알린다 (Runner 가 사이클 시작·종료 때 다시 쓸어 담는다).
 *   그래서 `use {}` 안에서 계산한 분석 결과가 삭제 실패로 사라지지 않는다.
 */
class TempDownload(
    val path: Path,
    val size: Long,
    /** 요청한 알고리즘 → 소문자 hex */
    val digests: Map<DigestAlgo, String>,
    private val onDeleteFailure: (Path, Exception) -> Unit = { _, _ -> },
) : AutoCloseable {
    override fun close() {
        var last: Exception? = null
        for (attempt in 0 until DELETE_ATTEMPTS) {
            try {
                Files.deleteIfExists(path)
                return
            } catch (e: IOException) {
                last = e
            } catch (e: SecurityException) {
                onDeleteFailure(path, e)
                return
            }
            try {
                Thread.sleep(minOf(DELETE_BASE_DELAY_MS shl attempt, DELETE_MAX_DELAY_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        onDeleteFailure(path, last ?: IOException("삭제 재시도 중단: $path"))
    }

    private companion object {
        const val DELETE_ATTEMPTS = 10
        const val DELETE_BASE_DELAY_MS = 50L
        const val DELETE_MAX_DELAY_MS = 500L
    }
}

/** 디스크에 쓰지 않고 스트리밍으로 계산한 해시. */
data class StreamDigest(val size: Long, val digests: Map<DigestAlgo, String>)

/**
 * 수집기의 유일한 외부 HTTP 통로. 구현(WP-C1 `KtorHttp`)이 UA·호스트별 간격·재시도·회로 차단을 책임진다.
 * 소스는 URL 을 **이미 인코딩된 문자열**로 넘기고, 구현은 다시 인코딩하지 않는다 (`%20` 보존).
 *
 * # 불변식
 * - 취소([kotlinx.coroutines.CancellationException]) 외의 어떤 예외도 밖으로 던지지 않는다 — 전부 [HttpResult] 로 바꾼다.
 * - 해시는 `request.algorithms ∪ request.expected.keys` 전부를 계산해 돌려준다.
 */
interface Http {
    /** GET 본문 전체. 304 면 [HttpResult.NotModified]. */
    suspend fun get(url: String, conditional: Conditional? = null, maxBytes: Int = DEFAULT_MAX_BODY_BYTES): HttpResult<ByteArray>

    /** `Range: bytes=offset-(offset+length-1)`. 206 만 Ok. [ResponseMeta.totalLength] 채움. */
    suspend fun getRange(url: String, offset: Long, length: Int): HttpResult<ByteArray>

    suspend fun head(url: String): HttpResult<Unit>

    /** 임시 디렉터리로 스트리밍 저장 + 해시. 실패 시 임시 파일을 남기지 않는다. */
    suspend fun download(url: String, request: DownloadRequest): HttpResult<TempDownload>

    /** 디스크에 쓰지 않고 스트리밍 해시만 (Purpur). */
    suspend fun digest(url: String, request: DownloadRequest): HttpResult<StreamDigest>

    companion object {
        const val DEFAULT_MAX_BODY_BYTES: Int = 32 * 1024 * 1024
    }
}
