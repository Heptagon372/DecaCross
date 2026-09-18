package kr.decacross.daemon.install.fetch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchResult
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.installHttpClient
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 동시성·취소·정리 (설계 §4.5 테스트 19~24). 실제 시간으로 돈다. */
class FetcherConcurrencyTest {
    private fun MockEngine.hosts(): List<String> = requestHistory.map { it.url.host }

    @Test
    fun `19 10개를 받아도 동시 요청은 4개를 넘지 않는다`(): Unit = runBlocking {
        withTempDir { dir ->
            val items = (0 until 10).map { i -> testItem(randomBytes(4_000, seed = i + 1), id = "item-$i") }
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val engine = MockEngine { request ->
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    delay(40)
                    val index = request.url.parameters["i"]?.toInt() ?: 0
                    serve(request, randomBytes(4_000, seed = index + 1))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            installHttpClient(TEST_UA, engine).use { client ->
                val indexed = items.mapIndexed { i, item -> item.copy(sources = listOf("$ORIGIN_URL?i=$i")) }
                val fetcher = Fetcher(client, dir, fastPolicy(), maxParallel = 4, progressIntervalMs = 10)
                val results = fetcher.fetchAll(indexed, RecordingListener())
                assertEquals(10, results.size)
                assertTrue(results.all { it is FetchResult.Fetched }, "전부 받아야 한다: $results")
                assertTrue(peak.get() <= 4, "동시 요청이 너무 많다: ${peak.get()}")
                assertEquals(10, engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `20 본문 도중 취소하면 결과 없이 오프셋이 정확히 남고 파일을 지울 수 있다`(): Unit = runBlocking {
        withTempDir { dir ->
            MockBodies().use { bodies ->
                val bytes = randomBytes(400_000)
                val item = testItem(bytes)
                val engine = MockEngine { _ ->
                    val headers = HeadersBuilder()
                    headers.append(HttpHeaders.ContentType, "application/java-archive")
                    headers.append(HttpHeaders.ContentLength, bytes.size.toString())
                    headers.append(HttpHeaders.ETag, "\"v1\"")
                    respond(bodies.slow(bytes, chunk = 8192, chunkDelayMs = 20), HttpStatusCode.OK, headers.build())
                }
                installHttpClient(TEST_UA, engine).use { client ->
                    val listener = RecordingListener()
                    val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                    var results: List<FetchResult>? = null
                    val job = launch { results = fetcher.fetchAll(listOf(item), listener) }
                    delay(250)
                    job.cancelAndJoin()

                    assertNull(results, "취소는 결과가 아니다")
                    val part = partPath(dir, item)
                    assertTrue(Files.exists(part), "조각은 남긴다")
                    val meta = PartialStore(dir).readMeta(item)
                    assertEquals(Files.size(part), meta?.durableLength, "durableLength 는 실제 파일 크기와 같아야 한다")
                    assertTrue(Files.size(part) > 0, "일부는 받았어야 한다")
                    // 핸들이 모두 닫혔다 (Windows 에서 열려 있으면 지울 수 없다)
                    Files.delete(part)
                    assertTrue(Files.notExists(part))
                }
            }
        }
    }

    @Test
    fun `21 형제 코루틴이 실패하면 재시도도 Failed 보고도 하지 않는다`(): Unit = runBlocking {
        withTempDir { dir ->
            MockBodies().use { bodies ->
                val bytes = randomBytes(400_000)
                val item = testItem(bytes)
                val engine = MockEngine { _ ->
                    val headers = HeadersBuilder()
                    headers.append(HttpHeaders.ContentType, "application/java-archive")
                    headers.append(HttpHeaders.ContentLength, bytes.size.toString())
                    headers.append(HttpHeaders.ETag, "\"v1\"")
                    respond(bodies.slow(bytes, chunk = 8192, chunkDelayMs = 20), HttpStatusCode.OK, headers.build())
                }
                installHttpClient(TEST_UA, engine).use { client ->
                    val listener = RecordingListener()
                    val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                    var results: List<FetchResult>? = null
                    assertFailsWith<IOException> {
                        coroutineScope {
                            launch { results = fetcher.fetchAll(listOf(item), listener) }
                            launch {
                                delay(200)
                                throw IOException("형제 실패")
                            }
                        }
                    }
                    assertNull(results)
                    assertEquals(0, listener.only<FetchItemEvent.Failed>().size, "취소를 전송 실패로 오인하면 안 된다 (F12)")
                    assertEquals(0, listener.only<FetchItemEvent.Retrying>().size)
                    assertEquals(1, engine.requestHistory.size)
                }
            }
        }
    }

    @Test
    fun `23 한 항목의 무결성 실패는 형제를 Aborted 로 만든다`(): Unit = runBlocking {
        withTempDir { dir ->
            MockBodies().use { bodies ->
                val slowA = randomBytes(400_000, seed = 11)
                val bad = randomBytes(20_000, seed = 12)
                val slowB = randomBytes(400_000, seed = 13)
                val itemA = testItem(slowA, id = "a", sources = listOf("$ORIGIN_URL?k=a"))
                val itemBad = testItem(bad, id = "bad", sources = listOf("$ORIGIN_URL?k=bad"))
                val itemB = testItem(slowB, id = "b", sources = listOf("$ORIGIN_URL?k=b"))
                val engine = MockEngine { request ->
                    when (request.url.parameters["k"]) {
                        "bad" -> serve(request, bad, totalOverride = bad.size + 9L)
                        "a" -> slowResponse(bodies, slowA)
                        else -> slowResponse(bodies, slowB)
                    }
                }
                installHttpClient(TEST_UA, engine).use { client ->
                    val listener = RecordingListener()
                    val fetcher = Fetcher(client, dir, fastPolicy(), maxParallel = 4, progressIntervalMs = 10)
                    val results = fetcher.fetchAll(listOf(itemA, itemBad, itemB), listener)
                    val badResult = assertIs<FetchResult.Failed>(results[1])
                    assertIs<FetchError.SizeMismatch>(badResult.error)
                    for (index in listOf(0, 2)) {
                        val aborted = assertIs<FetchResult.Failed>(results[index])
                        assertEquals(FetchError.Aborted("bad"), aborted.error)
                    }
                }
            }
        }
    }

    @Test
    fun `24 discard 는 잠금이 비었을 때만 지운다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = randomBytes(5_000)
            val item = testItem(bytes)
            val engine = MockEngine { _ -> respond(ByteArray(0), HttpStatusCode.NotFound) }
            installHttpClient(TEST_UA, engine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)

                // 다른 프로세스가 잠금을 쥐고 있으면 아무것도 지우지 않는다 (같은 JVM 은 OverlappingFileLockException)
                seedPartial(dir, item, bytes, upTo = 1_000)
                val lockPath = dir.resolve("${item.sha256}.lock")
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        fetcher.discard(item)
                        assertTrue(Files.exists(partPath(dir, item)), "잠긴 동안에는 남겨야 한다")
                        assertTrue(Files.exists(metaPath(dir, item)))
                    }
                }

                assertTrue(Files.exists(lockPath), "쥐고 있는 잠금 파일은 지우지 않는다")

                // 잠금이 풀리면 지운다
                fetcher.discard(item)
                assertTrue(Files.notExists(partPath(dir, item)))
                assertTrue(Files.notExists(metaPath(dir, item)))
                // ★ 회귀 (D-I8): 잠금 파일도 함께 치운다 — 남기면 설치 한 번마다 0 바이트 파일이 캐시에 쌓인다
                assertTrue(Files.notExists(lockPath), "잠금을 놓았으면 잠금 파일도 지운다: $lockPath")
            }
        }
    }

    @Test
    fun `7일 지난 조각은 Fetcher 가 처음 돌 때 치운다`(): Unit = runBlocking {
        withTempDir { dir ->
            val oldBytes = randomBytes(1_000, seed = 41)
            val freshBytes = randomBytes(1_000, seed = 42)
            val oldItem = testItem(oldBytes, id = "old")
            val freshItem = testItem(freshBytes, id = "fresh")
            seedPartial(dir, oldItem, oldBytes, upTo = 500)
            seedPartial(dir, freshItem, freshBytes, upTo = 500)
            val ancient = FileTime.fromMillis(System.currentTimeMillis() - PARTIAL_MAX_AGE_MS - 60_000)
            Files.setLastModifiedTime(partPath(dir, oldItem), ancient)
            Files.setLastModifiedTime(metaPath(dir, oldItem), ancient)

            val target = randomBytes(2_000, seed = 43)
            val targetItem = testItem(target, id = "target")
            val engine = MockEngine { request -> serve(request, target) }
            installHttpClient(TEST_UA, engine).use { client ->
                val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 10)
                assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(targetItem), RecordingListener()).single())
            }
            assertTrue(Files.notExists(partPath(dir, oldItem)), "오래된 조각은 지운다")
            assertTrue(Files.notExists(metaPath(dir, oldItem)))
            assertTrue(Files.exists(partPath(dir, freshItem)), "최근 조각은 남긴다")
        }
    }
}

private fun MockRequestHandleScope.slowResponse(
    bodies: MockBodies,
    body: ByteArray,
): HttpResponseData {
    val headers = HeadersBuilder()
    headers.append(HttpHeaders.ContentType, "application/java-archive")
    headers.append(HttpHeaders.ContentLength, body.size.toString())
    headers.append(HttpHeaders.ETag, "\"v1\"")
    return respond(bodies.slow(body, chunk = 8192, chunkDelayMs = 20), HttpStatusCode.OK, headers.build())
}
