package kr.decacross.collector.testkit

import kr.decacross.collector.http.Conditional
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.DownloadRequest
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.http.Http
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.http.ResponseMeta
import kr.decacross.collector.http.StreamDigest
import kr.decacross.collector.http.TempDownload
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** 가짜 요청 기록 한 줄. */
data class FakeRequest(val method: String, val url: String, val conditional: Conditional? = null, val range: LongRange? = null)

/** 가짜 응답. */
sealed interface FakeResponse {
    /** 본문. etag 가 조건부 요청의 etag 와 같으면 FakeHttp 가 304 로 바꾼다. */
    class Body(
        val bytes: ByteArray,
        val status: Int = 200,
        val etag: String? = null,
        val lastModified: String? = null,
    ) : FakeResponse {
        constructor(text: String, etag: String? = null) : this(text.encodeToByteArray(), etag = etag)
    }

    data class Status(val status: Int, val body: String = "") : FakeResponse

    data class Fail(val kind: FailureKind, val message: String = "fake failure") : FakeResponse
}

/**
 * URL → 응답 테이블 기반 [Http]. 등록하지 않은 URL 은 404.
 * download 는 [tempDir] 에 실제 임시 파일을 만든다 (테스트가 "jar 가 남지 않음"을 검증할 수 있게).
 * 모든 WP 가 공유하는 WP0 소유 파일 — 수정하지 말고, 필요한 도우미는 각자 테스트 패키지에 만든다.
 *
 * 실제 KtorHttp 와 맞춘 의미: [FakeResponse.Body] 의 status 가 2xx 가 아니면 모든 메서드가 [HttpResult.Status],
 * Range 시작이 본문 끝 이상이면 416 [HttpResult.Status], 해시는 `algorithms ∪ expected.keys`.
 */
class FakeHttp(private val tempDir: Path) : Http {
    private val lock = Any()
    private val routes = LinkedHashMap<String, (FakeRequest) -> FakeResponse>()
    private val log = ArrayList<FakeRequest>()

    val requests: List<FakeRequest> get() = synchronized(lock) { ArrayList(log) }

    fun on(url: String, response: FakeResponse): FakeHttp = on(url) { response }

    fun on(url: String, handler: (FakeRequest) -> FakeResponse): FakeHttp {
        synchronized(lock) { routes[url] = handler }
        return this
    }

    fun onJson(url: String, json: String, etag: String? = null): FakeHttp = on(url, FakeResponse.Body(json, etag))

    private fun respond(req: FakeRequest): FakeResponse {
        val handler = synchronized(lock) {
            log += req
            routes[req.url]
        }
        return handler?.invoke(req) ?: FakeResponse.Status(404, """{"error":"not found (FakeHttp)"}""")
    }

    override suspend fun get(url: String, conditional: Conditional?, maxBytes: Int): HttpResult<ByteArray> =
        when (val r = respond(FakeRequest("GET", url, conditional))) {
            is FakeResponse.Body -> {
                val meta = ResponseMeta(r.status, r.etag, r.lastModified, r.bytes.size.toLong())
                when {
                    r.status !in 200..299 -> nonSuccess(r)
                    conditional?.etag != null && conditional.etag == r.etag -> HttpResult.NotModified(meta.copy(status = 304))
                    conditional?.lastModified != null && conditional.lastModified == r.lastModified -> HttpResult.NotModified(meta.copy(status = 304))
                    r.bytes.size > maxBytes -> HttpResult.Failure(FailureKind.TOO_LARGE, "${r.bytes.size} > $maxBytes")
                    else -> HttpResult.Ok(r.bytes, meta)
                }
            }

            is FakeResponse.Status -> HttpResult.Status(ResponseMeta(r.status), r.body.take(512))

            is FakeResponse.Fail -> HttpResult.Failure(r.kind, r.message)
        }

    override suspend fun getRange(url: String, offset: Long, length: Int): HttpResult<ByteArray> {
        val range = offset until offset + length
        return when (val r = respond(FakeRequest("RANGE", url, range = range))) {
            is FakeResponse.Body -> {
                val size = r.bytes.size.toLong()
                when {
                    r.status !in 200..299 -> nonSuccess(r)

                    offset < 0 || length <= 0 -> HttpResult.Failure(FailureKind.BAD_RANGE, "range $offset+$length of $size")

                    // 실제 서버처럼 시작 위치가 본문 끝 이상이면 416
                    offset >= size -> HttpResult.Status(ResponseMeta(416, totalLength = size), "")

                    else -> {
                        val end = minOf(size, offset + length).toInt()
                        val slice = r.bytes.copyOfRange(offset.toInt(), end)
                        HttpResult.Ok(slice, ResponseMeta(206, r.etag, r.lastModified, slice.size.toLong(), totalLength = size))
                    }
                }
            }

            is FakeResponse.Status -> HttpResult.Status(ResponseMeta(r.status), r.body.take(512))

            is FakeResponse.Fail -> HttpResult.Failure(r.kind, r.message)
        }
    }

    override suspend fun head(url: String): HttpResult<Unit> =
        when (val r = respond(FakeRequest("HEAD", url))) {
            is FakeResponse.Body -> when {
                r.status !in 200..299 -> HttpResult.Status(ResponseMeta(r.status), "")
                else -> HttpResult.Ok(Unit, ResponseMeta(r.status, r.etag, r.lastModified, r.bytes.size.toLong()))
            }

            is FakeResponse.Status -> HttpResult.Status(ResponseMeta(r.status), "")

            is FakeResponse.Fail -> HttpResult.Failure(r.kind, r.message)
        }

    override suspend fun download(url: String, request: DownloadRequest): HttpResult<TempDownload> {
        val r = respond(FakeRequest("DOWNLOAD", url))
        if (r is FakeResponse.Body && r.status !in 200..299) return nonSuccess(r)
        return when (r) {
            is FakeResponse.Body -> when (val checked = check(r.bytes, request)) {
                is HttpResult.Ok -> {
                    Files.createDirectories(tempDir)
                    val path = Files.createTempFile(tempDir, "dl-", ".part")
                    Files.write(path, r.bytes)
                    HttpResult.Ok(TempDownload(path, checked.value.size, checked.value.digests), checked.meta)
                }

                is HttpResult.NotModified -> checked

                is HttpResult.Status -> checked

                is HttpResult.Failure -> checked
            }

            is FakeResponse.Status -> HttpResult.Status(ResponseMeta(r.status), r.body.take(512))

            is FakeResponse.Fail -> HttpResult.Failure(r.kind, r.message)
        }
    }

    override suspend fun digest(url: String, request: DownloadRequest): HttpResult<StreamDigest> =
        when (val r = respond(FakeRequest("DIGEST", url))) {
            is FakeResponse.Body -> if (r.status in 200..299) check(r.bytes, request) else nonSuccess(r)
            is FakeResponse.Status -> HttpResult.Status(ResponseMeta(r.status), r.body.take(512))
            is FakeResponse.Fail -> HttpResult.Failure(r.kind, r.message)
        }

    private fun nonSuccess(r: FakeResponse.Body): HttpResult<Nothing> =
        HttpResult.Status(ResponseMeta(r.status, r.etag, r.lastModified, r.bytes.size.toLong()), r.bytes.decodeToString().take(512))

    private fun check(bytes: ByteArray, request: DownloadRequest): HttpResult<StreamDigest> {
        if (bytes.size > request.maxBytes) return HttpResult.Failure(FailureKind.TOO_LARGE, "${bytes.size} > ${request.maxBytes}")
        val algos = request.algorithms + request.expected.keys
        val digests = algos.associateWith { hex(it, bytes) }
        for ((algo, want) in request.expected) {
            if (!want.equals(digests[algo], ignoreCase = true)) {
                return HttpResult.Failure(FailureKind.DIGEST_MISMATCH, "$algo ${digests[algo]} != $want")
            }
        }
        return HttpResult.Ok(StreamDigest(bytes.size.toLong(), digests), ResponseMeta(200, contentLength = bytes.size.toLong()))
    }

    companion object {
        fun hex(algo: DigestAlgo, bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance(algo.jcaName).digest(bytes))
    }
}
