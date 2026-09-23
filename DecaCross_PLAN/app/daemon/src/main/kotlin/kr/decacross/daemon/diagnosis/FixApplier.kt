package kr.decacross.daemon.diagnosis

import kotlinx.serialization.json.Json
import kr.decacross.analysis.readJarMeta
import kr.decacross.daemon.install.Config
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.runtime.EnsureResult
import kr.decacross.daemon.runtime.RuntimeInstaller
import kr.decacross.daemon.store.ServerRegistry
import java.nio.file.Files
import java.nio.file.Path

sealed interface FixOutcome {
    data class Applied(val messageKo: String, val restartRequired: Boolean) : FixOutcome

    data class Rejected(val reasonKo: String) : FixOutcome

    data class Unsupported(val reasonKo: String) : FixOutcome
}

/**
 * 진단 카드의 fix 를 실제로 수행한다. 서버가 정지 상태일 때만 파일을 건드린다.
 *
 * - ChangeJava(feature): 런타임 확보 → server.json javaExe 교체 → start 스크립트 재생성
 * - SuggestPort(port): server.properties `server-port` + server.json
 * - DisablePlugin(name|package): plugins 폴더의 jar 중 이름 또는 main 패키지가 맞는 것을 `.jar.disabled` 로
 */
class FixApplier(
    private val registry: ServerRegistry,
    private val runtimeInstaller: RuntimeInstaller,
    private val isRunning: (String) -> Boolean,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun apply(id: String, action: FixActionDto): FixOutcome {
        val server = registry.get(id) ?: return FixOutcome.Rejected("서버 없음: $id")
        if (isRunning(id)) return FixOutcome.Rejected("서버를 먼저 정지하세요")
        val dir = Path.of(server.dir)
        return when (action.type) {
            "ChangeJava" -> {
                val feature = action.arg?.toIntOrNull() ?: return FixOutcome.Rejected("Java 버전 인자가 없습니다")
                when (val r = runtimeInstaller.ensureRuntime(feature, RuntimeInstaller.currentOs(), RuntimeInstaller.currentArch())) {
                    is EnsureResult.Failed -> FixOutcome.Rejected(r.messageKo)

                    is EnsureResult.Ok -> {
                        val updated = server.copy(javaExe = r.info.exe.toString())
                        save(dir, updated)
                        Config.writeStartScripts(dir, updated.javaExe, updated.ramMb, updated.coreJar, updated.name)
                        FixOutcome.Applied("Java $feature (${r.info.exe}) 로 전환했습니다", restartRequired = true)
                    }
                }
            }

            "SuggestPort" -> {
                val port = action.arg?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return FixOutcome.Rejected("포트 인자가 잘못됐습니다")
                val props = dir.resolve("server.properties")
                val lines = if (Files.isRegularFile(props)) Files.readAllLines(props) else emptyList()
                val out = lines.filterNot { it.startsWith("server-port=") } + "server-port=$port"
                Files.write(props, out)
                save(dir, server.copy(port = port))
                FixOutcome.Applied("포트를 $port 로 바꿨습니다", restartRequired = true)
            }

            "DisablePlugin" -> {
                val key = action.arg?.trim()?.takeIf { it.isNotEmpty() } ?: return FixOutcome.Rejected("플러그인 이름이 없습니다")
                val plugins = dir.resolve("plugins")
                if (!Files.isDirectory(plugins)) return FixOutcome.Rejected("plugins 폴더가 없습니다")
                val target = Files.list(plugins).use { s -> s.filter { it.toString().endsWith(".jar") }.toList() }
                    .firstOrNull { jar -> matchesPlugin(jar, key) }
                    ?: return FixOutcome.Rejected("'$key' 에 해당하는 플러그인 jar 를 찾지 못했습니다")
                Files.move(target, target.resolveSibling(target.fileName.toString() + ".disabled"))
                FixOutcome.Applied("${target.fileName} 을 비활성화했습니다 (.disabled)", restartRequired = true)
            }

            "ShowEulaDialog" -> FixOutcome.Unsupported("EULA 동의는 UI 에서 사용자가 직접 체크합니다")

            "InstallDependency" -> FixOutcome.Unsupported("의존 플러그인 자동 설치는 07 단계(솔버 통합)에서 연결됩니다")

            else -> FixOutcome.Unsupported("'${action.type}' 은 안내만 제공합니다")
        }
    }

    private fun matchesPlugin(jar: Path, key: String): Boolean {
        val file = jar.fileName.toString()
        if (file.contains(key, ignoreCase = true)) return true
        val meta = runCatching { readJarMeta(jar) }.getOrNull() ?: return false
        if (meta.name.equals(key, ignoreCase = true)) return true
        val main = meta.main ?: return false
        return main.startsWith(key) || key.startsWith(main.substringBeforeLast('.'))
    }

    private fun save(dir: Path, server: InstalledServer) {
        Files.writeString(dir.resolve(".decacross/server.json"), json.encodeToString(InstalledServer.serializer(), server))
    }
}
