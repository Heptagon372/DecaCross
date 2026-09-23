package kr.decacross.ui.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 데몬을 **별도 JVM 프로세스**로 띄운다 (명세 §8.1: -Xmx192m, 상주).
 *
 * - 현재 UI 가 쓰는 java 실행파일 + 같은 클래스패스를 그대로 쓴다 (`:app:daemon` 이 런타임 의존성이라 전부 들어있다).
 * - UI 수명에 묶지 않는다: 파이프를 상속하지 않고 stdout/stderr 를 `logs/daemon.log` 로 보낸다.
 *   창을 닫아도, UI 프로세스가 죽어도 데몬은 산다 (05 완료 판정).
 * - ★ 서버용 런타임과는 무관하다. 이 java 는 데몬(런처) 용이다 (불변식 9 와 충돌 없음).
 */
object DaemonLauncher {
    const val MAIN_CLASS = "kr.decacross.daemon.MainKt"

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** 현재 프로세스의 java 실행파일. `ProcessHandle` 이 못 주면 `java.home/bin/java` 로 대체. */
    fun currentJava(): String =
        ProcessHandle.current().info().command().orElseGet {
            Paths.get(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java").toString()
        }

    fun command(java: String = currentJava(), classpath: String = System.getProperty("java.class.path")): List<String> =
        listOf(
            java,
            "-Xmx192m",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            classpath,
            MAIN_CLASS,
        )

    /** 데몬을 띄우고 즉시 돌아온다. 준비 여부는 [DaemonDiscovery] 가 `daemon.json` 폴링으로 판단한다. */
    suspend fun spawn(logsDir: Path): Process = withContext(Dispatchers.IO) {
        Files.createDirectories(logsDir)
        val log = logsDir.resolve("daemon.log").toFile()
        ProcessBuilder(command())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
            .start()
    }
}
