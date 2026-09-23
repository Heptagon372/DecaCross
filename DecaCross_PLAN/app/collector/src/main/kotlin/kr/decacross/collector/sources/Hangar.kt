package kr.decacross.collector.sources

import kotlinx.serialization.Serializable
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.ContentRaw
import kr.decacross.collector.HttpOutcome
import kr.decacross.collector.isOpenLicense
import kr.decacross.collector.isRedistributable
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.Dep
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Source
import org.slf4j.LoggerFactory

const val HANGAR_BASE_URL: String = "https://hangar.papermc.io/api/v1"

// ── hangar.papermc.io API v1 (2026-09 실측) ──────────────────────────────────

@Serializable
data class HangarPage<T>(val pagination: HangarPagination = HangarPagination(), val result: List<T> = emptyList())

@Serializable
data class HangarPagination(val count: Int = 0, val limit: Int = 0, val offset: Int = 0)

@Serializable
data class HangarProject(
    val id: Long = 0,
    val name: String,
    val namespace: HangarNamespace,
    val stats: HangarStats = HangarStats(),
    val description: String? = null,
    val settings: HangarSettings = HangarSettings(),
    val avatarUrl: String? = null,
)

@Serializable
data class HangarNamespace(val owner: String, val slug: String)

@Serializable
data class HangarStats(val downloads: Long = 0, val stars: Long = 0)

/** `settings.license`: {name: "GPL-3.0" | null, type: "GPL" | "MIT" | "Custom" | "Unspecified" …, url}. */
@Serializable
data class HangarSettings(val license: HangarLicense = HangarLicense())

@Serializable
data class HangarLicense(val name: String? = null, val type: String? = null, val url: String? = null)

@Serializable
data class HangarVersion(
    val id: Long = 0,
    val name: String,
    val createdAt: String? = null,
    /** 키: PAPER | VELOCITY | WATERFALL */
    val downloads: Map<String, HangarDownload> = emptyMap(),
    val pluginDependencies: Map<String, List<HangarPluginDep>> = emptyMap(),
    /** 키: 플랫폼, 값: MC 라벨 목록 ("1.21.8", "26.3" …) */
    val platformDependencies: Map<String, List<String>> = emptyMap(),
)

/** `downloadUrl` 은 Hangar CDN, `externalUrl` 은 외부 호스팅 (그땐 fileInfo 가 비어 있을 수 있다). */
@Serializable
data class HangarDownload(val fileInfo: HangarFileInfo? = null, val externalUrl: String? = null, val downloadUrl: String? = null)

@Serializable
data class HangarFileInfo(val name: String? = null, val sizeBytes: Long? = null, val sha256Hash: String? = null)

@Serializable
data class HangarPluginDep(val name: String, val required: Boolean = true, val projectId: Long? = null, val externalUrl: String? = null)

/**
 * Hangar 라이선스: SPDX 에 가까운 `name` 을 먼저, 없으면 `type` 을 본다. 둘 중 하나라도 허용목록이면 그 값을 쓴다.
 * 판정 자체는 [isRedistributable] 이 한다 — 여기서는 "어느 문자열을 라이선스로 기록할지" 만 고른다.
 */
fun hangarLicenseString(l: HangarLicense): String? = when {
    isOpenLicense(l.name) -> l.name
    isOpenLicense(l.type) -> l.type
    else -> l.name ?: l.type
}

/**
 * Hangar. 별 순 프로젝트 목록 → 프로젝트별 버전(PAPER 다운로드가 있는 것만) → [ContentCollection].
 * slug 는 `namespace.slug` 를 소문자로 정규화한다 (Modrinth 와 같은 키 공간; 충돌은 파이프라인이 처리).
 */
class HangarSource(private val http: CollectorHttp, private val baseUrl: String = HANGAR_BASE_URL) {
    suspend fun projects(offset: Int, limit: Int = 25): HttpOutcome<HangarPage<HangarProject>> =
        http.getJson("$baseUrl/projects?limit=$limit&offset=$offset&sort=-stars")

    suspend fun versions(slug: String, limit: Int = 25): HttpOutcome<HangarPage<HangarVersion>> =
        http.getJson("$baseUrl/projects/$slug/versions?limit=$limit")

    suspend fun collect(mcByLabel: Map<String, McOrdinal>, maxContent: Int, maxVersionsPerContent: Int): ContentCollection {
        val notes = ArrayList<String>()
        val projects = ArrayList<HangarProject>()
        var offset = 0
        while (projects.size < maxContent) {
            val page = when (val r = projects(offset, minOf(25, maxContent - projects.size))) {
                is HttpOutcome.Failed -> {
                    log.warn("Hangar 프로젝트 목록 실패 offset={}: {}", offset, r.message)
                    break
                }

                is HttpOutcome.Ok -> r.value
            }
            if (page.result.isEmpty()) break
            projects += page.result.take(maxContent - projects.size)
            offset += page.result.size
            if (offset >= page.pagination.count) break
        }

        val contents = ArrayList<ContentRaw>()
        val versions = ArrayList<ContentVersion>()
        for (p in projects) {
            val slug = p.namespace.slug.lowercase()
            val license = hangarLicenseString(p.settings.license)
            contents += ContentRaw(
                content = Content(slug, p.name, ContentKind.PLUGIN, Source.HANGAR, license, isRedistributable(license, Source.HANGAR)),
                sourceId = p.id.toString(),
                author = p.namespace.owner,
                downloads = p.stats.downloads,
                iconUrl = p.avatarUrl,
                description = p.description,
                pageUrl = "https://hangar.papermc.io/${p.namespace.owner}/${p.namespace.slug}",
            )
            val vs = when (val r = versions(p.namespace.slug, limit = maxOf(25, maxVersionsPerContent))) {
                is HttpOutcome.Failed -> {
                    log.warn("Hangar 버전 실패 {}: {}", slug, r.message)
                    emptyList()
                }

                is HttpOutcome.Ok -> r.value.result
            }
            var taken = 0
            for (v in vs) {
                if (taken >= maxVersionsPerContent) break
                val dl = v.downloads["PAPER"]
                if (dl == null) {
                    notes += "hangar $slug@${v.name}: PAPER 다운로드 없음 → 생략"
                    continue
                }
                taken += 1
                val (mcMin, mcMax) = mcRangeOf(v.platformDependencies["PAPER"].orEmpty(), mcByLabel)
                val deps = v.pluginDependencies["PAPER"].orEmpty().map { d ->
                    Dep(if (d.required) DepKind.REQUIRE else DepKind.OPTIONAL, DepTarget.Slug(d.name.lowercase()))
                }
                versions += ContentVersion(
                    slug = slug,
                    version = v.name,
                    fileUrl = dl.downloadUrl ?: dl.externalUrl,
                    sha256 = dl.fileInfo?.sha256Hash?.takeIf { it.isNotBlank() },
                    size = dl.fileInfo?.sizeBytes,
                    loaders = setOf(LoaderFamily.BUKKIT),
                    mcMin = mcMin,
                    mcMax = mcMax,
                    deps = deps,
                )
            }
        }
        log.info("hangar: 콘텐츠 {}개, 버전 {}개, 노트 {}건", contents.size, versions.size, notes.size)
        return ContentCollection(contents, versions, notes)
    }

    private companion object {
        val log = LoggerFactory.getLogger(HangarSource::class.java)
    }
}
