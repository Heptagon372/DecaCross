package kr.decacross.daemon.install.assemble

import kotlinx.coroutines.awaitCancellation
import kr.decacross.compat.db.InMemoryCompatDb
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.daemon.install.ArtifactFetcher
import kr.decacross.daemon.install.ArtifactVerifier
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.DirectoryMover
import kr.decacross.daemon.install.DiskSpaceProbe
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.EulaNotice
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItem
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchListener
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.FetchResult
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallPipeline
import kr.decacross.daemon.install.InstallPlan
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.ItemProgress
import kr.decacross.daemon.install.ItemState
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.MemoryInfo
import kr.decacross.daemon.install.MemoryProbe
import kr.decacross.daemon.install.MoveRetryPolicy
import kr.decacross.daemon.install.StagingCommitter
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.runtime.JavaCandidate
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaOrigin
import kr.decacross.daemon.runtime.JavaRequirement
import kr.decacross.daemon.runtime.JavaSelection
import kr.decacross.daemon.testkit.FakeServerJar
import kr.decacross.daemon.testkit.TestJars
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 파이프라인 테스트용 가짜와 고정 장치. 네트워크·실제 서버·실제 사용자 폴더를 쓰지 않는다 (§4.2 규칙 3·8).
 */

/** [TestJars.serverJar] 바이트를 캐시에 놓아 주는 가짜 다운로더. 호출 횟수를 센다. */
internal class FakeFetcher(private val partialDir: Path, private val bytes: ByteArray) : ArtifactFetcher {
    var fetchCalls: Int = 0
    var discardCalls: Int = 0
    var cacheHits: Int = 0

    /** null 이 아니면 모든 항목을 이 오류로 실패시킨다. */
    var failWith: FetchError? = null

    /** 결과를 돌려주기 전에 실행할 것 (취소 시나리오에서 `awaitCancellation()`). */
    var beforeResult: (suspend () -> Unit)? = null

    override suspend fun fetchAll(items: List<FetchItem>, listener: FetchListener): List<FetchResult> {
        fetchCalls++
        beforeResult?.invoke()
        val error = failWith
        if (error != null) {
            for (item in items) listener.onEvent(FetchItemEvent.Failed(item.id, error))
            return items.map { FetchResult.Failed(it, error) }
        }
        Files.createDirectories(partialDir)
        return items.map { item ->
            val file = partialDir.resolve(item.sha256 + ".part")
            val cached = Files.exists(file)
            if (cached) cacheHits++ else Files.write(file, bytes)
            val size = Files.size(file)
            listener.onEvent(FetchItemEvent.Started(item.id, item.sources.first(), 0))
            listener.onProgress(
                FetchProgress(size, size, 0, listOf(ItemProgress(item.id, size, size, ItemState.DONE))),
            )
            listener.onEvent(FetchItemEvent.Completed(item.id, size, cached))
            FetchResult.Fetched(item, file, 0, if (cached) null else item.sources.first())
        }
    }

    override suspend fun discard(item: FetchItem) {
        discardCalls++
        Files.deleteIfExists(partialDir.resolve(item.sha256 + ".part"))
    }
}

/** sha256·크기만 보는 가짜 검증기 ([kr.decacross.daemon.install.verifyFile] 은 WP-FETCH 소유라 부르지 않는다). */
internal class FakeVerifier : ArtifactVerifier {
    var calls: Int = 0

    /** null 이 아니면 그대로 돌려준다 (무결성 실패 주입). */
    var forced: VerifyOutcome? = null

    override suspend fun verify(file: Path, item: FetchItem): VerifyOutcome {
        calls++
        forced?.let { return it }
        val actual = sha256Hex(Files.readAllBytes(file))
        val size = Files.size(file)
        return when {
            size != item.size -> VerifyOutcome.SizeMismatch(item.size, size)
            actual != item.sha256 -> VerifyOutcome.HashMismatch(item.sha256, actual)
            else -> VerifyOutcome.Verified(actual, size)
        }
    }
}

/** 고정된 Java 선택 결과. */
internal class FakeJavaLocator(private val result: JavaLocateResult) : JavaLocator {
    override suspend fun locate(requirement: JavaRequirement, explicit: Path?): JavaLocateResult = result

    companion object {
        fun found(javaPath: Path, feature: Int = 21, warningsKo: List<String> = emptyList()): FakeJavaLocator =
            FakeJavaLocator(
                JavaLocateResult.Found(
                    JavaSelection(javaPath, feature, "$feature.0.1", JavaOrigin.INSTALLED, warningsKo),
                    listOf(JavaCandidate(javaPath, JavaOrigin.INSTALLED, "$feature.0.1", feature, null)),
                ),
            )

        fun notFound(requirement: JavaRequirement = JavaRequirement(21, 21)): FakeJavaLocator =
            FakeJavaLocator(JavaLocateResult.NotFound(requirement, emptyList()))
    }
}

/** 계획 확인과 EULA 답을 고정한다. 동의는 **테스트가 명시적으로 지정할 때만** 나온다. */
internal class FakeInteraction(
    private val planOk: Boolean = true,
    private val answer: EulaAnswer = EulaAnswer.Accepted(ConsentChannel.INTERACTIVE_PROMPT),
    private val hangAtEula: Boolean = false,
) : InstallInteraction {
    var planCalls: Int = 0
    var eulaCalls: Int = 0

    override suspend fun confirmPlan(plan: InstallPlan): Boolean {
        planCalls++
        return planOk
    }

    override suspend fun requestEulaConsent(notice: EulaNotice): EulaAnswer {
        eulaCalls++
        if (hangAtEula) awaitCancellation()
        return answer
    }
}

/** 설치 한 벌을 돌릴 임시 환경 (내부 루트 / 사용자 루트를 분리해 트리 비교가 캐시에 흔들리지 않게 한다, critique A9). */
internal class PipelineFixture(name: String) {
    val root: Path = Files.createTempDirectory("dcx-pipe-$name")
    val internalRoot: Path = root.resolve("internal")
    val userRoot: Path = root.resolve("user")
    val serversRoot: Path = userRoot.resolve("x").resolve("y").resolve("servers")
    val paths: DecaPaths = DecaPaths(internalRoot, ServersRoot(serversRoot, ServersRootSource.OPTION, null))

    val jarBytes: ByteArray = TestJars.serverJar(seed = 3, randomEntryBytes = 32 * 1024)
    val jarSha256: String = TestJars.sha256Hex(jarBytes)

    val mc: McVersion = McVersion(
        ordinal = McOrdinal(1940),
        label = "1.21.8",
        releasedAt = Instant.parse("2025-07-17T00:00:00Z"),
        javaMin = 21,
        javaRecommended = 21,
        rpFormat = null,
        dpFormat = null,
    )
    val build: CoreBuild = CoreBuild(
        core = CoreKey.PAPER,
        mc = mc.ordinal,
        build = "60",
        channel = Channel.STABLE,
        downloadUrl = "https://example.invalid/v1/objects/$jarSha256/paper-1.21.8-60.jar",
        sha256 = jarSha256,
        size = jarBytes.size.toLong(),
    )
    val db: InMemoryCompatDb = InMemoryCompatDb(listOf(mc), listOf(build))
    val profiles: LaunchProfiles = (loadLaunchProfiles() as LaunchProfilesLoad.Loaded).profiles

    val fetcher: FakeFetcher = FakeFetcher(paths.partialDir, jarBytes)
    val verifier: FakeVerifier = FakeVerifier()

    /** 실제로 띄우지 않는 합성 절대경로 (렌더링만 한다). */
    val javaPath: Path = (root.root ?: root).resolve("jdk-21").resolve("bin").resolve("java.exe")

    var javaLocator: JavaLocator = FakeJavaLocator.found(javaPath)
    var interaction: InstallInteraction = FakeInteraction()
    var memory: MemoryProbe = MemoryProbe { MemoryInfo(16L * GIB, 8L * GIB) }
    var disk: DiskSpaceProbe = DiskSpaceProbe { 100L * GIB }
    var committer: StagingCommitter = StagingCommitter(DirectoryMover.NIO, MoveRetryPolicy(attempts = 3, initialDelayMs = 1, maxDelayMs = 2))
    var installId: String = "t-1"

    val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-17T13:05:00Z")
    }

    init {
        // §4.2 규칙 8: eula.txt 가 생기는 테스트는 FakeServerJar 를 둔 임시 루트 안에서만 돈다
        FakeServerJar.write(root.resolve("paper-1.21.8.jar"))
        Files.createDirectories(internalRoot)
    }

    fun pipeline(): InstallPipeline = InstallPipeline(
        db = db,
        paths = paths,
        fetcher = fetcher,
        verifier = verifier,
        javaLocator = javaLocator,
        interaction = interaction,
        profiles = profiles,
        env = emptyMap(),
        memory = memory,
        disk = disk,
        committer = committer,
        clock = clock,
        newInstallId = { installId },
    )

    fun request(
        serverName: String = "demo",
        ramMb: Int = 4096,
        mcLabel: String = "1.21.8",
        core: CoreKey = CoreKey.PAPER,
    ): InstallRequest = InstallRequest(mcLabel = mcLabel, core = core, serverName = serverName, ramMb = ramMb)

    companion object {
        const val GIB: Long = 1024L * 1024L * 1024L
    }
}
