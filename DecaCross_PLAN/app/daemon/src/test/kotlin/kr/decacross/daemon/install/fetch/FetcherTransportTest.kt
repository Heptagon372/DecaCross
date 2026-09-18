package kr.decacross.daemon.install.fetch

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchResult
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.RetryPolicy
import kr.decacross.daemon.install.installHttpClient
import kr.decacross.daemon.testkit.LocalHttpServer
import kr.decacross.daemon.testkit.TestJars
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 소켓 위 동작 (설계 §4.5 "Real transport"). 127.0.0.1 JDK HttpServer 만 쓴다 — ★ 네트워크로 나가지 않는다.
 * 전체 15초 예산.
 */
class FetcherTransportTest {
    /** 소켓 타임아웃만 1초로 줄인 테스트 클라이언트 (운영 기본은 30초라 테스트가 너무 길어진다). */
    private fun shortSocketClient(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(UserAgent) { agent = TEST_UA }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 1_000
            requestTimeoutMillis = null
        }
        engine { requestTimeout = 0 }
    }

    private fun onceOnly(): RetryPolicy =
        RetryPolicy(maxRetriesPerSource = 0, maxAttemptsPerSource = 1, baseDelayMs = 5, maxDelayMs = 10, maxJitterMs = 1)

    @Test
    fun `A 본문이 멈추면 소켓 타임아웃 안에 실패한다`(): Unit = runBlocking {
        withTempDir { dir ->
            LocalHttpServer().use { server ->
                val bytes = randomBytes(200_000)
                server.put("/stall", LocalHttpServer.Resource(bytes, stallAfterBytes = 8192, stallMs = 3_000))
                val item = testItem(bytes, sources = listOf(server.url("/stall")))
                shortSocketClient().use { client ->
                    val fetcher = Fetcher(client, dir, onceOnly(), progressIntervalMs = 20)
                    val startNanos = System.nanoTime()
                    val result = fetcher.fetchAll(listOf(item), RecordingListener()).single()
                    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                    assertIs<FetchResult.Failed>(result)
                    // 설계: socketTimeout + 0.5초. 병렬로 다른 작업이 도는 기계라 상한을 넉넉히 둔다.
                    assertTrue(elapsedMs < 3_000, "너무 오래 걸렸다: ${elapsedMs}ms")
                }
            }
        }
    }

    @Test
    fun `B 3초짜리 느린 본문도 운영 타임아웃이면 끝까지 받는다`(): Unit = runBlocking {
        withTempDir { dir ->
            LocalHttpServer().use { server ->
                val bytes = randomBytes(300_000)
                server.put("/slow", LocalHttpServer.Resource(bytes, bytesPerSecond = 100_000))
                val item = testItem(bytes, sources = listOf(server.url("/slow")))
                installHttpClient(TEST_UA).use { client ->
                    val fetcher = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 50)
                    val listener = RecordingListener()
                    val fetched = assertIs<FetchResult.Fetched>(fetcher.fetchAll(listOf(item), listener).single())
                    assertEquals(item.sha256, TestJars.sha256Hex(Files.readAllBytes(fetched.file)))
                    assertEquals(1, server.requestsTo("/slow").size)
                    assertTrue(listener.progress.isNotEmpty(), "진행률이 몇 번은 와야 한다")
                }
            }
        }
    }

    @Test
    fun `C 본문이 끊기면 다음 실행이 이어받아 해시가 맞는다`(): Unit = runBlocking {
        withTempDir { dir ->
            LocalHttpServer().use { server ->
                val bytes = randomBytes(250_000)
                val url = server.url("/cut")
                val item = testItem(bytes, sources = listOf(url))
                server.put("/cut", LocalHttpServer.Resource(bytes, cutAfterBytes = 60_000))
                installHttpClient(TEST_UA).use { client ->
                    val first = Fetcher(client, dir, onceOnly(), progressIntervalMs = 20)
                        .fetchAll(listOf(item), RecordingListener()).single()
                    assertIs<FetchResult.Failed>(first)
                    val partial = Files.size(partPath(dir, item))
                    assertTrue(partial in 1 until bytes.size.toLong(), "일부만 받았어야 한다: $partial")

                    server.put("/cut", LocalHttpServer.Resource(bytes))
                    val listener = RecordingListener()
                    val second = Fetcher(client, dir, fastPolicy(), progressIntervalMs = 20)
                        .fetchAll(listOf(item), listener).single()
                    val fetched = assertIs<FetchResult.Fetched>(second)
                    assertEquals(partial, fetched.resumedFrom)
                    assertEquals(item.sha256, TestJars.sha256Hex(Files.readAllBytes(fetched.file)))
                    val resumed = server.requestsTo("/cut").last()
                    assertEquals("bytes=$partial-", resumed.headers["range"])
                }
            }
        }
    }

    @Test
    fun `D 서버가 Range 를 무시해도 같은 응답 하나로 끝낸다`(): Unit = runBlocking {
        withTempDir { dir ->
            LocalHttpServer().use { server ->
                val bytes = randomBytes(120_000)
                val url = server.url("/noRange")
                val item = testItem(bytes, sources = listOf(url))
                server.put("/noRange", LocalHttpServer.Resource(bytes, ignoreRange = true))
                seedPartial(dir, item, bytes, upTo = 40_000, url = url)
                installHttpClient(TEST_UA).use { client ->
                    val listener = RecordingListener()
                    val fetched = assertIs<FetchResult.Fetched>(
                        Fetcher(client, dir, fastPolicy(), progressIntervalMs = 20)
                            .fetchAll(listOf(item), listener).single(),
                    )
                    assertEquals(1, server.requestsTo("/noRange").size)
                    assertEquals(40_000L, listener.only<FetchItemEvent.ResumeRejected>().single().discardedBytes)
                    assertEquals(item.sha256, TestJars.sha256Hex(Files.readAllBytes(fetched.file)))
                }
            }
        }
    }

    @Test
    fun `E 302 를 따라가도 Range 와 If-Range 가 남는다`(): Unit = runBlocking {
        withTempDir { dir ->
            LocalHttpServer().use { server ->
                val bytes = randomBytes(90_000)
                val url = server.url("/redir")
                val item = testItem(bytes, sources = listOf(url))
                server.put("/redir", LocalHttpServer.Resource(bytes, redirectTo = "/real"))
                server.put("/real", LocalHttpServer.Resource(bytes))
                seedPartial(dir, item, bytes, upTo = 30_000, url = url)
                installHttpClient(TEST_UA).use { client ->
                    val fetched = assertIs<FetchResult.Fetched>(
                        Fetcher(client, dir, fastPolicy(), progressIntervalMs = 20)
                            .fetchAll(listOf(item), RecordingListener()).single(),
                    )
                    val redirected = server.requestsTo("/real").single()
                    assertEquals("bytes=30000-", redirected.headers["range"])
                    assertEquals("\"v1\"", redirected.headers["if-range"])
                    assertEquals(item.sha256, TestJars.sha256Hex(Files.readAllBytes(fetched.file)))
                }
            }
        }
    }
}
