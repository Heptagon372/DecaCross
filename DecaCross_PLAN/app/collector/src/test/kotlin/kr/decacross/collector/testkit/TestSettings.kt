package kr.decacross.collector.testkit

import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.RunMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock
import kotlin.time.Instant

/** 테스트용 임시 디렉터리 (호출자가 [deleteTree] 로 지운다). JUnit @TempDir 에 의존하지 않는다. */
fun newTempDir(prefix: String = "decacross-test-"): Path = createTempDirectory(prefix)

fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

/** 디렉터리에 남은 일반 파일 수 ("분석한 jar 가 남지 않음" 검증용). */
fun countFiles(dir: Path): Int = if (!Files.exists(dir)) 0 else Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.count().toInt() }

fun testSettings(tempDir: Path): CollectorSettings = CollectorSettings(tempDir = tempDir)

/** 고정 시계. */
class FixedClock(var now: Instant = Instant.parse("2026-09-17T00:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** 호출되면 테스트를 실패시키는 분석기 (다운로드·분석이 없어야 하는 테스트용). 조용히 Unreadable 을 기록하지 않는다. */
val NO_ANALYZER: JarAnalyzer = JarAnalyzer { jar -> throw AssertionError("테스트: 분석기가 호출되면 안 된다 ($jar)") }

fun testContext(
    http: FakeHttp,
    store: RecordingStore,
    settings: CollectorSettings,
    analyzer: JarAnalyzer = NO_ANALYZER,
    mode: RunMode = RunMode.ONCE,
    clock: Clock = FixedClock(),
): CollectContext = CollectContext(mode, http, store, settings, analyzer, clock)
