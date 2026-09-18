package kr.decacross.daemon.install.assemble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.DirectoryMover
import kr.decacross.daemon.install.DiskSpaceProbe
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FileOrigin
import kr.decacross.daemon.install.INCOMPLETE_MARKER_NAME
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallFailure
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.MANIFEST_FILE_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.MemoryInfo
import kr.decacross.daemon.install.MemoryProbe
import kr.decacross.daemon.install.MoveRetryPolicy
import kr.decacross.daemon.install.StagingCommitter
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.openStaging
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.testkit.TreeSnapshot
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** 설치 상태머신 (DESIGN2 §2.2·§2.4). 실제 서버·네트워크·사용자 폴더 없이 전부 임시 디렉터리에서 돈다. */
class PipelineTest {
    // ── 행복 경로 ────────────────────────────────────────────────

    @Test
    fun happyPath_emitsDocumentedEventOrder_andCommitsOnce() {
        val fixture = PipelineFixture("happy")
        val events = collect(fixture)

        assertEquals(
            listOf(
                "stage:RESOLVE",
                "resolved",
                "stage:PLAN",
                "planned",
                "stage:FETCH",
                "fetch",
                "stage:VERIFY",
                "verified",
                "stage:LAYOUT",
                "stage:CONFIG",
                "stage:EULA",
                "stage:READY",
                "ready",
            ),
            summary(events),
        )

        val ready = events.filterIsInstance<InstallEvent.Ready>().single()
        val serverDir = fixture.serversRoot.resolve("demo")
        assertEquals(serverDir, ready.server.dir)
        assertEquals(
            listOf(
                ".decacross",
                ".decacross/launch.json",
                ".decacross/manifest.json",
                "eula.txt",
                "paper-1.21.8.jar",
                "server.properties",
                "start.bat",
                "start.sh",
            ),
            TreeSnapshot.of(serverDir).keys.map { it.replace('\\', '/') }.sorted(),
        )
        assertFalse(Files.exists(fixture.paths.stagingRoot), "커밋 뒤 .staging 은 남지 않는다")
        assertEquals(1, fixture.fetcher.discardCalls, "성공 뒤 캐시 조각을 지운다 (D-I8)")

        // 매니페스트는 설치된 모든 파일을 디스크의 실제 sha256 으로 적는다 (D-I45)
        val manifest = Json.decodeFromString(
            InstallManifest.serializer(),
            Files.readString(serverDir.resolve(META_DIR_NAME).resolve(MANIFEST_FILE_NAME)),
        )
        assertEquals(6, manifest.files.size, manifest.files.map { it.path }.toString())
        assertEquals("t-1", manifest.installId)
        assertEquals("2026-09-17T13:05:00Z", manifest.createdAt)
        assertEquals(1940, manifest.mcOrdinal)
        assertEquals("60", manifest.coreBuild)
        for (entry in manifest.files) {
            val file = serverDir.resolve(entry.path)
            assertTrue(Files.isRegularFile(file), entry.path)
            assertEquals(sha256Hex(Files.readAllBytes(file)), entry.sha256, entry.path)
            assertEquals(Files.size(file), entry.size, entry.path)
        }
        assertEquals(
            FileOrigin.DOWNLOADED,
            manifest.files.single { it.path == "paper-1.21.8.jar" }.origin,
        )
        assertTrue(manifest.files.filter { it.path != "paper-1.21.8.jar" }.all { it.origin == FileOrigin.GENERATED })

        val launch = Json.decodeFromString(
            LaunchSpec.serializer(),
            Files.readString(serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME)),
        )
        assertEquals(fixture.javaPath.toString(), launch.javaPath)
        assertEquals(4096, launch.xmxMb)
        assertEquals("aikar-base", launch.flagProfileId)
        assertEquals("paper-1.21.8.jar", launch.jarFileName)
    }

    @Test
    fun happyPath_createsMissingServerRootLevels() {
        val fixture = PipelineFixture("levels")
        assertFalse(Files.exists(fixture.userRoot))
        collect(fixture)
        assertTrue(Files.isDirectory(fixture.serversRoot.resolve("demo")))
    }

    // ── 단계별 실패 주입 ──────────────────────────────────────────

    @Test
    fun resolveFailure_unknownMc() {
        val fixture = PipelineFixture("f-resolve")
        val events = collect(fixture, fixture.request(mcLabel = "1.21.99"))
        val failure = assertRolledBack(events, InstallStage.RESOLVE, fixture)
        assertTrue(failure is InstallFailure.UnknownMc, "$failure")
        assertEquals(listOf("1.21.8"), failure.sameFamily, "계열 안내 (접두사 축약)")
    }

    @Test
    fun planFailure_invalidName() {
        val fixture = PipelineFixture("f-name")
        val events = collect(fixture, fixture.request(serverName = "CON"))
        assertTrue(assertRolledBack(events, InstallStage.PLAN, fixture) is InstallFailure.InvalidServerName)
    }

    @Test
    fun planFailure_serverExists() {
        val fixture = PipelineFixture("f-exists")
        Files.createDirectories(fixture.serversRoot.resolve("Demo"))
        val events = collect(fixture, fixture.request(serverName = "demo"))
        assertTrue(assertRolledBack(events, InstallStage.PLAN, fixture) is InstallFailure.ServerExists)
    }

    @Test
    fun planFailure_javaNotFound() {
        val fixture = PipelineFixture("f-java")
        fixture.javaLocator = FakeJavaLocator.notFound()
        assertTrue(assertRolledBack(collect(fixture), InstallStage.PLAN, fixture) is InstallFailure.JavaNotFound)
    }

    @Test
    fun planFailure_insufficientDisk() {
        val fixture = PipelineFixture("f-disk")
        fixture.disk = DiskSpaceProbe { 1024 }
        assertTrue(assertRolledBack(collect(fixture), InstallStage.PLAN, fixture) is InstallFailure.InsufficientDisk)
    }

    @Test
    fun planFailure_invalidRam() {
        val fixture = PipelineFixture("f-ram")
        val events = collect(fixture, fixture.request(ramMb = 512))
        assertTrue(assertRolledBack(events, InstallStage.PLAN, fixture) is InstallFailure.InvalidRam)
    }

    @Test
    fun planFailure_planRejected() {
        val fixture = PipelineFixture("f-plan")
        fixture.interaction = FakeInteraction(planOk = false)
        assertEquals(InstallFailure.PlanRejected, assertRolledBack(collect(fixture), InstallStage.PLAN, fixture))
        assertEquals(0, fixture.fetcher.fetchCalls, "계획을 거절하면 받지 않는다")
    }

    @Test
    fun fetchFailure_isReportedWithItemId() {
        val fixture = PipelineFixture("f-fetch")
        fixture.fetcher.failWith = FetchError.SourcesExhausted(emptyList())
        val failure = assertRolledBack(collect(fixture), InstallStage.FETCH, fixture)
        assertTrue(failure is InstallFailure.DownloadFailed, "$failure")
        assertTrue(failure.itemId.startsWith("core:"), failure.itemId)
    }

    @Test
    fun verifyFailure_discardsOnce_andNeverRetries() {
        val fixture = PipelineFixture("f-verify")
        fixture.verifier.forced = VerifyOutcome.HashMismatch("a".repeat(64), "b".repeat(64))
        val events = collect(fixture)
        assertTrue(assertRolledBack(events, InstallStage.VERIFY, fixture) is InstallFailure.IntegrityFailed)
        assertEquals(1, fixture.fetcher.fetchCalls)
        assertEquals(1, fixture.verifier.calls)
        assertEquals(1, fixture.fetcher.discardCalls, "오염된 조각은 즉시 버린다")
        assertTrue(
            events.filterIsInstance<InstallEvent.FetchNotice>().none { it.event is FetchItemEvent.Retrying },
            "무결성 실패는 재시도하지 않는다 (critique A8)",
        )
    }

    @Test
    fun layoutFailure_stagingPathBlockedByRegularFile() {
        val fixture = PipelineFixture("f-layout")
        Files.createDirectories(fixture.serversRoot)
        Files.writeString(fixture.serversRoot.resolve(DecaPaths.STAGING_DIR_NAME), "방해 파일")
        assertTrue(assertRolledBack(collect(fixture), InstallStage.LAYOUT, fixture) is InstallFailure.LocalIo)
    }

    @Test
    fun layoutFailure_nameLockHeldByAnotherInstall() {
        val fixture = PipelineFixture("f-busy")
        Files.createDirectories(fixture.paths.stagingRoot)
        val lockFile = Files.createFile(fixture.paths.stagingRoot.resolve(nameLockFileName("demo")))
        // 잠긴 파일은 Windows 에서 읽을 수 없으므로 스냅샷은 잠그기 전에 찍는다
        val before = TreeSnapshot.of(fixture.userRoot)
        val held = assertIs<LockAcquire.Held>(acquireLockFile(lockFile), "이름 잠금을 잡지 못했다")
        val events = try {
            runBlocking { fixture.pipeline().run(fixture.request()).toList() }
        } finally {
            held.handle.close()
        }
        assertTrue(assertRolledBack(events, InstallStage.LAYOUT, fixture, before) is InstallFailure.NameBusy)
    }

    @Test
    fun configFailure_isInjectedAtStageEntry() {
        val fixture = PipelineFixture("f-config")
        val pipeline = fixture.pipeline()
        pipeline.faultBeforeStage = { stage ->
            if (stage == InstallStage.CONFIG) InstallFailure.LocalIo(null, "주입된 CONFIG 실패") else null
        }
        val before = TreeSnapshot.of(fixture.userRoot)
        val events = runBlocking { pipeline.run(fixture.request()).toList() }
        assertFalse(Files.exists(fixture.paths.stagingRoot), "롤백 뒤 .staging 은 없다")
        assertTrue(assertRolledBack(events, InstallStage.CONFIG, fixture, before) is InstallFailure.LocalIo)
    }

    @Test
    fun eulaDeclined_rollsBackAndKeepsCacheForRerun() {
        val fixture = PipelineFixture("f-eula")
        fixture.interaction = FakeInteraction(answer = EulaAnswer.Declined("입력 없음(EOF)"))
        val events = collect(fixture)
        assertTrue(assertRolledBack(events, InstallStage.EULA, fixture) is InstallFailure.EulaDeclined)
        assertEquals(0, fixture.fetcher.discardCalls, "받은 파일은 캐시에 남긴다")

        // 재실행: 캐시 적중 → 다시 받지 않는다
        fixture.interaction = FakeInteraction(answer = EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT))
        val second = collect(fixture)
        assertTrue(second.last() is InstallEvent.Ready, "${second.last()}")
        assertEquals(1, fixture.fetcher.cacheHits, "두 번째 실행은 캐시를 쓴다")
        assertEquals(2, fixture.fetcher.fetchCalls)
    }

    @Test
    fun readyFailure_commitAlwaysDenied() {
        val fixture = PipelineFixture("f-ready")
        fixture.committer = StagingCommitter(
            DirectoryMover { _, target -> throw AccessDeniedException(target.toString()) },
            MoveRetryPolicy(attempts = 2, initialDelayMs = 1, maxDelayMs = 2),
        )
        assertTrue(assertRolledBack(collect(fixture), InstallStage.READY, fixture) is InstallFailure.CommitFailed)
    }

    // ── 승인 전에는 아무것도 건드리지 않는다 (critique M5) ──────────

    @Test
    fun planRejected_leavesLeftoversUntouched_andApprovalSweepsThem() {
        val rejecting = PipelineFixture("m5-reject")
        seedLeftovers(rejecting)
        rejecting.interaction = FakeInteraction(planOk = false)
        val before = TreeSnapshot.of(rejecting.userRoot)
        val events = runBlocking { rejecting.pipeline().run(rejecting.request()).toList() }

        val warnings = events.filterIsInstance<InstallEvent.Warning>().map { it.messageKo }
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.all { it.contains("남긴 조각") }, "$warnings")
        assertEquals(InstallFailure.PlanRejected, assertRolledBack(events, InstallStage.PLAN, rejecting, before))
        assertEquals(before, TreeSnapshot.of(rejecting.userRoot), "거절하면 남은 조각도 그대로 둔다")

        val accepting = PipelineFixture("m5-accept")
        seedLeftovers(accepting)
        val ok = runBlocking { accepting.pipeline().run(accepting.request()).toList() }
        assertTrue(ok.last() is InstallEvent.Ready, "${ok.last()}")
        assertFalse(
            Files.exists(accepting.paths.stagingRoot.resolve("old-1")),
            "승인 뒤 LAYOUT 스윕이 남은 스테이징을 치운다",
        )
        assertTrue(Files.isRegularFile(accepting.serversRoot.resolve("demo").resolve("paper-1.21.8.jar")))
        assertFalse(
            Files.exists(accepting.serversRoot.resolve("demo").resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME)),
            "INCOMPLETE 였던 폴더는 치우고 새로 만든다",
        )
    }

    // ── 취소 (critique A6) ───────────────────────────────────────

    @Test
    fun cancelDuringFetch_stopsEventsAndLeavesNoTrace() {
        val fixture = PipelineFixture("c-fetch")
        fixture.fetcher.beforeResult = { awaitCancellation() }
        val before = TreeSnapshot.of(fixture.userRoot)
        val events = runUntilThenCancel(fixture) { it is InstallEvent.StageEntered && it.stage == InstallStage.FETCH }
        assertTrue(
            events.last() is InstallEvent.StageEntered && (events.last() as InstallEvent.StageEntered).stage == InstallStage.FETCH,
            "취소 뒤에는 사건을 더 내보내지 않는다: ${summary(events)}",
        )
        assertEquals(before, TreeSnapshot.of(fixture.userRoot))
    }

    @Test
    fun cancelAtEula_rollsBackAndAllowsRerunWithSameName() {
        val fixture = PipelineFixture("c-eula")
        fixture.interaction = FakeInteraction(hangAtEula = true)
        val before = TreeSnapshot.of(fixture.userRoot)
        val events = runUntilThenCancel(fixture) { it is InstallEvent.StageEntered && it.stage == InstallStage.EULA }
        assertEquals(InstallStage.EULA, (events.last() as InstallEvent.StageEntered).stage, summary(events).toString())
        assertFalse(Files.exists(fixture.paths.stagingRoot), "스테이징과 잠금 파일이 남지 않는다")
        assertEquals(before, TreeSnapshot.of(fixture.userRoot))

        fixture.interaction = FakeInteraction()
        val second = collect(fixture)
        assertTrue(second.last() is InstallEvent.Ready, "같은 이름으로 다시 설치할 수 있어야 한다: ${summary(second)}")
    }

    @Test
    fun cancelWhileSendingRolledBack_runsRollbackExactlyOnce() {
        val fixture = PipelineFixture("c-rollback")
        fixture.interaction = FakeInteraction(answer = EulaAnswer.Declined("테스트"))
        val pipeline = fixture.pipeline()
        var rollbacks = 0
        pipeline.onRollback = { rollbacks++ }
        val seen = ArrayList<InstallEvent>()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                pipeline.run(fixture.request()).collect { event ->
                    seen.add(event)
                    if (event is InstallEvent.StageEntered && event.stage == InstallStage.ROLLBACK) {
                        this@launch.cancel(CancellationException("테스트: 롤백 사건 전송 중 취소"))
                        yield()
                    }
                }
            }
            job.join()
        }
        assertEquals(1, rollbacks, "롤백은 설치당 한 번만 돈다")
        assertTrue(seen.any { it is InstallEvent.Failed }, summary(seen).toString())
        assertFalse(Files.exists(fixture.paths.stagingRoot))
    }

    @Test
    fun `스테이징을 연 직후 취소돼도 스테이징과 잠금을 치운다`() {
        // ★ 회귀: `withContext(Dispatchers.IO) { openStaging(...) }` 가 취소되면 결과(StagingArea)가 버려져
        //   롤백이 `.staging/{id}/` 와 잠금 파일 두 개를 보지 못한 채 "치웠다" 고 보고했다.
        val fixture = PipelineFixture("c-open")
        val pipeline = fixture.pipeline()
        val jobRef = AtomicReference<Job?>(null)
        pipeline.openStagingFn = { root, id, name ->
            val opened = openStaging(root, id, name)
            // 여는 블록 **안에서** 취소가 들어온 상태를 만든다 (밖에서는 이 창을 맞출 수 없다)
            jobRef.get()?.cancel(CancellationException("테스트: 스테이징을 연 직후 취소"))
            opened
        }
        val before = TreeSnapshot.of(fixture.userRoot)
        val events = CopyOnWriteArrayList<InstallEvent>()
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                pipeline.run(fixture.request()).collect { events.add(it) }
            }
            jobRef.set(job)
            job.start()
            withTimeout(30_000) { job.join() }
        }
        assertTrue(jobRef.get()?.isCancelled == true, "테스트가 실제로 취소를 걸었어야 한다")
        assertFalse(Files.exists(fixture.paths.stagingRoot), "스테이징과 잠금 파일이 남지 않는다")
        assertEquals(before, TreeSnapshot.of(fixture.userRoot), "사용자 폴더는 그대로여야 한다")
    }

    @Test
    fun `커밋이 끝난 뒤 들어온 취소는 롤백하지 않는다`() {
        // ★ 회귀: 이동이 성공한 뒤 취소되면 `withContext` 가 결과를 버려 CommitResult.Committed 가 사라지고,
        //   파이프라인이 "롤백했다" 고 말하면서 완성된 서버 폴더는 사용자 루트에 남았다.
        val fixture = PipelineFixture("c-commit")
        val jobRef = AtomicReference<Job?>(null)
        fixture.committer = StagingCommitter(
            DirectoryMover { source, target ->
                DirectoryMover.NIO.moveAtomically(source, target)
                jobRef.get()?.cancel(CancellationException("테스트: 커밋 직후 취소"))
            },
            MoveRetryPolicy(attempts = 2, initialDelayMs = 1, maxDelayMs = 2),
        )
        val pipeline = fixture.pipeline()
        var rollbacks = 0
        pipeline.onRollback = { rollbacks++ }
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                pipeline.run(fixture.request()).collect { }
            }
            jobRef.set(job)
            job.start()
            withTimeout(30_000) { job.join() }
        }
        assertEquals(0, rollbacks, "커밋이 끝났으면 롤백은 돌지 않는다")
        val serverDir = fixture.serversRoot.resolve("demo")
        assertTrue(Files.isRegularFile(serverDir.resolve("paper-1.21.8.jar")), "커밋된 서버가 온전해야 한다")
        assertTrue(Files.isRegularFile(serverDir.resolve(META_DIR_NAME).resolve(MANIFEST_FILE_NAME)))
        assertFalse(Files.exists(fixture.paths.stagingRoot), "스테이징은 남지 않는다")
    }

    // ── PLAN 의 메모리 판정 (환경 탐침은 주입한다) ────────────────

    @Test
    fun `요청 RAM 이 물리 메모리보다 크면 PLAN 에서 막는다`() {
        val fixture = PipelineFixture("f-mem")
        fixture.memory = MemoryProbe { MemoryInfo(2L * PipelineFixture.GIB, 1L * PipelineFixture.GIB) }
        val failure = assertRolledBack(collect(fixture, fixture.request(ramMb = 4096)), InstallStage.PLAN, fixture)
        val invalid = assertIs<InstallFailure.InvalidRam>(failure, "$failure")
        assertTrue(invalid.reasonKo.contains("물리 메모리"), invalid.reasonKo)
    }

    @Test
    fun `AlwaysPreTouch 인데 여유 메모리가 적으면 경고한다`() {
        val fixture = PipelineFixture("w-mem")
        fixture.memory = MemoryProbe { MemoryInfo(16L * PipelineFixture.GIB, 1L * PipelineFixture.GIB) }
        val events = collect(fixture, fixture.request(ramMb = 4096))
        val warnings = events.filterIsInstance<InstallEvent.Warning>().map { it.messageKo }
        assertTrue(warnings.any { it.contains("지금 쓸 수 있는 메모리") }, warnings.toString())
        assertTrue(events.last() is InstallEvent.Ready, "경고일 뿐 설치는 계속된다: ${summary(events)}")
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 실행 직전 사용자 폴더 스냅샷 (롤백 뒤 같은지 비교한다). */
    private var beforeSnapshot: Map<String, String> = emptyMap()

    private fun collect(fixture: PipelineFixture, request: InstallRequest = fixture.request()): List<InstallEvent> {
        beforeSnapshot = TreeSnapshot.of(fixture.userRoot)
        return runBlocking { fixture.pipeline().run(request).toList() }
    }

    private fun runUntilThenCancel(
        fixture: PipelineFixture,
        request: InstallRequest = fixture.request(),
        until: (InstallEvent) -> Boolean,
    ): List<InstallEvent> = runBlocking {
        val events = CopyOnWriteArrayList<InstallEvent>()
        val job = launch(Dispatchers.Default) { fixture.pipeline().run(request).collect { events.add(it) } }
        withTimeout(30_000) {
            while (events.none(until)) delay(5)
        }
        job.cancelAndJoin()
        events.toList()
    }

    /** `.staging/old-1` (잠금 없음) 과 `INCOMPLETE` 가 붙은 `demo` 폴더를 심는다. */
    private fun seedLeftovers(fixture: PipelineFixture) {
        val staging = Files.createDirectories(fixture.paths.stagingRoot.resolve("old-1"))
        Files.writeString(staging.resolve("조각.txt"), "이전 설치")
        Files.writeString(fixture.paths.stagingRoot.resolve("old-1.lock"), "")
        val half = Files.createDirectories(fixture.serversRoot.resolve("demo").resolve(META_DIR_NAME))
        Files.writeString(half.resolve(INCOMPLETE_MARKER_NAME), "gone-1")
        Files.writeString(fixture.serversRoot.resolve("demo").resolve("paper-1.21.8.jar"), "반쯤 복사된 파일")
    }

    private fun assertRolledBack(
        events: List<InstallEvent>,
        stage: InstallStage,
        fixture: PipelineFixture,
        before: Map<String, String> = beforeSnapshot,
    ): InstallFailure {
        val failed = events.filterIsInstance<InstallEvent.Failed>().singleOrNull() ?: fail("Failed 사건이 하나여야 한다: ${summary(events)}")
        assertEquals(stage, failed.stage, summary(events).toString())
        val tail = events.takeLast(3)
        assertTrue(tail[0] is InstallEvent.Failed, summary(events).toString())
        assertEquals(InstallStage.ROLLBACK, (tail[1] as InstallEvent.StageEntered).stage, summary(events).toString())
        val rolledBack = tail[2] as? InstallEvent.RolledBack ?: fail("마지막은 RolledBack: ${summary(events)}")
        assertEquals(stage, rolledBack.failedStage)
        assertTrue(rolledBack.cleanedUp, "남은 것: ${rolledBack.leftovers}")
        assertContentEquals(emptyList(), rolledBack.leftovers)
        assertEquals(before, TreeSnapshot.of(fixture.userRoot), "사용자 폴더는 실패 전과 같아야 한다")
        return failed.failure
    }

    private fun summary(events: List<InstallEvent>): List<String> {
        val out = ArrayList<String>(events.size)
        for (event in events) {
            val label = when (event) {
                is InstallEvent.StageEntered -> "stage:${event.stage}"
                is InstallEvent.Resolved -> "resolved"
                is InstallEvent.Planned -> "planned"
                is InstallEvent.Progress -> "fetch"
                is InstallEvent.FetchNotice -> "fetch"
                is InstallEvent.Verified -> "verified"
                is InstallEvent.Warning -> "warning"
                is InstallEvent.Ready -> "ready"
                is InstallEvent.Failed -> "failed:${event.stage}"
                is InstallEvent.RolledBack -> "rolledBack"
            }
            if (label == "fetch" && out.lastOrNull() == "fetch") continue
            out.add(label)
        }
        return out
    }
}
