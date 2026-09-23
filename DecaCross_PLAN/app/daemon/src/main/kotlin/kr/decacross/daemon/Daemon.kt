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
import kr.decacross.compat.resolve
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
import kr.decacross.dcx.evaluate
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

    /** 08: 웹 브리지가 넣은 설치 요청. 사용자가 네이티브 다이얼로그에서 승인하기 전엔 파일을 쓰지 않는다. */
    val pendingInstalls = kr.decacross.daemon.install.PendingInstalls()

    /** 대기 중인 .dcx 설치를 승인 → 설치 잡. 레시피의 변수 값은 UI 가 묻고 [vars] 로 넘긴다. */
    fun approvePending(id: String, vars: Map<String, String>, acceptEula: Boolean, ramMb: Int?): InstallJob? {
        val p = pendingInstalls.take(id) ?: return null
        val r = p.recipe
        val ev = r.evaluate(vars)
        val core = runCatching { kr.decacross.compat.model.CoreKey.valueOf(r.target.core.type.uppercase()) }.getOrDefault(kr.decacross.compat.model.CoreKey.PAPER)
        val ram = ramMb ?: r.runtime?.memory?.max?.let { parseMem(it) } ?: 2048
        val req = InstallRequest(
            name = ev.vars["SERVER_NAME"]?.takeIf { it.isNotBlank() } ?: r.name,
            mc = r.target.minecraft,
            core = core,
            ramMb = ram,
            port = r.network?.port ?: 25565,
            acceptEula = acceptEula,
            properties = ev.serverProperties,
            plugins = ev.content.filter { it.kind == "plugin" && it.slug != null && it.source != "url" }.mapNotNull { it.slug },
        )
        return submitInstall(req)
    }

    private fun parseMem(s: String): Int? {
        val m = Regex("^(\\d+)([GgMm])$").find(s.trim()) ?: return null
        val n = m.groupValues[1].toInt()
        return if (m.groupValues[2].lowercase() == "g") n * 1024 else n
    }
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
            // 07: 플러그인이 있으면 엔진으로 의존성까지 확정. 충돌이면 한국어 설명 + fix 를 그대로 전달한다.
            var plugins = emptyList<kr.decacross.compat.model.ContentVersion>()
            var javaExe = java.exe
            if (req.plugins.isNotEmpty()) {
                val outcome = resolve(
                    kr.decacross.compat.ResolveRequest(
                        mc = kr.decacross.compat.McSelector.Exact(req.mc),
                        core = req.core,
                        wants = req.plugins.map { kr.decacross.compat.Want(it, kr.decacross.compat.model.ContentKind.PLUGIN) },
                        os = currentOs(),
                        arch = currentArch(),
                        ramMb = systemMemoryMb().toInt(),
                        allowExperimental = req.allowExperimental,
                        hostOverheadMb = hostOverheadMb(),
                    ),
                    db,
                )
                when (outcome) {
                    is kr.decacross.compat.ResolveOutcome.Conflict -> {
                        val e = outcome.explanation
                        val text = buildString {
                            append(e.headlineKo)
                            e.causeChain.forEach { append("\n  · ").append(it.textKo) }
                            e.fixes.forEachIndexed { i, f -> append("\n  ").append(i + 1).append(") ").append(f.labelKo).append(if (f.recommended) " (권장)" else "") }
                        }
                        emit(InstallEvent.Failed(InstallStage.RESOLVE, InstallError.Io(text)))
                        return@submit
                    }

                    is kr.decacross.compat.ResolveOutcome.Ok -> {
                        val plan = outcome.plan
                        plugins = plan.items.map { it.content }
                        plan.items.filter { it.autoAdded }.forEach { emit(InstallEvent.Message("자동 추가: ${it.content.slug} ${it.content.version} — ${it.reason ?: "의존성"}")) }
                        plan.warnings.forEach { emit(InstallEvent.Message("⚠ ${it.textKo}")) }
                        if (plan.java.feature != mc.javaRecommended) {
                            when (val r2 = runtimeInstaller.ensureRuntime(plan.java.feature, currentOs(), currentArch())) {
                                is EnsureResult.Ok -> javaExe = r2.info.exe

                                is EnsureResult.Failed -> {
                                    emit(InstallEvent.Failed(InstallStage.RESOLVE, InstallError.Io(r2.messageKo)))
                                    return@submit
                                }
                            }
                        }
                    }
                }
            }
            val spec = InstallSpec(
                name = req.name, mc = mc, core = build, javaExe = javaExe, ramMb = req.ramMb,
                acceptEula = req.acceptEula, port = req.port, properties = req.properties, plugins = plugins,
            )
            pipeline.run(spec).collect { emit(it) }
        }
    }

    private val fixApplier = kr.decacross.daemon.diagnosis.FixApplier(registry, runtimeInstaller) { id ->
        manager.status(id)?.state?.let { it != kr.decacross.daemon.process.ServerState.STOPPED && it != kr.decacross.daemon.process.ServerState.CRASHED && it != kr.decacross.daemon.process.ServerState.CRASH_LOOP } ?: false
    }

    suspend fun applyFix(id: String, action: kr.decacross.daemon.diagnosis.FixActionDto): kr.decacross.daemon.diagnosis.FixOutcome = fixApplier.apply(id, action)

    /** POST /api/resolve — 엔진을 그대로 노출. 웹 위저드(08)도 같은 엔진을 쓴다 (판정 로직 단일화, D2). */
    fun resolveRequest(req: kr.decacross.compat.ResolveRequest): kr.decacross.compat.ResolveOutcome = resolve(req, db)

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
