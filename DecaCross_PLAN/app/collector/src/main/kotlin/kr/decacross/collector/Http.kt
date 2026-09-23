package kr.decacross.collector

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 기본 User-Agent. ★ 불변식 17: 식별 가능한 UA + 연락처. 환경변수 `DECACROSS_UA` 로 덮어쓴다. */
const val DEFAULT_USER_AGENT: String = "DecaCross/0.1 (+https://github.com/Heptagon372/DecaCross)"

fun resolveUserAgent(): String = System.getenv("DECACROSS_UA")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

/** 요청 결과. 예외 대신 sealed 로 돌려준다 — 호출자가 소스별로 "실패해도 계속" 을 정할 수 있게. */
sealed interface HttpOutcome<out T> {
    data class Ok<T>(val value: T, val status: Int) : HttpOutcome<T>

    /** [status] 는 HTTP 응답이 있었을 때만. 네트워크 예외면 null. */
    data class Failed(val url: String, val status: Int?, val message: String) : HttpOutcome<Nothing>
}

fun <T> HttpOutcome<T>.valueOrNull(): T? = (this as? HttpOutcome.Ok<T>)?.value

/** 다운로드 결과: 스트리밍 중 계산한 sha256 (hex, 소문자) 과 바이트 수. */
data class Downloaded(val sha256: String, val size: Long)

/**
 * 수집기 공용 HTTP 클라이언트.
 *
 * - 호스트별 레이트리밋: 같은 호스트에는 [minIntervalMs] 간격 이상 (기본 500 ms = 2 req/s)
 * - 재시도: IOException / 429 / 5xx 에 대해 [backoffMs] 순서로 대기 (기본 500 → 1500 → 4000 ms, 총 3회 시도).
 *   `Retry-After` 헤더가 있으면 그 값을 우선한다.
 * - 타임아웃 30 s. UA 는 [userAgent].
 * - [engine] 과 [sleeper] 는 테스트 주입용 (MockEngine, 가짜 대기).
 */
class CollectorHttp(
    engine: HttpClientEngine = CIO.create(),
    val userAgent: String = resolveUserAgent(),
    private val minIntervalMs: Long = 500,
    private val backoffMs: List<Long> = listOf(500, 1500, 4000),
    private val timeoutMs: Long = 30_000,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(UserAgent) { agent = userAgent }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
    }

    private class HostGate(val mutex: Mutex = Mutex(), var lastAt: Long? = null)

    /** 한 번의 시도 결과: 끝(성공 또는 재시도 불가 실패) / 재시도 (429·5xx·IOException). */
    private sealed interface Attempt<out T> {
        data class Done<T>(val outcome: HttpOutcome<T>) : Attempt<T>

        data class Retry(val failure: HttpOutcome.Failed, val retryAfterMs: Long?) : Attempt<Nothing>
    }

    private val gates = ConcurrentHashMap<String, HostGate>()

    /** 호스트별 최소 간격을 보장한다. 대기는 호스트 락 안에서 하므로 동시 호출도 직렬화된다. */
    private suspend fun throttle(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        val gate = gates.computeIfAbsent(host) { HostGate() }
        gate.mutex.withLock {
            val last = gate.lastAt
            if (last != null) {
                val wait = last + minIntervalMs - clock()
                if (wait > 0) sleeper(wait)
            }
            gate.lastAt = clock()
        }
    }

    /**
     * 재시도 루프. [handle] 은 성공 응답을 소비한다 (본문 읽기·파일 저장). 응답 상태가 429/5xx 이면 재시도,
     * 그 외 4xx 는 즉시 실패. 시도 횟수는 `backoffMs.size` (마지막 시도 뒤엔 대기하지 않는다).
     */
    private suspend fun <T> withRetry(url: String, handle: suspend (HttpResponse) -> T): HttpOutcome<T> {
        var last: HttpOutcome.Failed = HttpOutcome.Failed(url, null, "시도 없음")
        for (attempt in backoffMs.indices) {
            throttle(url)
            val attemptResult: Attempt<T> = try {
                client.prepareGet(url).execute { resp ->
                    val code = resp.status.value
                    when {
                        code in 200..299 -> Attempt.Done(HttpOutcome.Ok(handle(resp), code))

                        code == 429 || code >= 500 -> Attempt.Retry(
                            HttpOutcome.Failed(url, code, "HTTP $code"),
                            resp.headers["Retry-After"]?.trim()?.toLongOrNull()?.times(1000),
                        )

                        else -> Attempt.Done(HttpOutcome.Failed(url, code, "HTTP $code"))
                    }
                }
            } catch (e: IOException) {
                Attempt.Retry(HttpOutcome.Failed(url, null, e.message ?: e::class.simpleName.orEmpty()), null)
            }
            when (attemptResult) {
                is Attempt.Done -> return attemptResult.outcome

                is Attempt.Retry -> {
                    last = attemptResult.failure
                    log.debug("재시도 대상 ({}회차) {}: {}", attempt + 1, url, last.message)
                    if (attempt < backoffMs.lastIndex) sleeper(attemptResult.retryAfterMs ?: backoffMs[attempt])
                }
            }
        }
        log.warn("요청 포기 {}: {}", url, last.message)
        return last
    }

    suspend fun getText(url: String): HttpOutcome<String> = withRetry(url) { it.bodyAsText() }

    /** JSON 본문을 [T] 로 디코딩. 디코딩 실패도 [HttpOutcome.Failed] 로 돌려준다 (상태는 응답 코드). */
    suspend inline fun <reified T> getJson(url: String): HttpOutcome<T> =
        when (val r = getText(url)) {
            is HttpOutcome.Failed -> r

            is HttpOutcome.Ok -> runCatching { json.decodeFromString<T>(r.value) }
                .fold({ HttpOutcome.Ok(it, r.status) }, { HttpOutcome.Failed(url, r.status, "JSON 디코딩 실패: ${it.message}") })
        }

    /** [dest] 로 스트리밍 저장하면서 sha256 을 계산한다. 실패하면 부분 파일을 지운다. */
    suspend fun download(url: String, dest: Path): HttpOutcome<Downloaded> {
        dest.parent?.let { Files.createDirectories(it) }
        val r = withRetry(url) { resp ->
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            DigestInputStream(resp.bodyAsChannel().toInputStream(), digest).use { input ->
                Files.newOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
            Downloaded(digest.digest().joinToString("") { "%02x".format(it) }, size)
        }
        if (r is HttpOutcome.Failed) runCatching { Files.deleteIfExists(dest) }
        return r
    }

    /** 단순 GET (상태만 궁금할 때). */
    suspend fun head(url: String): Int = runCatching { client.get(url).status.value }.getOrDefault(-1)

    override fun close(): Unit = client.close()

    private companion object {
        val log = LoggerFactory.getLogger(CollectorHttp::class.java)
    }
}
