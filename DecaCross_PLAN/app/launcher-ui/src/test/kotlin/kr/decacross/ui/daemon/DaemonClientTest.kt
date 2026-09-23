package kr.decacross.ui.daemon

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kr.decacross.daemon.api.InstallRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DaemonClientTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** 토큰이 맞으면 데몬처럼 응답, 아니면 401 ErrorDto. */
    private fun fakeDaemon() = MockEngine { req ->
        if (req.headers[HttpHeaders.Authorization] != "Bearer $token") {
            return@MockEngine respond("""{"errorKo":"토큰이 없거나 틀립니다"}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        when {
            req.url.encodedPath == "/api/system" ->
                respond("""{"totalMemoryMb":32768,"recommendedRamMb":6144,"daemonRssMb":80,"version":"0.1.0"}""", HttpStatusCode.OK, jsonHeaders)

            req.url.encodedPath == "/api/servers" ->
                respond(
                    """[{"id":"demo","name":"demo","mcLabel":"1.21.8","core":"PAPER","build":"60","port":25565,"ramMb":2048,"state":"RUNNING"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )

            req.url.encodedPath == "/api/servers/demo/start" && req.method == HttpMethod.Post ->
                respond("""{"errorKo":"이미 실행 중입니다"}""", HttpStatusCode.Conflict, jsonHeaders)

            req.url.encodedPath == "/api/servers/demo/command" && req.method == HttpMethod.Post ->
                respond("", HttpStatusCode.Accepted)

            req.url.encodedPath == "/api/servers/demo" && req.method == HttpMethod.Delete ->
                if (req.url.parameters["keepWorld"] == "false") respond("", HttpStatusCode.NoContent) else respond("""{"errorKo":"keepWorld 누락"}""", HttpStatusCode.BadRequest, jsonHeaders)

            req.url.encodedPath == "/api/install" && req.method == HttpMethod.Post -> {
                val body = req.body.toByteArray().decodeToString()
                assertTrue(body.contains("\"acceptEula\":true"), body)
                assertTrue(body.contains("\"core\":\"PAPER\""), body)
                respond("""{"jobId":"job-1"}""", HttpStatusCode.Accepted, jsonHeaders)
            }

            req.url.encodedPath == "/api/nope" -> respond("<html>not json</html>", HttpStatusCode.NotFound)

            else -> respond("""{"errorKo":"서버 없음"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private val ep = DaemonEndpoint(port = 27565, pid = 1, token = token)

    @Test
    fun `sends bearer token and decodes typed responses`() = runTest {
        DaemonClient(fakeDaemon()).use { c ->
            val sys = c.system(ep)
            assertEquals(6144, sys.recommendedRamMb)
            val servers = c.servers(ep)
            assertEquals("demo", servers.single().id)
            assertEquals(kr.decacross.daemon.process.ServerState.RUNNING, servers.single().state)
            c.sendCommand(ep, "demo", "list")
            c.delete(ep, "demo", keepWorld = false)
            assertEquals("job-1", c.install(ep, InstallRequest(name = "n", mc = "1.21.8", ramMb = 2048, acceptEula = true)).jobId)
        }
    }

    @Test
    fun `wrong token maps to Api 401 with daemon message`() = runTest {
        DaemonClient(fakeDaemon()).use { c ->
            val e = assertFailsWith<DaemonException.Api> { c.system(ep.copy(token = "bad")) }
            assertEquals(401, e.status)
            assertEquals("토큰이 없거나 틀립니다", e.messageKo)
        }
    }

    @Test
    fun `ErrorDto body maps to Api exception with Korean message`() = runTest {
        DaemonClient(fakeDaemon()).use { c ->
            val e = assertFailsWith<DaemonException.Api> { c.start(ep, "demo") }
            assertEquals(409, e.status)
            assertEquals("이미 실행 중입니다", e.messageKo)
        }
    }

    @Test
    fun `non-JSON error body falls back to raw text`() = runTest {
        DaemonClient(fakeDaemon()).use { c ->
            val e = assertFailsWith<DaemonException.Api> { c.server(ep, "../nope") }
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `connection failure maps to Unreachable`() = runTest {
        val engine = MockEngine { throw java.net.ConnectException("Connection refused") }
        DaemonClient(engine).use { c ->
            assertFailsWith<DaemonException.Unreachable> { c.system(ep) }
        }
    }

    // ── 발견 ─────────────────────────────────────────────

    private fun tempAppData(): Path = Files.createTempDirectory("dcx-ui-test")

    @Test
    fun `discovery reads daemon json and token and verifies it is alive`() = runBlocking {
        val dir = tempAppData()
        Files.writeString(dir.resolve("daemon.json"), """{"port":27565,"pid":4242,"version":"0.1.0"}""")
        Files.writeString(dir.resolve("ui.token"), token + "\n")
        var spawned = 0
        DaemonClient(fakeDaemon()).use { c ->
            val d = DaemonDiscovery(dir, c, spawn = { spawned++ })
            val found = d.connect()
            assertEquals(27565, found.port)
            assertEquals(4242, found.pid)
            assertEquals(token, found.token)
            assertEquals(0, spawned, "살아있는 데몬이 있으면 띄우지 않는다")
        }
    }

    @Test
    fun `discovery spawns when files are missing and picks up the new daemon`() = runBlocking {
        val dir = tempAppData()
        DaemonClient(fakeDaemon()).use { c ->
            var spawned = 0
            val d = DaemonDiscovery(
                dir,
                c,
                spawn = {
                    spawned++
                    // 데몬이 뜨면서 파일을 쓰는 것을 흉내낸다
                    Files.writeString(dir.resolve("ui.token"), token)
                    Files.writeString(dir.resolve("daemon.json"), """{"port":27566,"pid":7,"version":"0.1.0"}""")
                },
                startTimeout = 2_000.milliseconds,
                pollInterval = 10.milliseconds,
            )
            assertNull(d.readEndpoint())
            val found = d.connect()
            assertEquals(1, spawned)
            assertEquals(27566, found.port)
        }
    }

    @Test
    fun `discovery respawns when the recorded daemon rejects the stale token`() = runBlocking {
        val dir = tempAppData()
        Files.writeString(dir.resolve("daemon.json"), """{"port":27565,"pid":1,"version":"0.1.0"}""")
        Files.writeString(dir.resolve("ui.token"), "stale-token")
        DaemonClient(fakeDaemon()).use { c ->
            var spawned = 0
            val d = DaemonDiscovery(
                dir,
                c,
                spawn = {
                    spawned++
                    Files.writeString(dir.resolve("ui.token"), token)
                },
                startTimeout = 2_000.milliseconds,
                pollInterval = 10.milliseconds,
            )
            val found = d.connect()
            assertEquals(1, spawned)
            assertEquals(token, found.token)
        }
    }

    @Test
    fun `discovery gives up with Unreachable after the timeout`() = runBlocking {
        val dir = tempAppData()
        val dead = MockEngine { throw java.net.ConnectException("refused") }
        DaemonClient(dead).use { c ->
            val d = DaemonDiscovery(dir, c, spawn = {}, startTimeout = 100.milliseconds, pollInterval = 10.milliseconds)
            assertFailsWith<DaemonException.Unreachable> { d.connect() }
        }
    }
}
