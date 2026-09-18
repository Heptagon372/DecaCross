package kr.decacross.collector.sources.hangar

import kotlinx.serialization.Serializable

// ── Hangar v1 응답 DTO (필요한 필드만; 모르는 필드는 CollectorJson 이 무시) ─────────────

@Serializable
internal data class HgProjects(val result: List<HgProject>)

@Serializable
internal data class HgProject(
    val id: Long,
    val name: String,
    val namespace: HgNamespace,
    val stats: HgStats,
    val description: String? = null,
    val avatarUrl: String? = null,
    val settings: HgSettings? = null,
)

/** 단건 조회(`/api/v1/projects/{id}`)에서 slug 만 쓴다. */
@Serializable
internal data class HgProjectRef(val id: Long? = null, val namespace: HgNamespace)

@Serializable
internal data class HgNamespace(val owner: String, val slug: String)

@Serializable
internal data class HgStats(val downloads: Long = 0)

@Serializable
internal data class HgSettings(val license: HgLicense? = null)

@Serializable
internal data class HgLicense(val name: String? = null, val url: String? = null, val type: String? = null)

@Serializable
internal data class HgVersions(val result: List<HgVersion>)

@Serializable
internal data class HgVersion(
    val id: Long,
    val name: String,
    val createdAt: String,
    val channel: HgChannel,
    val downloads: Map<String, HgDownload> = emptyMap(),
    val pluginDependencies: Map<String, List<HgPluginDep>> = emptyMap(),
    val platformDependencies: Map<String, List<String>> = emptyMap(),
)

@Serializable
internal data class HgChannel(val name: String, val flags: List<String> = emptyList())

@Serializable
internal data class HgDownload(val fileInfo: HgFileInfo? = null, val externalUrl: String? = null, val downloadUrl: String? = null)

@Serializable
internal data class HgFileInfo(val name: String, val sizeBytes: Long, val sha256Hash: String)

@Serializable
internal data class HgPluginDep(val name: String, val projectId: Long? = null, val required: Boolean = false, val externalUrl: String? = null)

/** 대상 플랫폼 (AE-11). Phase 1 은 Paper 계열만. */
internal const val HANGAR_PLATFORM: String = "PAPER"

/** 채널 플래그: 불안정 빌드. */
internal const val FLAG_UNSTABLE: String = "UNSTABLE"

/** PAPER 다운로드 항목. */
internal fun HgVersion.paperDownload(): HgDownload? = downloads[HANGAR_PLATFORM]

/** Hangar 가 직접 호스팅하는 jar 인가 (외부 링크 버전은 절대 받지 않는다, D56). */
internal fun HgVersion.isHosted(): Boolean = paperDownload()?.let { it.fileInfo != null && it.downloadUrl != null } == true
