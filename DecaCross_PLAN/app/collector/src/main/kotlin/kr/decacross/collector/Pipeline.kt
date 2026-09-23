package kr.decacross.collector

import kr.decacross.collector.analysis.ANALYZER_VERSION
import kr.decacross.collector.analysis.JarAnalyzer
import kr.decacross.collector.db.PostgresSink
import kr.decacross.collector.sources.AdoptiumSource
import kr.decacross.collector.sources.HangarSource
import kr.decacross.collector.sources.ManifestEntry
import kr.decacross.collector.sources.ModrinthSource
import kr.decacross.collector.sources.MojangSource
import kr.decacross.collector.sources.PaperSource
import kr.decacross.collector.sources.PurpurSource
import kr.decacross.collector.sources.toStub
import kr.decacross.compat.db.CompatFixture
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 소스 이름 (CLI `--sources`). */
enum class SourceName { MOJANG, PAPER, PURPUR, ADOPTIUM, MODRINTH, HANGAR }

/** `--loop` 그룹과 주기 (설계서 §9). */
enum class SourceGroup(val sources: Set<SourceName>, val intervalMs: Long) {
    MANIFEST(setOf(SourceName.MOJANG), 5 * 60_000L),
    CORES(setOf(SourceName.PAPER, SourceName.PURPUR), 15 * 60_000L),
    CONTENT(setOf(SourceName.MODRINTH, SourceName.HANGAR), 6 * 3_600_000L),
    RUNTIMES(setOf(SourceName.ADOPTIUM), 24 * 3_600_000L),
}

data class CollectorOptions(
    val sources: Set<SourceName>,
    val out: Path,
    val input: Path?,
    val maxClientJars: Int = 20,
    val maxContent: Int = 100,
    val maxVersionsPerContent: Int = 5,
    val analyzeJars: Boolean = false,
    val maxJars: Int = 30,
    val dryRun: Boolean = false,
    val tmpDir: Path = Path.of("app", "collector", ".tmp"),
    val databaseUrl: String? = System.getenv("DATABASE_URL"),
)

/** javaVersion 이 없는 아주 오래된 버전의 대체값. 명세 §2 javaMin 후보 중 최솟값. */
private const val JAVA_MIN_FALLBACK = 8

/**
 * 수집 파이프라인. 한 번의 [runOnce] = (`--in` 스냅샷) + 선택한 소스 → 병합 → 스냅샷/보고서 (+ Postgres).
 *
 * 병합 규칙:
 * - mcVersions: 라벨 기준. 기존 행의 ordinal 은 절대 바뀌지 않는다. 새로 읽은 pack 포맷·protocol 만 덧입힌다.
 *   manifest 에서 사라진 라벨도 지우지 않는다 (append-only 데이터).
 * - coreBuilds: (core, mc, build) 기준, 새 값 우선.
 * - content: slug 기준. Modrinth 와 Hangar 가 같은 slug 면 Modrinth 를 남기고 Hangar 는 건너뛴다 (보고서에 기록).
 * - contentVersions: (slug, version) 기준. 새 메타가 jar 분석 결과(sha256/javaMajor/apiVersion/PROVIDES)를 갖고 있지 않으면
 *   기존 분석 결과를 보존한다.
 */
class CollectorPipeline(
    private val opts: CollectorOptions,
    private val http: CollectorHttp,
    private val mojang: MojangSource = MojangSource(http),
    private val paper: PaperSource = PaperSource(http),
    private val purpur: PurpurSource = PurpurSource(http),
    private val adoptium: AdoptiumSource = AdoptiumSource(http),
    private val modrinth: ModrinthSource = ModrinthSource(http),
    private val hangar: HangarSource = HangarSource(http),
) {
    suspend fun runOnce(sources: Set<SourceName> = opts.sources): CollectorReport {
        val startedAt = Instant.now()
        val inputPath = opts.input ?: opts.out.takeIf { Files.isRegularFile(it) }
        val existing = inputPath?.let(::readSnapshot) ?: CompatFixture()
        if (inputPath != null) log.info("기존 스냅샷 {} (mc={}, cores={}, content={})", inputPath, existing.mcVersions.size, existing.coreBuilds.size, existing.content.size)

        val notes = ArrayList<String>()
        val javaFallback = ArrayList<String>()
        val packFilled = ArrayList<String>()
        val coreSkipped = ArrayList<String>()
        val contentSkipped = ArrayList<String>()
        val jarsAnalyzed = ArrayList<String>()
        var ordinalOmitted: List<String> = emptyList()
        var runtimes = 0

        // ── 1. MC 버전 ────────────────────────────────────────────────────────
        val mcByLabel = LinkedHashMap<String, McVersion>(existing.mcVersions.associateBy { it.label })
        if (SourceName.MOJANG in sources) {
            val manifest = when (val r = mojang.manifest()) {
                is HttpOutcome.Failed -> {
                    log.error("Mojang manifest 실패: {}", r.message)
                    null
                }

                is HttpOutcome.Ok -> r.value
            }
            if (manifest != null) {
                val entries = manifest.versions.mapNotNull { e -> e.toStub()?.let { it to e } }
                val assignment = assignOrdinals(mcByLabel.values.map { ExistingOrdinal(it.label, it.ordinal, it.isSnapshot) }, entries.map { it.first })
                ordinalOmitted = assignment.omitted
                notes += assignment.notes.map { "ordinals: $it" }

                val entryByLabel: Map<String, ManifestEntry> = entries.associate { it.first.label to it.second }
                val newLabels = entries.map { it.first.label }.filter { it in assignment.ordinals && it !in mcByLabel }
                log.info("Mojang: manifest {}개 (release+snapshot), 서수 있음 {}개, 신규 {}개", entries.size, assignment.ordinals.size, newLabels.size)
                val rawByLabel = HashMap<String, McRaw>()
                for (label in newLabels) {
                    val entry = entryByLabel[label] ?: continue
                    val raw = mojang.collect(entry) ?: continue
                    rawByLabel[label] = raw
                    val ordinal = assignment.ordinals[label] ?: continue
                    if (raw.javaMajor == null) javaFallback += label
                    mcByLabel[label] = raw.toMcVersion(ordinal)
                }

                // pack 포맷: 포맷이 비어 있는 버전 최신순, 캡만큼 client.jar 를 읽는다
                val needFormats = mcByLabel.values
                    .filter { it.rpFormat == null || it.dpFormat == null }
                    .sortedByDescending { it.releasedAt }
                    .take(opts.maxClientJars)
                val candidates = ArrayList<McRaw>()
                for (v in needFormats) {
                    val raw = rawByLabel[v.label]
                        ?: entryByLabel[v.label]?.let { mojang.collect(it) }
                        ?: continue
                    if (raw.clientUrl != null) candidates += raw
                }
                val formats = mojang.fillPackFormats(candidates, opts.tmpDir, opts.maxClientJars)
                for ((label, f) in formats) {
                    val v = mcByLabel[label] ?: continue
                    mcByLabel[label] = v.copy(
                        rpFormat = f.rp ?: v.rpFormat,
                        dpFormat = f.dp ?: v.dpFormat,
                        protocol = f.protocol ?: v.protocol,
                    )
                    packFilled += label
                    f.notes.forEach { notes += "pack $label: $it" }
                }
            }
        }
        val ordinals: Map<String, McOrdinal> = mcByLabel.mapValues { it.value.ordinal }

        // ── 2. 코어 빌드 ──────────────────────────────────────────────────────
        val builds = LinkedHashMap<Triple<CoreKey, McOrdinal, String>, CoreBuild>()
        existing.coreBuilds.forEach { builds[it.key()] = it }
        if (SourceName.PAPER in sources) paper.collect(ordinals, coreSkipped).forEach { builds[it.key()] = it }
        if (SourceName.PURPUR in sources) purpur.collect(ordinals, coreSkipped).forEach { builds[it.key()] = it }

        // ── 3. Adoptium (보고용) ───────────────────────────────────────────────
        if (SourceName.ADOPTIUM in sources) {
            val entries = adoptium.collect()
            runtimes = entries.size
            if (!opts.dryRun) writeJavaRuntimes(entries, opts.out.resolveSibling("java-runtimes.json"))
        }

        // ── 4. 콘텐츠 ─────────────────────────────────────────────────────────
        val contents = LinkedHashMap(existing.content.associateBy { it.slug })
        val contentMeta = HashMap<String, ContentRaw>()
        val versions = LinkedHashMap(existing.contentVersions.associateBy { it.slug to it.version })
        fun absorb(sourceLabel: String, c: kr.decacross.collector.sources.ContentCollection) {
            notes += c.notes
            val accepted = HashSet<String>()
            for (raw in c.contents) {
                val prev = contents[raw.content.slug]
                if (prev != null && prev.source != raw.content.source) {
                    contentSkipped += "$sourceLabel:${raw.content.slug} (이미 ${prev.source} 가 같은 slug)"
                    continue
                }
                contents[raw.content.slug] = raw.content
                contentMeta[raw.content.slug] = raw
                accepted += raw.content.slug
            }
            for (cv in c.versions) {
                if (cv.slug !in accepted) continue
                val key = cv.slug to cv.version
                versions[key] = versions[key]?.let { old -> preserveAnalysis(old, cv) } ?: cv
            }
        }
        if (SourceName.MODRINTH in sources) absorb("modrinth", modrinth.collect(ordinals, opts.maxContent, opts.maxVersionsPerContent))
        if (SourceName.HANGAR in sources) absorb("hangar", hangar.collect(ordinals, opts.maxContent, opts.maxVersionsPerContent))

        // ── 5. jar 분석 (선택) ────────────────────────────────────────────────
        val state = readAnalysisState(opts.out)
        val analyzed = LinkedHashMap(state.analyzed)
        if (opts.analyzeJars) {
            val analyzer = JarAnalyzer(http, opts.tmpDir, contents.keys)
            val targets = versions.values
                .filter { it.fileUrl != null && analyzed[analysisKey(it)] != ANALYZER_VERSION }
                .take(opts.maxJars)
            log.info("jar 분석 대상 {}개 (analyzer={})", targets.size, ANALYZER_VERSION)
            for (cv in targets) {
                val url = cv.fileUrl ?: continue
                val r = analyzer.analyze(url) ?: continue
                versions[cv.slug to cv.version] = analyzer.apply(cv, r)
                analyzed[analysisKey(cv)] = ANALYZER_VERSION
                jarsAnalyzed += analysisKey(cv)
                r.notes.forEach { notes += "jar ${analysisKey(cv)}: $it" }
            }
        }

        // ── 6. 출력 ───────────────────────────────────────────────────────────
        val fixture = CompatFixture(
            mcVersions = mcByLabel.values.sortedBy { it.ordinal },
            coreBuilds = builds.values.toList(),
            content = contents.values.toList(),
            contentVersions = versions.values.toList(),
            confidence = existing.confidence,
        )
        var postgres: String? = null
        if (!opts.dryRun) {
            writeSnapshot(fixture, opts.out)
            writeAnalysisState(opts.out, AnalysisState(analyzed))
            val dbUrl = opts.databaseUrl
            if (!dbUrl.isNullOrBlank()) {
                postgres = runCatching {
                    PostgresSink(dbUrl).use { it.upsertAll(fixture, contentMeta) }
                    "ok"
                }
                    .getOrElse { "실패: ${it.message}" }
                log.info("Postgres: {}", postgres)
            }
        }
        val report = CollectorReport(
            startedAt = startedAt.toString(),
            finishedAt = Instant.now().toString(),
            sources = sources.map { it.name.lowercase() },
            dryRun = opts.dryRun,
            counts = CollectorReport.Counts(
                mcVersions = fixture.mcVersions.size,
                mcReleases = fixture.mcVersions.count { !it.isSnapshot },
                mcSnapshots = fixture.mcVersions.count { it.isSnapshot },
                coreBuilds = fixture.coreBuilds.size,
                content = fixture.content.size,
                contentVersions = fixture.contentVersions.size,
                redistributable = fixture.content.count { it.redistributable },
            ),
            javaMinFallback = javaFallback,
            ordinalOmitted = ordinalOmitted,
            packFormatsFilled = packFilled,
            coreVersionsSkipped = coreSkipped,
            contentSkipped = contentSkipped,
            notes = notes,
            jarsAnalyzed = jarsAnalyzed,
            javaRuntimes = runtimes,
            postgres = postgres,
        )
        if (!opts.dryRun) writeReport(report, opts.out.resolveSibling("collector-report.json"))
        log.info(
            "완료: mcVersions={} (release {} / snapshot {}), coreBuilds={}, content={}, contentVersions={}, redistributable={}{}",
            report.counts.mcVersions, report.counts.mcReleases, report.counts.mcSnapshots, report.counts.coreBuilds,
            report.counts.content, report.counts.contentVersions, report.counts.redistributable, if (opts.dryRun) " [dry-run]" else "",
        )
        return report
    }

    private fun CoreBuild.key() = Triple(core, mc, build)

    /** 새 메타에 없는 분석 결과(sha256·javaMajor·apiVersion·PROVIDES 의존)는 기존 것을 유지한다. */
    private fun preserveAnalysis(old: ContentVersion, new: ContentVersion): ContentVersion {
        val provides = old.deps.filter { it.kind == DepKind.PROVIDES }
        val newTargets = new.deps.map { it.kind to it.target }.toSet()
        return new.copy(
            sha256 = new.sha256 ?: old.sha256,
            javaMajor = new.javaMajor ?: old.javaMajor,
            apiVersion = new.apiVersion ?: old.apiVersion,
            deps = new.deps + provides.filter { (it.kind to it.target) !in newTargets },
        )
    }

    private fun McRaw.toMcVersion(ordinal: McOrdinal): McVersion {
        val javaMin = javaMajor ?: JAVA_MIN_FALLBACK.also { log.info("{}: javaVersion 없음 → javaMin={} 로 대체", label, it) }
        return McVersion(
            ordinal = ordinal,
            label = label,
            releasedAt = releasedAt,
            isSnapshot = isSnapshot,
            javaMin = javaMin,
            // 권장 = 최소. 더 높은 LTS 를 권하려면 "그 MC 가 그 Java 에서 도는가" 데이터가 필요하고, 그건 하드코딩이다.
            javaRecommended = javaMin,
            rpFormat = rpFormat,
            dpFormat = dpFormat,
            protocol = protocol,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(CollectorPipeline::class.java)
    }
}
