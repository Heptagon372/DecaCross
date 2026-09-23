package kr.decacross.ui.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kr.decacross.daemon.DaemonInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 데몬 찾기·자동 기동.
 *
 * 1. `<appData>/daemon.json` (`{port, pid, version}`) + `<appData>/ui.token` 을 읽는다.
 * 2. `GET /api/system` 으로 살아있는지 확인한다 (죽은 데몬이 남긴 파일일 수 있다).
 * 3. 없거나 응답이 없으면 [spawn] 으로 별도 프로세스를 띄우고, [startTimeout] 동안 새 `daemon.json` 을 폴링한다.
 *
 * 파일 I/O 는 전부 [Dispatchers.IO] 에서 한다 — UI 스레드에서 불러도 멈추지 않는다.
 */
class DaemonDiscovery(
    private val appData: Path,
    private val client: DaemonClient,
    private val spawn: suspend () -> Unit,
    private val startTimeout: Duration = 15.seconds,
    private val pollInterval: Duration = 0.4.seconds,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 파일만 읽는다. 둘 다 있어야 엔드포인트가 된다. 살아있는지는 확인하지 않는다. */
    suspend fun readEndpoint(): DaemonEndpoint? = withContext(Dispatchers.IO) {
        val infoFile = appData.resolve(DAEMON_JSON)
        val tokenFile = appData.resolve(UI_TOKEN)
        if (!Files.isRegularFile(infoFile) || !Files.isRegularFile(tokenFile)) return@withContext null
        val info = runCatching { json.decodeFromString(DaemonInfo.serializer(), Files.readString(infoFile)) }.getOrNull()
            ?: return@withContext null
        val token = Files.readString(tokenFile).trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        DaemonEndpoint(info.port, info.pid, token)
    }

    /** 살아있는 데몬 엔드포인트. 없으면 자동 기동. 실패하면 [DaemonException.Unreachable]. */
    suspend fun connect(): DaemonEndpoint {
        readEndpoint()?.let { ep -> if (alive(ep)) return ep }
        spawn()
        val started = TimeSource.Monotonic.markNow()
        var lastSeen: DaemonEndpoint? = null
        while (started.elapsedNow() < startTimeout) {
            delay(pollInterval)
            val ep = readEndpoint() ?: continue
            // 죽은 데몬의 옛 파일을 새 데몬으로 착각하지 않도록 매번 실제로 찔러본다
            if (alive(ep)) return ep
            lastSeen = ep
        }
        throw DaemonException.Unreachable(
            if (lastSeen == null) {
                "데몬이 ${startTimeout.inWholeSeconds}초 안에 뜨지 않았습니다 (logs/daemon.log 확인)"
            } else {
                "데몬 파일은 있지만(포트 ${lastSeen.port}) 응답이 없습니다"
            },
        )
    }

    /** `GET /api/system` 이 200 이면 살아있다고 본다. 401(토큰 불일치)도 실패로 보고 재기동 대상으로 취급한다. */
    suspend fun alive(ep: DaemonEndpoint): Boolean =
        try {
            client.system(ep)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val DAEMON_JSON = "daemon.json"
        const val UI_TOKEN = "ui.token"
    }
}
