package kr.decacross.daemon

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.decacross.compat.db.InMemoryCompatDb
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.Os
import kr.decacross.daemon.api.InstallRequest
import kr.decacross.daemon.api.UiToken
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.InstallError
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallJob
import kr.decacross.daemon.install.InstallJobs
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.InstallPipeline
import kr.decacross.daemon.install.InstallSpec
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.defaultFetcher
import kr.decacross.daemon.install.defaultHttpClient
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.process.ServerManager
import kr.decacross.daemon.runtime.Cas
import kr.decacross.daemon.runtime.EnsureResult
import kr.decacross.daemon.runtime.RuntimeInstaller
import kr.decacross.daemon.store.DevCompatFixture
import kr.decacross.daemon.store.ServerRegistry
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** `%LOCALAPPDATA%/Decacross/daemon.json` — UI/CLI 가 데몬을 찾는 방법. */
@Serializable
data class DaemonInfo(val port: Int, val pid: Long, val version: String)

/**
 * 데몬 상태 묶음. 라우팅은 이 객체만 본다 (UI 라우트와 브리지 라우트가 같은 객체를 쓰되 핸들러는 공유하지 않는다).
 */
class Daemon(
    val paths: DecaPaths,
    val token: String,
    val db: InMemoryCompatDb = DevCompatFixture.db(),
    private val client: HttpClient = defaultHttpClient(),
    private val fetcher: Fetcher = defaultFetcher(),
) {
    val scope = CoroutineScope(SupervisorJob())
    val registry = ServerRegistry(paths)
    val manager = ServerManager(scope, registry)
    val cas = Cas(paths.cacheBlobs)
    val runtimeInstaller = RuntimeInstaller(paths, client, fetcher)
    val installJobs = InstallJobs(scope)
    private val pipeline = InstallPipeline(paths, fetcher, cas)
    private val json = Json { ignoreUnknownKeys = true }

    /** 설치 요청 → 잡. MC/빌드가 없으면 null (400). 런타임 확보는 잡 안에서 한다. */
    fun submitInstall(req: InstallRequest): InstallJob? {
        val mc = db.mcByLabel(req.mc) ?: return null
        val build = db.coreBuilds(req.core, mc.ordinal, stableOnly = true).firstOrNull()
            ?: db.coreBuilds(req.core, mc.ordinal, stableOnly = false).firstOrNull()?.takeIf { req.allowExperimental && it.channel == Channel.EXPERIMENTAL }
            ?: return null
        return installJobs.submit { emit ->
            emit(InstallEvent.Message("Java ${mc.javaRecommended} 런타임 확인 중"))
            val java = when (val r = runtimeInstaller.ensureRuntime(mc.javaRecommended, currentOs(), currentArch()) { done, total ->
                emit(InstallEvent.Progress(InstallStage.RESOLVE, done, total, "Temurin ${mc.javaRecommended}"))
            }) {
                is EnsureResult.Ok -> r.info

                is EnsureResult.Failed -> {
                    emit(InstallEvent.Failed(InstallStage.RESOLVE, InstallError.Io(r.messageKo)))
                    return@submit
                }
            }
            val spec = InstallSpec(
                name = req.name, mc = mc, core = build, javaExe = java.exe, ramMb = req.ramMb,
                acceptEula = req.acceptEula, port = req.port, properties = req.properties,
            )
            pipeline.run(spec).collect { emit(it) }
        }
    }

    fun casGc(): Long {
        val referenced = registry.list().flatMap { s ->
            val m = Path.of(s.dir).resolve(".decacross/manifest.json")
            if (Files.isRegularFile(m)) json.decodeFromString(InstallManifest.serializer(), Files.readString(m)).files.map { it.sha256 } else emptyList()
        }.toSet()
        return cas.gc(referenced, Duration.ofDays(30))
    }

    fun systemMemoryMb(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize?.div(1_048_576) ?: 8192

    fun daemonRssMb(): Long = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576

    /** hostOverheadMb = 데몬 실사용 (+ UI 실사용은 UI 가 /api/system 호출 시 쿼리로 더한다 — 05). */
    fun hostOverheadMb(): Int = daemonRssMb().toInt()

    suspend fun shutdown() {
        manager.stopAll()
    }

    companion object {
        const val VERSION = "0.1.0"

        fun currentOs(): Os = RuntimeInstaller.currentOs()

        fun currentArch(): Arch = RuntimeInstaller.currentArch()

        fun create(paths: DecaPaths = DecaPaths.detect()): Daemon = Daemon(paths, UiToken.writeNew(paths.appData))
    }
}
