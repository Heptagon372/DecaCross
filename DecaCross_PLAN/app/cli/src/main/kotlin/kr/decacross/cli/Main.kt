package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.PackFormat
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallPipeline
import kr.decacross.daemon.install.InstallSpec
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.runtime.JavaRuntime
import kr.decacross.daemon.store.DevCompatFixture
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DecaCross : CliktCommand(name = "decacross") {
    override fun run() = Unit
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * `lookup 1.21.8` → mc / ordinal / javaMin / javaRec / rpFormat / dpFormat + 코어 최신 빌드.
 * 1주차 완료 판정 (명세 §12): `lookup 1.21.8` → `java=21, paper build=N`.
 */
class Lookup : CliktCommand(name = "lookup") {
    private val mc by argument(help = "MC 버전 라벨 (예: 1.21.8, 26.3)")
    private val core by option("--core", help = "코어 (기본 paper)").enum<CoreKey> { it.name.lowercase() }.default(CoreKey.PAPER)
    private val experimental by option("--experimental", help = "실험 채널 빌드도 표시").flag()

    override fun run() {
        val db: CompatDb = DevCompatFixture.db()
        val v = db.mcByLabel(mc)
        if (v == null) {
            echo("알 수 없는 MC 버전: $mc", err = true)
            val family = db.mcInFamily(mc)
            if (family.isNotEmpty()) echo("  같은 계열: ${family.joinToString(", ") { it.label }}", err = true)
            throw ProgramResult(2)
        }
        echo(
            "mc=${v.label} (ordinal=${v.ordinal.value}) javaMin=${v.javaMin} javaRec=${v.javaRecommended} " +
                "rpFormat=${fmt(v.rpFormat)} dpFormat=${fmt(v.dpFormat)}" +
                (if (v.isSnapshot) " [snapshot]" else ""),
        )
        val coreName = core.name.lowercase()
        val best = db.coreBuilds(core, v.ordinal, stableOnly = !experimental).firstOrNull()
        if (best == null) {
            val any = db.coreBuilds(core, v.ordinal, stableOnly = false).firstOrNull()
            if (any != null && any.channel == Channel.EXPERIMENTAL) {
                echo("$coreName build=없음 (STABLE 없음 — 실험 빌드 ${any.build} 만 존재, --experimental 로 표시)")
            } else {
                echo("$coreName build=없음 (미수집)")
            }
            return
        }
        echo(
            "$coreName build=${best.build} (${best.channel.name.lowercase()}) " +
                "size=${"%.1f".format(best.size / 1_048_576.0)}MB sha256=${best.sha256.take(12)}…",
        )
    }

    private fun fmt(pf: PackFormat?): String = pf?.toString() ?: "?(미수집)"
}

/**
 * `create --mc 1.21.8 --core paper --ram 4G --name demo [--start] [--accept-eula]`
 * 3주차 완료 판정 (명세 §12): 서버 폴더 생성 → 기동 → 콘솔에 "Done (" 출현.
 */
class Create : CliktCommand(name = "create") {
    private val mc by option("--mc", help = "MC 버전 (예: 1.21.8)").required()
    private val core by option("--core").enum<CoreKey> { it.name.lowercase() }.default(CoreKey.PAPER)
    private val ram by option("--ram", help = "RAM (예: 4G, 2048M)").default("2G")
    private val name by option("--name", help = "서버 이름 (폴더명)").required()
    private val port by option("--port").int().default(25565)
    private val start by option("--start", help = "설치 후 바로 기동").flag()
    private val acceptEula by option("--accept-eula", help = "Mojang EULA 에 동의함 (없으면 물어봄)").flag()
    private val experimental by option("--experimental", help = "STABLE 빌드가 없으면 실험 빌드 허용").flag()
    private val javaPath by option("--java", help = "서버 실행용 java 절대경로 (기본: 시스템 java 탐지)")
    private val stopAfterDone by option("--stop-after-done", help = "'Done (' 출현 후 정상 종료 (데모/CI 용)").flag()

    override fun run() = runBlocking {
        val paths = DecaPaths.detect()
        val db = DevCompatFixture.db()
        val version = db.mcByLabel(mc) ?: run {
            echo("알 수 없는 MC 버전: $mc", err = true)
            throw ProgramResult(2)
        }
        val build = db.coreBuilds(core, version.ordinal, stableOnly = true).firstOrNull()
            ?: db.coreBuilds(core, version.ordinal, stableOnly = false).firstOrNull()?.takeIf { experimental }
            ?: run {
                echo("${core.name.lowercase()} 빌드 없음 (mc=$mc). 실험 빌드를 허용하려면 --experimental", err = true)
                throw ProgramResult(2)
            }
        if (build.channel == Channel.EXPERIMENTAL) echo("⚠ 실험 채널 빌드 ${build.build} 를 사용합니다")

        // Java: 03 단계는 시스템 java. 버전이 안 맞으면 명확한 에러 (자동 설치는 04).
        val java = javaPath?.let { JavaRuntime.probe(Paths.get(it)) } ?: JavaRuntime.findSystemJava(bundledJre = paths.bundledJre)
        if (java == null) {
            echo("실행 가능한 Java 를 찾지 못했습니다. --java <경로> 로 지정하세요.", err = true)
            throw ProgramResult(3)
        }
        if (java.feature < version.javaMin) {
            echo("Java ${java.feature} (${java.exe}) 로는 $mc 를 실행할 수 없습니다 — Java ${version.javaMin} 이상 필요. --java 로 지정하세요.", err = true)
            throw ProgramResult(3)
        }
        echo("Java ${java.feature}: ${java.exe}")

        val ramMb = parseRam(ram) ?: run {
            echo("RAM 형식 오류: $ram (예: 4G, 2048M)", err = true)
            throw ProgramResult(2)
        }

        // EULA: 자동 동의 금지. 명시적으로 --accept-eula 이거나 프롬프트에서 y.
        val eula = acceptEula || askEula()
        if (!eula) {
            echo("EULA 에 동의하지 않아 설치를 진행하지 않습니다.")
            throw ProgramResult(4)
        }

        val spec = InstallSpec(name = name, mc = version, core = build, javaExe = java.exe, ramMb = ramMb, acceptEula = true, port = port)
        val pipeline = InstallPipeline(paths, kr.decacross.daemon.install.defaultFetcher())
        var installed: InstalledServer? = null
        var lastPct = -1
        pipeline.run(spec).collect { ev ->
            when (ev) {
                is InstallEvent.StageChanged -> echo("[${ev.stage}]")

                is InstallEvent.Progress -> {
                    val pct = ev.total?.let { (ev.done * 100 / it).toInt() } ?: -1
                    if (pct != lastPct && (pct % 10 == 0 || pct < 0)) {
                        lastPct = pct
                        echo("  ${ev.detailKo}: ${ev.done / 1_048_576}MB" + (ev.total?.let { "/${it / 1_048_576}MB ($pct%)" } ?: ""))
                    }
                }

                is InstallEvent.Message -> echo("  ${ev.textKo}")

                is InstallEvent.Failed -> {
                    echo("✖ ${ev.stage}: ${ev.error.messageKo}", err = true)
                    throw ProgramResult(1)
                }

                is InstallEvent.Completed -> installed = ev.server
            }
        }
        val server = installed ?: throw ProgramResult(1)
        echo("✔ 서버 생성 완료: ${server.dir}")
        echo("  런처 없이 실행: ${Paths.get(server.dir).resolve("start.bat")}")
        if (start) ConsoleRunner.run(server, stopAfterDone)
    }

    private fun askEula(): Boolean {
        echo("Minecraft 서버를 실행하려면 Mojang EULA 동의가 필요합니다: https://aka.ms/MinecraftEULA")
        echo("동의하면 eula.txt 에 eula=true 로 기록됩니다. 동의하십니까? [y/N] ", trailingNewline = false)
        val answer = readlnOrNull()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }

    companion object {
        /** "4G" → 4096, "2048M" → 2048, "4096" → 4096. */
        fun parseRam(s: String): Int? {
            val m = Regex("^(\\d+)\\s*([GgMm]?)[Bb]?$").find(s.trim()) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return when (m.groupValues[2].lowercase()) {
                "g" -> n * 1024
                else -> n
            }
        }
    }
}

class ListServers : CliktCommand(name = "list") {
    override fun run() {
        val paths = DecaPaths.detect()
        val servers = readServers(paths)
        if (servers.isEmpty()) {
            echo("서버 없음 (${paths.serversRoot})")
            return
        }
        for (s in servers) echo("${s.name.padEnd(20)} ${s.mcLabel.padEnd(8)} ${s.core.name.lowercase()}-${s.build.padEnd(6)} :${s.port}  ${s.ramMb}MB  ${s.dir}")
    }
}

class Start : CliktCommand(name = "start") {
    private val name by argument(help = "서버 이름")
    private val stopAfterDone by option("--stop-after-done").flag()

    override fun run() {
        val paths = DecaPaths.detect()
        val server = readServers(paths).firstOrNull { it.name == name } ?: run {
            echo("서버 없음: $name", err = true)
            throw ProgramResult(2)
        }
        ConsoleRunner.run(server, stopAfterDone)
    }
}

internal fun readServers(paths: DecaPaths): List<InstalledServer> {
    if (!Files.isDirectory(paths.serversRoot)) return emptyList()
    return Files.list(paths.serversRoot).use { stream ->
        stream.filter { Files.isRegularFile(it.resolve(".decacross/server.json")) }
            .map { dir: Path -> json.decodeFromString(InstalledServer.serializer(), Files.readString(dir.resolve(".decacross/server.json"))) }
            .toList()
    }.sortedBy { it.name }
}

fun main(args: Array<String>) = DecaCross().subcommands(Lookup(), Create(), ListServers(), Start()).main(args)
