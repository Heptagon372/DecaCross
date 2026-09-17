package kr.decacross.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.PackFormat
import kr.decacross.daemon.store.DevCompatFixture

class DecaCross : CliktCommand(name = "decacross") {
    override fun run() = Unit
}

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

fun main(args: Array<String>) = DecaCross().subcommands(Lookup()).main(args)
