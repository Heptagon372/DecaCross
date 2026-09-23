package kr.decacross.daemon.install

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** ★ 식별 가능한 User-Agent (CLAUDE.md 불변식 17). generic UA 는 PaperMC 정책 위반. */
const val DECACROSS_USER_AGENT: String = "DecaCross/0.1 (+https://github.com/Heptagon372/DecaCross)"

fun defaultHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(UserAgent) { agent = DECACROSS_USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 30 * 60_000 // 큰 jar 다운로드
        }
        expectSuccess = false
    }

/** 기본 클라이언트를 쓰는 다운로더. 앱 계층(CLI/UI)은 Ktor 타입을 몰라도 된다. */
fun defaultFetcher(): Fetcher = Fetcher(defaultHttpClient())

class FetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 다운로더. HTTP Range 로 중단 재개, 3회 재시도(지수 백오프), 진행률 보고.
 * 미러 목록이 있으면 원본 실패 후 순서대로 시도한다.
 */
class Fetcher(
    private val client: HttpClient,
    private val maxRetries: Int = 3,
    private val backoffMs: List<Long> = listOf(500, 1_500, 4_000),
) {
    /**
     * [urls] 를 순서대로 시도해 [dest] 에 저장한다. `dest.part` 가 남아 있으면 Range 로 이어받는다.
     * 성공 시 [dest] 가 완성 파일이다. 실패 시 [FetchException].
     */
    suspend fun download(
        urls: List<String>,
        dest: Path,
        expectedSize: Long? = null,
        onProgress: suspend (done: Long, total: Long?) -> Unit = { _, _ -> },
    ) {
        require(urls.isNotEmpty())
        Files.createDirectories(dest.parent)
        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        var last: Exception? = null
        for (url in urls) {
            for (attempt in 0..maxRetries) {
                try {
                    fetchOnce(url, part, expectedSize, onProgress)
                    Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: Exception) {
                    last = e
                    if (attempt < maxRetries) delay(backoffMs.getOrElse(attempt) { backoffMs.last() })
                }
            }
        }
        throw FetchException("다운로드 실패: ${urls.first()}", last)
    }

    private suspend fun fetchOnce(
        url: String,
        part: Path,
        expectedSize: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ) {
        val existing = if (Files.exists(part)) Files.size(part) else 0L
        client.prepareGet(url) {
            if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
        }.execute { resp ->
            val resume = existing > 0 && resp.status == HttpStatusCode.PartialContent
            if (!resp.status.isSuccess()) throw FetchException("HTTP ${resp.status.value} ($url)")
            val total = resp.contentLength()?.let { if (resume) it + existing else it } ?: expectedSize
            val options =
                if (resume) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
            var done = if (resume) existing else 0L
            val channel = resp.bodyAsChannel()
            val buf = ByteArray(128 * 1024)
            Files.newOutputStream(part, *options).use { out ->
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            if (total != null && done != total) throw FetchException("전송이 중간에 끊겼습니다 ($done/$total)")
        }
    }
}
