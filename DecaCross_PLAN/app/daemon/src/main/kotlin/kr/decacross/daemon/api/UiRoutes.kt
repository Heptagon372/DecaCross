package kr.decacross.daemon.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.decacross.compat.resolve.recommendedRamMb
import kr.decacross.daemon.Daemon
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.process.ShutdownResult
import kr.decacross.daemon.process.StartOutcome
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI API (명세 §7.1). 전부 Bearer 토큰 뒤에 있다.
 *
 * # 불변식 (CLAUDE.md 15)
 * 이 핸들러들을 브리지 라우트(08)에서 재사용하지 않는다. 브리지는 별도 트리에서 `/ping`, `/install` 만 노출한다.
 */
fun Route.uiRoutes(d: Daemon) {
    route("/api") {
        // ── 인증 ───────────────────────────────────────────
        install(uiAuthPlugin) { token = d.token }

        get("/system") {
            val total = d.systemMemoryMb()
            call.respond(SystemInfoDto(total, recommendedRamMb(total.toInt(), d.hostOverheadMb()), d.daemonRssMb(), Daemon.VERSION))
        }

        get("/mc") {
            val db = d.db
            call.respond(
                db.allMc().asReversed().map { v ->
                    McVersionDto(v.label, v.ordinal.value, v.javaMin, v.javaRecommended, v.isSnapshot, db.coreBuilds(kr.decacross.compat.model.CoreKey.PAPER, v.ordinal).isNotEmpty())
                },
            )
        }

        // ── 서버 ───────────────────────────────────────────
        get("/servers") {
            call.respond(
                d.registry.list().map { s ->
                    ServerSummary(s.name, s.name, s.mcLabel, s.core, s.build, s.port, s.ramMb, d.manager.status(s.name)?.state ?: kr.decacross.daemon.process.ServerState.STOPPED)
                },
            )
        }
        get("/servers/{id}") {
            val id = call.id()
            val s = d.registry.get(id) ?: return@get call.notFound()
            val st = d.manager.status(id) ?: return@get call.notFound()
            call.respond(ServerDetail(s, st, d.manager.recentLines(id), d.manager.diagnoses(id)))
        }
        post("/servers/{id}/start") {
            when (val r = d.manager.start(call.id())) {
                is StartOutcome.Started -> call.respond(mapOf("pid" to r.pid))
                is StartOutcome.Rejected -> call.respond(HttpStatusCode.Conflict, ErrorDto(r.reasonKo))
            }
        }
        post("/servers/{id}/stop") {
            val req = runCatching { call.receive<StopRequest>() }.getOrDefault(StopRequest())
            when (val r = d.manager.stop(call.id(), force = req.force)) {
                null -> call.respond(HttpStatusCode.Conflict, ErrorDto("실행 중이 아닙니다"))
                is ShutdownResult.Graceful -> call.respond(mapOf("result" to "graceful", "exitCode" to r.exitCode))
                is ShutdownResult.Terminated -> call.respond(mapOf("result" to "terminated", "exitCode" to r.exitCode))
                is ShutdownResult.Killed -> call.respond(mapOf("result" to "killed", "warningKo" to "강제 종료됨 — 월드 손상 가능"))
            }
        }
        post("/servers/{id}/command") {
            val req = call.receive<CommandRequest>()
            if (d.manager.command(call.id(), req.line)) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.Conflict, ErrorDto("실행 중이 아닙니다"))
        }
        delete("/servers/{id}") {
            val id = call.id()
            val keepWorld = call.request.queryParameters["keepWorld"]?.toBoolean() ?: true
            if (d.manager.status(id)?.state?.let { it != kr.decacross.daemon.process.ServerState.STOPPED && it != kr.decacross.daemon.process.ServerState.CRASHED } == true) {
                return@delete call.respond(HttpStatusCode.Conflict, ErrorDto("먼저 정지하세요"))
            }
            if (d.registry.delete(id, keepWorld)) call.respond(HttpStatusCode.NoContent) else call.notFound()
        }

        /** 로그·상태 스트리밍. 로그는 50ms 배칭(불변식 14), 상태는 1초 주기. */
        webSocket("/servers/{id}/stream") {
            val id = call.id()
            if (d.registry.get(id) == null) {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "서버 없음"))
                return@webSocket
            }
            // 최근 로그 replay
            d.manager.recentLines(id).chunked(200).forEach { sendSerialized<StreamFrame>(StreamFrame.Log(it)) }
            val statusJob = launch {
                while (true) {
                    d.manager.status(id)?.let { sendSerialized<StreamFrame>(StreamFrame.Status(it)) }
                    delay(1000)
                }
            }
            val logJob = launch {
                while (true) {
                    val lines = d.manager.lines(id)
                    if (lines == null) {
                        delay(500)
                        continue
                    }
                    lines.chunkedTimeout(50.milliseconds, 200).collect { batch -> sendSerialized<StreamFrame>(StreamFrame.Log(batch)) }
                }
            }
            val diagJob = launch {
                while (true) {
                    val flow = d.manager.diagnosisFlow(id)
                    if (flow == null) {
                        delay(500)
                        continue
                    }
                    flow.collect { sendSerialized<StreamFrame>(StreamFrame.DiagnosisFrame(it)) }
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val line = frame.readText()
                        if (line.isNotBlank()) d.manager.command(id, line)
                    }
                }
            } finally {
                statusJob.cancel()
                logJob.cancel()
                diagJob.cancel()
            }
        }

        // ── 설치 ───────────────────────────────────────────
        post("/install") {
            val req = call.receive<InstallRequest>()
            val job = d.submitInstall(req) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("알 수 없는 MC 버전 또는 빌드 없음: ${req.mc}"))
            call.respond(HttpStatusCode.Accepted, JobRef(job.id))
        }
        delete("/install/{jobId}") {
            if (d.installJobs.cancel(call.parameters["jobId"].orEmpty())) call.respond(HttpStatusCode.NoContent) else call.notFound()
        }
        webSocket("/install/{jobId}/stream") {
            val job = d.installJobs.get(call.parameters["jobId"].orEmpty()) ?: run {
                close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.CANNOT_ACCEPT, "작업 없음"))
                return@webSocket
            }
            job.events.collect { ev ->
                val frame = when (ev) {
                    is InstallEvent.StageChanged -> InstallFrame("stage", stage = ev.stage.name)
                    is InstallEvent.Progress -> InstallFrame("progress", stage = ev.stage.name, done = ev.done, total = ev.total, textKo = ev.detailKo)
                    is InstallEvent.Message -> InstallFrame("message", textKo = ev.textKo)
                    is InstallEvent.Failed -> InstallFrame("failed", stage = ev.stage.name, textKo = ev.error.messageKo)
                    is InstallEvent.Completed -> InstallFrame("completed", server = ev.server)
                }
                sendSerialized(frame)
                if (ev is InstallEvent.Failed || ev is InstallEvent.Completed) {
                    close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.NORMAL, "done"))
                    return@collect
                }
            }
        }

        // ── 런타임 / CAS ───────────────────────────────────
        get("/runtimes") {
            call.respond(d.runtimeInstaller.installed(kr.decacross.daemon.runtime.RuntimeInstaller.currentOs()).map { RuntimeDto(it.feature, it.path.toString(), it.versionString) })
        }
        post("/runtimes/{feature}") {
            val feature = call.parameters["feature"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("feature 가 숫자가 아닙니다"))
            when (val r = d.runtimeInstaller.ensureRuntime(feature, kr.decacross.daemon.runtime.RuntimeInstaller.currentOs(), kr.decacross.daemon.runtime.RuntimeInstaller.currentArch())) {
                is kr.decacross.daemon.runtime.EnsureResult.Ok -> call.respond(mapOf("path" to r.info.exe.toString()))
                is kr.decacross.daemon.runtime.EnsureResult.Failed -> call.respond(HttpStatusCode.BadGateway, ErrorDto(r.messageKo))
            }
        }
        get("/cas/stats") {
            val s = d.cas.stats()
            call.respond(CasStatsDto(s.blobCount, s.totalBytes))
        }
        post("/cas/gc") {
            call.respond(mapOf("freedBytes" to d.casGc()))
        }

        // ── 진단 (06) ──────────────────────────────────────
        get("/servers/{id}/diagnosis") {
            val id = call.id()
            if (d.registry.get(id) == null) return@get call.notFound()
            call.respond(d.manager.diagnoses(id))
        }
        post("/servers/{id}/fix") {
            val req = call.receive<FixRequest>()
            when (val r = d.applyFix(call.id(), req.action)) {
                is kr.decacross.daemon.diagnosis.FixOutcome.Applied -> call.respond(FixResult(r.messageKo, r.restartRequired))
                is kr.decacross.daemon.diagnosis.FixOutcome.Rejected -> call.respond(HttpStatusCode.Conflict, ErrorDto(r.reasonKo))
                is kr.decacross.daemon.diagnosis.FixOutcome.Unsupported -> call.respond(HttpStatusCode.NotImplemented, ErrorDto(r.reasonKo))
            }
        }
        post("/servers/{id}/bisect") { call.respond(HttpStatusCode.NotImplemented, ErrorDto("Phase 2 (F-46)")) }
    }
}

class UiAuthConfig {
    var token: String = ""
}

/** `/api` 트리 전용 Bearer 검사. 응답하면 이후 핸들러는 실행되지 않는다. */
val uiAuthPlugin = io.ktor.server.application.createRouteScopedPlugin("UiAuth", ::UiAuthConfig) {
    val token = pluginConfig.token
    onCall { call ->
        val header = call.request.headers["Authorization"]
            ?: call.request.queryParameters["token"]?.let { "Bearer $it" } // WS 는 헤더를 못 붙이는 클라이언트가 있다
        if (!bearerMatches(header, token)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("토큰이 없거나 틀립니다"))
        }
    }
}

private fun ApplicationCall.id(): String = parameters["id"].orEmpty()

private suspend fun ApplicationCall.notFound() = respond(HttpStatusCode.NotFound, ErrorDto("서버 없음"))
