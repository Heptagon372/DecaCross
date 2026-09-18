package kr.decacross.collector.sources.mojang

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kr.decacross.collector.CollectorJson

// ── collector_state 키와 값 (JSON, CollectorJson) ──────────────────────────────

internal const val MANIFEST_STATE_KEY: String = "mojang.manifest"

internal fun versionStateKey(label: String): String = "mojang.version.$label"

internal fun jarMetaStateKey(label: String): String = "mojang.jarmeta.$label"

/**
 * `mojang.manifest`. 원문 그대로 저장한다 (Mojang ETag 는 따옴표가 없다).
 *
 * # 불변식
 * - 서수 발급이 완전히 끝난 뒤에만 기록한다 (D12). 그렇지 않으면 다음 사이클이 무조건 요청을 보내 누락분을 발급한다.
 */
@Serializable
internal data class ManifestState(val etag: String? = null, val lastModified: String? = null)

/** `mojang.version.<label>`: per-version JSON 에서 얻은 jar 위치. jar 메타 읽기와 sha1 변화 감지에 쓴다. */
@Serializable
internal data class VersionState(
    val jsonUrl: String,
    val jsonSha1: String,
    val clientUrl: String,
    val clientSha1: String,
    val clientSize: Long,
    val serverUrl: String? = null,
    val serverSha1: String? = null,
    val serverSize: Long? = null,
    val javaMajor: Int? = null,
) {
    fun client(): VersionJson.Artifact = VersionJson.Artifact(clientSha1, clientSize, clientUrl)

    fun server(): VersionJson.Artifact? {
        val url = serverUrl ?: return null
        val sha1 = serverSha1 ?: return null
        val size = serverSize ?: return null
        return VersionJson.Artifact(sha1, size, url)
    }

    companion object {
        fun of(entry: Manifest.Entry, vj: VersionJson): VersionState = VersionState(
            jsonUrl = entry.url,
            jsonSha1 = entry.sha1,
            clientUrl = vj.downloads.client.url,
            clientSha1 = vj.downloads.client.sha1,
            clientSize = vj.downloads.client.size,
            serverUrl = vj.downloads.server?.url,
            serverSha1 = vj.downloads.server?.sha1,
            serverSize = vj.downloads.server?.size,
            javaMajor = vj.javaVersion?.majorVersion,
        )
    }
}

/**
 * `mojang.jarmeta.<label>`. [clientSha1] 이 현재 client jar sha1 과 같고 [status] 가 FOUND/NONE/TOO_LARGE 면 다시 읽지 않는다.
 * FAILED 는 [attempts] 와 [nextAttemptAt](ISO-8601) 으로 지수 백오프한다.
 *
 * [rp]/[dp] 는 `PackFormat.toString()` 문자열이다 (D8). null 필드는 JSON 에서 빠진다 (SQL `->>` 로 읽으면 null).
 * [bytes]/[requests] 는 기본값이 없어서 항상 기록된다.
 */
@Serializable
internal data class JarMetaState(
    val clientSha1: String,
    val status: String,
    val jar: String? = null,
    val era: String? = null,
    val rp: String? = null,
    val dp: String? = null,
    val protocol: Int? = null,
    val worldVersion: Int? = null,
    val seriesId: String? = null,
    val bytes: Long,
    val requests: Int,
    val attempts: Int? = null,
    val nextAttemptAt: String? = null,
    val reason: String? = null,
)

/** 상태 JSON 해석. 깨졌으면 없는 것으로 본다 (다음 기록이 덮어쓴다). */
internal fun <T> decodeStateOrNull(serializer: KSerializer<T>, text: String?): T? {
    if (text == null) return null
    return try {
        CollectorJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

internal fun <T> encodeState(serializer: KSerializer<T>, value: T): String = CollectorJson.encodeToString(serializer, value)
