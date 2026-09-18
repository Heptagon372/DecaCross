package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.DEFAULT_UA_CONTACT
import kr.decacross.daemon.install.DefaultInstallEnvironment
import kr.decacross.daemon.install.Difficulty
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallInteraction
import kr.decacross.daemon.install.InstallRequest
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.ServerSettings
import kr.decacross.daemon.install.UA_CONTACT_ENV
import kr.decacross.daemon.install.buildDaemonUserAgent
import kr.decacross.daemon.install.defaultServerName
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.SystemJavaLocator
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/** 설치 사건 흐름 경계 (테스트는 가짜 흐름을 넣는다 — 실제 파이프라인·네트워크를 쓰지 않는다). */
fun interface InstallFlow {
    fun run(request: InstallRequest): Flow<InstallEvent>
}

/**
 * 설치 환경 경계. 운영 구현은 [DefaultInstallEnvironment] 를 감싸 HttpClient 수명을 쥔다
 * (CLI 는 Ktor 타입을 직접 보지 않는다 — 검증된 사실 #3).
 */
interface InstallEnvironment : AutoCloseable {
    fun flow(
        db: CompatDb,
        javaLocator: JavaLocator,
        interaction: InstallInteraction,
        profiles: LaunchProfiles,
    ): InstallFlow
}

/** 운영 구현. */
private class DefaultEnvironment(paths: DecaPaths, userAgent: String) : InstallEnvironment {
    private val delegate = DefaultInstallEnvironment(paths, userAgent)

    override fun flow(
        db: CompatDb,
        javaLocator: JavaLocator,
        interaction: InstallInteraction,
        profiles: LaunchProfiles,
    ): InstallFlow {
        val pipeline = delegate.pipeline(db, javaLocator, interaction, profiles)
        return InstallFlow { request -> pipeline.run(request) }
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * `decacross create` (DESIGN2 §2.15).
 *
 * # 불변식
 * - 명령줄 자체가 계획 확인이다 (SCP-I4). 그래도 PLAN 요약은 출력한다.
 * - ★ `--accept-eula` 는 사용자가 직접 붙였을 때만 동의로 전달된다. CLI 는 그 밖의 어떤 경로로도 동의를 만들지 않는다.
 * - `--name` 을 생략하면 [defaultServerName] (예: `paper-1.21.8`, SCP-I16).
 * - Ctrl+C: 설치 작업을 취소하고 롤백을 최대 15초 기다린 뒤 종료 코드 130.
 */
class CreateCommand(
    private val io: ConsoleIo = ConsoleIo(),
    private val hostOs: Os = currentOs(),
    private val envProvider: (Os) -> Map<String, String> = { os -> hostEnvironment(os) },
    private val pathsResolver: PathsResolver = PathsResolver.SYSTEM,
    private val dbProvider: () -> CompatDb = { DevCompatFixture.db() },
    private val profilesLoader: () -> LaunchProfilesLoad = { loadLaunchProfiles() },
    private val environmentFactory: (DecaPaths, String) -> InstallEnvironment = { paths, ua -> DefaultEnvironment(paths, ua) },
    private val javaLocatorFactory: (Map<String, String>, Os, List<Path>) -> JavaLocator =
        { env, os, excludedRoots -> SystemJavaLocator(env, os, excludedRoots) },
    private val runner: suspend (Path, LaunchSpec, CompiledConsolePatterns) -> Int =
        { dir, spec, patterns -> ServerRunner(io).run(dir, spec, patterns) },
    private val hooks: ShutdownHookRegistrar = ShutdownHookRegistrar.SYSTEM,
) : CliktCommand(name = "create") {
    private val mc by option("--mc", help = "MC 버전 라벨 (예: 1.21.8)").required()
    private val core by option("--core", help = "서버 코어 (paper|purpur|folia)")
        .enum<CoreKey> { it.name.lowercase() }
        .default(CoreKey.PAPER)
    private val ramMb by option("--ram", help = "서버 메모리 (4G / 4096M / 4096)")
        .convert { text ->
            when (val parsed = parseRamMb(text)) {
                is RamParse.Ok -> parsed.megabytes
                is RamParse.Invalid -> fail(parsed.reasonKo)
            }
        }
        .required()
    private val nameOption by option("--name", help = "서버 이름 (생략하면 코어-버전, 예: paper-1.21.8)")
    private val start by option("--start", help = "설치가 끝나면 바로 실행").flag()
    private val acceptEula by option("--accept-eula", help = "Minecraft EULA 에 동의함을 명령줄로 전달 (사용자 본인만 사용)").flag()
    private val javaOption by option("--java", help = "서버용 java 실행 파일 절대경로").path()
    private val serversDirOption by option("--servers-dir", help = "서버 폴더 루트").path()
    private val experimental by option("--experimental", help = "실험 채널 빌드도 허용").flag()
    private val motd by option("--motd", help = "server.properties 의 motd (생략하면 서버 이름)")
    private val maxPlayers by option("--max-players", help = "최대 인원").int().default(20)
    private val difficulty by option("--difficulty", help = "난이도 (peaceful|easy|normal|hard)")
        .enum<Difficulty> { it.name.lowercase() }
        .default(Difficulty.EASY)
    private val noPretouch by option("--no-pretouch", help = "-XX:+AlwaysPreTouch 를 빼고 만든다 (여유 메모리가 적을 때)").flag()

    override fun run() {
        val code = runBlocking { execute() }
        if (code != ExitCodes.OK) throw ProgramResult(code)
    }

    private suspend fun execute(): Int {
        val env = envProvider(hostOs)
        // 경로 결정과 프로파일 읽기는 디스크를 본다 (문서 폴더 탐색·리소스 읽기) — 막히는 I/O 는 IO 디스패처로
        val resolution = withContext(Dispatchers.IO) { pathsResolver.resolve(env, hostOs, serversDirOption) }
        val paths =
            when (resolution) {
                is PathsResolution.Invalid -> {
                    io.out("[실패] ${resolution.reasonKo}")
                    return ExitCodes.INPUT
                }

                is PathsResolution.Resolved -> resolution.paths
            }
        paths.serversRoot.noticeKo?.let { io.out(it) }
        val userAgent = buildDaemonUserAgent(env[UA_CONTACT_ENV] ?: DEFAULT_UA_CONTACT)
        if (userAgent == null) {
            io.out("[실패] $UA_CONTACT_ENV 값을 User-Agent 에 쓸 수 없습니다 (괄호·제어문자 불가)")
            return ExitCodes.INPUT
        }
        val profiles =
            when (val load = withContext(Dispatchers.IO) { profilesLoader() }) {
                is LaunchProfilesLoad.Invalid -> {
                    io.out("[실패] ${load.reason}")
                    return ExitCodes.UNEXPECTED
                }

                is LaunchProfilesLoad.Loaded -> load.profiles
            }
        val request = InstallRequest(
            mcLabel = mc,
            core = core,
            serverName = nameOption ?: defaultServerName(core, mc),
            ramMb = ramMb,
            allowExperimental = experimental,
            settings = ServerSettings(motd = motd, maxPlayers = maxPlayers, difficulty = difficulty),
            javaOverride = javaOption,
            preTouch = !noPretouch,
        )
        val renderer = EventRenderer(io)
        environmentFactory(paths, userAgent).use { environment ->
            val excludedRoots = listOf(paths.internalRoot.resolve("jre"))
            val flow = environment.flow(
                dbProvider(),
                javaLocatorFactory(env, hostOs, excludedRoots),
                CliInteraction(acceptEula, io),
                profiles,
            )
            when (collectInstall(flow, request, renderer)) {
                CollectOutcome.CANCELLED -> {
                    renderer.renderCancelled()
                    return ExitCodes.CANCELLED
                }

                CollectOutcome.FAILED -> return ExitCodes.UNEXPECTED

                CollectOutcome.COMPLETED -> Unit
            }
        }
        if (renderer.exitCode != ExitCodes.OK) return renderer.exitCode
        val server = renderer.installed
        if (server == null) {
            io.out("[실패] 설치 흐름이 결과 없이 끝났습니다")
            return ExitCodes.UNEXPECTED
        }
        if (!start) {
            io.out("실행하려면: decacross start ${server.name}")
            return ExitCodes.OK
        }
        val patterns = compileConsolePatterns(profiles.console)
        if (patterns == null) {
            io.out("[실패] 콘솔 인식 정규식(launch-profiles.json)이 잘못돼 서버를 띄우지 못했습니다")
            io.out("  해결: 서버는 이미 만들어졌습니다 — 런처를 다시 설치한 뒤 decacross start ${server.name}")
            return ExitCodes.UNEXPECTED
        }
        return runner(server.dir, server.launch, patterns)
    }

    /** [collectInstall] 결과. */
    private enum class CollectOutcome { COMPLETED, CANCELLED, FAILED }

    /** 설치 흐름 수집. 롤백은 파이프라인이 `NonCancellable` 로 끝낸다. */
    private suspend fun collectInstall(
        flow: InstallFlow,
        request: InstallRequest,
        renderer: EventRenderer,
    ): CollectOutcome =
        supervisorScope {
            val job = async { flow.run(request).collect { event -> renderer.render(event) } }
            val hook = Thread({
                job.cancel()
                runBlocking { withTimeoutOrNull(15.seconds) { job.join() } }
            }, "dcx-cli-install")
            hooks.add(hook)
            try {
                job.await()
                CollectOutcome.COMPLETED
            } catch (e: CancellationException) {
                // 내 코루틴이 취소된 것이면 그대로 전파한다 (취소를 삼키지 않는다)
                coroutineContext.ensureActive()
                CollectOutcome.CANCELLED
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                // ★ 사용자에게 스택 트레이스를 보여 주지 않는다: 파이프라인이 사건 대신 예외로 끝나도 한 줄로 알린다
                io.out("[실패] 예상 못 한 오류: ${e.message ?: e::class.simpleName ?: "알 수 없음"}")
                io.out("  해결: 같은 명령을 다시 실행해 보고, 계속되면 이 메시지를 그대로 알려 주세요")
                CollectOutcome.FAILED
            } finally {
                hooks.remove(hook)
            }
        }
}
