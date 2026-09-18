package kr.decacross.daemon.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.MANIFEST_FILE_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.process.InterruptResult
import kr.decacross.daemon.process.WindowsConsoleCtrl
import kr.decacross.daemon.testkit.FakeServerJar
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * DESIGN2 §5.2 #6 (critique M2, SCP-I17): `bin\cli.bat` 경로의 **진짜 Ctrl+C**.
 * 설치된 CLI(`installDist`)를 별도 프로세스로 띄우고 그 콘솔에 CTRL_C_EVENT 를 보내, CLI 가 강제 종료가 아니라
 * `save-all` → `stop` 순서(불변식 13)로 서버를 끄는지 본다.
 *
 * - 진짜 마인크래프트 서버는 쓰지 않는다 — [FakeServerJar] 뿐이다. `eula.txt` 는 이 임시 트리 안에서만 만든다 (§4.2 규칙 8).
 * - 설치된 CLI 의 위치는 Gradle 이 시스템 속성 `decacross.cli.lib` 로 준다 (app/daemon/build.gradle.kts 가
 *   `:app:cli:installDist` 에 의존하고 그 디렉터리를 입력으로 선언한다 — 선언이 없으면 CLI 만 고쳤을 때
 *   이 테스트가 캐시에서 복원돼 **옛 CLI** 를 시험한 결과가 초록으로 남는다).
 *   속성이 없거나 디렉터리가 없으면(IDE 에서 단독 실행) `assumeTrue` 로 건너뛴다 — Gradle 실행에서는 건너뛰지 않는다.
 * - cmd.exe 가 키보드 Ctrl+C 에 띄우는 "Terminate batch job (Y/N)?" 는 여기서 확인하지 못한다 (R-19, JVM 쪽만 본다).
 * - 설계 문서와 다른 점: 표준오류를 표준출력에 합쳐(`redirectErrorStream`) 파이프 하나로 읽는다. 셋 다 파이프인 것은 같고
 *   (CLI 는 자기 숨은 콘솔을 받는다), 읽는 스레드가 하나라 어느 쪽이 먼저 차도 막히지 않는다. 표준입력은 열어 둔다
 *   (닫으면 EOF 경로가 돌아 Ctrl+C 를 확인할 수 없다).
 */
class CliCtrlCIntegrationTest {
    @Test
    fun realCtrlC_runsSaveAllThenStop() {
        assumeTrue(currentOs() == Os.WINDOWS, "CTRL_C 헬퍼는 Windows 전용")
        val libProperty = System.getProperty("decacross.cli.lib")
        assumeTrue(libProperty != null, "decacross.cli.lib 이 없다 (Gradle 밖에서 돌렸다)")
        val lib = Path.of(libProperty).normalize()
        assumeTrue(Files.isDirectory(lib), "installDist 결과가 없다: $lib")

        val root = Files.createTempDirectory("dcx-it-ctrlc")
        val serverDir = root.resolve(SERVER_NAME)
        var cli: Process? = null
        var serverPid: Long? = null
        try {
            installFakeServer(serverDir)
            val output = CopyOnWriteArrayList<String>()
            val process = ProcessBuilder(
                FakeServerJar.testJava().toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-cp",
                lib.toString() + File.separator + "*",
                CLI_MAIN,
                "start",
                SERVER_NAME,
                "--servers-dir",
                root.toString(),
            ).directory(root.toFile()).redirectErrorStream(true).start()
            cli = process
            val pump = Thread({ pump(process, output) }, "cli-out")
            pump.isDaemon = true
            pump.start()

            // ① CLI 가 서버 준비 완료를 알릴 때까지 (여기까지는 평범한 실행이다)
            assertTrue(
                await(60_000) { output.any { line -> line.contains(READY_LINE) } },
                "준비 완료 줄이 없다: ${output.joinToString("\n")}",
            )
            serverPid = serverPidFrom(serverDir)
            assertTrue(serverPid != null, "가짜 서버가 pid 를 남기지 않았다: ${FakeServerJar.events(serverDir)}")

            // ② 진짜 CTRL_C_EVENT 를 CLI 콘솔에 보낸다 (서버는 자기 콘솔을 가지므로 직접 받지 않는다)
            val sent = runBlocking { WindowsConsoleCtrl.sendCtrlC(process.pid(), 30.seconds) }
            assertEquals(InterruptResult.Sent, sent, "헬퍼가 신호를 보내지 못했다")

            // ③ CLI 의 셧다운 훅이 프로토콜을 돌린다: save-all → stop → 종료
            assertTrue(
                await(60_000) { "stop-exit" in FakeServerJar.events(serverDir) },
                "종료 프로토콜이 끝나지 않았다: ${FakeServerJar.events(serverDir)} / ${output.joinToString("\n")}",
            )
            val events = FakeServerJar.events(serverDir)
            val saveAll = events.indexOf("stdin:save-all")
            val stop = events.indexOf("stdin:stop")
            val stopExit = events.indexOf("stop-exit")
            assertTrue(saveAll >= 0 && stop > saveAll && stopExit > stop, "순서가 다르다: $events")

            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI 가 끝나지 않았다: ${output.joinToString("\n")}")
            val exitCode = process.exitValue()
            println("[CliCtrlCIntegrationTest] CLI 종료 코드 = $exitCode")
            assertTrue(
                exitCode == 130 || exitCode == 0,
                "콘솔 Ctrl+C 뒤 종료 코드는 130(SIGINT) 또는 0 이어야 한다: $exitCode",
            )
            val alive = serverPid?.let { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } ?: false
            assertFalse(alive, "가짜 서버가 살아 있다: pid $serverPid")
        } finally {
            // 뒷정리: 이 테스트가 만든 임시 트리의 프로세스만 강제 종료한다 (헬퍼 없이)
            serverPid?.let { pid -> ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() } }
            cli?.let { process ->
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(20, TimeUnit.SECONDS)
            }
            deleteRecursivelyQuietly(root)
        }
    }

    /** 설치 결과와 같은 모양의 서버 폴더 (가짜 jar + launch.json + manifest.json + 동의된 eula.txt). */
    private fun installFakeServer(serverDir: Path) {
        Files.createDirectories(serverDir.resolve(META_DIR_NAME))
        FakeServerJar.write(serverDir.resolve(JAR_NAME))
        val spec = LaunchSpec(
            javaPath = FakeServerJar.testJava().toString(),
            javaFeature = Runtime.version().feature(),
            xmsMb = 64,
            xmxMb = 64,
            // 테스트가 실패해도 가짜 서버가 오래 남지 않게 (ServerFixture 와 같은 안전장치)
            jvmFlags = listOf("-Dfake.maxLifeMs=120000"),
            jarFileName = JAR_NAME,
        )
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        Files.writeString(
            serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME),
            json.encodeToString(LaunchSpec.serializer(), spec),
        )
        val manifest = InstallManifest(
            installId = "it-ctrlc",
            serverName = SERVER_NAME,
            createdAt = "2026-09-18T09:00:00Z",
            mcLabel = "1.21.8",
            mcOrdinal = 1940,
            core = "PAPER",
            coreBuild = "60",
            coreChannel = "STABLE",
            files = emptyList(),
        )
        Files.writeString(
            serverDir.resolve(META_DIR_NAME).resolve(MANIFEST_FILE_NAME),
            json.encodeToString(InstallManifest.serializer(), manifest),
        )
        // ★ 규칙 8: 가짜 서버 jar 가 있는 이 임시 트리 안에서만 만든다. 사용자의 동의를 대신하는 것이 아니라,
        //   CLI 가 EULA 프롬프트 없이 기동 경로로 곧장 가게 하는 테스트 장치다.
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")
    }

    /** `fake-events.txt` 의 `start pid=N` 에서 가짜 서버 pid 를 읽는다. */
    private fun serverPidFrom(serverDir: Path): Long? =
        FakeServerJar.events(serverDir)
            .firstOrNull { it.startsWith("start pid=") }
            ?.substringAfter("start pid=")
            ?.trim()
            ?.toLongOrNull()

    private fun pump(process: Process, sink: MutableList<String>) {
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                sink.add(line)
            }
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val SERVER_NAME = "demo"
        const val JAR_NAME = "paper-1.21.8.jar"
        const val CLI_MAIN = "kr.decacross.cli.MainKt"

        /** `ServerRunner` 가 준비 완료 때 찍는 줄. */
        const val READY_LINE = "서버 준비 완료"
    }
}
