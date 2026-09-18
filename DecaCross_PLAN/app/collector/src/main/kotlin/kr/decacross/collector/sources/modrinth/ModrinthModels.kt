package kr.decacross.collector.sources.modrinth

import kotlinx.serialization.Serializable
import kr.decacross.compat.model.LoaderFamily

// ── Modrinth v2 응답 DTO (필요한 필드만; 모르는 필드는 CollectorJson 이 무시) ─────────────
// ★ 검색 hit 의 project_type 필드는 일부러 없다: `project_type:plugin` facet 으로 찾아도 hit 은 "mod" 를 보고한다.
//   그 값으로 거르면 전부 사라진다 (SCP-15).

@Serializable
internal data class MrSearch(val hits: List<MrHit>, val total_hits: Int = 0)

@Serializable
internal data class MrHit(
    val project_id: String,
    val slug: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val downloads: Long = 0,
    val icon_url: String? = null,
    val license: String? = null,
)

@Serializable
internal data class MrVersion(
    val id: String,
    val project_id: String,
    val version_number: String,
    val version_type: String,
    val date_published: String,
    val downloads: Long = 0,
    val loaders: List<String> = emptyList(),
    val game_versions: List<String> = emptyList(),
    val files: List<MrFile> = emptyList(),
    val dependencies: List<MrDep> = emptyList(),
)

@Serializable
internal data class MrFile(
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long,
    val hashes: Map<String, String> = emptyMap(),
)

@Serializable
internal data class MrDep(
    val project_id: String? = null,
    val version_id: String? = null,
    val file_name: String? = null,
    val dependency_type: String,
)

@Serializable
internal data class MrProject(val id: String, val slug: String, val loaders: List<String> = emptyList())

/** Bukkit 계열 로더 (AE-11). 검색 facet·버전 목록 loaders 필터·의존 대상 판정에 같은 집합을 쓴다. */
internal val BUKKIT_LOADERS: Set<String> = setOf("paper", "spigot", "bukkit", "purpur", "folia")

/** Modrinth 로더 이름 → [LoaderFamily]. 모르는 로더는 null (무시). */
internal fun loaderFamilyOf(loader: String): LoaderFamily? = when (loader) {
    in BUKKIT_LOADERS -> LoaderFamily.BUKKIT
    "fabric", "quilt" -> LoaderFamily.FABRIC
    "forge", "neoforge" -> LoaderFamily.FORGE
    else -> null
}

/** 분석·저장 대상 파일: primary, 없으면 첫 파일. 이름이 `.jar` 로 끝나지 않으면 null. */
internal fun MrVersion.jarFile(): MrFile? {
    val file = files.firstOrNull { it.primary } ?: files.firstOrNull() ?: return null
    return file.takeIf { it.filename.endsWith(".jar", ignoreCase = true) }
}
