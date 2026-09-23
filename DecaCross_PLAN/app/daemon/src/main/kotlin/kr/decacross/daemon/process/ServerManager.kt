package kr.decacross.daemon.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.decacross.daemon.install.Config
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.store.ServerRegistry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class ServerState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED, CRASH_LOOP }

/** 1초 주기로 UI 에 보내는 상태 (명세 §7.1). tps/ram 은 06(JMX/로그)에서 채운다. */
@Serializable
data class ServerStatus(
    val id: String,
    val state: ServerState,
    val pid: Long? = null,
    val uptimeSec: Long? = null,
    val players: List<String> = emptyList(),
    val maxPlayers: Int? = null,
    val tps: Double? = null,
    val ramUsedMb: Long? = null,
    val ramMaxMb: Long? = null,
    val port: Int,
    val crashesRecent: Int = 0,
)

sealed interface StartOutcome {
    data class Started(val pid: Long) : StartOutcome

    data class Rejected(val reasonKo: String) : StartOutcome
}

/**
 * 데몬이 감독하는 서버 프로세스들. UI 가 닫혀도 여기 있는 프로세스는 산다.
 * 로그는 [Managed.recent] 링 버퍼(최근 5,000줄)에 쌓고 [Managed.process.lines] 로 실시간 스트리밍한다.
 */
class ServerManager(
    private val scope: CoroutineScope,
    private val registry: ServerRegistry,
) {
    private val log = LoggerFactory.getLogger(ServerManager::class.java)

    inner class Managed(val server: InstalledServer, val process: ServerProcess) {
        val state = MutableStateFlow(ServerState.STARTING)
        val players = LinkedHashSet<String>()
        val recent = ArrayDeque<String>(RECENT_LINES)
        val crashLoop = CrashLoop()

        @Volatile
        var stopping = false
        var reader: Job? = null

        @Synchronized
        fun push(line: String) {
            if (recent.size >= RECENT_LINES) recent.removeFirst()
            recent.addLast(line)
        }

        @Synchronized
        fun snapshot(): List<String> = recent.toList()
    }

    private val managed = ConcurrentHashMap<String, Managed>()
    private val crashLoops = ConcurrentHashMap<String, CrashLoop>()

    fun ids(): Set<String> = managed.keys

    fun get(id: String): Managed? = managed[id]

    fun stateOf(id: String): StateFlow<ServerState>? = managed[id]?.state

    fun lines(id: String): SharedFlow<String>? = managed[id]?.process?.lines

    fun recentLines(id: String): List<String> = managed[id]?.snapshot().orEmpty()

    fun status(id: String): ServerStatus? {
        val server = registry.get(id) ?: return null
        val m = managed[id]
        val state = m?.state?.value ?: ServerState.STOPPED
        return ServerStatus(
            id = id,
            state = state,
            pid = m?.process?.pid?.takeIf { m.process.isAlive },
            uptimeSec = m?.takeIf { it.process.isAlive }?.let { Duration.between(it.process.startedAt, Instant.now()).seconds },
            players = m?.players?.toList().orEmpty(),
            port = server.port,
            crashesRecent = crashLoops[id]?.recentCount() ?: 0,
        )
    }

    fun start(id: String): StartOutcome {
        val server = registry.get(id) ?: return StartOutcome.Rejected("서버 없음: $id")
        managed[id]?.let { if (it.process.isAlive) return StartOutcome.Rejected("이미 실행 중 (pid ${it.process.pid})") }
        val javaExe = Path.of(server.javaExe)
        if (!Files.isRegularFile(javaExe)) return StartOutcome.Rejected("Java 를 찾을 수 없습니다: $javaExe")
        val dir = Path.of(server.dir)
        if (!Files.isRegularFile(dir.resolve(server.coreJar))) return StartOutcome.Rejected("코어 jar 없음: ${server.coreJar}")
        if (!Files.isRegularFile(dir.resolve("eula.txt")) || !Files.readString(dir.resolve("eula.txt")).contains("eula=true")) {
            return StartOutcome.Rejected("EULA 미동의 — eula.txt 를 확인하세요")
        }
        val loop = crashLoops.getOrPut(id) { CrashLoop() }
        val process = try {
            ServerProcess.start(scope, dir, javaExe, Config.jvmArgs(server.ramMb), server.coreJar)
        } catch (e: Exception) {
            return StartOutcome.Rejected("프로세스 기동 실패: ${e.message}")
        }
        val m = Managed(server, process)
        managed[id] = m
        m.reader = scope.launch {
            process.lines.collect { line ->
                m.push(line)
                track(m, line)
            }
        }
        scope.launch {
            val code = process.awaitExit()
            m.reader?.cancel()
            val wasStopping = m.stopping
            m.players.clear()
            if (wasStopping || code == 0) {
                m.state.value = ServerState.STOPPED
            } else {
                val tripped = loop.record()
                m.state.value = if (tripped) ServerState.CRASH_LOOP else ServerState.CRASHED
                log.warn("서버 {} 비정상 종료 (exit={}) — 최근 크래시 {}회{}", id, code, loop.recentCount(), if (tripped) " ★ 크래시 루프, 자동 재시작 중단" else "")
            }
        }
        log.info("서버 {} 기동 (pid {})", id, process.pid)
        return StartOutcome.Started(process.pid)
    }

    suspend fun stop(id: String, force: Boolean = false, onPhase: suspend (ShutdownPhase) -> Unit = {}): ShutdownResult? {
        val m = managed[id] ?: return null
        if (!m.process.isAlive) return ShutdownResult.Graceful(m.process.exitCode ?: 0)
        m.stopping = true
        m.state.value = ServerState.STOPPING
        return m.process.shutdown(force = force, onPhase = onPhase)
    }

    fun command(id: String, line: String): Boolean {
        val m = managed[id] ?: return false
        if (!m.process.isAlive) return false
        m.process.send(line)
        return true
    }

    /** 데몬 종료 시 전부 정상 종료. */
    suspend fun stopAll() {
        for (id in managed.keys.toList()) runCatching { stop(id) }
    }

    /** 원문 로그에서 상태 추적 (Done / 접속 / 퇴장). 이벤트 파싱 본체는 logparse(06). */
    private fun track(m: Managed, line: String) {
        if (m.state.value == ServerState.STARTING && line.contains("Done (") && line.contains("For help")) {
            m.state.value = ServerState.RUNNING
            crashLoops[m.server.name]?.reset()
        }
        JOIN.find(line)?.let { m.players += it.groupValues[1] }
        LEAVE.find(line)?.let { m.players -= it.groupValues[1] }
    }

    companion object {
        const val RECENT_LINES = 5_000
        private val JOIN = Regex("""]: (\S+) joined the game$""")
        private val LEAVE = Regex("""]: (\S+) (?:left the game|lost connection)""")
    }
}
