package kr.decacross.collector

import kotlinx.serialization.Serializable
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.PackFormat
import kotlin.time.Instant

/**
 * Mojang 에서 읽은 MC 버전 원자료. 서수는 아직 없다 — [assignOrdinals] 가 붙인다.
 * `javaMajor` 가 null 이면 아주 오래된 버전(manifest 에 `javaVersion` 없음) → 8 로 대체하고 로그.
 */
data class McRaw(
    val label: String,
    val isSnapshot: Boolean,
    val releasedAt: Instant,
    val javaMajor: Int?,
    val clientUrl: String? = null,
    val clientSha1: String? = null,
    val clientSize: Long? = null,
    /** ★ 리소스팩 포맷과 데이터팩 포맷은 별개 필드 (불변식 4) */
    val rpFormat: PackFormat? = null,
    val dpFormat: PackFormat? = null,
    val protocol: Int? = null,
)

/**
 * 엔진 [Content] 에는 없지만 DB(`content` 테이블)에는 있는 원본 메타. 스냅샷에는 [content] 만 나가고,
 * 나머지는 Postgres 싱크와 보고서에만 쓴다.
 */
data class ContentRaw(
    val content: Content,
    val sourceId: String? = null,
    val author: String? = null,
    val downloads: Long? = null,
    val iconUrl: String? = null,
    val description: String? = null,
    val pageUrl: String? = null,
)

/** Adoptium 런타임 한 줄 (`java-runtimes.json`). 보고용 — 엔진 픽스처에는 들어가지 않는다. */
@Serializable
data class JavaRuntimeEntry(
    val feature: Int,
    val releaseName: String,
    val os: String,
    val arch: String,
    val imageType: String,
    val fileName: String,
    val url: String,
    val sha256: String?,
    val size: Long?,
)
