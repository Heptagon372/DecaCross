package kr.decacross.collector.sources

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.ContentRaw
import kr.decacross.collector.HttpOutcome
import kr.decacross.collector.isRedistributable
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.Dep
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Source
import org.slf4j.LoggerFactory

const val MODRINTH_BASE_URL: String = "https://api.modrinth.com/v2"

/** Bukkit 계열 로더. 검색 facet 과 `/version?loaders=` 둘 다 이 목록을 쓴다. */
val MODRINTH_BUKKIT_LOADERS: List<String> = listOf("paper", "spigot", "purpur", "bukkit", "folia")

// ── api.modrinth.com v2 (2026-09 실측) ───────────────────────────────────────
// 검색 결과의 `project_type` 은 플러그인이어도 "mod" 로 오고 `all_project_types` 에 "plugin" 이 들어 있다.
// facet 은 `project_type:plugin` 이 맞다 (`project_type:mod` 는 109건, `plugin` 은 17,307건).
// `license` 는 검색 결과에 SPDX 문자열로 바로 들어 있어 `/v2/project/{id}` 를 따로 부르지 않는다.

@Serializable
data class ModrinthSearch(val hits: List<ModrinthHit> = emptyList(), val offset: Int = 0, val limit: Int = 0, val total_hits: Int = 0)

@Serializable
data class ModrinthHit(
    val project_id: String,
    val slug: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    /** SPDX id ("MIT", "LGPL-3.0-only", "ARR", "LicenseRef-Custom" …) */
    val license: String? = null,
    val downloads: Long = 0,
    val icon_url: String? = null,
    val categories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val project_type: String? = null,
)

@Serializable
data class ModrinthVersion(
    val id: String,
    val project_id: String,
    val version_number: String,
    val game_versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val dependencies: List<ModrinthDependency> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
    val date_published: String? = null,
    val version_type: String? = null,
)

/** `dependency_type`: required | optional | incompatible | embedded. `project_id` 가 null 이고 `version_id` 만 있을 수도 있다. */
@Serializable
data class ModrinthDependency(val project_id: String? = null, val version_id: String? = null, val dependency_type: String = "")

/** `hashes`: sha1 / sha512 만. ★ sha256 없음 → 받아서 해시하기 전엔 null. */
@Serializable
data class ModrinthFile(
    val url: String,
    val filename: String = "",
    val primary: Boolean = false,
    val size: Long = 0,
    val hashes: Map<String, String> = emptyMap(),
)

/** `GET /v2/projects?ids=[...]` 한 건. 의존 project_id → slug 변환용. */
@Serializable
data class ModrinthProjectRef(val id: String, val slug: String)

/**
 * Modrinth. 검색(다운로드순, Bukkit 계열 플러그인) → 프로젝트별 버전 → [ContentCollection].
 * 캡: [collect] 의 maxContent / maxVersionsPerContent.
 */
class ModrinthSource(private val http: CollectorHttp, private val baseUrl: String = MODRINTH_BASE_URL) {
    private fun facets(): String {
        val loaders = MODRINTH_BUKKIT_LOADERS.joinToString(",") { "\"categories:$it\"" }
        return "[[$loaders],[\"project_type:plugin\"]]"
    }

    suspend fun search(offset: Int, limit: Int): HttpOutcome<ModrinthSearch> =
        http.getJson("$baseUrl/search?facets=${facets().encodeURLParameter()}&index=downloads&limit=$limit&offset=$offset")

    suspend fun versions(projectId: String): HttpOutcome<List<ModrinthVersion>> {
        val loaders = MODRINTH_BUKKIT_LOADERS.joinToString(",") { "\"$it\"" }
        return http.getJson("$baseUrl/project/$projectId/version?loaders=${"[$loaders]".encodeURLParameter()}")
    }

    /** project id → slug. 50개씩 나눠 조회. 실패한 묶음은 빠진다 (호출자는 id 를 그대로 slug 로 쓰지 않고 로그). */
    suspend fun slugsOf(ids: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in ids.distinct().chunked(50)) {
            val q = chunk.joinToString(",") { "\"$it\"" }
            when (val r = http.getJson<List<ModrinthProjectRef>>("$baseUrl/projects?ids=${"[$q]".encodeURLParameter()}")) {
                is HttpOutcome.Failed -> log.warn("Modrinth projects 조회 실패: {}", r.message)
                is HttpOutcome.Ok -> r.value.forEach { out[it.id] = it.slug }
            }
        }
        return out
    }

    suspend fun collect(mcByLabel: Map<String, McOrdinal>, maxContent: Int, maxVersionsPerContent: Int): ContentCollection {
        val notes = ArrayList<String>()
        val hits = ArrayList<ModrinthHit>()
        var offset = 0
        while (hits.size < maxContent) {
            val limit = minOf(100, maxContent - hits.size)
            val page = when (val r = search(offset, limit)) {
                is HttpOutcome.Failed -> {
                    log.warn("Modrinth 검색 실패 offset={}: {}", offset, r.message)
                    break
                }

                is HttpOutcome.Ok -> r.value
            }
            if (page.hits.isEmpty()) break
            hits += page.hits.take(maxContent - hits.size)
            offset += page.hits.size
            if (offset >= page.total_hits) break
        }
        val idToSlug = HashMap<String, String>()
        hits.forEach { idToSlug[it.project_id] = it.slug }

        // 1) 버전 수집 (의존 slug 는 아직 모름 → project_id 로 모아둔다)
        data class Pending(val hit: ModrinthHit, val versions: List<ModrinthVersion>)
        val pending = ArrayList<Pending>()
        val depIds = HashSet<String>()
        for (hit in hits) {
            val vs = when (val r = versions(hit.project_id)) {
                is HttpOutcome.Failed -> {
                    log.warn("Modrinth 버전 실패 {}: {}", hit.slug, r.message)
                    emptyList()
                }

                is HttpOutcome.Ok -> r.value.take(maxVersionsPerContent)
            }
            vs.flatMap { it.dependencies }.mapNotNullTo(depIds) { it.project_id }
            pending += Pending(hit, vs)
        }
        val unknown = depIds.filter { it !in idToSlug }
        if (unknown.isNotEmpty()) idToSlug += slugsOf(unknown)

        // 2) 엔진 타입으로
        val contents = ArrayList<ContentRaw>()
        val versions = ArrayList<ContentVersion>()
        for ((hit, vs) in pending) {
            contents += ContentRaw(
                content = Content(
                    slug = hit.slug,
                    name = hit.title,
                    kind = ContentKind.PLUGIN,
                    source = Source.MODRINTH,
                    license = hit.license,
                    redistributable = isRedistributable(hit.license, Source.MODRINTH),
                ),
                sourceId = hit.project_id,
                author = hit.author,
                downloads = hit.downloads,
                iconUrl = hit.icon_url,
                description = hit.description,
                pageUrl = "https://modrinth.com/plugin/${hit.slug}",
            )
            for (v in vs) {
                val file = v.files.firstOrNull { it.primary } ?: v.files.firstOrNull()
                val (mcMin, mcMax) = mcRangeOf(v.game_versions, mcByLabel)
                val deps = v.dependencies.mapNotNull { d ->
                    val kind = depKindOf(d.dependency_type)
                    if (kind == null) {
                        notes += "modrinth ${hit.slug}@${v.version_number}: 의존 '${d.dependency_type}' (${d.project_id ?: d.version_id}) 표현 불가 → 생략"
                        return@mapNotNull null
                    }
                    val pid = d.project_id
                    if (pid == null) {
                        notes += "modrinth ${hit.slug}@${v.version_number}: version_id 만 있는 의존 ${d.version_id} → 생략"
                        return@mapNotNull null
                    }
                    val slug = idToSlug[pid]
                    if (slug == null) {
                        notes += "modrinth ${hit.slug}@${v.version_number}: 의존 $pid 의 slug 를 못 찾음 → 생략"
                        return@mapNotNull null
                    }
                    Dep(kind, DepTarget.Slug(slug))
                }
                versions += ContentVersion(
                    slug = hit.slug,
                    version = v.version_number,
                    fileUrl = file?.url,
                    sha256 = null,
                    size = file?.size,
                    loaders = v.loaders.mapNotNull(::loaderFamilyOf).toSet(),
                    mcMin = mcMin,
                    mcMax = mcMax,
                    deps = deps,
                )
            }
        }
        log.info("modrinth: 콘텐츠 {}개, 버전 {}개, 노트 {}건", contents.size, versions.size, notes.size)
        return ContentCollection(contents, versions, notes)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModrinthSource::class.java)
    }
}
