package kr.decacross.ui.daemon

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kr.decacross.daemon.api.CasStatsDto
import kr.decacross.daemon.api.CommandRequest
import kr.decacross.daemon.api.ErrorDto
import kr.decacross.daemon.api.InstallFrame
import kr.decacross.daemon.api.InstallRequest
import kr.decacross.daemon.api.JobRef
import kr.decacross.daemon.api.McVersionDto
import kr.decacross.daemon.api.RuntimeDto
import kr.decacross.daemon.api.ServerDetail
import kr.decacross.daemon.api.ServerSummary
import kr.decacross.daemon.api.StopRequest
import kr.decacross.daemon.api.StreamFrame
import kr.decacross.daemon.api.SystemInfoDto
import java.io.IOException

/**
 * 데몬 UI API 클라이언트 (명세 §7.1). 모든 라우트를 suspend 함수 하나씩으로 감싼다.
 * JSON 설정은 데몬의 `daemonJson` 과 동일해야 한다 (`type` 판별자, null 생략).
 *
 * 비즈니스 로직은 없다 — 요청을 보내고 응답을 타입으로 바꿀 뿐이다.
 */
class DaemonClient(
    engine: HttpClientEngine = CIO.create(),
) : AutoCloseable {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        expectSuccess = false
    }

    // ── 시스템 ─────────────────────────────────────────────

    suspend fun system(ep: DaemonEndpoint): SystemInfoDto = get(ep, "/api/system")

    suspend fun mcVersions(ep: DaemonEndpoint): List<McVersionDto> = get(ep, "/api/mc")

    // ── 서버 ───────────────────────────────────────────────

    suspend fun servers(ep: DaemonEndpoint): List<ServerSummary> = get(ep, "/api/servers")

    suspend fun server(ep: DaemonEndpoint, id: String): ServerDetail = get(ep, "/api/servers/$id")

    suspend fun start(ep: DaemonEndpoint, id: String) {
        post<Unit>(ep, "/api/servers/$id/start", body = null).ensureOk()
    }

    /** 정상 종료는 save-all→stop→…(불변식 13) 이 끝날 때까지 응답이 안 온다. 반드시 별도 코루틴에서 부를 것. */
    suspend fun stop(ep: DaemonEndpoint, id: String, force: Boolean = false) {
        post(ep, "/api/servers/$id/stop", StopRequest(force)).ensureOk()
    }

    suspend fun sendCommand(ep: DaemonEndpoint, id: String, line: String) {
        post(ep, "/api/servers/$id/command", CommandRequest(line)).ensureOk()
    }

    suspend fun delete(ep: DaemonEndpoint, id: String, keepWorld: Boolean) {
        call {
            http.delete("${ep.httpBase}/api/servers/$id") {
                bearerAuth(ep.token)
                parameter("keepWorld", keepWorld)
            }
        }.ensureOk()
    }

    /**
     * 로그·상태 스트림. 연결이 끊기면 Flow 가 완료(또는 예외)된다 — 재연결은 호출자 몫.
     * 데몬은 접속 직후 최근 로그를 replay 한다.
     */
    fun streamServer(ep: DaemonEndpoint, id: String): Flow<StreamFrame> = flow {
        wsCall {
            http.webSocket("${ep.wsBase}/api/servers/$id/stream?token=${ep.token}") {
                for (frame in incoming) {
                    if (frame is Frame.Text) emit(json.decodeFromString(StreamFrame.serializer(), frame.readText()))
                }
            }
        }
    }

    // ── 설치 ───────────────────────────────────────────────

    suspend fun install(ep: DaemonEndpoint, req: InstallRequest): JobRef = post(ep, "/api/install", req).bodyOrThrow()

    fun streamInstall(ep: DaemonEndpoint, jobId: String): Flow<InstallFrame> = flow {
        wsCall {
            http.webSocket("${ep.wsBase}/api/install/$jobId/stream?token=${ep.token}") {
                for (frame in incoming) {
                    if (frame is Frame.Text) emit(json.decodeFromString(InstallFrame.serializer(), frame.readText()))
                }
            }
        }
    }

    suspend fun cancelInstall(ep: DaemonEndpoint, jobId: String) {
        call { http.delete("${ep.httpBase}/api/install/$jobId") { bearerAuth(ep.token) } }.ensureOk()
    }

    // ── 런타임 / CAS ───────────────────────────────────────

    suspend fun runtimes(ep: DaemonEndpoint): List<RuntimeDto> = get(ep, "/api/runtimes")

    suspend fun ensureRuntime(ep: DaemonEndpoint, feature: Int) {
        post<Unit>(ep, "/api/runtimes/$feature", body = null).ensureOk()
    }

    suspend fun casStats(ep: DaemonEndpoint): CasStatsDto = get(ep, "/api/cas/stats")

    suspend fun casGc(ep: DaemonEndpoint) {
        post<Unit>(ep, "/api/cas/gc", body = null).ensureOk()
    }

    override fun close() = http.close()

    // ── 내부 ───────────────────────────────────────────────

    private suspend inline fun <reified T> get(ep: DaemonEndpoint, path: String): T =
        call { http.get(ep.httpBase + path) { bearerAuth(ep.token) } }.bodyOrThrow()

    private suspend inline fun <reified B : Any> post(ep: DaemonEndpoint, path: String, body: B?): HttpResponse =
        call {
            http.post(ep.httpBase + path) {
                bearerAuth(ep.token)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }

    /** 네트워크 계층 예외 → [DaemonException.Unreachable]. 취소는 그대로 전파. */
    private inline fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DaemonException.Unreachable("데몬에 연결할 수 없습니다: ${e.message ?: e.javaClass.simpleName}", e)
        }

    private inline fun wsCall(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            throw DaemonException.Unreachable("스트림 연결 끊김: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** 2xx 가 아니면 `ErrorDto` 를 읽어 [DaemonException.Api] 로 던진다. */
    private suspend fun HttpResponse.ensureOk(): HttpResponse {
        if (status.value in 200..299) return this
        val text = bodyAsText()
        val ko = runCatching { json.decodeFromString(ErrorDto.serializer(), text).errorKo }.getOrNull()
            ?: text.ifBlank { "HTTP ${status.value}" }
        throw DaemonException.Api(status.value, ko)
    }

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T = ensureOk().body()
}
