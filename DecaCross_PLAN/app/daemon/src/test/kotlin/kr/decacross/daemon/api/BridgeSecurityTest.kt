package kr.decacross.daemon.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kr.decacross.daemon.Daemon
import kr.decacross.daemon.daemonModule
import kr.decacross.daemon.paths.DecaPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 명세 §7.2 보안 체크리스트 — 전부 테스트로 강제. */
class BridgeSecurityTest {
    private val port = 27565

    private fun daemon(): Daemon {
        val root = Files.createTempDirectory("bridge")
        return Daemon(DecaPaths(root.resolve("home"), root.resolve("servers")), token = "t0ken")
    }

    private val goodRecipe = """{"dcx":"1.1","id":"demo-pack","name":"데모","target":{"minecraft":"1.21.8","core":{"type":"paper"}},"content":[]}"""

    @Test
    fun bridgeSecurity_originNotWhitelisted_is403() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        val r = client.get("/ping") {
            header("Origin", "https://evil.example")
            header("Host", "127.0.0.1:$port")
            header("X-Decacross-Bridge", "1")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        val none = client.get("/ping") {
            header("Host", "127.0.0.1:$port")
            header("X-Decacross-Bridge", "1")
        }
        assertEquals(HttpStatusCode.Forbidden, none.status, "Origin 없음도 403")
    }

    @Test
    fun bridgeSecurity_hostMismatch_is403_dnsRebinding() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        for (host in listOf("localhost:$port", "127.0.0.1", "127.0.0.1:27566", "evil.example:$port")) {
            val r = client.get("/ping") {
                header("Origin", "https://decacross.kr")
                header("Host", host)
                header("X-Decacross-Bridge", "1")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status, "Host=$host")
        }
    }

    @Test
    fun bridgeSecurity_missingBridgeHeader_is403() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        val r = client.get("/ping") {
            header("Origin", "https://decacross.kr")
            header("Host", "127.0.0.1:$port")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun bridgeSecurity_pingExposesOnlyAppAndVersion() = testApplication {
        val d = daemon()
        Files.createDirectories(d.paths.serversRoot.resolve("secret-server/.decacross"))
        application { daemonModule(d, port) }
        val r = client.get("/ping") {
            header("Origin", "https://decacross.kr")
            header("Host", "127.0.0.1:$port")
            header("X-Decacross-Bridge", "1")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertEquals("""{"app":"decacross","version":"${Daemon.VERSION}"}""", body)
        assertFalse(body.contains("secret") || body.contains("servers") || body.contains(System.getProperty("user.name")))
        assertEquals("https://decacross.kr", r.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun bridgeSecurity_installWritesNothingBeforeApproval() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        val r = client.post("/install") {
            header("Origin", "http://localhost:3000")
            header("Host", "127.0.0.1:$port")
            header("X-Decacross-Bridge", "1")
            header("Content-Type", "application/json")
            setBody(goodRecipe)
        }
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("pendingId"))
        assertFalse(Files.exists(d.paths.serversRoot), "승인 전에는 서버 폴더도 스테이징도 만들지 않는다")
        assertFalse(Files.exists(d.paths.cacheTmp))
        assertEquals(1, d.pendingInstalls.list().size)
        // 승인 목록은 UI 토큰 뒤에만 보인다
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/pending").status)
        val pending = client.get("/api/pending") { header("Authorization", "Bearer t0ken") }
        assertTrue(pending.bodyAsText().contains("demo-pack"))
    }

    @Test
    fun bridgeSecurity_uiHandlersAreNotReachableWithoutToken_viaBridgeTree() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        // 브리지 헤더를 다 갖춰도 UI 라우트는 열리지 않는다 (별도 트리)
        val r = client.get("/api/servers") {
            header("Origin", "https://decacross.kr")
            header("Host", "127.0.0.1:$port")
            header("X-Decacross-Bridge", "1")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun bridgeSecurity_preflightAllowsOnlyWhitelistedOrigin() = testApplication {
        val d = daemon()
        application { daemonModule(d, port) }
        val ok = client.options("/install") {
            header("Origin", "https://decacross.kr")
            header("Host", "127.0.0.1:$port")
        }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertTrue((ok.headers["Access-Control-Allow-Headers"] ?: "").contains("X-Decacross-Bridge"))
        val bad = client.options("/install") {
            header("Origin", "https://evil.example")
            header("Host", "127.0.0.1:$port")
        }
        assertEquals(HttpStatusCode.Forbidden, bad.status)
    }
}
