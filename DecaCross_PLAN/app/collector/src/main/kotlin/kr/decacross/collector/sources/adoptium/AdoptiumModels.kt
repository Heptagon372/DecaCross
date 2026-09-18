package kr.decacross.collector.sources.adoptium

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Adoptium API v3 응답 DTO. 필드 이름은 API 원문(snake_case) 그대로 ─────────────

/** `GET /v3/info/available_releases`. */
@Serializable
internal data class AvailableReleases(val available_releases: List<Int>, val available_lts_releases: List<Int>)

/** `GET /v3/assets/latest/{feature}/hotspot` 원소. */
@Serializable
internal data class AdoptAsset(val binary: AdoptBinary, val release_name: String, val version: AdoptVersion)

/**
 * [os]: `windows` | `mac` | `linux` | `alpine-linux` | `aix` …, [architecture]: `x64` | `aarch64` | …,
 * [image_type]: `jre` | `jdk` | `debugimage` | `staticlibs` … 원문. [pkg] 는 압축 파일 (installer 는 쓰지 않는다).
 */
@Serializable
internal data class AdoptBinary(
    val os: String,
    val architecture: String,
    val image_type: String,
    val heap_size: String = "normal",
    val jvm_impl: String = "hotspot",
    @SerialName("package") val pkg: AdoptPackage? = null,
    val updated_at: String? = null,
)

@Serializable
internal data class AdoptPackage(val name: String, val link: String, val checksum: String? = null, val size: Long)

/** Java 8 응답에는 `patch`/`optional` 이 없다 — 받지 않는다. semver 도 4단 버전에서 깨지므로 쓰지 않는다. */
@Serializable
internal data class AdoptVersion(val major: Int, val openjdk_version: String)

/** collector_state `adoptium.latest.<feature>`. */
@Serializable
internal data class AdoptiumLatestState(val etag: String? = null, val lastModified: String? = null)
