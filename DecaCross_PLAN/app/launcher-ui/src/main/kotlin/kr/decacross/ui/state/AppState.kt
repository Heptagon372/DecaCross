package kr.decacross.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.decacross.daemon.api.InstallFrame
import kr.decacross.daemon.api.InstallRequest
import kr.decacross.daemon.api.McVersionDto
import kr.decacross.daemon.api.ServerSummary
import kr.decacross.daemon.api.StreamFrame
import kr.decacross.daemon.api.SystemInfoDto
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.process.ServerState
import kr.decacross.daemon.process.ServerStatus
import kr.decacross.ui.console.CommandHistory
import kr.decacross.ui.console.ConsoleBuffer
import kr.decacross.ui.console.ConsoleSnapshot
import kr.decacross.ui.daemon.DaemonClient
import kr.decacross.ui.daemon.DaemonDiscovery
import kr.decacross.ui.daemon.DaemonEndpoint
import kr.decacross.ui.daemon.DaemonException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 데몬 연결 상태 (사이드바 하단 표시). */
sealed interface Connection {
    data object Connecting : Connection

    data class Connected(val endpoint: DaemonEndpoint) : Connection

    data class Failed(val messageKo: String) : Connection
}

/** 새 서버 설치 진행 상태 (다이얼로그 하단). */
data class InstallProgress(
    val jobId: String,
    val stage: String? = null,
    val doneBytes: Long? = null,
    val totalBytes: Long? = null,
    val messages: List<String> = emptyList(),
    val failed: String? = null,
    val completed: InstalledServer? = null,
) {
    val finished: Boolean get() = failed != null || completed != null
}

/**
 * 화면 상태 전부. Compose 스냅샷 상태이고, 갱신은 [scope] (메인 디스패처) 에서만 한다.
 * 로직은 없다 — 데몬 호출 결과를 상태에 옮기고, 스트림을 콘솔 버퍼에 흘려 넣을 뿐이다.
 */
class AppState(
    private val scope: CoroutineScope,
    private val client: DaemonClient,
    private val discovery: DaemonDiscovery,
) {
    var connection: Connection by mutableStateOf(Connection.Connecting)
        private set
    var system: SystemInfoDto? by mutableStateOf(null)
        private set
    var servers: List<ServerSummary> by mutableStateOf(emptyList())
        private set
    var mcVersions: List<McVersionDto> by mutableStateOf(emptyList())
        private set

    var selectedId: String? by mutableStateOf(null)
        private set
    var selectedServer: InstalledServer? by mutableStateOf(null)
        private set
    var status: ServerStatus? by mutableStateOf(null)
        private set
    var console: ConsoleSnapshot by mutableStateOf(ConsoleSnapshot.EMPTY)
        private set
    var streamConnected: Boolean by mutableStateOf(false)
        private set

    /** 진행 중인 서버 작업 설명 (버튼 비활성 + 상단 표시). null 이면 유휴. */
    var pendingAction: String? by mutableStateOf(null)
        private set

    /** 마지막 `shutdown` 프레임의 단계. STOPPING 이 아니면 null. */
    var shutdownPhase: String? by mutableStateOf(null)
        private set

    var install: InstallProgress? by mutableStateOf(null)
        private set

    /** 스낵바용 오류. UI 가 보여준 뒤 [clearError]. */
    var lastError: String? by mutableStateOf(null)
        private set

    val history = CommandHistory()

    private val buffer = ConsoleBuffer()
    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var installJob: Job? = null

    val endpoint: DaemonEndpoint? get() = (connection as? Connection.Connected)?.endpoint

    // ── 연결 ─────────────────────────────────────────────

    /** 시작 시 한 번. 데몬을 찾고(없으면 띄우고) 폴링을 시작한다. 실패해도 재시도 루프가 계속 돈다. */
    fun connect() {
        scope.launch {
            var backoff = 1.seconds
            while (isActive) {
                connection = Connection.Connecting
                try {
                    val ep = discovery.connect()
                    connection = Connection.Connected(ep)
                    backoff = 1.seconds
                    startPolling(ep)
                    return@launch
                } catch (e: DaemonException) {
                    connection = Connection.Failed(e.messageKo)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15.seconds)
                }
            }
        }
    }

    private fun startPolling(ep: DaemonEndpoint) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    servers = client.servers(ep)
                    if (tick % 5 == 0) system = client.system(ep)
                    if (mcVersions.isEmpty()) mcVersions = client.mcVersions(ep)
                } catch (e: DaemonException.Unreachable) {
                    // 데몬이 죽었다 — 발견부터 다시 (자동 재기동 포함)
                    connection = Connection.Failed(e.messageKo)
                    streamJob?.cancel()
                    streamConnected = false
                    connect()
                    return@launch
                } catch (e: DaemonException) {
                    lastError = e.messageKo
                }
                tick++
                delay(2.seconds)
            }
        }
    }

    fun refreshServers() {
        val ep = endpoint ?: return
        scope.launch { runAction(null) { servers = client.servers(ep) } }
    }

    // ── 선택 / 스트림 ─────────────────────────────────────

    fun select(id: String?) {
        if (id == selectedId) return
        selectedId = id
        selectedServer = null
        status = null
        shutdownPhase = null
        history.reset()
        buffer.clear()
        console = buffer.snapshot()
        streamJob?.cancel()
        streamConnected = false
        if (id == null) return
        streamJob = scope.launch { streamLoop(id) }
    }

    /** WS 끊기면 백오프 재연결. 데몬이 최근 로그를 replay 하므로 연결마다 버퍼를 비운다. */
    private suspend fun streamLoop(id: String) {
        var backoff = 500.milliseconds
        while (scope.isActive) {
            val ep = endpoint
            if (ep == null) {
                delay(1.seconds)
                continue
            }
            try {
                selectedServer = client.server(ep, id).server
                buffer.clear()
                console = buffer.snapshot()
                client.streamServer(ep, id).collect { frame ->
                    if (!streamConnected) streamConnected = true
                    backoff = 500.milliseconds
                    onFrame(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 연결 실패·끊김 — 아래에서 재시도
            }
            streamConnected = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(10.seconds)
        }
    }

    private fun onFrame(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.Log -> {
                buffer.append(frame.lines)
                console = buffer.snapshot()
            }

            is StreamFrame.Status -> {
                status = frame.status
                if (frame.status.state != ServerState.STOPPING) shutdownPhase = null
            }

            is StreamFrame.Shutdown -> shutdownPhase = frame.phase

            else -> Unit // 06 단계 프레임(진단 등)은 이 화면에서 아직 쓰지 않는다
        }
    }

    // ── 서버 조작 (전부 데몬 호출) ─────────────────────────

    fun start() = serverAction("시작 중…") { ep, id -> client.start(ep, id) }

    fun stop(force: Boolean) = serverAction(if (force) "강제 종료 중…" else "정지 중… (save-all → stop)") { ep, id ->
        client.stop(ep, id, force)
    }

    fun restart() = serverAction("재시작 중…") { ep, id ->
        client.stop(ep, id, force = false)
        client.start(ep, id)
    }

    fun delete(keepWorld: Boolean) {
        val ep = endpoint ?: return
        val id = selectedId ?: return
        scope.launch {
            runAction("삭제 중…") {
                client.delete(ep, id, keepWorld)
                select(null)
                servers = client.servers(ep)
            }
        }
    }

    fun sendCommand(line: String) {
        val ep = endpoint ?: return
        val id = selectedId ?: return
        if (line.isBlank()) return
        history.push(line)
        scope.launch { runAction(null) { client.sendCommand(ep, id, line) } }
    }

    private fun serverAction(label: String, block: suspend (DaemonEndpoint, String) -> Unit) {
        val ep = endpoint ?: return
        val id = selectedId ?: return
        if (pendingAction != null) return
        scope.launch {
            runAction(label) {
                block(ep, id)
                servers = client.servers(ep)
            }
        }
    }

    private suspend fun runAction(label: String?, block: suspend () -> Unit) {
        if (label != null) pendingAction = label
        try {
            block()
        } catch (e: DaemonException) {
            lastError = e.messageKo
        } finally {
            if (label != null) pendingAction = null
        }
    }

    // ── 설치 ─────────────────────────────────────────────

    /** `POST /api/install` → 진행 스트림. EULA 동의는 호출자(다이얼로그 체크박스)가 보장한다 — 여기서 자동으로 켜지 않는다. */
    fun install(req: InstallRequest) {
        val ep = endpoint ?: return
        installJob?.cancel()
        installJob = scope.launch {
            val job = try {
                client.install(ep, req)
            } catch (e: DaemonException) {
                install = InstallProgress(jobId = "", failed = e.messageKo)
                return@launch
            }
            install = InstallProgress(job.jobId)
            try {
                client.streamInstall(ep, job.jobId).collect { f -> install = install?.apply(f) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (install?.finished != true) install = install?.copy(failed = "진행 스트림 끊김: ${e.message}")
            }
            if (install?.completed != null) runAction(null) { servers = client.servers(ep) }
        }
    }

    fun cancelInstall() {
        val ep = endpoint
        val jobId = install?.jobId
        installJob?.cancel()
        if (ep != null && !jobId.isNullOrEmpty()) scope.launch { runCatching { client.cancelInstall(ep, jobId) } }
        install = null
    }

    fun dismissInstall() {
        install = null
    }

    fun clearError() {
        lastError = null
    }

    private fun InstallProgress.apply(f: InstallFrame): InstallProgress = when (f.type) {
        "stage" -> copy(stage = f.stage, doneBytes = null, totalBytes = null)
        "progress" -> copy(stage = f.stage ?: stage, doneBytes = f.done, totalBytes = f.total, messages = f.textKo?.let { appendMsg(it) } ?: messages)
        "message" -> copy(messages = f.textKo?.let { appendMsg(it) } ?: messages)
        "failed" -> copy(stage = f.stage ?: stage, failed = f.textKo ?: "설치 실패")
        "completed" -> copy(completed = f.server)
        else -> this
    }

    private fun InstallProgress.appendMsg(m: String): List<String> =
        if (messages.lastOrNull() == m) messages else (messages + m).takeLast(50)
}
