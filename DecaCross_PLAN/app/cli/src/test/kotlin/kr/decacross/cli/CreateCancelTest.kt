package kr.decacross.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kr.decacross.cli.testkit.FakeHooks
import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.MANIFEST_FILE_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.encodeLaunchJson
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaRequirement
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `create` 의 비정상 종료 경로: Ctrl+C(셧다운 훅) 취소 → 130, 흐름이 예외로 끝남 → 1.
 * 실제 파이프라인 대신 가짜 흐름을 쓴다 (네트워크·사용자 폴더 없음).
 */
class CreateCancelTest {
    private class HangingEnvironment : InstallEnvironment {
        override fun flow(
            db: CompatDb,
            javaLocator: JavaLocator,
            interaction: InstallInteraction,
            profiles: LaunchProfiles,
        ): InstallFlow = InstallFlow { _ -> hangingFlow() }

        override fun close() = Unit

        private fun hangingFlow(): Flow<InstallEvent> = flow {
            emit(InstallEvent.StageEntered(InstallStage.RESOLVE))
            emit(InstallEvent.StageEntered(InstallStage.FETCH))
            awaitCancellation()
        }
    }

    /**
     * 커밋 지점을 **지난 뒤** Ctrl+C 가 들어온 경우 (verify03 F2).
     * 파이프라인이 실제로 그렇듯 완성된 서버 폴더를 사용자 루트에 두고, 사건 없이 취소로 끝난다.
     */
    private class CommittedThenHangingEnvironment(private val serverDir: Path) : InstallEnvironment {
        override fun flow(
            db: CompatDb,
            javaLocator: JavaLocator,
            interaction: InstallInteraction,
            profiles: LaunchProfiles,
        ): InstallFlow = InstallFlow { _ ->
            flow {
                emit(InstallEvent.StageEntered(InstallStage.RESOLVE))
                emit(InstallEvent.StageEntered(InstallStage.READY))
                val meta = serverDir.resolve(META_DIR_NAME)
                Files.createDirectories(meta)
                Files.write(meta.resolve(LAUNCH_FILE_NAME), encodeLaunchJson(Fixtures.spec))
                Files.writeString(
                    meta.resolve(MANIFEST_FILE_NAME),
                    Json.encodeToString(InstallManifest.serializer(), Fixtures.manifest(SERVER_NAME)),
                )
                awaitCancellation()
            }
        }

        override fun close() = Unit
    }

    /** 파이프라인이 `InstallEvent.Failed` 대신 예외로 끝나는 경우 (리소스 손상 등). */
    private class ThrowingEnvironment : InstallEnvironment {
        override fun flow(
            db: CompatDb,
            javaLocator: JavaLocator,
            interaction: InstallInteraction,
            profiles: LaunchProfiles,
        ): InstallFlow = InstallFlow { _ ->
            flow {
                emit(InstallEvent.StageEntered(InstallStage.RESOLVE))
                throw IllegalStateException("호환성 데이터가 깨졌습니다")
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `설치 흐름이 예외로 끝나면 스택 트레이스 대신 1 로 끝낸다`() {
        val tmp = Files.createTempDirectory("dcx-cli-throw")
        tmp.toFile().deleteOnExit()
        val paths = DecaPaths(
            tmp.resolve("internal"),
            ServersRoot(tmp.resolve("servers"), ServersRootSource.OPTION, null),
        )
        val recording = RecordingIo()
        val command = CreateCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            environmentFactory = { _, _ -> ThrowingEnvironment() },
            javaLocatorFactory = { _, _, _ ->
                JavaLocator { requirement: JavaRequirement, _: Path? -> JavaLocateResult.NotFound(requirement, emptyList()) }
            },
            runner = { _, _, _ -> error("실패했으면 서버를 띄우지 않는다") },
            hooks = FakeHooks(),
        )
        val result = command.test(listOf("--mc", "1.21.8", "--core", "paper", "--ram", "4G"))
        assertEquals(ExitCodes.UNEXPECTED, result.statusCode, recording.text())
        assertTrue(recording.text().contains("예상 못 한 오류"), recording.text())
        assertTrue(recording.text().contains("호환성 데이터가 깨졌습니다"), recording.text())
    }

    @Test
    fun `셧다운 훅이 설치를 취소하면 130 으로 끝난다`() {
        val tmp = Files.createTempDirectory("dcx-cli-cancel")
        tmp.toFile().deleteOnExit()
        val paths = DecaPaths(
            tmp.resolve("internal"),
            ServersRoot(tmp.resolve("servers"), ServersRootSource.OPTION, null),
        )
        val recording = RecordingIo()
        val hooks = FakeHooks()
        val command = CreateCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            environmentFactory = { _, _ -> HangingEnvironment() },
            javaLocatorFactory = { _, _, _ ->
                JavaLocator { requirement: JavaRequirement, _: Path? -> JavaLocateResult.NotFound(requirement, emptyList()) }
            },
            runner = { _, _, _ -> error("취소됐으면 서버를 띄우지 않는다") },
            hooks = hooks,
        )
        val resultRef = AtomicReference<CliktCommandTestResult?>(null)
        val worker = Thread({
            resultRef.set(command.test(listOf("--mc", "1.21.8", "--core", "paper", "--ram", "4G")))
        }, "create-under-test")
        worker.start()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (hooks.added.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        val hook = assertNotNull(hooks.added.firstOrNull(), "설치 취소 훅이 등록돼야 한다")
        hook.start()
        hook.join(30_000)
        worker.join(30_000)
        val result = assertNotNull(resultRef.get(), "명령이 끝나야 한다")
        assertEquals(ExitCodes.CANCELLED, result.statusCode, recording.text())
        assertTrue(recording.text().contains("취소"), recording.text())
    }

    @Test
    fun `커밋 뒤에 취소되면 이어받기 대신 start 를 안내한다`() {
        // ★ 회귀 (verify03 F2): 커밋 지점 뒤의 취소에도 CLI 는 "다시 실행하면 받은 부분부터 이어받습니다" 라고 했다.
        //   그 안내대로 다시 실행하면 PLAN 이 ServerExists 로 죽는다 — 서버는 이미 만들어져 있기 때문이다.
        val tmp = Files.createTempDirectory("dcx-cli-committed-cancel")
        tmp.toFile().deleteOnExit()
        val serversRoot = tmp.resolve("servers")
        val paths = DecaPaths(tmp.resolve("internal"), ServersRoot(serversRoot, ServersRootSource.OPTION, null))
        val recording = RecordingIo()
        val hooks = FakeHooks()
        val command = CreateCommand(
            io = recording.io,
            hostOs = Os.LINUX,
            envProvider = { emptyMap() },
            pathsResolver = { _, _, _ -> PathsResolution.Resolved(paths) },
            dbProvider = { DevCompatFixture.db() },
            profilesLoader = { loadLaunchProfiles() },
            environmentFactory = { _, _ -> CommittedThenHangingEnvironment(serversRoot.resolve(SERVER_NAME)) },
            javaLocatorFactory = { _, _, _ ->
                JavaLocator { requirement: JavaRequirement, _: Path? -> JavaLocateResult.NotFound(requirement, emptyList()) }
            },
            runner = { _, _, _ -> error("취소됐으면 서버를 띄우지 않는다") },
            hooks = hooks,
        )
        val resultRef = AtomicReference<CliktCommandTestResult?>(null)
        val worker = Thread({
            resultRef.set(command.test(listOf("--mc", "1.21.8", "--core", "paper", "--ram", "4G")))
        }, "create-committed-cancel")
        worker.start()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (hooks.added.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        val hook = assertNotNull(hooks.added.firstOrNull(), "설치 취소 훅이 등록돼야 한다")
        hook.start()
        hook.join(30_000)
        worker.join(30_000)
        val result = assertNotNull(resultRef.get(), "명령이 끝나야 한다")
        assertEquals(ExitCodes.CANCELLED, result.statusCode, recording.text())
        assertTrue(recording.text().contains("decacross start $SERVER_NAME"), recording.text())
        assertFalse(recording.text().contains("이어받습니다"), "거짓 안내를 하면 안 된다: ${recording.text()}")
    }

    private companion object {
        /** `--name` 을 생략했을 때의 기본 이름 (`{core}-{mc}`). */
        const val SERVER_NAME: String = "paper-1.21.8"
    }
}
