package kr.decacross.daemon.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kr.decacross.daemon.Daemon
import kr.decacross.daemon.daemonModule
import kr.decacross.daemon.paths.DecaPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class UiRoutesTest {
    private fun daemon(): Daemon {
        val root = Files.createTempDirectory("daemon")
        return Daemon(DecaPaths(root.resolve("home"), root.resolve("servers")), token = "t0ken")
    }

    @Test
    fun requestsWithoutToken_are401() = testApplication {
        val d = daemon()
        application { daemonModule(d) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/servers").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/servers") { header("Authorization", "Bearer wrong") }.status)
    }

    @Test
    fun withToken_listsServersAndSystem() = testApplication {
        val d = daemon()
        application { daemonModule(d) }
        val r = client.get("/api/servers") { header("Authorization", "Bearer t0ken") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("[]", r.bodyAsText())
        val sys = client.get("/api/system") { header("Authorization", "Bearer t0ken") }
        assertEquals(HttpStatusCode.OK, sys.status)
        assertContains(sys.bodyAsText(), "recommendedRamMb")
        val mc = client.get("/api/mc") { header("Authorization", "Bearer t0ken") }
        assertContains(mc.bodyAsText(), "\"label\":\"1.21.8\"")
    }

    @Test
    fun unknownServer_is404_andStartRejected() = testApplication {
        val d = daemon()
        application { daemonModule(d) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/servers/nope") { header("Authorization", "Bearer t0ken") }.status)
    }

    @Test
    fun bearerMatches_isExact() {
        kotlin.test.assertTrue(bearerMatches("Bearer abc", "abc"))
        kotlin.test.assertFalse(bearerMatches("Bearer ab", "abc"))
        kotlin.test.assertFalse(bearerMatches(null, "abc"))
        kotlin.test.assertFalse(bearerMatches("abc", "abc"), "Bearer 접두 필수")
    }
}
