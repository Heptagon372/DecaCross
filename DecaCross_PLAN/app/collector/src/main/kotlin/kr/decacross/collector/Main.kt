package kr.decacross.collector

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 수집기 진입점.
 *
 * `--once` (기본) 는 선택한 소스를 한 번 돌고 스냅샷을 쓴다. `--loop` 는 그룹별 주기로 반복한다
 * (manifest 5분, 코어 15분, 콘텐츠 6시간, Adoptium 24시간 — 설계서 §9).
 *
 * # 불변식 (CLAUDE.md 17)
 * 모든 외부 호출은 식별 가능한 User-Agent + 연락처 ([DEFAULT_USER_AGENT], `DECACROSS_UA` 로 덮어씀).
 */
class CollectorCommand : CliktCommand(name = "collector") {
    private val once by option("--once", help = "한 번 실행 (기본)").flag(default = true)
    private val loop by option("--loop", help = "그룹별 주기로 반복 실행").flag()
    private val sources by option("--sources", help = "쉼표 구분: mojang,paper,purpur,adoptium,modrinth,hangar").default("mojang,paper")
    private val out by option("--out", help = "스냅샷(CompatFixture JSON) 출력 경로").path().default(Path.of("build", "collector", "snapshot.json"))
    private val input by option("--in", help = "기존 스냅샷 (기본: --out 이 있으면 그것)").path(mustExist = true)
    private val maxClientJars by option("--max-client-jars", help = "pack 포맷을 읽을 client.jar 최대 수").int().default(20)
    private val maxContent by option("--max-content", help = "소스당 콘텐츠 최대 수").int().default(100)
    private val maxVersionsPerContent by option("--max-versions-per-content").int().default(5)
    private val analyzeJars by option("--analyze-jars", help = "플러그인 jar 를 받아 ASM 분석 (즉시 폐기)").flag()
    private val maxJars by option("--max-jars", help = "한 번에 분석할 jar 최대 수").int().default(30)
    private val dryRun by option("--dry-run", help = "수집만 하고 파일·DB 를 쓰지 않는다").flag()

    override fun run() {
        val names = sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            SourceName.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw com.github.ajalt.clikt.core.UsageError("알 수 없는 소스: $name")
        }.toSet()
        val opts = CollectorOptions(
            sources = names,
            out = out,
            input = input,
            maxClientJars = maxClientJars,
            maxContent = maxContent,
            maxVersionsPerContent = maxVersionsPerContent,
            analyzeJars = analyzeJars,
            maxJars = maxJars,
            dryRun = dryRun,
            tmpDir = Path.of("app", "collector", ".tmp").takeIf { Files.isDirectory(Path.of("app", "collector")) }
                ?: Path.of(".tmp"),
        )
        val http = CollectorHttp()
        log.info("UA = {}", http.userAgent)
        http.use {
            val pipeline = CollectorPipeline(opts, http)
            runBlocking {
                if (loop) runLoop(pipeline, opts) else pipeline.runOnce()
            }
        }
        if (!once && !loop) log.warn("--once / --loop 둘 다 없음 — --once 로 실행했다")
    }

    /** 그룹마다 코루틴 하나. 실행은 뮤텍스로 직렬화 — 스냅샷 파일을 동시에 쓰지 않는다. 구조적 동시성 (GlobalScope 없음). */
    private suspend fun runLoop(pipeline: CollectorPipeline, opts: CollectorOptions) = coroutineScope {
        val gate = Mutex()
        for (group in SourceGroup.entries) {
            val mine = group.sources intersect opts.sources
            if (mine.isEmpty()) continue
            launch {
                while (isActive) {
                    gate.withLock {
                        runCatching { pipeline.runOnce(mine) }
                            .onFailure { log.error("{} 그룹 실패: {}", group, it.message, it) }
                    }
                    delay(group.intervalMs)
                }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CollectorCommand::class.java)
    }
}

fun main(args: Array<String>) = CollectorCommand().main(args)
