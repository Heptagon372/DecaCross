package kr.decacross.daemon.install.fetch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.prepareGet
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchResult
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.RetryPolicy
import kr.decacross.daemon.install.installHttpClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 설계 §2.11 전송 상태기계 (MockEngine). 실제 소켓 동작은 [FetcherTransportTest] 가 본다.
 *
 * ★ 네트워크로 나가지 않는다: 모든 주소가 `.invalid` 이고 MockEngine 은 DNS 를 타지 않는다.
 */
class FetcherMockTest {
    private fun HttpRequestData.host(): String = url.host

    private fun requestsTo(engine: MockEngine, host: String): List<HttpRequestData> =
        engine.requestHistory.filter { it.url.host == host }

    @Test
    fun `1 새로 받으면 바이트가 정확하고 진행률이 전체 크기까지 오른다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(300_000)
            val item = testItem(bytes)
            val engine = MockEngine { request -> serve(request, bytes) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
                assertEquals(0L, fetched.resumedFrom)
                assertEquals(ORIGIN_URL, fetched.source)
                assertEquals(1, engine.requestHistory.size)
                assertNull(engine.requestHistory.single().headers[HttpHeaders.Range])

                val done = listener.progress.map { it.doneBytes }
                assertEquals(done.sorted(), done, "진행률은 단조 증가해야 한다")
                assertEquals(bytes.size.toLong(), listener.progress.last().doneBytes)
                assertEquals(bytes.size.toLong(), listener.progress.last().totalBytes)
                val completed = listener.only<FetchItemEvent.Completed>().single()
                assertEquals(false, completed.fromCache)
                assertEquals(1, listener.only<FetchItemEvent.Started>().size)
            }
        }
    }

    @Test
    fun `2 부분 파일이 있으면 Range 와 If-Range 를 보내고 206 으로 완성한다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(200_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 70_000)
            val engine = MockEngine { request -> serve(request, bytes) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                val request = engine.requestHistory.single()
                assertEquals("bytes=70000-", request.headers[HttpHeaders.Range])
                assertEquals("\"v1\"", request.headers[HttpHeaders.IfRange])
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertEquals(70_000L, fetched.resumedFrom)
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
            }
        }
    }

    @Test
    fun `3 약한 ETag 만 있으면 다음 시도는 Range 를 보내지 않는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(120_000)
            val item = testItem(bytes)
            val calls = AtomicInteger()
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() == 1) {
                    // 약한 ETag + Last-Modified 없음 → 검증자 없음. 게다가 본문이 짧다.
                    serve(request, bytes, etag = "W/\"v1\"", truncateTo = 40_000)
                } else {
                    serve(request, bytes, etag = "W/\"v1\"")
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(2, engine.requestHistory.size)
                assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Range] == null }, "Range 를 보내면 안 된다")
                assertEquals(0L, fetched.resumedFrom)
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
                // 받아 둔 40 000 바이트를 버리는 것도 ResumeRejected 다 (진행률이 줄어드는 유일한 경우)
                val rejected = listener.only<FetchItemEvent.ResumeRejected>().single()
                assertEquals(40_000L, rejected.discardedBytes)
                assertEquals(0L, listener.only<FetchItemEvent.Started>().single().resumedFrom)
            }
        }
    }

    @Test
    fun `4 서버가 Range 를 무시하면 같은 응답을 처음부터 쓴다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(150_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 50_000)
            val engine = MockEngine { request -> serve(request, bytes, ignoreRange = true) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(1, engine.requestHistory.size, "같은 응답을 재사용해야 한다 (F11)")
                val rejected = listener.only<FetchItemEvent.ResumeRejected>().single()
                assertEquals(50_000L, rejected.discardedBytes)
                assertEquals(0L, fetched.resumedFrom)
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
            }
        }
    }

    @Test
    fun `5 206 시작 위치가 틀리면 처음부터, 두 번째면 이 소스를 포기한다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(80_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 20_000)
            // 항상 엉뚱한 시작 위치의 206
            val engine = MockEngine { _ ->
                val headers = HeadersBuilder()
                headers.append(HttpHeaders.ContentType, "application/java-archive")
                headers.append(HttpHeaders.ContentRange, "bytes 5-${bytes.size - 1}/${bytes.size}")
                respond(bytes.copyOfRange(5, bytes.size), HttpStatusCode.PartialContent, headers.build())
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                assertIs<FetchError.SourcesExhausted>(failed.error)
                assertEquals(2, engine.requestHistory.size, "Restart 두 번이면 이 소스를 포기한다")
            }
        }
    }

    @Test
    fun `5b multipart byteranges 응답은 처음부터 받는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(40_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 10_000)
            val calls = AtomicInteger()
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() == 1) {
                    val headers = HeadersBuilder()
                    headers.append(HttpHeaders.ContentType, "multipart/byteranges; boundary=abc")
                    headers.append(HttpHeaders.ContentRange, "bytes 10000-${bytes.size - 1}/${bytes.size}")
                    respond(ByteArray(0), HttpStatusCode.PartialContent, headers.build())
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(2, engine.requestHistory.size)
                assertNull(engine.requestHistory[1].headers[HttpHeaders.Range], "조각을 버렸으니 Range 없음")
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
            }
        }
    }

    @Test
    fun `6 Content-Range 전체 크기가 다르면 즉시 중단하고 조각을 지운다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(60_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            seedPartial(dir, item, bytes, upTo = 20_000)
            val engine = MockEngine { request -> serve(request, bytes, totalOverride = bytes.size + 7L) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.SizeMismatch>(failed.error)
                assertEquals("Content-Range", error.where)
                assertEquals(bytes.size + 7L, error.observed)
                assertEquals(1, engine.requestHistory.size, "재시도도 미러도 없다")
                assertTrue(Files.notExists(partPath(dir, item)), "오염된 조각은 지운다")
                assertTrue(Files.notExists(metaPath(dir, item)))
                // 실패 이벤트도 결과와 같은 오류로 한 번 나간다 (§2.15 가 "받기 실패" 줄을 여기서 만든다)
                val failedEvent = listener.only<FetchItemEvent.Failed>().single()
                assertEquals(failed.error, failedEvent.error)
                assertEquals(item.id, failedEvent.itemId)
            }
        }
    }

    @Test
    fun `6b 416 의 전체 크기가 다르면 무결성 실패`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(30_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 10_000)
            val engine = MockEngine { _ ->
                respond(
                    ByteArray(0),
                    HttpStatusCode.fromValue(416),
                    headersOf(HttpHeaders.ContentRange, "bytes */${bytes.size + 3}"),
                )
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.SizeMismatch>(failed.error)
                assertEquals("Content-Range", error.where)
                assertEquals(1, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `7 이미 다 받은 조각은 요청 없이 캐시 적중`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(50_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = bytes.size)
            val engine = MockEngine { _ -> error("요청이 있으면 안 된다") }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(0, engine.requestHistory.size)
                assertEquals(bytes.size.toLong(), fetched.resumedFrom)
                assertNull(fetched.source)
                assertEquals(true, listener.only<FetchItemEvent.Completed>().single().fromCache)
            }
        }
    }

    @Test
    fun `8 416 전체 크기는 맞지만 덜 받았으면 처음부터`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(40_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 15_000)
            val calls = AtomicInteger()
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() == 1) {
                    respond(
                        ByteArray(0),
                        HttpStatusCode.fromValue(416),
                        headersOf(HttpHeaders.ContentRange, "bytes */${bytes.size}"),
                    )
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(2, engine.requestHistory.size)
                assertNull(engine.requestHistory[1].headers[HttpHeaders.Range])
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
            }
        }
    }

    @Test
    fun `9 스트리밍 중 끊기면 받은 만큼부터 이어받고 실패로 세지 않는다`(): Unit = runBlocking {
        withTempDir { dir ->
            MockBodies().use { bodies ->
                val bytes = randomBytes(100_000)
                val item = testItem(bytes)
                val calls = AtomicInteger()
                var durableAtRetry: Long? = null
                val engine = MockEngine { request ->
                    if (calls.incrementAndGet() == 1) {
                        val headers = HeadersBuilder()
                        headers.append(HttpHeaders.ContentType, "application/java-archive")
                        headers.append(HttpHeaders.ContentLength, bytes.size.toString())
                        headers.append(HttpHeaders.ETag, "\"v1\"")
                        respond(bodies.failAfter(bytes, 32_768), HttpStatusCode.OK, headers.build())
                    } else {
                        // 두 번째 요청 시점의 조각 길이를 그대로 붙잡아 Range 와 대조한다
                        durableAtRetry = PartialStore(dir).readMeta(item)?.durableLength
                        serve(request, bytes)
                    }
                }
                installHttpClient(TEST_UA, engine).use { client ->
                    val listener = RecordingListener()
                    val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                    val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                    assertEquals(2, engine.requestHistory.size)
                    val received = durableAtRetry
                    assertTrue(received != null && received > 0, "끊기기 전에 받은 바이트가 있어야 한다: $received")
                    val range = engine.requestHistory[1].headers[HttpHeaders.Range]
                    assertEquals("bytes=$received-", range, "받은 바이트 수에서 이어받아야 한다")
                    val retrying = listener.only<FetchItemEvent.Retrying>().single()
                    assertEquals(5L, retrying.delayMs, "새 바이트를 받았으니 기본 지연만")
                    assertContentEquals(bytes, Files.readAllBytes(fetched.file))
                }
            }
        }
    }

    @Test
    fun `10 본문이 짧으면 일시 실패로 보고 이어받는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(90_000)
            val item = testItem(bytes)
            val calls = AtomicInteger()
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() == 1) serve(request, bytes, truncateTo = 30_000) else serve(request, bytes)
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(2, engine.requestHistory.size)
                assertEquals("bytes=30000-", engine.requestHistory[1].headers[HttpHeaders.Range])
                assertEquals(30_000L, fetched.resumedFrom)
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
            }
        }
    }

    @Test
    fun `10b 새 바이트를 받은 시도는 실패로 세지 않아 재시도 한도를 넘겨도 이어받는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(60_000)
            val item = testItem(bytes)
            val calls = AtomicInteger()
            // 여섯 번 연속으로 5 000 바이트만 보내고 끊는다 (매번 새 바이트 = progressed).
            // SCP-I11: 진행이 있는 시도는 실패로 세지 않으므로 maxRetriesPerSource(3) 를 넘어도 계속 간다.
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() <= 6) serve(request, bytes, truncateTo = 5_000) else serve(request, bytes)
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(7, engine.requestHistory.size, "진행이 있는 한 4회에서 멈추면 안 된다")
                assertEquals("bytes=30000-", engine.requestHistory[6].headers[HttpHeaders.Range])
                assertEquals(30_000L, fetched.resumedFrom)
                assertContentEquals(bytes, Files.readAllBytes(fetched.file))
                val retrying = listener.only<FetchItemEvent.Retrying>()
                assertEquals(6, retrying.size)
                assertTrue(retrying.all { it.delayMs == 5L }, "진행한 시도는 기본 지연만: $retrying")
            }
        }
    }

    @Test
    fun `로컬 쓰기 실패는 LocalIo 로 끝나고 요청도 미러도 없다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(10_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            // `.part` 자리에 비어 있지 않은 디렉터리 → FileChannel.open 이 IOException (디스크 가득과 같은 분류)
            val part = partPath(dir, item)
            Files.createDirectories(part)
            Files.write(part.resolve("blocker.txt"), byteArrayOf(1))
            val engine = MockEngine { request -> serve(request, bytes) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.LocalIo>(failed.error)
                assertEquals(part, error.path)
                assertEquals(0, engine.requestHistory.size, "로컬 오류는 미러로 넘어가지 않는다")
                assertEquals(1, listener.only<FetchItemEvent.Failed>().size)
            }
        }
    }

    @Test
    fun `execute 안에서 던진 로컬 오류는 감싸이지 않고 그대로 올라온다`(): Unit = runBlocking {
        // 이 가정이 깨지면 디스크 오류가 일반 catch 로 흘러 "이 미러가 이상하다"로 잘못 분류된다 (설계 §2.11 표 마지막 줄)
        val bytes = randomBytes(2_000)
        val engine = MockEngine { request -> serve(request, bytes) }
        installHttpClient(TEST_UA, engine).use { client ->
            val thrown = assertFailsWith<LocalIoException> {
                client.prepareGet(ORIGIN_URL).execute {
                    throw LocalIoException(Path.of("가상.part"), IOException("디스크 가득"))
                }
            }
            assertEquals("가상.part", thrown.path.toString())
        }
    }

    @Test
    fun `11 503 이 이어지면 정확히 4회 시도하고 미러로 넘어간다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(20_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            val engine = MockEngine { request ->
                if (request.host() == "origin.invalid") {
                    respond(ByteArray(0), HttpStatusCode.ServiceUnavailable)
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(4, requestsTo(engine, "origin.invalid").size, "1회 + 재시도 3회")
                assertEquals(1, requestsTo(engine, "mirror.invalid").size)
                assertEquals(MIRROR_URL, fetched.source)
                val switched = listener.only<FetchItemEvent.SourceSwitched>().single()
                assertEquals(0, switched.fromIndex)
                assertEquals(1, switched.toIndex)
                assertEquals(3, listener.only<FetchItemEvent.Retrying>().size)
                // 18: 모든 요청에 UA 가 붙는다 (불변식 17)
                assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.UserAgent] == TEST_UA })
            }
        }
    }

    @Test
    fun `12 404 면 재시도 없이 바로 미러로`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(20_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            val engine = MockEngine { request ->
                if (request.host() == "origin.invalid") {
                    respond(ByteArray(0), HttpStatusCode.NotFound)
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), RecordingListener()).single())
                assertEquals(1, requestsTo(engine, "origin.invalid").size)
                assertEquals(MIRROR_URL, fetched.source)
            }
        }
    }

    @Test
    fun `13 모든 소스를 소진하면 SourcesExhausted 이고 조각은 남는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(40_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            seedPartial(dir, item, bytes, upTo = 12_000)
            val engine = MockEngine { _ -> respond(ByteArray(0), HttpStatusCode.BadGateway) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.SourcesExhausted>(failed.error)
                assertEquals(8, error.attempts.size, "소스마다 4회")
                assertEquals(8, engine.requestHistory.size)
                // 첫 소스의 네 번은 전부 씨앗 조각에서 이어받는다 = 전송 실패가 조각을 지우지 않았다
                assertTrue(
                    requestsTo(engine, "origin.invalid").all { it.headers[HttpHeaders.Range] == "bytes=12000-" },
                    "전송 실패로는 조각을 버리지 않는다",
                )
                // 미러는 저장된 검증자가 자기 것이 아니라 처음부터 받는다 (설계 §2.11) → 그때 ResumeRejected 한 번
                assertTrue(requestsTo(engine, "mirror.invalid").all { it.headers[HttpHeaders.Range] == null })
                assertEquals(12_000L, listener.only<FetchItemEvent.ResumeRejected>().single().discardedBytes)
                // Started 는 "이 소스에서 실제로 이어받을 오프셋"을 알린다 — 미러는 처음부터라 0
                assertEquals(
                    listOf(12_000L, 0L),
                    listener.only<FetchItemEvent.Started>().map { it.resumedFrom },
                )
                assertTrue(Files.exists(partPath(dir, item)), "조각은 남긴다 (다음 실행이 이어받는다)")
                assertTrue(Files.exists(metaPath(dir, item)))
                assertEquals(1, listener.only<FetchItemEvent.Failed>().size)
            }
        }
    }

    @Test
    fun `13b 소진해도 이미 받은 바이트는 그대로 남는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(40_000)
            val item = testItem(bytes)
            seedPartial(dir, item, bytes, upTo = 12_345)
            val engine = MockEngine { _ -> respond(ByteArray(0), HttpStatusCode.ServiceUnavailable) }
            installHttpClient(TEST_UA, engine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), RecordingListener()).single())
                assertEquals(12_345L, Files.size(partPath(dir, item)))
                assertEquals(12_345L, PartialStore(dir).readMeta(item)?.durableLength)
            }
        }
    }

    @Test
    fun `14 Content-Length 가 기대와 다르면 재시도도 미러도 없다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(20_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            val engine = MockEngine { request -> serve(request, bytes, totalOverride = bytes.size + 5L) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.SizeMismatch>(failed.error)
                assertEquals("Content-Length", error.where)
                assertEquals(bytes.size + 5L, error.observed)
                assertEquals(1, engine.requestHistory.size)
                assertTrue(Files.notExists(partPath(dir, item)))
            }
        }
    }

    @Test
    fun `15 Content-Length 없이 더 보내면 크기를 넘는 순간 끊는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(1_000)
            val item = testItem(bytes)
            val engine = MockEngine { request -> serve(request, bytes, sendContentLength = false, extraBytes = 1) }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), listener).single())
                val error = assertIs<FetchError.SizeMismatch>(failed.error)
                assertEquals("stream overflow", error.where)
                assertEquals(bytes.size + 1L, error.observed)
            }
        }
    }

    @Test
    fun `16 gzip 은 이 소스 포기, text_html 은 UnexpectedContent`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(5_000)
            val item = testItem(bytes)
            val gzipEngine = MockEngine { request -> serve(request, bytes, contentEncoding = "gzip") }
            installHttpClient(TEST_UA, gzipEngine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), RecordingListener()).single())
                assertIs<FetchError.SourcesExhausted>(failed.error)
                assertEquals(1, gzipEngine.requestHistory.size)
            }
        }
        withTempDir { dir ->
            val bytes = randomBytes(5_000)
            val item = testItem(bytes)
            val htmlEngine = MockEngine { request ->
                serve(request, "<html>portal</html>".toByteArray(), contentType = "text/html; charset=utf-8")
            }
            installHttpClient(TEST_UA, htmlEngine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val failed = assertIs<FetchResult.Failed>(fetcher.fetchAll(listOf(item), RecordingListener()).single())
                val error = assertIs<FetchError.UnexpectedContent>(failed.error)
                assertEquals(ORIGIN_URL, error.source)
                assertEquals(1, htmlEngine.requestHistory.size)
            }
        }
    }

    @Test
    fun `17 Retry-After 0 은 그대로 따르고 너무 길면 소스를 건너뛴다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(10_000)
            val item = testItem(bytes)
            val calls = AtomicInteger()
            val engine = MockEngine { request ->
                if (calls.incrementAndGet() == 1) {
                    respond(
                        ByteArray(0),
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.RetryAfter, "0"),
                    )
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(0L, listener.only<FetchItemEvent.Retrying>().single().delayMs)
                assertEquals(2, engine.requestHistory.size)
            }
        }
        withTempDir { dir ->
            val bytes = randomBytes(10_000)
            val item = testItem(bytes, sources = listOf(ORIGIN_URL, MIRROR_URL))
            val engine = MockEngine { request ->
                if (request.host() == "origin.invalid") {
                    respond(
                        ByteArray(0),
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.RetryAfter, "3600"),
                    )
                } else {
                    serve(request, bytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val listener = RecordingListener()
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                assertEquals(1, requestsTo(engine, "origin.invalid").size, "한 시간은 기다리지 않는다")
                assertEquals(MIRROR_URL, fetched.source)
                assertEquals(0, listener.only<FetchItemEvent.Retrying>().size)
            }
        }
    }

    @Test
    fun `19b 백오프 동안에는 동시 실행 권한을 쥐지 않는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val slowBytes = randomBytes(2_000, seed = 1)
            val fastBytes = randomBytes(2_000, seed = 2)
            val slowItem = testItem(slowBytes, id = "a", sources = listOf(ORIGIN_URL))
            val fastItem = testItem(fastBytes, id = "b", sources = listOf(MIRROR_URL))
            val originCalls = AtomicInteger()
            val engine = MockEngine { request ->
                if (request.host() == "origin.invalid") {
                    if (originCalls.incrementAndGet() == 1) {
                        respond(ByteArray(0), HttpStatusCode.ServiceUnavailable)
                    } else {
                        serve(request, slowBytes)
                    }
                } else {
                    serve(request, fastBytes)
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val policy = RetryPolicy(baseDelayMs = 200, maxDelayMs = 200, maxJitterMs = 1)
                val fetcher = Fetcher(client, dir, policy, maxParallel = 1, progressIntervalMs = 10)
                val results = fetcher.fetchAll(listOf(slowItem, fastItem), RecordingListener())
                assertTrue(results.all { it is FetchResult.Fetched })
                val hosts = engine.requestHistory.map { it.url.host }
                val mirrorAt = hosts.indexOf("mirror.invalid")
                val secondOriginAt = hosts.indexOfLast { it == "origin.invalid" }
                assertTrue(
                    mirrorAt in 0 until secondOriginAt,
                    "백오프 중에 다른 항목이 내려받아야 한다: $hosts",
                )
            }
        }
    }
}
