package kr.decacross.collector.sources.purpur

import kotlinx.serialization.Serializable

// ── Purpur API v2 응답 DTO ──────────────────────────────────────────────
// ★ `commits` 필드는 절대 두지 않는다: 커밋 작성자의 개인 이메일이 들어 있다 (CollectorJson 이 무시한다).

/** `GET /v2/purpur`. */
@Serializable
internal data class PurpurProject(val versions: List<String>)

/** `GET /v2/purpur/{v}`. */
@Serializable
internal data class PurpurVersion(val version: String, val builds: PurpurBuildsBrief)

@Serializable
internal data class PurpurBuildsBrief(val latest: String, val all: List<String>)

/** `GET /v2/purpur/{v}?detailed=true`. `builds.latest` 는 객체라서 받지 않는다. */
@Serializable
internal data class PurpurVersionDetailed(val version: String, val builds: PurpurBuildsDetailed)

@Serializable
internal data class PurpurBuildsDetailed(val all: List<PurpurBuild>)

/**
 * 빌드 하나. [result]: `SUCCESS` | `FAILURE` 원문. [timestamp] 는 epoch 밀리초.
 * [metadata] `type` 이 `experimental` 이면 실험 빌드.
 */
@Serializable
internal data class PurpurBuild(
    val build: String,
    val result: String,
    val timestamp: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
    // ★ Purpur 1.20+ FAILURE 빌드는 "md5": null (1.20 이전은 ""). non-null String 이면 버전 하나의 detailed 응답 전체가 역직렬화 실패한다.
    val md5: String? = null,
)

/**
 * collector_state `purpur.budget.<UTC yyyy-MM-dd>`.
 *
 * [bytes]: 그날 스트리밍한 누적 바이트. 해시 성공뿐 아니라 본문을 받은 실패(md5 불일치·중간 끊김·크기 초과)도 포함한다.
 * [mismatched]: 그날 md5 가 맞지 않았던 `"<버전>/<빌드>"`. 같은 날에는 다시 스트리밍하지 않는다 (다음 날 한 번 재시도).
 * 비어 있으면 JSON 에서 빠진다 (`{"bytes": n}`).
 */
@Serializable
internal data class PurpurBudgetState(val bytes: Long, val mismatched: List<String> = emptyList())
