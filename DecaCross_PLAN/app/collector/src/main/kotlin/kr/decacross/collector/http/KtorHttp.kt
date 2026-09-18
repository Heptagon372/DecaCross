package kr.decacross.collector.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareHead
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.HttpSettings
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.UnresolvedAddressException
import java.nio.channels.UnsupportedAddressTypeException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [Http] 의 운영 구현 (Ktor CIO).
 *
 * 플러그인 순서가 의미를 가진다: 재시도(바깥) → 호스트 간격([HostPacer]) → 타임아웃(안쪽).
 * 그래서 재시도마다 다시 간격을 지키고, 타임아웃 예외도 재시도 대상이 된다.
 *
 * # 불변식
 * - 모든 요청에 [CollectorSettings.userAgent] (CLAUDE.md 불변식 17).
 * - 취소([CancellationException]) 외의 예외는 밖으로 던지지 않는다. 전부 [HttpResult] 로 바꾼다.
 * - URL 문자열은 이미 인코딩된 것으로 보고 그대로 쓴다 (`%20`, `%2B` 보존).
 * - 다운로드 임시 파일은 성공해서 [TempDownload] 로 넘긴 경우에만 남는다. 실패·취소 시 채널을 먼저 닫고 지운다.
 * - 회로 차단기는 논리 호출 1회당 한 번만 결과를 센다.
 *
 * @param engine 테스트용 엔진 (MockEngine). null 이면 CIO 엔진을 만들고 [close] 에서 닫는다.
 */
class KtorHttp(
    private val settings: CollectorSettings,
    engine: HttpClientEngine? = null,
) : Http,
    AutoCloseable {
    private val httpSettings: HttpSettings = settings.http
    internal val pacer: HostPacer = HostPacer(settings)
    internal val breaker: CircuitBreaker = CircuitBreaker(settings)
    private val ownedEngine: HttpClientEngine?
    private val client: HttpClient

    init {
        val actual = engine ?: createCioEngine(httpSettings)
        ownedEngine = if (engine == null) actual else null
        client = HttpClient(actual) {
            expectSuccess = false
            install(UserAgent) { agent = settings.userAgent }
            install(HttpRequestRetry) {
                maxRetries = httpSettings.maxRetries
                retryIf { _, response -> response.status.value in RETRY_STATUSES }
                retryOnExceptionIf { _, cause -> isRetryableException(cause) }
                delayMillis(respectRetryAfterHeader = false) { retry ->
                    val headers = response?.headers
                    retryDelayMillis(headers?.let { h -> { name: String -> h[name] } }, retry, httpSettings.maxRetryDelayMs)
                }
            }
            install(hostPacerPlugin(pacer))
            install(HttpTimeout) {
                connectTimeoutMillis = httpSettings.connectTimeoutMs
                socketTimeoutMillis = httpSettings.socketTimeoutMs
                requestTimeoutMillis = null
            }
        }
    }

    override suspend fun get(url: String, conditional: Conditional?, maxBytes: Int): HttpResult<ByteArray> = guarded(url) {
        client.prepareGet(url) {
            // 헤더 값은 원문 그대로 (Mojang ETag 는 따옴표가 없다)
            conditional?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            conditional?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }.execute { response ->
            val meta = response.meta()
            when (response.status.value) {
                304 -> HttpResult.NotModified(meta)
                in 200..299 -> readBody(response, meta, maxBytes.toLong(), FailureKind.TOO_LARGE)
                else -> HttpResult.Status(meta, response.snippet())
            }
        }
    }

    override suspend fun getRange(url: String, offset: Long, length: Int): HttpResult<ByteArray> {
        if (offset < 0 || length <= 0) return HttpResult.Failure(FailureKind.BAD_RANGE, "잘못된 범위: offset=$offset length=$length")
        val last = offset + length - 1
        return guarded(url) {
            client.prepareGet(url) { header(HttpHeaders.Range, "bytes=$offset-$last") }.execute { response ->
                val meta = response.meta()
                when (response.status.value) {
                    206 -> readBody(response, meta, length.toLong(), FailureKind.BAD_RANGE)

                    // 서버가 Range 를 무시하고 전체를 보냄 — 본문을 읽지 않는다 (수백 MB 일 수 있다)
                    200 -> HttpResult.Failure(FailureKind.BAD_RANGE, "server ignored Range")

                    else -> HttpResult.Status(meta, response.snippet())
                }
            }
        }
    }

    override suspend fun head(url: String): HttpResult<Unit> = guarded(url) {
        client.prepareHead(url).execute { response ->
            val meta = response.meta()
            when (response.status.value) {
                304 -> HttpResult.NotModified(meta)
                in 200..299 -> HttpResult.Ok(Unit, meta)
                else -> HttpResult.Status(meta, "")
            }
        }
    }

    override suspend fun download(url: String, request: DownloadRequest): HttpResult<TempDownload> = guarded(url) {
        val state = AttemptState()
        withAttempts { downloadAttempt(url, request, state) }
    }

    override suspend fun digest(url: String, request: DownloadRequest): HttpResult<StreamDigest> = guarded(url) {
        val state = AttemptState()
        withAttempts { streamAttempt(url, request, state, sink = null) }
    }

    override fun close() {
        client.close()
        ownedEngine?.close()
    }

    // ── 논리 호출 1회: 회로 차단 + 예외 → 결과 ─────────────────────────────

    private suspend fun <T> guarded(url: String, block: suspend () -> HttpResult<T>): HttpResult<T> {
        val host = hostOf(url) ?: return HttpResult.Failure(FailureKind.NETWORK, "URL 을 해석할 수 없다: $url")
        val admission = breaker.acquire(host)
        if (admission == CircuitBreaker.Admission.REJECTED) {
            return HttpResult.Failure(FailureKind.CIRCUIT_OPEN, "회로 차단 중: $host (연속 실패 ${httpSettings.circuitBreakerFailures}회)")
        }
        var outcome: CircuitBreaker.Outcome? = null
        try {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                classifyException(e).failure
            }
            outcome = CircuitBreaker.classify(result)
            return result
        } finally {
            breaker.record(host, admission, outcome)
        }
    }

    // ── 스트리밍 (download / digest) ─────────────────────────────────────

    /** 한 논리 호출 안의 시도들이 공유하는 상태. */
    private class AttemptState {
        /** DIGEST_MISMATCH 재시도는 Content-Length 가 없을 때 한 번만 */
        var mismatchRetried: Boolean = false
    }

    private class Attempt<out T>(val result: HttpResult<T>, val retryable: Boolean)

    /** 본문 도중 실패 등 재시도 가능한 결과면 D22 백오프 후 [HttpSettings.downloadAttempts] 까지 다시 시도한다. */
    private suspend fun <T> withAttempts(block: suspend () -> Attempt<T>): HttpResult<T> {
        val attempts = httpSettings.downloadAttempts.coerceAtLeast(1)
        var n = 0
        while (true) {
            n++
            val attempt = block()
            if (!attempt.retryable || n >= attempts) return attempt.result
            val wait = retryDelayMillis(null, n, httpSettings.maxRetryDelayMs)
            log.info("다운로드 재시도 {}/{} ({} ms 후): {}", n + 1, attempts, wait, (attempt.result as? HttpResult.Failure)?.message)
            delay(wait)
        }
    }

    private suspend fun downloadAttempt(url: String, request: DownloadRequest, state: AttemptState): Attempt<TempDownload> {
        val tempDir = settings.tempDir
        // 임시 파일은 execute {} 밖에서 만든다: 취소가 execute 의 정리 단계에서 터져도 finally 가 지울 수 있게.
        // 경로는 중단점 없이 먼저 정하고, 파일 생성은 그 경로를 지우는 try/finally 안에서 한다.
        // withContext 는 취소되면 블록이 이미 만든 결과를 버리고 CancellationException 을 던지므로 (prompt cancellation),
        // 생성 호출의 반환값에 기대면 만들어진 빈 파일·열린 채널을 놓친다 (INV-3). 채널은 블록 안에서 바로 [channel] 에 넣는다.
        val tmp: Path = tempDir.resolve("$TEMP_PREFIX${UUID.randomUUID()}$TEMP_SUFFIX")
        var handedOff = false
        var channel: FileChannel? = null
        try {
            val ch = try {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(tempDir)
                    FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).also { channel = it }
                }
            } catch (e: IOException) {
                return Attempt(HttpResult.Failure(FailureKind.IO, "임시 파일을 만들 수 없다: $e"), retryable = false)
            }
            val attempt = streamAttempt(url, request, state, ch)
            return when (val r = attempt.result) {
                is HttpResult.Ok -> {
                    // Windows: 넘기기 전에 채널을 닫아야 분석·삭제가 된다
                    withContext(NonCancellable + Dispatchers.IO) { ch.close() }
                    val download = TempDownload(tmp, r.value.size, r.value.digests, onDeleteFailure = ::logDeleteFailure)
                    handedOff = true
                    Attempt(HttpResult.Ok(download, r.meta), retryable = false)
                }

                is HttpResult.NotModified -> Attempt(r, attempt.retryable)

                is HttpResult.Status -> Attempt(r, attempt.retryable)

                is HttpResult.Failure -> Attempt(r, attempt.retryable)
            }
        } finally {
            if (!handedOff) {
                withContext(NonCancellable + Dispatchers.IO) {
                    closeQuietly(channel)
                    deleteQuietly(tmp)
                }
            }
        }
    }

    /**
     * 한 번의 GET 을 스트리밍하며 해시(+ [sink] 가 있으면 파일 쓰기).
     * `execute {}` 가 정상 반환한 뒤에만 결과가 확정된다 (응답 정리 단계의 취소는 호출자에게 전파).
     */
    private suspend fun streamAttempt(url: String, request: DownloadRequest, state: AttemptState, sink: FileChannel?): Attempt<StreamDigest> {
        var bodyStarted = false
        return try {
            client.prepareGet(url).execute { response ->
                bodyStarted = true
                val meta = response.meta()
                if (response.status.value != 200) return@execute Attempt(HttpResult.Status(meta, response.snippet()), retryable = false)
                val declared = meta.contentLength
                if (declared != null && declared > request.maxBytes) {
                    return@execute Attempt(HttpResult.Failure(FailureKind.TOO_LARGE, "Content-Length $declared > ${request.maxBytes}"), retryable = false)
                }
                val digests = (request.algorithms + request.expected.keys).associateWith { MessageDigest.getInstance(it.jcaName) }
                val body = response.bodyAsChannel()
                val chunk = ByteArray(CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val n = body.readAvailable(chunk, 0, chunk.size)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > request.maxBytes) {
                        return@execute Attempt(HttpResult.Failure(FailureKind.TOO_LARGE, "본문이 ${request.maxBytes} 바이트를 넘는다"), retryable = false)
                    }
                    for (md in digests.values) md.update(chunk, 0, n)
                    if (sink != null) writeChunk(sink, chunk, n)
                }
                if (declared != null && total != declared) {
                    return@execute Attempt(HttpResult.Failure(FailureKind.NETWORK, "short body: $total / Content-Length $declared"), retryable = true)
                }
                val hex = digests.mapValues { (_, md) -> HEX.formatHex(md.digest()) }
                val mismatch = request.expected.entries.firstOrNull { (algo, want) -> !want.equals(hex[algo], ignoreCase = true) }
                if (mismatch != null) {
                    // Content-Length 가 없으면 잘린 전송과 구별할 수 없으므로 한 번만 다시 받는다
                    val retry = declared == null && !state.mismatchRetried
                    if (retry) state.mismatchRetried = true
                    val detail = "${mismatch.key} ${hex[mismatch.key]} != ${mismatch.value}"
                    return@execute Attempt(HttpResult.Failure(FailureKind.DIGEST_MISMATCH, detail), retryable = retry)
                }
                Attempt(HttpResult.Ok(StreamDigest(total, hex), meta), retryable = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalIoException) {
            Attempt(HttpResult.Failure(FailureKind.IO, "로컬 파일 쓰기 실패: ${e.cause}"), retryable = false)
        } catch (e: Exception) {
            val c = classifyException(e)
            // 헤더 전 예외는 재시도 플러그인이 이미 재시도했다. 본문 도중의 알려진 네트워크 예외만 다시 시도한다.
            Attempt(c.failure, retryable = bodyStarted && c.known)
        }
    }

    private suspend fun writeChunk(sink: FileChannel, chunk: ByteArray, n: Int) {
        try {
            withContext(Dispatchers.IO) {
                val buffer = ByteBuffer.wrap(chunk, 0, n)
                while (buffer.hasRemaining()) sink.write(buffer)
            }
        } catch (e: IOException) {
            throw LocalIoException(e)
        }
    }

    /** 로컬 디스크 오류 표시용. IOException 이 아니어야 네트워크 오류로 섞이지 않는다. */
    private class LocalIoException(cause: IOException) : RuntimeException(cause)

    // ── 응답 도우미 ─────────────────────────────────────────────────────

    private suspend fun readBody(response: HttpResponse, meta: ResponseMeta, limit: Long, overflow: FailureKind): HttpResult<ByteArray> {
        val declared = meta.contentLength
        if (declared != null && declared > limit) return HttpResult.Failure(overflow, "Content-Length $declared > $limit")
        val body: ByteReadChannel = response.bodyAsChannel()
        val out = ByteArrayOutputStream((declared ?: INITIAL_BUFFER_BYTES.toLong()).coerceIn(0L, INITIAL_BUFFER_BYTES.toLong()).toInt())
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val n = body.readAvailable(chunk, 0, chunk.size)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > limit) return HttpResult.Failure(overflow, "본문이 $limit 바이트를 넘는다")
            out.write(chunk, 0, n)
        }
        if (declared != null && total < declared) return HttpResult.Failure(FailureKind.NETWORK, "short body: $total / Content-Length $declared")
        return HttpResult.Ok(out.toByteArray(), meta)
    }

    private fun HttpResponse.meta(): ResponseMeta = ResponseMeta(
        status = status.value,
        etag = headers[HttpHeaders.ETag],
        lastModified = headers[HttpHeaders.LastModified],
        contentLength = headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull(),
        totalLength = headers[HttpHeaders.ContentRange]?.let(::parseContentRangeTotal),
    )

    /** 오류 응답 본문 앞부분 (최대 512자). 읽다 실패하면 빈 문자열. */
    private suspend fun HttpResponse.snippet(): String = try {
        val body = bodyAsChannel()
        val buf = ByteArray(SNIPPET_BYTES)
        var read = 0
        while (read < buf.size) {
            val n = body.readAvailable(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        buf.copyOf(read).decodeToString().take(SNIPPET_CHARS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    private fun logDeleteFailure(path: Path, e: Exception) {
        log.warn("임시 jar 삭제 실패 (Runner 가 나중에 쓸어 담는다): {} — {}", path, e.toString())
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(KtorHttp::class.java)
        private val HEX: HexFormat = HexFormat.of()

        /** 재시도하는 상태 코드 (D22). 그 밖의 4xx 는 재시도하지 않는다. */
        val RETRY_STATUSES: Set<Int> = setOf(429, 500, 502, 503, 504)

        const val TEMP_PREFIX: String = "dl-"
        const val TEMP_SUFFIX: String = ".part"
        private const val CHUNK_BYTES = 64 * 1024
        private const val INITIAL_BUFFER_BYTES = 64 * 1024
        private const val SNIPPET_BYTES = 2048
        private const val SNIPPET_CHARS = 512
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val JITTER_MS = 1_000L

        private fun createCioEngine(s: HttpSettings): HttpClientEngine = CIO.create {
            maxConnectionsCount = 64
            // CIO 엔진의 requestTimeout(기본 15 s)은 요청에 HttpTimeoutCapability 가 없을 때만 적용된다
            // (Ktor 3.4.3 EndpointKt.getRequestTimeout: HttpTimeout 플러그인이 설치돼 있으면 무한). 이 클라이언트는 HttpTimeout 을
            // 설치하므로 원래 적용되지 않지만, 플러그인 설정이 바뀌어도 jar 다운로드·스트리밍 해시가 15 초에서 끊기지 않게 0(끔)으로 둔다.
            requestTimeout = 0
            endpoint {
                maxConnectionsPerRoute = 8
                connectTimeout = s.connectTimeoutMs
                connectAttempts = 2
            }
        }

        private fun hostPacerPlugin(pacer: HostPacer): ClientPlugin<Unit> = createClientPlugin("HostPacer") {
            on(Send) { request ->
                val host = request.url.host
                pacer.withPermit(host) {
                    val call = proceed(request)
                    pacer.observe(host, call.response.status.value) { name -> call.response.headers[name] }
                    call
                }
            }
        }

        /** 재시도 플러그인이 다시 보낼 예외인가 (D22). 취소는 절대 재시도하지 않는다. */
        fun isRetryableException(cause: Throwable): Boolean = cause !is CancellationException &&
            (
                cause is IOException ||
                    cause is HttpRequestTimeoutException ||
                    // 둘 다 IllegalArgumentException 계열이라 IOException 검사에 걸리지 않는다
                    cause is UnresolvedAddressException ||
                    cause is UnsupportedAddressTypeException
            )

        /**
         * 재시도 지연 (D22): `min(max(2 s·2^(n-1) + 지터 (60 s 상한), Retry-After | X-Ratelimit-Reset), maxRetryDelayMs)`.
         * @param header 직전 응답 헤더 조회 (예외로 실패했으면 null)
         * @param retry 1 부터 시작하는 재시도 번호
         */
        fun retryDelayMillis(
            header: ((String) -> String?)?,
            retry: Int,
            maxRetryDelayMs: Long,
            now: Instant = Clock.System.now(),
            jitterMs: Long = Random.nextLong(0, JITTER_MS),
        ): Long {
            val advised = header?.let { h ->
                h(HostPacer.RETRY_AFTER)?.let { parseRetryAfterMillis(it, now) }
                    ?: h(HostPacer.RATE_LIMIT_RESET)?.let(::parseRateLimitResetMillis)
            }
            val exponential = minOf(BASE_BACKOFF_MS shl (retry - 1).coerceIn(0, 10), MAX_BACKOFF_MS) + jitterMs
            return minOf(maxOf(exponential, advised ?: 0L), maxRetryDelayMs).coerceAtLeast(0L)
        }

        /** 예외 분류 결과. [known] 은 네트워크·타임아웃으로 알려진 예외인가. */
        class Classified(val failure: HttpResult.Failure, val known: Boolean)

        /** 설계 §8.3 오류 매핑. 분류 안 된 예외도 NETWORK 실패로 바꾼다 (던지지 않는다). */
        fun classifyException(e: Exception): Classified = when (e) {
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
                Classified(HttpResult.Failure(FailureKind.TIMEOUT, e.toString()), known = true)

            is IOException, is UnresolvedAddressException, is UnsupportedAddressTypeException ->
                Classified(HttpResult.Failure(FailureKind.NETWORK, e.toString()), known = true)

            else -> Classified(HttpResult.Failure(FailureKind.NETWORK, e.toString()), known = false)
        }

        /** `Content-Range: bytes a-b/TOTAL` (416 이면 범위 자리가 별표) 의 TOTAL. TOTAL 이 별표이거나 형식이 틀리면 null. */
        fun parseContentRangeTotal(value: String): Long? = value.substringAfterLast('/', "").trim().toLongOrNull()

        fun hostOf(url: String): String? = try {
            Url(url).host.takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

        private fun closeQuietly(channel: FileChannel?) {
            try {
                channel?.close()
            } catch (e: IOException) {
                log.debug("임시 파일 채널 닫기 실패: {}", e.toString())
            }
        }

        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (e: IOException) {
                log.warn("실패한 다운로드 임시 파일 삭제 실패 (Runner 가 나중에 쓸어 담는다): {} — {}", path, e.toString())
            }
        }
    }
}
