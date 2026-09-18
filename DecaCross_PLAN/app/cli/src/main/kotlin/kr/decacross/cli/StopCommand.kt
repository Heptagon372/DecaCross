package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ServerEntry
import kr.decacross.daemon.install.findServer
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.process.DetachedStopResult
import kr.decacross.daemon.process.PidRecord
import kr.decacross.daemon.process.deletePidFile
import kr.decacross.daemon.process.isWorldSessionLocked
import kr.decacross.daemon.process.liveServerProcess
import kr.decacross.daemon.process.readPidFile
import kr.decacross.daemon.process.stopDetachedServer
import java.nio.file.Path

/**
 * `decacross stop <이름>` — 콘솔을 잃은 서버(고아)를 안전하게 끈다 (SCP-I20).
 *
 * # 불변식
 * - ★ 강제 종료(`destroyForcibly`/taskkill)는 절대 하지 않는다. 월드 손상은 사용자가 결정할 일이다.
 * - 콘솔이 살아 있는 CLI 가 관리 중이면 신호를 보내지 않는다 (두 개의 종료 프로토콜이 겹치면 늦게 온 쪽이 먼저 반환한다).
 */
class StopCommand(
    private val io: ConsoleIo = ConsoleIo(),
    private val hostOs: Os = currentOs(),
    private val envProvider: (Os) -> Map<String, String> = { os -> hostEnvironment(os) },
    private val pathsResolver: PathsResolver = PathsResolver.SYSTEM,
    private val serverFinder: (Path, String) -> ServerEntry? = { root, name -> findServer(root, name) },
    private val liveCheck: (Path) -> ProcessHandle? = { dir -> liveServerProcess(dir) },
    private val sessionLockCheck: (Path) -> Boolean = { dir -> isWorldSessionLocked(dir) },
    private val pidReader: (Path) -> PidRecord? = { dir -> readPidFile(dir) },
    private val pidDeleter: (Path) -> Unit = { dir -> deletePidFile(dir) },
    private val launcherAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    private val detachedStop: suspend (ProcessHandle, Os) -> DetachedStopResult =
        { handle, os -> stopDetachedServer(handle, os) },
) : CliktCommand(name = "stop") {
    private val name by argument(name = "name", help = "서버 이름 (`decacross list` 의 이름)")
    private val serversDirOption by option("--servers-dir", help = "서버 폴더 루트").path()

    override fun run() {
        val code = runBlocking { execute() }
        if (code != ExitCodes.OK) throw ProgramResult(code)
    }

    private suspend fun execute(): Int {
        val env = envProvider(hostOs)
        val resolution = withContext(Dispatchers.IO) { pathsResolver.resolve(env, hostOs, serversDirOption) }
        val paths =
            when (resolution) {
                is PathsResolution.Invalid -> {
                    io.out("[실패] ${resolution.reasonKo}")
                    return ExitCodes.INPUT
                }

                is PathsResolution.Resolved -> resolution.paths
            }
        val entry = withContext(Dispatchers.IO) { serverFinder(paths.serversRoot.path, name) }
        if (entry == null) {
            io.out("[실패] 서버를 찾을 수 없습니다: $name")
            io.out("  해결: `decacross list` 로 이름을 확인하세요")
            return ExitCodes.SERVER_STATE
        }
        val handle = withContext(Dispatchers.IO) { liveCheck(entry.dir) }
        if (handle == null) {
            if (withContext(Dispatchers.IO) { sessionLockCheck(entry.dir) }) {
                io.out("[실패] DecaCross 밖에서 시작된 서버입니다 — 그 창에서 stop 을 입력하세요")
            } else {
                io.out("[실패] 실행 중이 아닙니다: $name")
            }
            return ExitCodes.SERVER_STATE
        }
        val launcherPid = withContext(Dispatchers.IO) { pidReader(entry.dir) }?.launcherPid
        if (launcherPid != null && launcherAlive(launcherPid)) {
            io.out("[실패] 콘솔이 있는 CLI(pid $launcherPid)가 관리 중입니다 — 그 콘솔에서 stop 을 입력하세요")
            return ExitCodes.SERVER_STATE
        }
        // ★ 아직 보내기 전이다 — "보냈습니다" 라고 하면 바로 아래 SignalFailed 줄과 앞뒤가 맞지 않는다
        io.out("종료 신호를 보냅니다 (pid ${handle.pid()}) — 서버 셧다운 훅이 월드를 저장할 때까지 기다립니다")
        return when (val result = detachedStop(handle, hostOs)) {
            DetachedStopResult.Stopped -> {
                withContext(Dispatchers.IO) { pidDeleter(entry.dir) }
                io.out("[완료] 종료했습니다 (서버 셧다운 훅이 저장)")
                ExitCodes.OK
            }

            is DetachedStopResult.SignalFailed -> {
                io.out("[실패] 종료 신호를 보내지 못했습니다: ${result.detail}")
                ExitCodes.UNEXPECTED
            }

            DetachedStopResult.StillRunning -> {
                io.out("[주의] 60초 안에 끝나지 않았습니다 — 강제 종료는 하지 않습니다(월드 손상 위험). 잠시 뒤 다시 확인하세요")
                ExitCodes.SERVER_FAILED
            }
        }
    }
}
