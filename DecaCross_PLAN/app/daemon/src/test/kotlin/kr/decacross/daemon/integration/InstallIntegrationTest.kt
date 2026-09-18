package kr.decacross.daemon.integration

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.INCOMPLETE_MARKER_NAME
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallFailure
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.MANIFEST_FILE_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.RetryPolicy
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.isEulaAccepted
import kr.decacross.daemon.install.renderStartBat
import kr.decacross.daemon.process.LaunchResult
import kr.decacross.daemon.process.ReadyState
import kr.decacross.daemon.process.ShutdownOutcome
import kr.decacross.daemon.process.ShutdownTimeouts
import kr.decacross.daemon.process.launchServer
import kr.decacross.daemon.process.runShutdownProtocol
import kr.decacross.daemon.testkit.FakeServerJar
import kr.decacross.daemon.testkit.LocalHttpServer
import kr.decacross.daemon.testkit.TestJars
import kr.decacross.daemon.testkit.TreeSnapshot
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * DESIGN2 §5.2 통합 테스트: 설치 파이프라인 + 진짜 HTTP 전송 + 진짜 검증 + 진짜 파일 시스템 + (마지막에) 진짜 프로세스.
 * 모든 것이 임시 폴더와 127.0.0.1 안에서 끝난다 (§4.2 규칙 3·8).
 */
class InstallIntegrationTest {
    // ── 1. 행복 경로: 받기 → 검증 → 배치 → 커밋 → 기동 → 안전 종료 ────────────

    @Test
    fun happyPath_localHttp() {
        IntegrationFixture("happy").use { fixture ->
            val events = runBlocking { fixture.pipeline().run(fixture.request()).toList() }
            val ready = readyOf(events)
            assertEquals(fixture.serverDir, ready.server.dir)
            assertEquals(1, fixture.requests().size, "원본에 한 번만 요청한다")

            // 커밋된 폴더의 모든 파일이 매니페스트의 sha256·크기와 같다 (D-I45)
            val manifest = Json.decodeFromString(
                InstallManifest.serializer(),
                Files.readString(ready.server.dir.resolve(META_DIR_NAME).resolve(MANIFEST_FILE_NAME)),
            )
            assertEquals(ready.server.manifest.files.map { it.path }, manifest.files.map { it.path })
            for (entry in manifest.files) {
                val file = ready.server.dir.resolve(entry.path)
                assertTrue(Files.isRegularFile(file), "매니페스트에 있는 파일이 없다: ${entry.path}")
                assertEquals(TestJars.sha256Hex(Files.readAllBytes(file)), entry.sha256, entry.path)
                assertEquals(Files.size(file), entry.size, entry.path)
            }
            // 받은 jar 가 그대로 설치됐다
            val installedJar = ready.server.dir.resolve(ready.server.launch.jarFileName)
            assertEquals(fixture.jarSha256, TestJars.sha256Hex(Files.readAllBytes(installedJar)))
            // 성공 뒤에는 이어받기 조각을 남기지 않는다 (D-I8)
            assertFalse(Files.exists(fixture.partFile), "성공 뒤 .part 가 남았다")
            assertFalse(Files.exists(fixture.metaFile), "성공 뒤 .part.json 이 남았다")
            assertFalse(Files.exists(fixture.paths.stagingRoot), "커밋 뒤 .staging 은 남지 않는다")
            // 동의한 실행이므로 서버 폴더의 eula.txt 를 카탈로그가 "동의됨" 으로 읽는다
            assertTrue(isEulaAccepted(ready.server.dir), "eula.txt 가 동의로 읽히지 않는다")

            // 설치된 실행 명세로 실제 기동 → 준비 완료 → save-all/stop 안전 종료
            val spec = ready.server.launch.copy(xmsMb = 64, xmxMb = 64, jvmFlags = emptyList())
            val launched = launchServer(ready.server.dir, spec, fixture.consolePatterns, outputLine = { })
            val process = (launched as? LaunchResult.Started)?.process ?: fail("기동 실패: $launched")
            try {
                runBlocking {
                    assertEquals(ReadyState.READY, process.awaitReady(60.seconds), "준비 완료 줄을 봐야 한다")
                    val report = runShutdownProtocol(
                        process,
                        fixture.consolePatterns.saved,
                        ShutdownTimeouts(saveAll = 10.seconds, stop = 20.seconds, terminate = 10.seconds, kill = 5.seconds),
                    )
                    assertEquals(ShutdownOutcome.STOPPED, report.outcome, "단계: ${report.steps}")
                    assertEquals(0, report.exitCode)
                }
                val fakeEvents = FakeServerJar.events(ready.server.dir)
                assertTrue("stdin:save-all" in fakeEvents, "$fakeEvents")
                assertTrue("stdin:stop" in fakeEvents, "$fakeEvents")
                assertTrue("stop-exit" in fakeEvents, "$fakeEvents")
            } finally {
                process.kill()
            }
        }
    }

    // ── 2. 무결성 실패는 되돌린다 (critique A8) ──────────────────────────────

    @Test
    fun integrity_rollsBack() {
        IntegrationFixture("integrity").use { fixture ->
            // 길이는 같고 한 바이트만 다르다 → 다운로드는 성공하고 VERIFY 가 잡아야 한다
            val tampered = fixture.jarBytes.copyOf()
            val at = tampered.size / 2
            tampered[at] = (tampered[at].toInt() xor 0xFF).toByte()
            fixture.serve(LocalHttpServer.Resource(tampered))
            val before = TreeSnapshot.of(fixture.userRoot)

            val events = runBlocking { fixture.pipeline().run(fixture.request()).toList() }

            val failed = failureOf(events, InstallStage.VERIFY)
            val integrity = assertIs<InstallFailure.IntegrityFailed>(failed)
            assertIs<VerifyOutcome.HashMismatch>(integrity.outcome, "크기는 같으니 해시 불일치여야 한다")
            assertEquals(1, fixture.requests().size, "무결성 실패는 재시도하지 않는다")
            assertFalse(Files.exists(fixture.partFile), "오염된 조각은 지운다")
            assertEquals(before, TreeSnapshot.of(fixture.userRoot), "사용자 폴더는 그대로여야 한다")
        }
    }

    // ── 3. EULA 거부 → 롤백, 다시 실행하면 캐시 적중 ─────────────────────────

    @Test
    fun eulaDeclined_thenRerunHitsCache() {
        IntegrationFixture("eula").use { fixture ->
            val before = TreeSnapshot.of(fixture.userRoot)
            val declining = RecordingInteraction(answer = EulaAnswer.Declined("테스트: 동의하지 않음"))

            val first = runBlocking { fixture.pipeline(interaction = declining).run(fixture.request()).toList() }

            val failure = failureOf(first, InstallStage.EULA)
            assertIs<InstallFailure.EulaDeclined>(failure)
            assertEquals(1, declining.eulaCalls)
            assertEquals(before, TreeSnapshot.of(fixture.userRoot), "거부하면 사용자 폴더에 아무것도 남지 않는다")
            val afterFirst = fixture.requests().size
            assertEquals(1, afterFirst)
            assertTrue(Files.exists(fixture.partFile), "거부해도 받은 조각은 캐시에 남는다 (다음 실행이 다시 받지 않게)")
            assertEquals(fixture.build.size, Files.size(fixture.partFile))

            val second = runBlocking { fixture.pipeline(installId = "it-2").run(fixture.request()).toList() }

            val ready = readyOf(second)
            assertTrue(isEulaAccepted(ready.server.dir), "동의한 실행만 eula.txt 를 쓴다")
            assertEquals(afterFirst, fixture.requests().size, "두 번째 실행은 요청 없이 캐시를 쓴다")
            val completed = second.filterIsInstance<InstallEvent.FetchNotice>()
                .map { it.event }
                .filterIsInstance<FetchItemEvent.Completed>()
                .single()
            assertTrue(completed.fromCache, "캐시 적중 사건이어야 한다")
        }
    }

    // ── 4. 끊긴 다운로드를 다음 실행이 이어받는다 (critique A4) ───────────────

    @Test
    fun resumeAfterCut() {
        IntegrationFixture("resume").use { fixture ->
            val half = fixture.jarBytes.size.toLong() / 2
            fixture.serve(LocalHttpServer.Resource(fixture.jarBytes, cutAfterBytes = half))

            val first = runBlocking {
                fixture.pipeline(fetcher = fixture.fetcher(RetryPolicy(maxAttemptsPerSource = 1)))
                    .run(fixture.request()).toList()
            }

            val failure = failureOf(first, InstallStage.FETCH)
            val download = assertIs<InstallFailure.DownloadFailed>(failure)
            assertIs<FetchError.SourcesExhausted>(download.error)
            assertEquals(half, Files.size(fixture.partFile), "받은 만큼은 남겨 둔다")
            val afterFirst = fixture.requests().size

            fixture.serve(LocalHttpServer.Resource(fixture.jarBytes))
            val second = runBlocking { fixture.pipeline(installId = "it-2").run(fixture.request()).toList() }

            readyOf(second)
            val resumeRequest = fixture.requests()[afterFirst]
            assertEquals("bytes=$half-", resumeRequest.headers["range"], "이어받기 요청이어야 한다")
            assertEquals("\"v1\"", resumeRequest.headers["if-range"], "검증자를 함께 보내야 한다")
        }
    }

    // ── 5. 생성된 start.bat 은 런처 없이도 돈다 (불변식 11, critique A1/A7) ────

    @Test
    fun generatedStartBat_runsWithoutLauncher() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        IntegrationFixture("startbat").use { fixture ->
            val events = runBlocking { fixture.pipeline().run(fixture.request()).toList() }
            val ready = readyOf(events)
            val serverDir = ready.server.dir

            // 설치된 그대로의 스크립트 구조에 힙·플래그만 줄인다 (4GB·AlwaysPreTouch 로는 빌드 기계가 버티지 못한다)
            val spec = ready.server.launch.copy(xmsMb = 64, xmxMb = 64, jvmFlags = emptyList())
            val bat = serverDir.resolve("start.bat")
            Files.write(bat, renderStartBat(spec, emptyMap()))
            val stdin = fixture.root.resolve("stdin.txt")
            Files.write(stdin, "stop\r\n".toByteArray(Charsets.US_ASCII))
            val elsewhere = Files.createDirectories(fixture.root.resolve("elsewhere"))

            // `&`·괄호가 든 경로에서도 되는 형태: cmd /d /s /c ""<절대경로>""
            val builder = ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"\"" + bat.toAbsolutePath() + "\"\"")
                .directory(elsewhere.toFile())
                .redirectInput(stdin.toFile())
                .redirectErrorStream(true)
            val process = builder.start()
            val text = try {
                val raw = process.inputStream.readBytes()
                assertTrue(process.waitFor(120, TimeUnit.SECONDS), "start.bat 이 끝나지 않았다")
                // 콘솔 코드페이지가 무엇이든 읽히게 두 해석을 모두 본다
                String(raw, nativeCharset()) + "\n" + String(raw, Charsets.UTF_8)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(20, TimeUnit.SECONDS)
            }

            assertTrue(text.contains("Done ("), "기동 완료 줄이 없다: $text")
            assertTrue(
                text.lines().any { it.contains("fake.cwd=") && it.trimEnd().endsWith(serverDir.fileName.toString()) },
                "작업 디렉터리가 서버 폴더가 아니다: $text",
            )
            val fakeEvents = FakeServerJar.events(serverDir)
            assertTrue("stdin:stop" in fakeEvents, "리다이렉트된 stop 이 서버에 닿지 않았다: $fakeEvents")
            assertTrue("stop-exit" in fakeEvents, "$fakeEvents")
            assertEquals(0, process.exitValue(), text)
        }
    }

    // ── 7. 계획을 거절하면 아무것도 쓰지 않는다 (critique M5) ─────────────────

    @Test
    fun planRejected_writesNothing() {
        IntegrationFixture("reject").use { fixture ->
            seedLeftovers(fixture)
            val before = TreeSnapshot.of(fixture.userRoot)
            val rejecting = RecordingInteraction(planOk = false)

            val events = runBlocking { fixture.pipeline(interaction = rejecting).run(fixture.request()).toList() }

            assertEquals(InstallFailure.PlanRejected, failureOf(events, InstallStage.PLAN))
            assertEquals(1, rejecting.planCalls)
            assertEquals(0, rejecting.eulaCalls)
            assertEquals(0, fixture.requests().size, "승인 전에는 한 바이트도 받지 않는다")
            assertFalse(Files.exists(fixture.paths.partialDir), "캐시 폴더조차 만들지 않는다")
            assertEquals(before, TreeSnapshot.of(fixture.userRoot), "남은 조각도 그대로 둔다 (스윕은 LAYOUT 의 일)")
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 이전 설치가 남긴 조각 두 가지: 잠금 없는 `.staging/old-1` 과 `INCOMPLETE` 가 붙은 서버 폴더. */
    private fun seedLeftovers(fixture: IntegrationFixture) {
        val staging = Files.createDirectories(fixture.paths.stagingRoot.resolve("old-1"))
        Files.writeString(staging.resolve("조각.txt"), "이전 설치")
        val half = Files.createDirectories(fixture.serverDir.resolve(META_DIR_NAME))
        Files.writeString(half.resolve(INCOMPLETE_MARKER_NAME), "gone-1")
    }

    private fun readyOf(events: List<InstallEvent>): InstallEvent.Ready {
        val failed = events.filterIsInstance<InstallEvent.Failed>().firstOrNull()
        if (failed != null) fail("실패했다: ${failed.stage} ${failed.failure}")
        return events.filterIsInstance<InstallEvent.Ready>().singleOrNull() ?: fail("Ready 가 없다: ${summary(events)}")
    }

    /** 실패 → ROLLBACK → RolledBack 순서까지 확인하고 실패 사유를 돌려준다. */
    private fun failureOf(events: List<InstallEvent>, stage: InstallStage): InstallFailure {
        val failed = events.filterIsInstance<InstallEvent.Failed>().singleOrNull()
            ?: fail("Failed 사건이 하나여야 한다: ${summary(events)}")
        assertEquals(stage, failed.stage, summary(events).toString())
        val tail = events.takeLast(3)
        assertTrue(tail[0] is InstallEvent.Failed, summary(events).toString())
        assertEquals(InstallStage.ROLLBACK, (tail[1] as InstallEvent.StageEntered).stage, summary(events).toString())
        val rolledBack = tail[2] as? InstallEvent.RolledBack ?: fail("마지막은 RolledBack 이어야 한다: ${summary(events)}")
        assertTrue(rolledBack.cleanedUp, "치우지 못한 것: ${rolledBack.leftovers}")
        return failed.failure
    }

    private fun summary(events: List<InstallEvent>): List<String> = events.map { event ->
        when (event) {
            is InstallEvent.StageEntered -> "stage:${event.stage}"
            is InstallEvent.Failed -> "failed:${event.stage}:${event.failure}"
            is InstallEvent.RolledBack -> "rolledBack"
            is InstallEvent.Ready -> "ready"
            else -> event::class.simpleName ?: "?"
        }
    }

    private fun nativeCharset(): Charset =
        runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrElse { Charsets.UTF_8 }
}
