package kr.decacross.daemon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 127.0.0.1 임의 포트의 JDK HttpServer. 실제 소켓 위에서 Range/If-Range/재시도/끊김을 재현한다 (MockEngine 이 못 보여주는 것).
 * 네트워크 밖으로 나가지 않는다. `use {}` 로 닫아라.
 */
class LocalHttpServer : AutoCloseable {
    /**
     * 제공할 자원 하나.
     *
     * @property statusSequence n 번째 요청(1부터)에 줄 상태. 목록 밖이거나 200 이면 정상 응답. 예: `[503, 503]` → 세 번째부터 정상.
     * @property ignoreRange Range 를 무시하고 항상 200 전체.
     * @property cutAfterBytes 본문을 이만큼만 쓰고 연결을 끊는다 (Content-Length 는 전체 길이 → 짧은 본문).
     * @property stallAfterBytes 이만큼(1 이상) 쓴 뒤 [stallMs] 동안 한 번 멈춘다.
     * @property bytesPerSecond 전송 속도 제한 (느린 본문).
     * @property redirectTo 302 Location (Range 헤더가 따라가는지 확인용).
     * @property retryAfter 비정상 상태 응답에 붙일 `Retry-After` 값.
     */
    data class Resource(
        val body: ByteArray,
        val etag: String? = "\"v1\"",
        val lastModified: String? = null,
        val contentType: String = "application/java-archive",
        val ignoreRange: Boolean = false,
        val statusSequence: List<Int> = emptyList(),
        val cutAfterBytes: Long? = null,
        val stallAfterBytes: Long? = null,
        val stallMs: Long = 0,
        val bytesPerSecond: Long? = null,
        val redirectTo: String? = null,
        val retryAfter: String? = null,
    )

    /** 받은 요청 기록. 헤더 이름은 소문자. */
    data class RecordedRequest(val method: String, val path: String, val headers: Map<String, String>)

    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val resources = ConcurrentHashMap<String, Resource>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** `http://127.0.0.1:<port>` */
    val baseUrl: String = "http://${server.address.address.hostAddress}:${server.address.port}"

    fun put(path: String, resource: Resource) {
        resources[path] = resource
        counters.remove(path)
    }

    fun url(path: String): String = baseUrl + path

    val requests: List<RecordedRequest> get() = recorded.toList()

    fun requestsTo(path: String): List<RecordedRequest> = recorded.filter { it.path == path }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            recorded.add(RecordedRequest(ex.requestMethod, path, headers))
            val res = resources[path]
            if (res == null) {
                ex.sendResponseHeaders(404, -1)
                return
            }
            val n = counters.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val forced = res.statusSequence.getOrNull(n - 1)
            if (forced != null && forced != 200) {
                res.retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
                ex.sendResponseHeaders(forced, -1)
                return
            }
            if (res.redirectTo != null) {
                ex.responseHeaders.add("Location", res.redirectTo)
                ex.sendResponseHeaders(302, -1)
                return
            }
            val size = res.body.size.toLong()
            ex.responseHeaders.add("Content-Type", res.contentType)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            res.etag?.let { ex.responseHeaders.add("ETag", it) }
            res.lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
            val range = headers["range"]?.let(::parseRange)
            val ifRange = headers["if-range"]
            val validatorOk = ifRange == null || ifRange == res.etag || (res.etag == null && ifRange == res.lastModified)
            if (range != null && !res.ignoreRange && validatorOk) {
                val (start, endInclusive) = range
                if (start >= size) {
                    ex.responseHeaders.add("Content-Range", "bytes */$size")
                    ex.sendResponseHeaders(416, -1)
                    return
                }
                val end = minOf(endInclusive ?: (size - 1), size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                writeBody(ex, 206, res, start, end + 1)
            } else {
                writeBody(ex, 200, res, 0, size)
            }
        } catch (e: IOException) {
            // 클라이언트가 먼저 끊음 — 테스트 시나리오의 일부일 수 있다
        } finally {
            try {
                // 고정 길이 본문을 덜 쓴 채 닫으면 JDK 가 연결을 끊고 IOException 을 던진다 (cutAfterBytes 의도)
                ex.close()
            } catch (e: IOException) {
                // 의도된 끊김
            }
        }
    }

    private fun writeBody(ex: HttpExchange, status: Int, res: Resource, from: Long, toExclusive: Long) {
        val length = toExclusive - from
        ex.sendResponseHeaders(status, if (length == 0L) -1 else length)
        if (length == 0L) return
        val out = ex.responseBody
        var written = 0L
        var pos = from
        val chunk = 16 * 1024
        while (pos < toExclusive) {
            val cut = res.cutAfterBytes
            if (cut != null && written >= cut) {
                out.flush()
                // 남은 길이를 쓰지 않고 닫는다 → 서버가 연결을 끊는다 (짧은 본문)
                return
            }
            var n = minOf(chunk.toLong(), toExclusive - pos)
            if (cut != null) n = minOf(n, cut - written)
            val stallAt = res.stallAfterBytes
            if (stallAt != null && written < stallAt) n = minOf(n, stallAt - written)
            out.write(res.body, pos.toInt(), n.toInt())
            out.flush()
            written += n
            pos += n
            if (stallAt != null && written == stallAt && res.stallMs > 0) Thread.sleep(res.stallMs)
            res.bytesPerSecond?.let { bps -> Thread.sleep(maxOf(1L, n * 1000L / bps)) }
        }
    }

    private fun parseRange(value: String): Pair<Long, Long?>? {
        val m = Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(value.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return start to end
    }
}
