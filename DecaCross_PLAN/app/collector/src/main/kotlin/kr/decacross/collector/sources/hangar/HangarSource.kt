package kr.decacross.collector.sources.hangar

import kr.decacross.collector.config.MIB
import kr.decacross.collector.content.AnalysisTarget
import kr.decacross.collector.content.CapabilityRulesLoader
import kr.decacross.collector.content.ContentPipeline
import kr.decacross.collector.content.DepAccumulator
import kr.decacross.collector.content.DownloadBudget
import kr.decacross.collector.content.Fetched
import kr.decacross.collector.content.LicensePolicy
import kr.decacross.collector.content.McLabelMapper
import kr.decacross.collector.content.PublishedAtParser
import kr.decacross.collector.content.VersionSelection
import kr.decacross.collector.content.effectiveAnalyzerVersion
import kr.decacross.collector.content.encSeg
import kr.decacross.collector.content.getJson
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.ContentRow
import kr.decacross.collector.store.ContentUpsertResult
import kr.decacross.collector.store.ContentVersionRow
import kr.decacross.collector.store.DepRow
import kr.decacross.collector.store.StoredVersion
import kr.decacross.collector.store.VersionMeta
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.Source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Hangar v1 (PAPER 플랫폼) 인덱스 → content / content_versions / content_deps (+ 선택 버전 jar 분석 후 즉시 삭제). */
class HangarSource : CollectorSource {
    override val id: SourceId = SourceId.HANGAR
    override val interval: Duration = 6.hours
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = HangarRun(ctx, ReportBuilder(id)).run()
}

/** 1패스에서 메타데이터를 기록한 프로젝트 (2패스 분석 대상). */
private class HgWritten(
    /** 기록한 버전 (PAPER 다운로드 항목이 있는 것). */
    val versions: List<HgVersion>,
    val ids: Map<String, Long>,
    val stored: Map<String, StoredVersion>,
)

/** 의존 대상 projectId → slug 조회 결과 (실행 안에서 캐시). */
private sealed interface SlugLookup {
    data class Found(val slug: String) : SlugLookup

    /** 404 — 확정적 부재. */
    data object Absent : SlugLookup

    /** 그 밖의 실패 — 부재인지 알 수 없다. */
    data class Failed(val detail: String) : SlugLookup
}

/**
 * `HangarSource.collect` 한 번 (§10.5).
 *
 * # 불변식
 * - `file_url` 에는 Hangar 가 직접 호스팅하는 `downloadUrl` 만 쓴다. 외부 링크(`externalUrl`)는 jar 가 아니므로 저장·다운로드하지 않는다 (D56).
 * - 플러그인 이름을 slug 로 쓰지 않는다 (D28). slug 조회가 404 가 아닌 이유로 실패하면 그 프로젝트의 버전 기록을 미룬다.
 * - 2패스: 메타데이터를 전부 쓴 뒤 분석한다. 요청·다운로드는 순차 실행한다.
 */
private class HangarRun(private val ctx: CollectContext, private val report: ReportBuilder) {
    private val settings = ctx.settings.content
    private val base = settings.hangarBaseUrl.trimEnd('/')
    private val dates = PublishedAtParser(report)
    private val slugCache = HashMap<Long, SlugLookup>()

    suspend fun run(): SourceReport {
        val policy = LicensePolicy.load()
        val analyzerVersion = effectiveAnalyzerVersion(CapabilityRulesLoader.load())

        // 1. 프로젝트 목록 (다운로드 내림차순, 페이지당 최대 50)
        val projects = LinkedHashMap<Long, HgProject>()
        var offset = 0
        while (offset < settings.hangarTop) {
            val limit = minOf(PROJECT_PAGE, settings.hangarTop - offset)
            val url = "$base/api/v1/projects?platform=$HANGAR_PLATFORM&sort=-downloads&limit=$limit&offset=$offset"
            val page = when (val r = ctx.http.getJson<HgProjects>(url, maxBytes = PROJECT_LIST_MAX_BYTES)) {
                is Fetched.Ok -> r.value.result

                is Fetched.Failed -> {
                    if (offset == 0) return report.build(SourceStatus.FAILED, "Hangar 프로젝트 목록 실패: ${r.detail}")
                    report.inc("projects.pageFailed")
                    report.warn("Hangar 프로젝트 목록 페이지 실패 (offset=$offset): ${r.detail}")
                    break
                }
            }
            report.inc("projects.pages")
            for (p in page) {
                if (projects.putIfAbsent(p.id, p) != null) report.inc("projects.duplicate")
            }
            if (page.isEmpty()) break
            offset += limit
        }
        report.inc("projects.listed", projects.size.toLong())

        // 2. 1패스 — content·버전 메타데이터
        val mapper = McLabelMapper(ctx.store.mcIndex())
        val written = ArrayList<HgWritten>()
        for (p in projects.values.take(settings.hangarTop)) {
            writeProject(p, mapper, policy)?.let { written += it }
        }

        // 3. 2패스 — 호스팅된 버전만 분석
        if (settings.analyzePerProject > 0) {
            val budget = DownloadBudget(settings.maxDownloadBytesPerCycle, settings.maxJarBytes)
            val pipeline = ContentPipeline(ctx, report, budget, analyzerVersion)
            for (w in written) {
                val selected = VersionSelection.hangar(w.versions, settings.analyzePerProject)
                if (selected.isEmpty()) {
                    if (w.versions.isNotEmpty()) report.inc("analysis.externalSkipped")
                    continue
                }
                for (v in selected) {
                    val d = v.paperDownload() ?: continue
                    val url = d.downloadUrl ?: continue
                    val versionId = w.ids[v.name] ?: continue
                    val sha = sha256Of(d)
                    pipeline.analyze(
                        AnalysisTarget(
                            contentVersionId = versionId,
                            storedAnalyzerVersion = w.stored[v.name]?.analyzerVersion,
                            url = url,
                            size = d.fileInfo?.sizeBytes,
                            knownSha256 = sha,
                            expected = if (sha != null) mapOf(DigestAlgo.SHA256 to sha) else emptyMap(),
                        ),
                    )
                }
            }
        }

        // 4. 상태
        val partial = PARTIAL_COUNTERS.any { report.count(it) > 0 }
        return report.build(if (partial) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    /** 2a–2d. 슬러그 충돌·버전 목록 실패·의존 대상 조회 실패로 미뤄지면 null. */
    private suspend fun writeProject(p: HgProject, mapper: McLabelMapper, policy: LicensePolicy): HgWritten? {
        // a. content 행
        val license = policy.hangar(p.settings?.license?.type, p.settings?.license?.name)
        val row = ContentRow(
            source = Source.HANGAR,
            sourceId = p.id.toString(),
            slug = p.namespace.slug,
            name = p.name,
            kind = ContentKind.PLUGIN,
            license = license.license,
            redistributable = license.redistributable,
            author = p.namespace.owner,
            downloads = p.stats.downloads,
            iconUrl = p.avatarUrl,
            description = p.description,
            pageUrl = "$PAGE_BASE/${encSeg(p.namespace.owner)}/${encSeg(p.namespace.slug)}",
        )
        val contentId = when (val r = ctx.store.upsertContent(row)) {
            is ContentUpsertResult.Stored -> {
                report.inc(if (r.inserted) "content.inserted" else "content.updated")
                r.id
            }

            is ContentUpsertResult.SlugConflict -> {
                report.inc("content.slugConflict")
                report.warn("slug 충돌로 건너뜀: ${r.detail}")
                return null
            }
        }

        // b. 버전 목록
        val fetched = fetchVersions(p, contentId) ?: return null

        // c. 버전 행 + 의존성
        val paperVersions = fetched.filter { v ->
            (v.paperDownload() != null).also { if (!it) report.inc("versions.noPaperDownload") }
        }

        // 의존 대상 slug 를 먼저 전부 조회한다. 404 가 아닌 실패가 하나라도 있으면 이 프로젝트의 버전 기록을 미룬다
        // (일시 실패를 "대상 없음"으로 착각해 기존 간선을 지우면 안 된다; 플러그인 이름으로 대신하지도 않는다)
        for (v in paperVersions) {
            for (dep in v.pluginDependencies[HANGAR_PLATFORM].orEmpty()) {
                val projectId = dep.projectId ?: continue
                if (projectId == p.id) continue
                val found = lookupSlug(projectId)
                if (found is SlugLookup.Failed) {
                    report.inc("deps.deferred")
                    report.warn("의존 대상 slug 조회 실패로 버전 기록을 미룸: ${p.namespace.slug} → projectId $projectId: ${found.detail}")
                    return null
                }
            }
        }

        val items = ArrayList<VersionMeta>()
        for (v in paperVersions) {
            val d = v.paperDownload() ?: continue
            val fileUrl = d.downloadUrl
            if (fileUrl == null) {
                // 외부 링크는 jar 가 아니라 GitHub/Patreon 같은 페이지다 → 저장하지 않는다 (D56)
                report.inc("versions.external")
                log.debug("외부 다운로드 버전 {} {}: {}", p.namespace.slug, v.name, d.externalUrl)
            }
            val deps = depsOf(p, v)
            val mc = mapper.map(v.platformDependencies[HANGAR_PLATFORM].orEmpty())
            if (mc.unknown > 0) report.inc("mc.unknownLabels", mc.unknown.toLong())
            report.inc("deps.written", deps.size.toLong())
            items += VersionMeta(
                ContentVersionRow(
                    version = v.name,
                    sourceVersionId = v.id.toString(),
                    channel = v.channel.name,
                    fileUrl = fileUrl,
                    sha256 = sha256Of(d),
                    size = d.fileInfo?.sizeBytes,
                    loaders = setOf(LoaderFamily.BUKKIT),
                    mcOrdinalMin = mc.min,
                    mcOrdinalMax = mc.max,
                    publishedAt = dates.parse(v.createdAt, "${p.namespace.slug} ${v.name}"),
                ),
                deps,
            )
        }

        // d. 저장
        if (items.isEmpty()) return HgWritten(emptyList(), emptyMap(), emptyMap())
        val ids = ctx.store.upsertContentVersionsMeta(contentId, items)
        report.inc("versions.written", items.size.toLong())
        val stored = ctx.store.contentVersions(contentId).associateBy { it.version }
        return HgWritten(paperVersions, ids, stored)
    }

    /**
     * `--once`: 첫 페이지만. `--loop`: 페이지가 가득 찼고, 페이지에 이미 저장된 sourceVersionId 가 없고,
     * offset 이 `versionsPerProject` 미만인 동안 이어서 받는다. 첫 페이지 실패면 null.
     */
    private suspend fun fetchVersions(p: HgProject, contentId: Long): List<HgVersion>? {
        val known = if (ctx.mode == RunMode.LOOP) {
            ctx.store.contentVersions(contentId).mapNotNullTo(HashSet()) { it.sourceVersionId }
        } else {
            emptySet()
        }
        val out = ArrayList<HgVersion>()
        var offset = 0
        while (true) {
            val url = "$base/api/v1/projects/${p.id}/versions?platform=$HANGAR_PLATFORM&limit=$VERSION_PAGE&offset=$offset"
            val page = when (val r = ctx.http.getJson<HgVersions>(url, maxBytes = PROJECT_LIST_MAX_BYTES)) {
                is Fetched.Ok -> r.value.result

                is Fetched.Failed -> {
                    if (offset == 0) {
                        report.inc("project.versionsFailed")
                        report.warn("버전 목록 실패 ${p.namespace.slug}: ${r.detail}")
                        return null
                    }
                    report.inc("project.versionsPageFailed")
                    report.warn("버전 목록 페이지 실패 ${p.namespace.slug} (offset=$offset): ${r.detail}")
                    break
                }
            }
            out += page
            if (ctx.mode != RunMode.LOOP) break
            offset += VERSION_PAGE
            val full = page.size >= VERSION_PAGE
            val reachedKnown = page.any { it.id.toString() in known }
            if (!full || reachedKnown || offset >= settings.versionsPerProject) break
        }
        // 페이지가 밀려 같은 버전이 두 번 올 수 있다 → 이름 기준으로 처음 것만
        return out.distinctBy { it.name }
    }

    /**
     * `pluginDependencies["PAPER"]` → REQUIRE/OPTIONAL (같은 source 의 slug, 중복 제거·REQUIRE 우선).
     * 외부 의존성(projectId 없음)과 확정적 부재(404)는 버린다. 조회 실패는 호출 전에 이미 프로젝트를 미뤘다.
     */
    private fun depsOf(p: HgProject, v: HgVersion): List<DepRow> {
        val acc = DepAccumulator()
        for (dep in v.pluginDependencies[HANGAR_PLATFORM].orEmpty()) {
            val projectId = dep.projectId
            if (projectId == null) {
                report.inc("deps.external")
                log.debug("외부 의존성 버림 {} {}: {} {}", p.namespace.slug, v.name, dep.name, dep.externalUrl)
                continue
            }
            if (projectId == p.id) {
                report.inc("deps.self")
                continue
            }
            when (val found = slugCache[projectId]) {
                is SlugLookup.Found -> acc.add(found.slug, if (dep.required) DepKind.REQUIRE else DepKind.OPTIONAL)
                SlugLookup.Absent -> report.inc("deps.unresolved")
                is SlugLookup.Failed, null -> error("의존 대상 slug 가 조회되지 않았다: projectId $projectId")
            }
        }
        return acc.rows()
    }

    /** projectId → slug. 결과(실패 포함)는 이 실행 안에서 캐시한다. */
    private suspend fun lookupSlug(projectId: Long): SlugLookup {
        slugCache[projectId]?.let { return it }
        val result = when (val r = ctx.http.getJson<HgProjectRef>("$base/api/v1/projects/$projectId", maxBytes = PROJECT_LIST_MAX_BYTES)) {
            is Fetched.Ok -> SlugLookup.Found(r.value.namespace.slug)

            is Fetched.Failed -> if (r.status == HTTP_NOT_FOUND) {
                SlugLookup.Absent
            } else {
                report.inc("deps.lookupFailed")
                SlugLookup.Failed(r.detail)
            }
        }
        slugCache[projectId] = result
        return result
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HangarSource::class.java)

        /** 공개 프로젝트 페이지 호스트 (API 기본 호스트와 같다). */
        const val PAGE_BASE = "https://hangar.papermc.io"

        /** 서버가 `limit` 을 50 으로 자른다 (live). */
        const val PROJECT_PAGE = 50
        const val VERSION_PAGE = 25
        const val HTTP_NOT_FOUND = 404

        /** 목록·단건 응답에 README(mainPageContent)가 들어 있다. */
        const val PROJECT_LIST_MAX_BYTES: Int = (16 * MIB).toInt()

        val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /** 하나라도 0 보다 크면 PARTIAL — 다음 사이클에 이어서 처리되는 실패들. */
        val PARTIAL_COUNTERS: List<String> = listOf(
            "projects.pageFailed",
            "project.versionsFailed",
            "project.versionsPageFailed",
            "deps.deferred",
            "analysis.budgetExhausted",
            "analysis.downloadFailed",
            "analysis.sizeMismatch",
            "analysis.unreadableUnverified",
        )

        /** 플랫폼이 준 sha256 (소문자 64 hex 일 때만). */
        fun sha256Of(d: HgDownload): String? = d.fileInfo?.sha256Hash?.lowercase()?.takeIf { SHA256_HEX.matches(it) }
    }
}
