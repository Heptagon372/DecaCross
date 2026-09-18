package kr.decacross.daemon.install.fetch

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.decacross.daemon.install.ArtifactKind
import kr.decacross.daemon.install.FetchItem
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchListener
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.RetryPolicy
import kr.decacross.daemon.install.buildDaemonUserAgent
import kr.decacross.daemon.testkit.TestJars
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** 테스트용 가짜 원본·미러 주소. MockEngine 은 DNS 를 타지 않는다. */
internal const val ORIGIN_URL = "https://origin.invalid/paper.jar"

internal const val MIRROR_URL = "https://mirror.invalid/paper.jar"

/** 검사할 UA (불변식 17). */
internal val TEST_UA: String = buildDaemonUserAgent() ?: error("UA 를 만들 수 없다")

/** 테스트용 빠른 재시도 정책 (설계 §4.5). */
internal fun fastPolicy(
    maxRetriesPerSource: Int = 3,
    maxAttemptsPerSource: Int = 12,
    maxRetryAfterMs: Long = 30_000,
): RetryPolicy = RetryPolicy(
    maxRetriesPerSource = maxRetriesPerSource,
    maxAttemptsPerSource = maxAttemptsPerSource,
    baseDelayMs = 5,
    maxDelayMs = 20,
    maxJitterMs = 1,
    maxRetryAfterMs = maxRetryAfterMs,
)

internal fun randomBytes(size: Int, seed: Int = 7): ByteArray = Random(seed).nextBytes(size)

internal fun testItem(
    bytes: ByteArray,
    sources: List<String> = listOf(ORIGIN_URL),
    id: String = "core:test.jar",
    kind: ArtifactKind = ArtifactKind.OTHER,
): FetchItem = FetchItem(id, sources, TestJars.sha256Hex(bytes), bytes.size.toLong(), kind)

/** 이벤트·진행률을 전부 모으는 리스너 (하나도 버리지 않는지 확인용). */
internal class RecordingListener : FetchListener {
    private val progressLog = CopyOnWriteArrayList<FetchProgress>()
    private val eventLog = CopyOnWriteArrayList<FetchItemEvent>()

    val progress: List<FetchProgress> get() = progressLog.toList()
    val events: List<FetchItemEvent> get() = eventLog.toList()

    override suspend fun onProgress(progress: FetchProgress) {
        progressLog.add(progress)
    }

    override suspend fun onEvent(event: FetchItemEvent) {
        eventLog.add(event)
    }
}

internal inline fun <reified T : FetchItemEvent> RecordingListener.only(): List<T> = events.filterIsInstance<T>()

/** 임시 디렉터리 하나를 만들고 끝나면 지운다. ★ 테스트는 실제 사용자 폴더를 절대 건드리지 않는다. */
internal inline fun <T> withTempDir(block: (Path) -> T): T {
    val dir = Files.createTempDirectory("dcx-fetch-")
    try {
        return block(dir)
    } finally {
        deleteRecursively(dir)
    }
}

internal fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: IOException) {
        // 테스트 뒷정리 실패는 무시 (임시 폴더)
    }
}

/**
 * Range/If-Range 를 지키는 일반 응답. 이상 동작은 인자로 주입한다.
 *
 * @param totalOverride `Content-Range`/`Content-Length` 에 적을 전체 크기 (기본 = 실제 길이)
 * @param truncateTo 실제로 보낼 바이트 수 (짧은 본문)
 * @param extraBytes 크기보다 더 보낼 바이트 수 (오버플로)
 */
@Suppress("LongParameterList")
internal fun MockRequestHandleScope.serve(
    request: HttpRequestData,
    body: ByteArray,
    etag: String? = "\"v1\"",
    lastModified: String? = null,
    date: String? = null,
    contentType: String? = "application/java-archive",
    ignoreRange: Boolean = false,
    totalOverride: Long? = null,
    sendContentLength: Boolean = true,
    truncateTo: Int? = null,
    extraBytes: Int = 0,
    contentEncoding: String? = null,
): HttpResponseData {
    val headers = HeadersBuilder()
    contentType?.let { headers.append(HttpHeaders.ContentType, it) }
    headers.append(HttpHeaders.AcceptRanges, "bytes")
    etag?.let { headers.append(HttpHeaders.ETag, it) }
    lastModified?.let { headers.append(HttpHeaders.LastModified, it) }
    date?.let { headers.append(HttpHeaders.Date, it) }
    contentEncoding?.let { headers.append(HttpHeaders.ContentEncoding, it) }

    val total = totalOverride ?: body.size.toLong()
    val ifRange = request.headers[HttpHeaders.IfRange]
    val validatorOk = ifRange == null || ifRange == etag || (etag == null && ifRange == lastModified)
    val start = request.headers[HttpHeaders.Range]
        ?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()

    if (start != null && !ignoreRange && validatorOk) {
        if (start >= body.size) {
            headers.append(HttpHeaders.ContentRange, "bytes */$total")
            return respond(ByteArray(0), HttpStatusCode.fromValue(416), headers.build())
        }
        val slice = body.copyOfRange(start.toInt(), body.size)
        headers.append(HttpHeaders.ContentRange, "bytes $start-${body.size - 1}/$total")
        val payload = shape(slice, truncateTo, extraBytes)
        if (sendContentLength) headers.append(HttpHeaders.ContentLength, slice.size.toString())
        return respond(payload, HttpStatusCode.PartialContent, headers.build())
    }
    val payload = shape(body, truncateTo, extraBytes)
    if (sendContentLength) headers.append(HttpHeaders.ContentLength, total.toString())
    return respond(payload, HttpStatusCode.OK, headers.build())
}

private fun shape(bytes: ByteArray, truncateTo: Int?, extraBytes: Int): ByteArray {
    val cut = if (truncateTo != null) bytes.copyOfRange(0, minOf(truncateTo, bytes.size)) else bytes
    if (extraBytes <= 0) return cut
    return cut + ByteArray(extraBytes) { 0x5A }
}

/**
 * MockEngine 응답 본문을 만드는 코루틴 소유자. `GlobalScope` 를 쓰지 않으려고 테스트가 직접 들고 닫는다
 * (MockRequestHandleScope.callContext 는 Ktor 내부라 쓸 수 없다).
 */
internal class MockBodies : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** [chunk] 바이트씩 [chunkDelayMs] 간격으로 보내는 느린 본문. */
    fun slow(body: ByteArray, chunk: Int = 8192, chunkDelayMs: Long = 20): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        scope.launch {
            try {
                var offset = 0
                while (offset < body.size) {
                    val n = minOf(chunk, body.size - offset)
                    channel.writeByteArray(body.copyOfRange(offset, offset + n))
                    channel.flush()
                    offset += n
                    delay(chunkDelayMs)
                }
                channel.flushAndClose()
            } catch (e: CancellationException) {
                channel.cancel(e)
                throw e
            } catch (e: Throwable) {
                channel.cancel(e)
            }
        }
        return channel
    }

    /**
     * [upTo] 바이트만 보내고 [cause] 로 연결을 끊는 본문 (스트리밍 중 IOException).
     * `cancel` 은 아직 읽히지 않은 버퍼를 버리므로, 조각씩 보내고 읽히기를 기다린 뒤에 끊는다.
     */
    fun failAfter(
        body: ByteArray,
        upTo: Int,
        chunk: Int = 8192,
        cause: Throwable = IOException("mock reset"),
    ): ByteReadChannel {
        val channel = ByteChannel(autoFlush = true)
        scope.launch {
            try {
                var offset = 0
                val end = minOf(upTo, body.size)
                while (offset < end) {
                    val n = minOf(chunk, end - offset)
                    channel.writeByteArray(body.copyOfRange(offset, offset + n))
                    channel.flush()
                    offset += n
                    delay(10)
                }
                delay(100)
                channel.cancel(cause)
            } catch (e: CancellationException) {
                channel.cancel(e)
                throw e
            } catch (e: Throwable) {
                channel.cancel(e)
            }
        }
        return channel
    }

    override fun close() {
        scope.cancel()
    }
}

/** 부분 파일을 미리 깔아 둔다 (이어받기 테스트). */
internal fun seedPartial(
    dir: Path,
    item: FetchItem,
    bytes: ByteArray,
    upTo: Int,
    url: String = ORIGIN_URL,
    etag: String? = "\"v1\"",
    lastModified: String? = null,
    date: String? = null,
) {
    Files.createDirectories(dir)
    Files.write(dir.resolve("${item.sha256}.part"), bytes.copyOfRange(0, upTo))
    PartialStore(dir).writeMeta(
        item,
        PartMeta(1, item.sha256, item.size, url, etag, lastModified, date, upTo.toLong()),
    )
}

internal fun partPath(dir: Path, item: FetchItem): Path = dir.resolve("${item.sha256}.part")

internal fun metaPath(dir: Path, item: FetchItem): Path = dir.resolve("${item.sha256}.part.json")
