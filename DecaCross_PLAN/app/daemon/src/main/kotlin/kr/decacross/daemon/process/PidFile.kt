package kr.decacross.daemon.process

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.PID_FILE_NAME
import kr.decacross.daemon.install.writeFileAtomically
import kr.decacross.daemon.paths.currentOs
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `.decacross/server.pid` (JSON). pid 재사용에 속지 않게 시작 시각·명령을 같이 적는다
 * (Windows `ProcessHandle.info()` 는 arguments 가 비어 있고 command·startInstant 만 준다).
 *
 * @property launcherPid 서버를 띄운 CLI·데몬 pid. 서버는 살아 있는데 이 프로세스가 없으면 "콘솔 없이 실행 중"(고아)이다.
 */
@Serializable
data class PidRecord(val pid: Long, val startedAtEpochMs: Long?, val command: String?, val launcherPid: Long? = null)

/** 기동 직후 기록 ([kr.decacross.daemon.install.writeFileAtomically]). */
fun writePidFile(serverDir: Path, handle: ProcessHandle, launcherPid: Long = ProcessHandle.current().pid()): LayoutIoResult {
    val info = handle.info()
    val record = PidRecord(
        pid = handle.pid(),
        startedAtEpochMs = info.startInstant().orElse(null)?.toEpochMilli(),
        command = info.command().orElse(null),
        launcherPid = launcherPid,
    )
    val bytes = pidJson.encodeToString(PidRecord.serializer(), record).toByteArray(StandardCharsets.UTF_8)
    return writeFileAtomically(pidFilePath(serverDir), bytes)
}

/** 없거나 손상됐으면 null. */
fun readPidFile(serverDir: Path): PidRecord? {
    val file = pidFilePath(serverDir)
    val text = try {
        if (!Files.isRegularFile(file)) return null
        Files.readString(file, StandardCharsets.UTF_8)
    } catch (e: IOException) {
        return null
    } catch (e: SecurityException) {
        return null
    }
    return try {
        pidJson.decodeFromString(PidRecord.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** 기록된 프로세스가 살아 있고 시작 시각(±1초)·명령이 일치하면 그 핸들. 아니면 null (낡은 파일은 지우지 않는다). */
fun liveServerProcess(serverDir: Path): ProcessHandle? {
    val record = readPidFile(serverDir) ?: return null
    val handle = ProcessHandle.of(record.pid).orElse(null) ?: return null
    if (!handle.isAlive) return null
    val info = handle.info()
    val startedAt = info.startInstant().orElse(null)?.toEpochMilli()
    val recordedStart = record.startedAtEpochMs
    if (startedAt != null && recordedStart != null && abs(startedAt - recordedStart) > PID_START_TOLERANCE_MS) return null
    val command = info.command().orElse(null)
    val recordedCommand = record.command
    if (command != null && recordedCommand != null && !commandsEqual(command, recordedCommand)) return null
    return handle
}

/** 종료 후 삭제. 실패는 무시한다 (다음 [liveServerProcess] 가 낡은 기록으로 판정). */
fun deletePidFile(serverDir: Path) {
    try {
        Files.deleteIfExists(pidFilePath(serverDir))
    } catch (e: IOException) {
        // 지우지 못해도 다음 판정이 낡은 기록을 걸러낸다
    } catch (e: SecurityException) {
        // 같음
    }
}

/** `<서버>/.decacross/server.pid`. */
private fun pidFilePath(serverDir: Path): Path = serverDir.resolve(META_DIR_NAME).resolve(PID_FILE_NAME)

/** pid 재사용 판정 허용 오차 (ms). */
private const val PID_START_TOLERANCE_MS = 1000L

/** Windows 는 경로 대소문자를 구분하지 않는다. */
private fun commandsEqual(a: String, b: String): Boolean =
    if (currentOs() == Os.WINDOWS) a.equals(b, ignoreCase = true) else a == b

private val pidJson = Json { ignoreUnknownKeys = true }

/**
 * 런처 밖에서(start.bat 더블클릭 등) 띄운 서버 감지: `server.properties` 의 `level-name`(없으면 `world`) 폴더의 `session.lock` 을
 * 이미 있을 때만 열어 `tryLock` 해 보고 즉시 푼다. 잠겨 있으면 true. 파일을 만들지 않는다. 판단 불가·오류 → false (critique windows #13).
 */
fun isWorldSessionLocked(serverDir: Path): Boolean {
    val levelName = readLevelName(serverDir)
    val lock = try {
        serverDir.resolve(levelName).resolve(SESSION_LOCK_NAME)
    } catch (e: InvalidPathException) {
        return false
    }
    if (!Files.isRegularFile(lock)) return false
    return try {
        // 파일을 만들지 않는다 (CREATE 없음) — 없으면 위에서 이미 false
        FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val acquired = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                // 같은 JVM 이 이미 잠근 경우도 "실행 중" 으로 본다
                return true
            }
            if (acquired == null) {
                true
            } else {
                acquired.release()
                false
            }
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** 월드 폴더 이름. `server.properties` 의 `level-name` (없으면 `world`). */
private fun readLevelName(serverDir: Path): String {
    val file = serverDir.resolve(SERVER_PROPERTIES_NAME)
    if (!Files.isRegularFile(file)) return DEFAULT_LEVEL_NAME
    val properties = Properties()
    try {
        // server.properties 는 ISO-8859-1 + `\uXXXX` 이스케이프 (java.util.Properties 형식)
        Files.newInputStream(file).use { properties.load(it) }
    } catch (e: IOException) {
        return DEFAULT_LEVEL_NAME
    } catch (e: IllegalArgumentException) {
        return DEFAULT_LEVEL_NAME
    }
    val name = properties.getProperty("level-name")
    return if (name.isNullOrBlank()) DEFAULT_LEVEL_NAME else name
}

private const val SERVER_PROPERTIES_NAME = "server.properties"
private const val SESSION_LOCK_NAME = "session.lock"
private const val DEFAULT_LEVEL_NAME = "world"

/** [stopDetachedServer] 결과. */
sealed interface DetachedStopResult {
    /** 신호 후 [exitObserved] 안에 끝났다 (종료 코드는 알 수 없다 — 우리 자식이 아니다). */
    data object Stopped : DetachedStopResult

    /** 신호를 보내지 못했다. */
    data class SignalFailed(val detail: String) : DetachedStopResult

    /** 신호는 보냈지만 제한 시간 안에 끝나지 않았다. 강제 종료는 하지 않는다 (사용자 결정). */
    data object StillRunning : DetachedStopResult
}

/**
 * 콘솔을 잃은 서버(고아) 정상 종료 — CLI `stop <이름>` (SCP-I20). stdin 이 없으므로 ①② 를 쓸 수 없어 ④ 부터:
 * Windows [WindowsConsoleCtrl.sendCtrlC], 그 외 `handle.destroy()` (SIGTERM) → 서버 셧다운 훅이 월드를 저장한다.
 * [wait] 동안 `onExit` 를 기다린다. ★ 강제 종료(`destroyForcibly`)는 하지 않는다.
 */
suspend fun stopDetachedServer(handle: ProcessHandle, os: Os, wait: Duration = 60.seconds): DetachedStopResult {
    val signal = if (os == Os.WINDOWS) {
        WindowsConsoleCtrl.sendCtrlC(handle.pid())
    } else if (handle.destroy()) {
        InterruptResult.Sent
    } else {
        InterruptResult.Failed("종료 신호를 보내지 못했습니다 (pid ${handle.pid()})")
    }
    when (signal) {
        is InterruptResult.Sent -> Unit
        is InterruptResult.Unsupported -> return DetachedStopResult.SignalFailed(signal.reasonKo)
        is InterruptResult.Failed -> return DetachedStopResult.SignalFailed(signal.detail)
    }
    val exited = withTimeoutOrNull(wait) { handle.onExit().await() }
    // ★ 강제 종료는 하지 않는다 (월드 손상 위험)
    return if (exited == null) DetachedStopResult.StillRunning else DetachedStopResult.Stopped
}
