package kr.decacross.daemon.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kr.decacross.daemon.Daemon
import kr.decacross.dcx.DcxParseResult
import kr.decacross.dcx.parseDcx

/** 웹 브리지 (명세 §7.2). 토큰 없음. 정보 노출 금지. */
@Serializable
data class PingDto(val app: String, val version: String)

@Serializable
data class BridgeInstallResponse(val pendingId: String, val messageKo: String)

/** 개발 시 localhost:3000 허용. 배포는 decacross.kr 만. */
val BRIDGE_ORIGINS: Set<String> = setOf("https://decacross.kr", "https://www.decacross.kr", "http://localhost:3000", "http://127.0.0.1:3000")

class BridgeGuardConfig {
    var port: Int = 0
    var origins: Set<String> = BRIDGE_ORIGINS
}

/**
 * 브리지 보안 (설계서 §6.2, 명세 §7.2 체크리스트). 셋 다 통과해야 핸들러가 돈다.
 * - `Origin` 화이트리스트 아니면 403
 * - `Host` 가 `127.0.0.1:{port}` 정확히 아니면 403 (DNS rebinding)
 * - `X-Decacross-Bridge: 1` 없으면 403 (단순요청 차단 → preflight 강제). OPTIONS(preflight)는 Origin 만 검사.
 */
val bridgeGuard = createRouteScopedPlugin("BridgeGuard", ::BridgeGuardConfig) {
    val cfg = pluginConfig
    onCall { call ->
        val origin = call.request.headers["Origin"]
        val host = call.request.headers["Host"]
        val isPreflight = call.request.local.method.value == "OPTIONS"
        when {
            origin == null || origin !in cfg.origins -> call.respond(HttpStatusCode.Forbidden, ErrorDto("허용되지 않은 출처"))

            host != "127.0.0.1:${cfg.port}" -> call.respond(HttpStatusCode.Forbidden, ErrorDto("잘못된 Host"))

            !isPreflight && call.request.headers["X-Decacross-Bridge"] != "1" -> call.respond(HttpStatusCode.Forbidden, ErrorDto("브리지 헤더 없음"))

            else -> {
                call.response.headers.append("Access-Control-Allow-Origin", origin)
                call.response.headers.append("Vary", "Origin")
                call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, X-Decacross-Bridge")
                call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            }
        }
    }
}

/**
 * ★ UI 라우트 핸들러를 재사용하지 않는다 (CLAUDE.md 15). 이 트리는 `/ping` 과 `/install` 뿐이며,
 * `/install` 은 파일을 쓰지 않고 "대기 목록"에 넣기만 한다 — 실제 설치는 UI 의 네이티브 확인 다이얼로그가 승인한 뒤.
 */
fun Route.bridgeRoutes(d: Daemon, port: Int) {
    route("/ping") {
        install(bridgeGuard) { this.port = port }
        options { call.respond(HttpStatusCode.NoContent) }

        /** ★ 이것만. 서버 목록·경로·사용자명은 절대 주지 않는다. */
        get { call.respond(PingDto("decacross", Daemon.VERSION)) }
    }
    route("/install") {
        install(bridgeGuard) { this.port = port }
        options { call.respond(HttpStatusCode.NoContent) }

        post {
            val body = call.receiveText()
            when (val parsed = parseDcx(body)) {
                is DcxParseResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorDto("레시피 오류: ${parsed.errorsKo.joinToString("; ")}"))

                is DcxParseResult.Ok -> {
                    val pending = d.pendingInstalls.add(parsed.recipe, call.request.headers["Origin"].orEmpty())
                    call.respond(HttpStatusCode.Accepted, BridgeInstallResponse(pending.id, "런처에서 설치 확인을 기다리는 중"))
                }
            }
        }
    }
}

internal fun ApplicationCall.originOrEmpty(): String = request.headers["Origin"].orEmpty()
