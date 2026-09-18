package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.EulaAnswer
import kr.decacross.daemon.install.EulaNotice
import kr.decacross.daemon.install.LAUNCH_FILE_NAME
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.MINECRAFT_EULA_URL
import kr.decacross.daemon.install.PRETOUCH_FLAG
import kr.decacross.daemon.install.ServerEntry
import kr.decacross.daemon.install.encodeLaunchJson
import kr.decacross.daemon.install.findServer
import kr.decacross.daemon.install.isEulaAccepted
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.install.renderStartBat
import kr.decacross.daemon.install.renderStartSh
import kr.decacross.daemon.install.writeEulaAccepted
import kr.decacross.daemon.install.writeFileAtomically
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.paths.currentOs
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaRequirement
import kr.decacross.daemon.runtime.SystemJavaLocator
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/** 생성되는 Windows 실행 스크립트 이름 (설계서 §14). */
const val START_BAT_FILE_NAME: String = "start.bat"

/** 생성되는 POSIX 실행 스크립트 이름. */
const val START_SH_FILE_NAME: String = "start.sh"

/**
 * 서버 폴더 접근 경계 (`start` 가 쓰는 것만). 테스트는 가짜로 대체한다.
 *
 * # 불변식
 * - ★ [acceptEula] 는 사용자의 명시적 동의([EulaAnswer.Accepted])를 받은 직후에만 부른다.
 */
interface ServerAccess {
    fun find(serversRoot: Path, name: String): ServerEntry?

    fun eulaAccepted(serverDir: Path): Boolean

    fun acceptEula(serverDir: Path, channel: ConsentChannel, acceptedAt: Instant): LayoutIoResult

    companion object {
        val SYSTEM: ServerAccess = object : ServerAccess {
            override fun find(serversRoot: Path, name: String): ServerEntry? = findServer(serversRoot, name)

            override fun eulaAccepted(serverDir: Path): Boolean = isEulaAccepted(serverDir)

            // ★ DESIGN2 §4.2 규칙 8 의 허용 호출 위치 (CLI `start` 의 동의 경로)
            override fun acceptEula(
                serverDir: Path,
                channel: ConsentChannel,
                acceptedAt: Instant,
            ): LayoutIoResult = writeEulaAccepted(serverDir, channel, acceptedAt)
        }
    }
}

/**
 * `--java … --save` 가 서버 폴더의 실행 정보를 다시 쓴다 (critique windows #9):
 * `.decacross/launch.json` → `start.bat` → `start.sh` 순서로 원자적 교체.
 *
 * # 불변식
 * - 렌더러는 주입할 수 있다 (테스트는 WP-INSTALL 구현 없이 "무엇을 어디에 쓰는가" 만 검사한다).
 * - ★ 두 스크립트를 **먼저 렌더링하고** 나서 쓴다. 렌더러가 거부하면(`IllegalArgumentException`) 한 파일도 바꾸지 않는다
 *   — 그러지 않으면 launch.json 만 새 Java 로 바뀌고 start.bat 은 옛 Java 로 남는다.
 * - 하나라도 실패하면 그 실패를 그대로 돌려준다. 이미 쓴 파일은 원자적 교체라 온전한 이전/이후 내용 중 하나다.
 *
 * @throws IllegalArgumentException 렌더러가 거부한 경우 ([renderStartBat] 계약). 호출자가 사용자 오류로 바꾼다.
 */
class LaunchScriptWriter(
    private val renderBat: (LaunchSpec, Map<String, String>) -> ByteArray = { spec, env -> renderStartBat(spec, env) },
    private val renderSh: (LaunchSpec) -> ByteArray = { spec -> renderStartSh(spec) },
) {
    fun write(serverDir: Path, spec: LaunchSpec, env: Map<String, String>): LayoutIoResult {
        val batBytes = renderBat(spec, env)
        val shBytes = renderSh(spec)
        val launchFile = serverDir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME)
        // ★ 설치가 쓰는 함수 그대로 (DESIGN2 §2.8). 여기서 따로 Json 을 만들면 들여쓰기·끝 줄바꿈이 달라져
        //   설치가 쓴 파일과 `start --save` 가 다시 쓴 파일의 바이트가 갈라진다.
        val encoded = encodeLaunchJson(spec)
        val launchResult = writeFileAtomically(launchFile, encoded)
        if (launchResult is LayoutIoResult.Failed) return launchResult
        val batResult = writeFileAtomically(serverDir.resolve(START_BAT_FILE_NAME), batBytes)
        if (batResult is LayoutIoResult.Failed) return batResult
        val shResult = writeFileAtomically(serverDir.resolve(START_SH_FILE_NAME), shBytes)
        if (shResult is LayoutIoResult.Failed) return shResult
        return launchResult
    }
}

/**
 * `decacross start <이름>` (DESIGN2 §2.15).
 *
 * # 불변식
 * - ★ `eula.txt` 에 동의가 없으면 서버를 띄우지 않는다. 동의는 `--accept-eula`(사용자가 직접) 또는 프롬프트 답 `y/yes/예` 뿐이다.
 * - `--java` 는 이번 실행만 바꾸고, `--save` 가 붙어야 `.decacross/launch.json`·`start.bat`·`start.sh` 를 다시 쓴다.
 * - `--no-pretouch` 는 이번 실행만 `-XX:+AlwaysPreTouch` 를 뺀다 (파일은 그대로).
 */
class StartCommand(
    private val io: ConsoleIo = ConsoleIo(),
    private val hostOs: Os = currentOs(),
    private val envProvider: (Os) -> Map<String, String> = { os -> hostEnvironment(os) },
    private val pathsResolver: PathsResolver = PathsResolver.SYSTEM,
    private val access: ServerAccess = ServerAccess.SYSTEM,
    private val scriptWriter: LaunchScriptWriter = LaunchScriptWriter(),
    private val dbProvider: () -> CompatDb = { DevCompatFixture.db() },
    private val profilesLoader: () -> LaunchProfilesLoad = { loadLaunchProfiles() },
    private val javaLocatorFactory: (Map<String, String>, Os, List<Path>) -> JavaLocator =
        { env, os, excludedRoots -> SystemJavaLocator(env, os, excludedRoots) },
    private val javaExists: (Path) -> Boolean = { path -> Files.isRegularFile(path) },
    private val clock: Clock = Clock.System,
    private val runner: suspend (Path, LaunchSpec, CompiledConsolePatterns) -> Int =
        { dir, spec, patterns -> ServerRunner(io).run(dir, spec, patterns) },
) : CliktCommand(name = "start") {
    private val name by argument(name = "name", help = "서버 이름 (`decacross list` 의 이름)")
    private val acceptEula by option("--accept-eula", help = "Minecraft EULA 에 동의함을 명령줄로 전달 (사용자 본인만 사용)").flag()
    private val javaOption by option("--java", help = "이번 실행에 쓸 java 실행 파일 절대경로").path()
    private val save by option("--save", help = "--java 로 준 경로를 서버 폴더에 저장 (launch.json·start.bat·start.sh)").flag()
    private val serversDirOption by option("--servers-dir", help = "서버 폴더 루트").path()
    private val noPretouch by option("--no-pretouch", help = "이번 실행만 -XX:+AlwaysPreTouch 를 뺀다").flag()

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
        val entry = withContext(Dispatchers.IO) { access.find(paths.serversRoot.path, name) }
        if (entry == null) {
            io.out("[실패] 서버를 찾을 수 없습니다: $name")
            io.out("  해결: `decacross list` 로 이름을 확인하세요")
            return ExitCodes.SERVER_STATE
        }
        val stored = entry.launch
        if (stored == null) {
            io.out("[실패] 실행 정보(.decacross/${LAUNCH_FILE_NAME})를 읽을 수 없습니다: ${entry.problemKo ?: "파일 없음"}")
            io.out("  해결: 서버를 다시 만들거나 백업에서 launch.json 을 되살리세요")
            return ExitCodes.SERVER_STATE
        }
        var spec = stored
        val override = javaOption
        if (override != null) {
            val applied = applyJavaOverride(entry, spec, override, env, paths)
            spec = applied.spec ?: return applied.exitCode
        }
        // 저장된 경로가 이 OS 에서 경로로 성립하지 않을 수도 있다 (다른 PC 에서 복사한 서버 폴더)
        val javaFile = try {
            Path.of(spec.javaPath)
        } catch (e: InvalidPathException) {
            null
        }
        if (javaFile == null || !withContext(Dispatchers.IO) { javaExists(javaFile) }) {
            io.out("[실패] Java 실행 파일이 없습니다: ${spec.javaPath}")
            io.out("  해결: decacross start $name --java <java 실행 파일 경로> --save")
            return ExitCodes.SERVER_STATE
        }
        val eulaCode = ensureEula(entry)
        if (eulaCode != ExitCodes.OK) return eulaCode
        if (noPretouch) spec = spec.copy(jvmFlags = spec.jvmFlags - PRETOUCH_FLAG)
        val profiles =
            when (val load = withContext(Dispatchers.IO) { profilesLoader() }) {
                is LaunchProfilesLoad.Invalid -> {
                    io.out("[실패] ${load.reason}")
                    return ExitCodes.UNEXPECTED
                }

                is LaunchProfilesLoad.Loaded -> load.profiles
            }
        val patterns = compileConsolePatterns(profiles.console)
        if (patterns == null) {
            io.out("[실패] 콘솔 인식 정규식(launch-profiles.json)이 잘못됐습니다")
            io.out("  해결: 런처를 다시 설치한 뒤 같은 명령을 실행하세요")
            return ExitCodes.UNEXPECTED
        }
        return runner(entry.dir, spec, patterns)
    }

    /** `--java` 적용 결과: [spec] 이 null 이면 [exitCode] 로 끝낸다. */
    private data class JavaOverrideResult(val spec: LaunchSpec?, val exitCode: Int)

    private suspend fun applyJavaOverride(
        entry: ServerEntry,
        spec: LaunchSpec,
        override: Path,
        env: Map<String, String>,
        paths: DecaPaths,
    ): JavaOverrideResult {
        if (!save) return JavaOverrideResult(spec.copy(javaPath = override.toAbsolutePath().toString()), ExitCodes.OK)
        val manifest = entry.manifest
        if (manifest == null) {
            io.out("[실패] 설치 기록(.decacross/manifest.json)이 없어 필요한 Java 버전을 알 수 없습니다")
            io.out("  해결: --save 없이 --java 만 주어 이번 실행에만 적용하세요")
            return JavaOverrideResult(null, ExitCodes.INPUT)
        }
        val mc = dbProvider().mcByLabel(manifest.mcLabel)
        if (mc == null) {
            io.out("[실패] 알 수 없는 마인크래프트 버전: ${manifest.mcLabel}")
            io.out("  해결: 호환성 데이터를 갱신한 뒤 다시 시도하세요")
            return JavaOverrideResult(null, ExitCodes.INPUT)
        }
        val locator = javaLocatorFactory(env, hostOs, listOf(paths.internalRoot.resolve("jre")))
        val requirement = JavaRequirement(mc.javaMin, mc.javaRecommended)
        val selected =
            when (val result = locator.locate(requirement, override)) {
                is JavaLocateResult.NotFound -> {
                    io.out("[실패] 준 경로의 Java 를 쓸 수 없습니다 (Java ${mc.javaMin} 이상 필요)")
                    for (candidate in result.candidates) {
                        io.out("  후보: ${candidate.path} -> ${candidate.feature ?: "?"} ${candidate.problemKo ?: ""}")
                    }
                    io.out("  해결: Java ${mc.javaRecommended} 의 java 실행 파일 절대경로를 주세요")
                    return JavaOverrideResult(null, ExitCodes.INPUT)
                }

                is JavaLocateResult.Found -> result.selection
            }
        for (warning in selected.warningsKo) io.out("[주의] $warning")
        val updated = spec.copy(javaPath = selected.javaPath.toString(), javaFeature = selected.feature)
        // 렌더러 계약 (daemon install/Config.kt): 사용자가 손댈 수 있는 launch.json 의 토큰(jvmFlags·jarFileName·serverArgs)이
        // 스크립트에 들어갈 수 없으면 IllegalArgumentException 이다. 스택 트레이스 대신 고칠 방법을 알려 준다.
        val written = try {
            withContext(Dispatchers.IO) { scriptWriter.write(entry.dir, updated, env) }
        } catch (e: IllegalArgumentException) {
            io.out("[실패] 실행 스크립트를 만들 수 없습니다: ${e.message ?: e.toString()}")
            io.out("  해결: $META_DIR_NAME/$LAUNCH_FILE_NAME 의 jvmFlags·jarFileName 을 ASCII 로 고치거나 서버를 다시 만드세요")
            return JavaOverrideResult(null, ExitCodes.INPUT)
        }
        if (written is LayoutIoResult.Failed) {
            io.out("[실패] 실행 정보를 저장하지 못했습니다: ${written.detail}")
            return JavaOverrideResult(null, ExitCodes.TRANSFER)
        }
        io.out("[완료] Java 경로를 저장했습니다: ${updated.javaPath}")
        return JavaOverrideResult(updated, ExitCodes.OK)
    }

    private suspend fun ensureEula(entry: ServerEntry): Int {
        if (withContext(Dispatchers.IO) { access.eulaAccepted(entry.dir) }) return ExitCodes.OK
        val notice = EulaNotice(MINECRAFT_EULA_URL, entry.name, entry.manifest?.mcLabel ?: "?")
        return when (val answer = CliInteraction(acceptEula, io).requestEulaConsent(notice)) {
            is EulaAnswer.Declined -> {
                io.out("[실패] EULA 에 동의하지 않아 서버를 실행하지 않습니다 (${answer.reasonKo})")
                io.out("  해결: $MINECRAFT_EULA_URL 를 읽고 동의한다면 프롬프트에 y 를 입력하세요")
                ExitCodes.EULA_DECLINED
            }

            is EulaAnswer.Accepted -> {
                val written = withContext(Dispatchers.IO) { access.acceptEula(entry.dir, answer.channel, clock.now()) }
                if (written is LayoutIoResult.Failed) {
                    io.out("[실패] eula.txt 를 쓰지 못했습니다: ${written.detail}")
                    ExitCodes.TRANSFER
                } else {
                    io.out("[완료] EULA 동의를 기록했습니다")
                    ExitCodes.OK
                }
            }
        }
    }
}
