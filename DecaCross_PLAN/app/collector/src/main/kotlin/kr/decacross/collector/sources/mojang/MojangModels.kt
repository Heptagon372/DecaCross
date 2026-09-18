package kr.decacross.collector.sources.mojang

import kotlinx.serialization.Serializable

/**
 * `version_manifest_v2.json`. 필요한 필드만 둔다 (나머지는 [kr.decacross.collector.CollectorJson] 이 무시).
 *
 * # 불변식
 * - [Entry.url] 은 받은 문자열 그대로 쓴다 (`%20` 등 이미 인코딩된 값 — 다시 인코딩하지 않는다).
 */
@Serializable
internal data class Manifest(val latest: Latest, val versions: List<Entry>) {
    @Serializable
    data class Latest(val release: String, val snapshot: String)

    /** [type] 은 `release` | `snapshot` | `old_beta` | `old_alpha` 원문. [sha1] 은 per-version JSON 의 sha1 이다. */
    @Serializable
    data class Entry(val id: String, val type: String, val url: String, val releaseTime: String, val sha1: String)
}

/** per-version JSON. label 은 항상 매니페스트 id 를 쓰고 여기 [id] 는 무시한다. */
@Serializable
internal data class VersionJson(val id: String, val javaVersion: JavaVersion? = null, val downloads: Downloads) {
    @Serializable
    data class JavaVersion(val component: String? = null, val majorVersion: Int)

    @Serializable
    data class Downloads(val client: Artifact, val server: Artifact? = null)

    @Serializable
    data class Artifact(val sha1: String, val size: Long, val url: String)
}
