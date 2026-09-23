package kr.decacross.daemon

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kr.decacross.compat.ResolveRequest
import kr.decacross.compat.db.InMemoryCompatDb
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.resolve
import kr.decacross.daemon.api.ErrorDto
import kr.decacross.daemon.api.McVersionDto
import kr.decacross.daemon.store.DevCompatFixture

/**
 * 웹(Next.js) 이 호출하는 서버측 엔진 (설계서 §4 웹 전략 A, 명세 §8 프롬프트 3).
 * 데몬과 같은 엔진 코드를 쓰므로 "웹에선 초록인데 런처에선 빨강"이 생길 수 없다 (D2).
 *
 * 읽기 전용·토큰 없음. 서버 목록·경로 같은 사용자 PC 정보는 여기 없다 — 호환성 DB 만 본다.
 * 포트: `ENGINE_PORT` (기본 27600). 배포 시엔 리버스 프록시 뒤에 둔다.
 */
@Serializable
data class ContentDto(val slug: String, val name: String, val kind: String, val source: String, val license: String?, val redistributable: Boolean, val versions: List<String>)

fun Application.engineModule(db: InMemoryCompatDb, allowedOrigins: List<String>) {
    install(ContentNegotiation) { json(daemonJson) }
    install(CORS) {
        allowedOrigins.forEach { o ->
            val (scheme, hostPort) = o.split("://", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            allowHost(hostPort, schemes = listOf(scheme))
        }
        allowHeader("Content-Type")
        allowNonSimpleContentTypes = true
    }
    routing {
        route("/engine") {
            get("/health") { call.respond(mapOf("ok" to true, "version" to Daemon.VERSION)) }
            get("/mc") {
                call.respond(
                    db.allMc().asReversed().map { v ->
                        McVersionDto(v.label, v.ordinal.value, v.javaMin, v.javaRecommended, v.isSnapshot, db.coreBuilds(CoreKey.PAPER, v.ordinal).isNotEmpty())
                    },
                )
            }
            get("/content") {
                val q = call.request.queryParameters["q"].orEmpty().trim().lowercase()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                val items = db.allContent()
                    .filter { q.isEmpty() || it.slug.contains(q) || it.name.lowercase().contains(q) }
                    .take(limit)
                    .map { c -> ContentDto(c.slug, c.name, c.kind.name, c.source.name, c.license, c.redistributable, db.contentVersions(c.slug).map { it.version }) }
                call.respond(items)
            }
            get("/content/{slug}") {
                val c = db.content(call.parameters["slug"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("없음"))
                call.respond(ContentDto(c.slug, c.name, c.kind.name, c.source.name, c.license, c.redistributable, db.contentVersions(c.slug).map { it.version }))
            }
            post("/resolve") {
                val req = call.receive<ResolveRequest>()
                if (req.wants.size > 100) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("콘텐츠는 100개까지"))
                call.respond(resolve(req, db))
            }
        }
    }
}

fun main() {
    val port = System.getenv("ENGINE_PORT")?.toIntOrNull() ?: 27600
    val origins = (System.getenv("ENGINE_ORIGINS") ?: "http://localhost:3000,https://decacross.kr").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val db = DevCompatFixture.db()
    embeddedServer(CIO, host = System.getenv("ENGINE_HOST") ?: "127.0.0.1", port = port) { engineModule(db, origins) }.start(wait = true)
}
