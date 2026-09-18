package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ServerEntry
import kr.decacross.daemon.install.listServers
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.process.PidRecord
import kr.decacross.daemon.process.liveServerProcess
import kr.decacross.daemon.process.readPidFile
import java.nio.file.Path

/**
 * `decacross list` — 서버 루트의 서버 목록. ★ 아무것도 쓰지 않는다 (읽기 전용).
 *
 * # 불변식
 * - 서버는 살아 있는데 그 서버를 띄운 CLI(`launcherPid`)가 죽었으면 "콘솔 없이 실행 중" 으로 표시하고 `stop <이름>` 을 안내한다
 *   (그렇지 않으면 사용자는 작업 관리자로 강제 종료할 수밖에 없다 — critique windows #5).
 */
class ListCommand(
    private val io: ConsoleIo = ConsoleIo(),
    private val hostOs: Os = currentOs(),
    private val envProvider: (Os) -> Map<String, String> = { os -> hostEnvironment(os) },
    private val pathsResolver: PathsResolver = PathsResolver.SYSTEM,
    private val listProvider: (Path) -> List<ServerEntry> = { root -> listServers(root) },
    private val liveCheck: (Path) -> ProcessHandle? = { dir -> liveServerProcess(dir) },
    private val pidReader: (Path) -> PidRecord? = { dir -> readPidFile(dir) },
    private val launcherAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) : CliktCommand(name = "list") {
    private val serversDirOption by option("--servers-dir", help = "서버 폴더 루트").path()

    override fun run() {
        val env = envProvider(hostOs)
        val paths =
            when (val resolution = pathsResolver.resolve(env, hostOs, serversDirOption)) {
                is PathsResolution.Invalid -> {
                    io.out("[실패] ${resolution.reasonKo}")
                    throw ProgramResult(ExitCodes.INPUT)
                }

                is PathsResolution.Resolved -> resolution.paths
            }
        io.out("서버 루트: ${paths.serversRoot.path}")
        paths.serversRoot.noticeKo?.let { io.out(it) }
        val entries = listProvider(paths.serversRoot.path)
        if (entries.isEmpty()) {
            io.out("서버가 없습니다. `decacross create --mc 1.21.8 --core paper --ram 4G` 로 만드세요.")
            return
        }
        io.out("이름 | MC | 코어(빌드) | 메모리 | 상태 | 경로")
        for (entry in entries) {
            val manifest = entry.manifest
            val launch = entry.launch
            val mc = manifest?.mcLabel ?: "?"
            val coreText =
                if (manifest == null) "?" else "${manifest.core.lowercase()}(${manifest.coreBuild})"
            val ram = if (launch == null) "?" else "${launch.xmxMb}MB"
            io.out("${entry.name} | $mc | $coreText | $ram | ${statusOf(entry)} | ${entry.dir}")
        }
    }

    private fun statusOf(entry: ServerEntry): String {
        val problem = entry.problemKo
        if (problem != null) return "문제: $problem"
        val handle = liveCheck(entry.dir) ?: return "정지"
        val launcherPid = pidReader(entry.dir)?.launcherPid
        val consoleAlive = launcherPid != null && launcherAlive(launcherPid)
        return if (consoleAlive) {
            "실행 중 pid ${handle.pid()}"
        } else {
            "콘솔 없이 실행 중 pid ${handle.pid()} (stop ${entry.name})"
        }
    }
}
