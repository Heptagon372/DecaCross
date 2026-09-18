package kr.decacross.collector.sources.fill

import kotlinx.serialization.Serializable

// ── Fill v3 응답 DTO. 필요한 필드만 둔다 (commits·java 등은 CollectorJson 이 무시) ──────────────

/** `GET /v3/projects/{p}/versions`. */
@Serializable
internal data class FillVersionsResponse(val versions: List<FillVersionEntry>)

/** [builds] 는 정렬을 보장하지 않는다 → 집합으로만 쓴다. */
@Serializable
internal data class FillVersionEntry(val version: FillVersion, val builds: List<Int>)

/** [id] 는 Mojang 매니페스트 id 와 정확히 같은 문자열이다 (D16). */
@Serializable
internal data class FillVersion(val id: String, val support: FillSupport? = null)

/** [status]: `SUPPORTED` | `UNSUPPORTED` … 원문. */
@Serializable
internal data class FillSupport(val status: String, val end: String? = null)

/**
 * `GET /v3/projects/{p}/versions/{v}/builds` 의 원소.
 * [channel] 은 문자열로 받아 [foldFillChannel] 로 접는다 (새 값이 와도 역직렬화가 실패하지 않게).
 * [time] 은 밀리초가 있을 수도 없을 수도 있다 (`2025-09-06T21:50:11.982Z` / `2026-09-16T19:27:27Z`).
 */
@Serializable
internal data class FillBuild(val id: Int, val time: String, val channel: String, val downloads: Map<String, FillDownload> = emptyMap())

@Serializable
internal data class FillDownload(val name: String, val checksums: FillChecksums, val size: Long, val url: String)

@Serializable
internal data class FillChecksums(val sha256: String? = null)

// ── collector_state 값 ──

/** `fill.<apiId>.versions`: versions 목록 ETag 와 그때 SUPPORTED 였던 id (304 일 때 후보). */
@Serializable
internal data class FillVersionsState(val etag: String? = null, val lastModified: String? = null, val supported: List<String> = emptyList())

/** `fill.<apiId>.builds.<id>`: 버전별 builds 목록 조건부 요청 값. */
@Serializable
internal data class FillBuildsState(val etag: String? = null, val lastModified: String? = null)

/** `fill.<apiId>.lastFullSweep`: 마지막으로 끝까지 돈 전체 순회 시각 (ISO-8601). */
@Serializable
internal data class FillSweepState(val at: String)
