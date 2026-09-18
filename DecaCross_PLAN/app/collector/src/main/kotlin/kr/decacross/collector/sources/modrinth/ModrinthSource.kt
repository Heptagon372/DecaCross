package kr.decacross.collector.sources.modrinth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import kr.decacross.collector.content.enc
import kr.decacross.collector.content.encSeg
import kr.decacross.collector.content.getJson
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.ContentRow
import kr.decacross.collector.store.ContentUpsertResult
import kr.decacross.collector.store.ContentVersionRow
import kr.decacross.collector.store.StoredVersion
import kr.decacross.collector.store.VersionMeta
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.Source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Modrinth v2 플러그인 인덱스 → content / content_versions / content_deps (+ 선택 버전 jar 분석 후 즉시 삭제). */
class ModrinthSource : CollectorSource {
    override val id: SourceId = SourceId.MODRINTH
    override val interval: Duration = 6.hours
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = ModrinthRun(ctx, ReportBuilder(id)).run()
}

/** 검색 hit 하나와 그 Bukkit 계열 버전 목록. */
private class Candidate(val hit: MrHit, val versions: List<MrVersion>) {
    /** 순위 기준: Bukkit 계열 버전 다운로드 합 (하이브리드 프로젝트의 전체 다운로드는 부풀려져 있다, D24). */
    val bukkitDownloads: Long = versions.sumOf { it.downloads }
}

/** 1패스에서 메타데이터를 기록한 프로젝트 (2패스 분석 대상). */
private class Written(
    /** 기록한 버전 (jar 파일이 있는 것만). */
    val jarVersions: List<MrVersion>,
    val ids: Map<String, Long>,
    val stored: Map<String, StoredVersion>,
)

/** 의존 대상 일괄 조회 결과. */
private class Resolution(
    /** 성공한 응답에 들어 있던 프로젝트 (id → 프로젝트). 성공한 배치에 없던 id 는 확정적 부재다. */
    val resolved: Map<String, MrProject>,
    /** 실패한 배치의 id — 부재인지 알 수 없다. */
    val unresolvable: Set<String>,
)

/**
 * `ModrinthSource.collect` 한 번 (§10.4).
 *
 * # 불변식
 * - 2패스: 모든 content·버전 메타데이터를 먼저 쓰고, 그다음 jar 를 분석한다 (분석 충돌·타임아웃이 메타데이터를 잃지 않게).
 * - 의존 대상 일괄 조회가 **실패한** 프로젝트는 이번 사이클에 버전·의존성을 쓰지 않는다 (기존 간선 유지, PARTIAL).
 * - 검색 hit 의 `project_type` 으로 거르지 않는다 (DTO 에 필드 자체가 없다).
 * - 요청·다운로드는 순차 실행한다.
 */
private class ModrinthRun(private val ctx: CollectContext, private val report: ReportBuilder) {
    private val settings = ctx.settings.content
    private val base = settings.modrinthBaseUrl.trimEnd('/')
    private val dates = PublishedAtParser(report)

    suspend fun run(): SourceReport {
        val policy = LicensePolicy.load()
        val analyzerVersion = effectiveAnalyzerVersion(CapabilityRulesLoader.load())

        // 1. 검색
        val hits = when (val searched = search()) {
            is Fetched.Ok -> searched.value
            is Fetched.Failed -> return report.build(SourceStatus.FAILED, "Modrinth 검색 실패: ${searched.detail}")
        }

        // 2. 후보별 Bukkit 계열 버전 목록 (순차)
        val candidates = ArrayList<Candidate>()
        for (hit in hits) {
            when (val r = ctx.http.getJson<List<MrVersion>>(versionListUrl(hit.project_id))) {
                is Fetched.Ok -> if (r.value.isEmpty()) report.inc("project.noBukkitVersions") else candidates += Candidate(hit, r.value)

                is Fetched.Failed -> {
                    report.inc("project.versionsFailed")
                    report.warn("버전 목록 실패 ${hit.slug}: ${r.detail}")
                }
            }
        }

        // 3. 순위: Bukkit 계열 다운로드 합 ↓, 동률이면 프로젝트 다운로드 ↓, slug ↑
        val kept = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.bukkitDownloads }
                    .thenByDescending { it.hit.downloads }
                    .thenBy { it.hit.slug },
            ).take(settings.modrinthTop)
        report.inc("project.kept", kept.size.toLong())
        val keptVersions = kept.map { keepVersions(it.versions) }

        // 4. 의존 대상 일괄 조회 (저장할 버전의 REQUIRE/OPTIONAL 의존성만)
        val depIds = LinkedHashSet<String>()
        for (versions in keptVersions) {
            for (v in versions) {
                for (d in v.dependencies) {
                    val pid = d.project_id
                    if (pid != null && kindOf(d.dependency_type) != null) depIds += pid
                }
            }
        }
        val resolution = resolveProjects(depIds)

        // 5. 1패스 — content·버전 메타데이터 (순위 순)
        val mapper = McLabelMapper(ctx.store.mcIndex())
        val written = ArrayList<Written>()
        for ((i, c) in kept.withIndex()) {
            writeProject(c, keptVersions[i], resolution, mapper, policy)?.let { written += it }
        }

        // 6. 2패스 — 선택한 버전만 분석 (순위 순, 순차)
        if (settings.analyzePerProject > 0) {
            val budget = DownloadBudget(settings.maxDownloadBytesPerCycle, settings.maxJarBytes)
            val pipeline = ContentPipeline(ctx, report, budget, analyzerVersion)
            for (w in written) {
                for (v in VersionSelection.modrinth(w.jarVersions, settings.analyzePerProject)) {
                    val file = v.jarFile() ?: continue
                    val versionId = w.ids[v.version_number] ?: continue
                    val sha1 = file.hashes["sha1"]
                    pipeline.analyze(
                        AnalysisTarget(
                            contentVersionId = versionId,
                            storedAnalyzerVersion = w.stored[v.version_number]?.analyzerVersion,
                            url = file.url,
                            size = file.size,
                            knownSha256 = null,
                            expected = if (sha1 != null) mapOf(DigestAlgo.SHA1 to sha1) else emptyMap(),
                        ),
                    )
                }
            }
        }

        // 7. 상태
        val partial = PARTIAL_COUNTERS.any { report.count(it) > 0 }
        return report.build(if (partial) SourceStatus.PARTIAL else SourceStatus.OK)
    }

    /** 검색 페이지를 모아 project_id 로 중복 제거한다 (다운로드 순 페이지는 요청 사이에 밀릴 수 있다). 한 페이지라도 실패하면 Failed. */
    private suspend fun search(): Fetched<List<MrHit>> {
        val hits = LinkedHashMap<String, MrHit>()
        var offset = 0
        while (offset < settings.modrinthCandidates) {
            val limit = minOf(SEARCH_PAGE, settings.modrinthCandidates - offset)
            val url = "$base/v2/search?facets=${enc(FACETS)}&index=downloads&limit=$limit&offset=$offset"
            val page = when (val r = ctx.http.getJson<MrSearch>(url)) {
                is Fetched.Ok -> r.value.hits
                is Fetched.Failed -> return r
            }
            report.inc("search.pages")
            for (hit in page) {
                if (hits.putIfAbsent(hit.project_id, hit) != null) report.inc("search.duplicateHits")
            }
            if (page.size < limit) break
            offset += limit
        }
        report.inc("search.hits", hits.size.toLong())
        return Fetched.Ok(hits.values.toList())
    }

    private fun versionListUrl(projectId: String): String =
        "$base/v2/project/${encSeg(projectId)}/version?include_changelog=false&loaders=${enc(LOADERS_JSON)}"

    /**
     * 저장할 버전: `version_number` 별로 가장 최근 게시본만 남기고 (D27), 게시 시각 내림차순으로 `versionsPerProject` 개.
     */
    private fun keepVersions(versions: List<MrVersion>): List<MrVersion> {
        val newest = VersionSelection.newestFirst<MrVersion> { it.date_published }
        val byNumber = LinkedHashMap<String, MrVersion>()
        for (v in versions) {
            val old = byNumber[v.version_number]
            if (old == null) {
                byNumber[v.version_number] = v
            } else {
                report.inc("versions.duplicateDropped")
                if (newest.compare(v, old) < 0) byNumber[v.version_number] = v
            }
        }
        return byNumber.values.sortedWith(newest).take(settings.versionsPerProject)
    }

    /** 의존 대상 id → 프로젝트 ([BULK_BATCH] 개씩). */
    private suspend fun resolveProjects(ids: Collection<String>): Resolution {
        val resolved = HashMap<String, MrProject>()
        val unresolvable = HashSet<String>()
        for (batch in ids.chunked(BULK_BATCH)) {
            val idsJson = JsonArray(batch.map { JsonPrimitive(it) }).toString()
            when (val r = ctx.http.getJson<List<MrProject>>("$base/v2/projects?ids=${enc(idsJson)}")) {
                // 모르는 id 는 응답에서 조용히 빠진다 (live) → 성공한 배치에 없으면 확정적 부재
                is Fetched.Ok -> for (p in r.value) resolved[p.id] = p

                is Fetched.Failed -> {
                    unresolvable += batch
                    report.inc("deps.batchFailed")
                    report.warn("의존 대상 일괄 조회 실패 (${batch.size}개): ${r.detail}")
                }
            }
        }
        return Resolution(resolved, unresolvable)
    }

    /** 5a–5c. 슬러그 충돌이거나 의존 대상 조회 실패로 미뤄지면 null (2패스 대상 아님). */
    private suspend fun writeProject(
        c: Candidate,
        versions: List<MrVersion>,
        resolution: Resolution,
        mapper: McLabelMapper,
        policy: LicensePolicy,
    ): Written? {
        val hit = c.hit

        // a. content 행
        val license = policy.modrinth(hit.license)
        val row = ContentRow(
            source = Source.MODRINTH,
            sourceId = hit.project_id,
            slug = hit.slug,
            name = hit.title,
            kind = ContentKind.PLUGIN,
            license = license.license,
            redistributable = license.redistributable,
            author = hit.author,
            downloads = c.bukkitDownloads,
            iconUrl = hit.icon_url,
            description = hit.description,
            pageUrl = "https://modrinth.com/project/${hit.slug}",
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

        // b. 버전 — jar 가 아닌 버전은 건너뛴다
        val jarVersions = ArrayList<MrVersion>()
        for (v in versions) {
            if (v.jarFile() == null) report.inc("versions.nonJarSkipped") else jarVersions += v
        }

        // 일시 실패를 "대상 없음"으로 착각해 간선을 지우면 안 된다 → 프로젝트 버전 전체를 다음 사이클로 미룬다
        val pending = jarVersions.asSequence()
            .flatMap { it.dependencies.asSequence() }
            .firstOrNull { d -> d.project_id in resolution.unresolvable && kindOf(d.dependency_type) != null }
        if (pending != null) {
            report.inc("deps.deferred")
            report.warn("의존 대상 조회 실패로 버전 기록을 미룸: ${hit.slug} → ${pending.project_id}")
            return null
        }

        val items = ArrayList<VersionMeta>()
        for (v in jarVersions) {
            val file = v.jarFile() ?: continue
            val mc = mapper.map(v.game_versions)
            if (mc.unknown > 0) report.inc("mc.unknownLabels", mc.unknown.toLong())
            val deps = depsOf(v, hit, resolution)
            report.inc("deps.written", deps.size.toLong())
            items += VersionMeta(
                ContentVersionRow(
                    version = v.version_number,
                    sourceVersionId = v.id,
                    channel = v.version_type,
                    fileUrl = file.url,
                    sha256 = null,
                    size = file.size,
                    loaders = v.loaders.mapNotNull { loaderFamilyOf(it) }.toSet(),
                    mcOrdinalMin = mc.min,
                    mcOrdinalMax = mc.max,
                    publishedAt = dates.parse(v.date_published, "${hit.slug} ${v.version_number}"),
                ),
                deps,
            )
        }

        // c. 저장
        if (items.isEmpty()) return Written(emptyList(), emptyMap(), emptyMap())
        val ids = ctx.store.upsertContentVersionsMeta(contentId, items)
        report.inc("versions.written", items.size.toLong())
        val stored = ctx.store.contentVersions(contentId).associateBy { it.version }
        return Written(jarVersions, ids, stored)
    }

    /** 한 버전의 플랫폼 의존성 → REQUIRE/OPTIONAL 행 (대상 slug 기준 중복 제거, REQUIRE 우선). 미뤄야 할 대상은 호출 전에 걸러졌다. */
    private fun depsOf(v: MrVersion, hit: MrHit, resolution: Resolution) = DepAccumulator().apply {
        for (d in v.dependencies) {
            val pid = d.project_id
            if (pid == null) {
                report.inc("deps.noProjectId")
                continue
            }
            val kind = kindOf(d.dependency_type)
            if (kind == null) {
                report.inc("deps.dropped.${d.dependency_type}")
                continue
            }
            val target = resolution.resolved[pid]
            when {
                target == null -> report.inc("deps.unresolved")
                target.id == hit.project_id -> report.inc("deps.self")
                target.loaders.none { it in BUKKIT_LOADERS } -> report.inc("deps.nonBukkitTarget")
                else -> add(target.slug, kind)
            }
        }
    }.rows()

    private companion object {
        /**
         * ★ `project_type:plugin` + 로더 카테고리 OR 묶음 (SCP-15). hit 은 여전히 `project_type: "mod"` 를 보고한다.
         */
        const val FACETS: String =
            """[["project_type:plugin"],["categories:paper","categories:spigot","categories:bukkit","categories:purpur","categories:folia"]]"""

        /** 버전 목록 loaders 필터 (Bukkit 계열, AE-11). */
        const val LOADERS_JSON: String = """["paper","spigot","bukkit","purpur","folia"]"""

        const val SEARCH_PAGE = 100
        const val BULK_BATCH = 100

        /** 하나라도 0 보다 크면 PARTIAL — 다음 사이클에 이어서 처리되는 실패들. */
        val PARTIAL_COUNTERS: List<String> = listOf(
            "project.versionsFailed",
            "deps.deferred",
            "analysis.budgetExhausted",
            "analysis.downloadFailed",
            "analysis.sizeMismatch",
            "analysis.unreadableUnverified",
        )

        /** `required` → REQUIRE, `optional` → OPTIONAL, 나머지(incompatible, embedded …)는 버린다. */
        fun kindOf(dependencyType: String): DepKind? = when (dependencyType) {
            "required" -> DepKind.REQUIRE
            "optional" -> DepKind.OPTIONAL
            else -> null
        }
    }
}
