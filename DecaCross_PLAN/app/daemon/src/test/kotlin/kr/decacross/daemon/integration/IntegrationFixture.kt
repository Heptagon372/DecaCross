package kr.decacross.daemon.integration

import io.ktor.client.HttpClient
import kr.decacross.compat.db.InMemoryCompatDb
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.daemon.install.ArtifactFetcher
import kr.decacross.daemon.install.ArtifactVerifier
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.DiskSpaceProbe
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.EulaNotice
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallPipeline
import kr.decacross.daemon.install.InstallPlan
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.MIN_RAM_MB
import kr.decacross.daemon.install.MemoryInfo
import kr.decacross.daemon.install.MemoryProbe
import kr.decacross.daemon.install.RetryPolicy
import kr.decacross.daemon.install.buildDaemonUserAgent
import kr.decacross.daemon.install.installHttpClient
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.ServersRoot
import kr.decacross.daemon.paths.ServersRootSource
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.runtime.JavaCandidate
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaOrigin
import kr.decacross.daemon.runtime.JavaSelection
import kr.decacross.daemon.testkit.FakeServerJar
import kr.decacross.daemon.testkit.LocalHttpServer
import kr.decacross.daemon.testkit.TestJars
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * DESIGN2 §5.2 통합 테스트 장치: **진짜** 다운로더·검증기·파일 시스템·프로세스를 쓰고, 바깥으로 나가는 것만 막는다.
 *
 * - 네트워크: 127.0.0.1 [LocalHttpServer] 하나 (§4.2 규칙 3 — 밖으로 나가지 않는다).
 * - 파일: [Files.createTempDirectory] 로 만든 루트 아래만 쓴다. 실제 사용자 폴더·레포를 건드리지 않는다.
 * - 서버 jar: WP0 테스트 킷의 [FakeServerJar] (진짜 마인크래프트 서버를 절대 쓰지 않는다, §4.2 규칙 8).
 *   그래서 이 루트 안에서 생기는 `eula.txt` 는 규칙 8 이 허용하는 유일한 경우다.
 *
 * # 설계 문서와 다른 점 (의도한 것)
 * - 메모리·디스크 조사기는 고정값이다. 실제 값을 쓰면 빌드 기계의 여유 메모리에 따라 경고 사건 수가 달라진다
 *   (검사 대상은 다운로드·검증·배치·커밋이지 PLAN 의 환경 판정이 아니다 — 그쪽은 `PipelineTest` 가 본다).
 * - Java 는 테스트 JVM 이다 (불변식 9 는 제품 코드 규칙이고, 가짜 서버 jar 는 이 JVM 으로만 돈다).
 */
internal class IntegrationFixture(name: String) : AutoCloseable {
    /** 이 테스트가 쓰는 모든 것의 부모. 임시 폴더다. */
    val root: Path = Files.createTempDirectory("dcx-it-$name")

    /** 내부 데이터(캐시). 사용자 폴더 비교가 캐시 때문에 흔들리지 않게 분리한다 (critique A9). */
    val internalRoot: Path = root.resolve("internal")

    /** "사용자 폴더" 역할. 트리 스냅샷 비교는 항상 이 경로로 한다. */
    val userRoot: Path = root.resolve("user")

    val serversRoot: Path = userRoot.resolve("servers")

    val paths: DecaPaths = DecaPaths(internalRoot, ServersRoot(serversRoot, ServersRootSource.OPTION, null))

    /** 원본 자원을 주는 로컬 서버. [PATH] 하나만 둔다. */
    val server: LocalHttpServer = LocalHttpServer()

    /** 실제로 실행 가능한 가짜 서버 jar 바이트 (설치 뒤 `launchServer`·`start.bat` 이 이것을 띄운다). */
    val jarBytes: ByteArray

    val jarSha256: String

    val mc: McVersion = McVersion(
        ordinal = McOrdinal(1940),
        label = "1.21.8",
        releasedAt = Instant.parse("2025-07-17T00:00:00Z"),
        javaMin = 21,
        javaRecommended = 21,
        rpFormat = null,
        dpFormat = null,
    )

    val build: CoreBuild

    val db: InMemoryCompatDb

    val profiles: LaunchProfiles = (loadLaunchProfiles() as? LaunchProfilesLoad.Loaded)?.profiles
        ?: error("launch-profiles.json 을 읽지 못했다")

    val consolePatterns: CompiledConsolePatterns = CompiledConsolePatterns.from(profiles.console)

    /** 운영과 같은 설정의 클라이언트 (UA·타임아웃). */
    val client: HttpClient = installHttpClient(buildDaemonUserAgent() ?: error("UA 를 만들 수 없다"))

    /** 설치가 만드는 서버 폴더. */
    val serverDir: Path get() = serversRoot.resolve(SERVER_NAME)

    /** 이어받기 캐시 파일 (`{sha256}.part`). */
    val partFile: Path get() = paths.partialDir.resolve("$jarSha256.part")

    /** 이어받기 메타 (`{sha256}.part.json`). */
    val metaFile: Path get() = paths.partialDir.resolve("$jarSha256.part.json")

    /** 서버로 쓸 java = 테스트 JVM (가짜 서버 jar 는 이 JVM 의 class 파일 버전이다). */
    val javaPath: Path = FakeServerJar.testJava()

    private val javaFeature: Int = Runtime.version().feature()

    /** 찾기를 흉내 내지 않는 고정 Java 선택 (탐색은 `SystemJavaLocatorTest` 가 본다). */
    val javaLocator: JavaLocator = JavaLocator { _, _ ->
        JavaLocateResult.Found(
            JavaSelection(javaPath, javaFeature, "$javaFeature.0.1", JavaOrigin.INSTALLED, emptyList()),
            listOf(JavaCandidate(javaPath, JavaOrigin.INSTALLED, "$javaFeature.0.1", javaFeature, null)),
        )
    }

    private val clock: Clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-18T09:00:00Z")
    }

    init {
        Files.createDirectories(internalRoot)
        Files.createDirectories(userRoot)
        // 규칙 8: eula.txt 가 생기는 트리에는 가짜 서버 jar 가 있다 (진짜 서버가 아님을 코드로 보장)
        jarBytes = Files.readAllBytes(FakeServerJar.write(root.resolve("source").resolve("fake-core.jar")))
        jarSha256 = TestJars.sha256Hex(jarBytes)
        build = CoreBuild(
            core = CoreKey.PAPER,
            mc = mc.ordinal,
            build = "60",
            channel = Channel.STABLE,
            downloadUrl = server.url(PATH),
            sha256 = jarSha256,
            size = jarBytes.size.toLong(),
        )
        db = InMemoryCompatDb(listOf(mc), listOf(build))
        serve(LocalHttpServer.Resource(jarBytes))
    }

    /** 원본 자원을 바꾼다 (끊김·변조 재현). 요청 기록은 유지되고 상태 순서 카운터만 초기화된다. */
    fun serve(resource: LocalHttpServer.Resource) {
        server.put(PATH, resource)
    }

    /** [PATH] 로 들어온 요청 기록 (재시도·이어받기 확인용). */
    fun requests(): List<LocalHttpServer.RecordedRequest> = server.requestsTo(PATH)

    /** 운영과 같은 [Fetcher] (정책만 테스트가 정한다). */
    fun fetcher(policy: RetryPolicy = RetryPolicy(baseDelayMs = 20, maxDelayMs = 50, maxJitterMs = 5)): Fetcher =
        Fetcher(client, paths.partialDir, policy, maxParallel = 1, progressIntervalMs = 50)

    /** 진짜 [Fetcher] + [ArtifactVerifier.DEFAULT] 를 쓰는 파이프라인. */
    fun pipeline(
        fetcher: ArtifactFetcher = fetcher(),
        interaction: InstallInteraction = RecordingInteraction(),
        installId: String = "it-1",
    ): InstallPipeline = InstallPipeline(
        db = db,
        paths = paths,
        fetcher = fetcher,
        verifier = ArtifactVerifier.DEFAULT,
        javaLocator = javaLocator,
        interaction = interaction,
        profiles = profiles,
        env = emptyMap(),
        memory = MemoryProbe { MemoryInfo(16L * GIB, 8L * GIB) },
        disk = DiskSpaceProbe { 100L * GIB },
        clock = clock,
        newInstallId = { installId },
    )

    fun request(serverName: String = SERVER_NAME, ramMb: Int = MIN_RAM_MB): InstallRequest =
        InstallRequest(mcLabel = mc.label, core = CoreKey.PAPER, serverName = serverName, ramMb = ramMb)

    override fun close() {
        server.close()
        client.close()
        deleteRecursivelyQuietly(root)
    }

    companion object {
        /** 로컬 서버가 코어 jar 를 주는 경로. */
        const val PATH: String = "/paper.jar"

        const val SERVER_NAME: String = "demo"

        private const val GIB: Long = 1024L * 1024L * 1024L
    }
}

/**
 * 계획 확인·EULA 답을 고정한다. ★ 동의는 테스트가 명시적으로 지정할 때만 나온다 (§4.2 규칙 8).
 * 기본값은 `--accept-eula` 와 같은 경로([ConsentChannel.CLI_FLAG])다 — 사람의 입력을 흉내 내지 않는다.
 */
internal class RecordingInteraction(
    private val planOk: Boolean = true,
    private val answer: EulaAnswer = EulaAnswer.Accepted(ConsentChannel.CLI_FLAG),
) : InstallInteraction {
    var planCalls: Int = 0
        private set

    var eulaCalls: Int = 0
        private set

    override suspend fun confirmPlan(plan: InstallPlan): Boolean {
        planCalls++
        return planOk
    }

    override suspend fun requestEulaConsent(notice: EulaNotice): EulaAnswer {
        eulaCalls++
        return answer
    }
}

/** 임시 트리 삭제 (뒷정리 실패는 테스트 결과를 바꾸지 않는다). */
internal fun deleteRecursivelyQuietly(root: Path) {
    if (!Files.exists(root)) return
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: IOException) {
        // 임시 폴더라 남아도 해가 없다
    }
}
