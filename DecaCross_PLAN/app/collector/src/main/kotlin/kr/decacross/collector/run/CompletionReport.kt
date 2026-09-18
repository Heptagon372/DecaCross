package kr.decacross.collector.run

import kr.decacross.collector.config.CompletionThresholds
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.store.SanityLevel
import kr.decacross.collector.store.SanityResult
import kr.decacross.collector.store.TableCounts
import kr.decacross.compat.model.CoreKey
import kotlin.time.Duration

/**
 * 수집 결과 보고서 (고정폭, 한국어 라벨).
 *
 * ```
 * == 수집 결과 ==
 * MOJANG    OK       12m03s  issue.inserted=539 skip.SLOT_OVERFLOW=315
 * == 테이블 ==
 * mc_versions 539 (release 103, snapshot 436) · core_builds 5727 (PAPER 5599, FOLIA 122) · content 200 · …
 * == 02 완료 판정 ==
 * mc_versions   539 ≥ 200  OK
 * ```
 */
object CompletionReport {
    private const val MAX_WARNINGS_SHOWN = 10

    fun render(
        reports: List<SourceReport>,
        counts: TableCounts,
        thresholds: CompletionThresholds,
        durations: Map<SourceId, Duration> = emptyMap(),
    ): String = buildString {
        appendLine("== 수집 결과 ==")
        for (r in reports) appendLine(sourceLine(r, durations[r.source]))
        appendLine("== 테이블 ==")
        appendLine(tablesLine(counts))
        appendLine("== 02 완료 판정 ==")
        for (line in thresholdLines(counts, thresholds)) appendLine(line)
    }.trimEnd()

    /** 소스 한 줄 (+ 오류·경고 들여쓰기 줄). `--loop` 에서도 쓴다. */
    fun sourceLine(r: SourceReport, duration: Duration?): String = buildString {
        append(r.source.name.padEnd(10))
        append(r.status.name.padEnd(8))
        append((duration?.let(::formatDuration) ?: "-").padStart(7))
        append("  ")
        append(r.counters.entries.joinToString(" ") { (k, v) -> "$k=$v" })
        if (r.status == SourceStatus.FAILED || r.error != null) r.error?.let { append("\n    오류: ").append(it) }
        for (w in r.warnings.take(MAX_WARNINGS_SHOWN)) append("\n    경고: ").append(w)
        if (r.warnings.size > MAX_WARNINGS_SHOWN) append("\n    경고 ${r.warnings.size - MAX_WARNINGS_SHOWN}개 더")
    }.trimEnd()

    fun tablesLine(c: TableCounts): String {
        val cores = CoreKey.entries.mapNotNull { k -> c.coreBuildsByCore[k]?.let { "${k.name} $it" } }
        val coreText = if (cores.isEmpty()) "" else " (${cores.joinToString(", ")})"
        return "mc_versions ${c.mcVersions} (release ${c.mcReleases}, snapshot ${c.mcSnapshots}) · " +
            "core_builds ${c.coreBuilds}$coreText · content ${c.content} · " +
            "content_versions ${c.contentVersions} (analyzed ${c.analyzedContentVersions}) · content_deps ${c.contentDeps} · " +
            "java_runtimes ${c.javaRuntimes}"
    }

    fun thresholdLines(c: TableCounts, t: CompletionThresholds): List<String> = listOf(
        thresholdLine("mc_versions", c.mcVersions, t.mcVersions),
        thresholdLine("core_builds", c.coreBuilds, t.coreBuilds),
        thresholdLine("content", c.content, t.content),
    )

    private fun thresholdLine(name: String, actual: Long, min: Long): String {
        val ok = actual >= min
        return "${name.padEnd(12)}${actual.toString().padStart(5)} ${if (ok) "≥" else "<"} ${min.toString().padEnd(4)} ${if (ok) "OK" else "미달"}"
    }

    /** 02 완료 기준(prompts/02) 충족 여부. */
    fun thresholdsMet(c: TableCounts, t: CompletionThresholds): Boolean =
        c.mcVersions >= t.mcVersions && c.coreBuilds >= t.coreBuilds && c.content >= t.content

    fun renderSanity(results: List<SanityResult>): String = buildString {
        appendLine("== 정합성 검사 ==")
        for (r in results) {
            append(r.level.name.padEnd(5)).append(' ').append(r.name)
            if (r.detail.isNotEmpty()) append(" — ").append(r.detail)
            appendLine()
        }
        val fails = results.count { it.level == SanityLevel.FAIL }
        val warns = results.count { it.level == SanityLevel.WARN }
        append("FAIL $fails · WARN $warns · PASS ${results.size - fails - warns}")
    }

    /** `12m03s`, `1m10s`, `5s`, `0s`, `1h02m`. */
    fun formatDuration(d: Duration): String {
        val total = d.inWholeSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }
}
