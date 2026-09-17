package kr.decacross.collector.store

import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.Distribution
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Os
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.model.Source
import kotlin.time.Instant

// ── DB 접근의 유일한 경계 (prompt 02 제약: "DB 접근은 한 곳에 모아라") ─────────────
// 구현: WP-C1 PgCollectorStore (plain JDBC). 테스트: testkit/RecordingStore.
// 아래 *Row / New* / Stored* 타입은 DB 행 DTO 다 (label·publishedAt·sourceId 같은 DB 전용 필드를 싣는다).
// 데이터 계약은 여전히 명세 §2 의 McVersion·CoreBuild·Content 등이다 — 이 DTO 를 엔진·데몬으로 내보내지 마라.

/**
 * 수집기가 DB 에 하는 일 전부.
 *
 * # 불변식
 * - `mc_versions.ordinal` 은 [insertMcVersions] 의 INSERT 로만 기록된다. ordinal 을 바꾸는 UPDATE 는 존재하지 않는다.
 * - `content.redistributable` 은 [ContentRow] 에 기본값 없이 들어온다 (호출자가 라이선스 정책으로 결정).
 * - 모든 메서드는 블로킹 JDBC 를 `Dispatchers.IO` 에서 실행한다 (구현 책임).
 */
interface CollectorStore : AutoCloseable {
    /** 대상이 개발용 임베디드 DB 인가. */
    val isDevDatabase: Boolean

    /** 프로세스 단위 실행 락 (`pg_try_advisory_lock`). 다른 수집기가 돌고 있으면 false. [close] 에서 해제. */
    suspend fun tryAcquireRunLock(): Boolean

    // ── mc_versions ──

    /** 전체 MC 버전 (ordinal 오름차순). */
    suspend fun mcIndex(): List<McRow>

    /**
     * 서수가 이미 계획된 신규 행을 한 트랜잭션으로 INSERT 한다 (`pg_advisory_xact_lock` 보유).
     * label 또는 ordinal 이 하나라도 이미 있으면 전부 롤백하고 [McInsertResult.Conflict].
     */
    suspend fun insertMcVersions(rows: List<NewMcVersion>): McInsertResult

    /** 서수 외 사실(pack format, protocol)만 갱신. 대상이 없으면 false. */
    suspend fun updateMcFacts(label: String, facts: McFacts): Boolean

    // ── core_builds ──

    /** mc label → 이미 저장된 build 문자열 집합. */
    suspend fun coreBuildKeys(core: CoreKey): Map<String, Set<String>>

    /**
     * `(core, mc label, build)` 기준 upsert. channel/url/sha256/size/published_at 이 달라졌을 때만 UPDATE.
     * mc label 이 mc_versions 에 없으면 그 행은 건너뛰고 [UpsertCount.skipped] 로 센다.
     */
    suspend fun upsertCoreBuilds(core: CoreKey, rows: List<CoreBuildRow>): UpsertCount

    // ── java_runtimes (0008) ──

    /** `mc_versions.java_min ∪ java_recommended` 의 서로 다른 값. */
    suspend fun javaFeaturesInUse(): Set<Int>

    /** `(distribution, feature, os, arch, image_type, release_name)` 기준 upsert. */
    suspend fun upsertJavaRuntimes(rows: List<JavaRuntimeRow>): UpsertCount

    // ── content / content_versions / content_deps ──

    /** `(source, source_id)` 기준 upsert. 같은 source 에서 slug 가 다른 source_id 에 이미 쓰였으면 [ContentUpsertResult.SlugConflict]. */
    suspend fun upsertContent(row: ContentRow): ContentUpsertResult

    suspend fun contentVersions(contentId: Long): List<StoredVersion>

    /**
     * 버전 메타데이터를 `(content_id, version)` 기준 upsert 하고, 각 버전의 REQUIRE/OPTIONAL 의존성을 교체한다.
     * 분석 결과 컬럼(java_major, analysis …)과 PROVIDES 의존성은 건드리지 않는다.
     * sha256/size 는 새 값이 null 이면 기존 값을 유지한다.
     * @return version → content_versions.id
     */
    suspend fun upsertContentVersionsMeta(contentId: Long, items: List<VersionMeta>): Map<String, Long>

    /** 분석 결과 컬럼을 기록하고 PROVIDES 의존성을 교체한다 (한 트랜잭션). */
    suspend fun recordAnalysis(contentVersionId: Long, record: AnalysisRecord)

    /** 같은 sha256 을 같은 분석기 버전으로 이미 분석한 결과 (Modrinth↔Hangar 동일 jar 재다운로드 방지). */
    suspend fun findAnalysisBySha256(sha256: String, analyzerVersion: String): AnalysisRecord?

    // ── collector_state (0008) ──

    /** JSON 텍스트. 없으면 null. */
    suspend fun getState(key: String): String?

    suspend fun putState(key: String, jsonValue: String)

    // ── 보고 ──

    suspend fun counts(): TableCounts

    /** 연결을 닫고 실행 락을 해제한다. 임베디드 DB 는 Runner 가 따로 닫는다. */
    override fun close()
}

data class McRow(
    val id: Long,
    val label: String,
    val ordinal: McOrdinal,
    val releasedAt: Instant,
    val isSnapshot: Boolean,
    val javaMin: Int,
    val javaRecommended: Int,
    val rpFormat: PackFormat?,
    val dpFormat: PackFormat?,
    val protocol: Int?,
    val clientJarUrl: String?,
    val clientJarSha1: String?,
)

/** 서수가 계획된 신규 MC 버전. */
data class NewMcVersion(
    val label: String,
    val ordinal: McOrdinal,
    val releasedAt: Instant,
    val isSnapshot: Boolean,
    val javaMin: Int,
    val javaRecommended: Int,
    val clientJarUrl: String?,
    val clientJarSha1: String?,
)

sealed interface McInsertResult {
    data class Inserted(val count: Int) : McInsertResult

    /** 계획이 낡았다 (label/ordinal 충돌). 아무것도 기록되지 않았다. */
    data class Conflict(val detail: String) : McInsertResult
}

/** 값 그대로 기록한다 (null 은 null 로). */
data class McFacts(val rpFormat: PackFormat?, val dpFormat: PackFormat?, val protocol: Int?)

data class CoreBuildRow(
    val mcLabel: String,
    val build: String,
    val channel: Channel,
    val downloadUrl: String,
    /** 소문자 64 hex */
    val sha256: String,
    val size: Long,
    val publishedAt: Instant?,
)

data class UpsertCount(val inserted: Int = 0, val updated: Int = 0, val unchanged: Int = 0, val skipped: Int = 0)

data class JavaRuntimeRow(
    val feature: Int,
    val os: Os,
    val arch: Arch,
    val imageType: ImageType,
    /** `jdk-21.0.12.1+1` | `jdk8u504-b01` */
    val releaseName: String,
    /** `21.0.12.1+1-LTS` (semver 필드는 쓰지 않는다) */
    val openjdkVersion: String,
    val packageName: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
    val publishedAt: Instant?,
    val distribution: Distribution = Distribution.TEMURIN,
)

data class ContentRow(
    val source: Source,
    /** 원본 저장소의 안정 ID (Modrinth project id, Hangar numeric id). slug 는 바뀔 수 있다. */
    val sourceId: String,
    val slug: String,
    val name: String,
    val kind: ContentKind,
    /** SPDX id 또는 원문. 모르면 null. */
    val license: String?,
    /** ★ 기본값 없음 (불변식 5). 허용 목록 정확 일치일 때만 true. */
    val redistributable: Boolean,
    val author: String?,
    val downloads: Long?,
    val iconUrl: String?,
    val description: String?,
    val pageUrl: String?,
)

sealed interface ContentUpsertResult {
    data class Stored(val id: Long, val inserted: Boolean) : ContentUpsertResult

    data class SlugConflict(val detail: String) : ContentUpsertResult
}

/** 플랫폼 메타데이터로 채우는 버전 행 (분석 전). */
data class ContentVersionRow(
    val version: String,
    val sourceVersionId: String?,
    /** Modrinth version_type / Hangar channel 이름 원문 */
    val channel: String?,
    val fileUrl: String?,
    /** 플랫폼이 준 sha256 (Hangar). 없으면 null → 기존 값 유지. */
    val sha256: String?,
    val size: Long?,
    /** DB `text[]` 에는 `LoaderFamily.name` (BUKKIT|FABRIC|FORGE|VANILLA, 0002 주석) 으로 쓴다. */
    val loaders: Set<LoaderFamily>,
    val mcOrdinalMin: McOrdinal?,
    val mcOrdinalMax: McOrdinal?,
    val publishedAt: Instant?,
)

/**
 * `content_deps` 한 행.
 *
 * # 불변식
 * - [targetSlug] 와 [targetCapability] 중 정확히 하나만 non-null (DB CHECK 와 동일).
 * - [targetSlug] 는 의존하는 콘텐츠와 **같은 source** 의 slug 다 (0002 에 target_source 컬럼이 없어서 생긴 규약).
 */
data class DepRow(
    val kind: DepKind,
    val targetSlug: String?,
    val targetCapability: Capability?,
    val range: String = "*",
) {
    init {
        require((targetSlug == null) != (targetCapability == null)) { "targetSlug/targetCapability 중 정확히 하나: $this" }
        require((kind == DepKind.PROVIDES) == (targetCapability != null)) { "PROVIDES ⇔ capability 대상: $this" }
    }
}

/** 버전 메타데이터 + 플랫폼 의존성(REQUIRE/OPTIONAL). */
data class VersionMeta(val row: ContentVersionRow, val deps: List<DepRow>) {
    init {
        require(deps.none { it.kind == DepKind.PROVIDES }) { "PROVIDES 는 recordAnalysis 로만 기록한다" }
    }
}

data class StoredVersion(
    val id: Long,
    val version: String,
    val sourceVersionId: String?,
    val sha256: String?,
    /** 분석된 적 없으면 null */
    val analyzerVersion: String?,
)

/** jar 분석 결과 (content_versions 분석 컬럼 + PROVIDES). */
data class AnalysisRecord(
    val sha256: String,
    val size: Long,
    val javaMajor: Int?,
    val apiVersion: String?,
    val packDecl: PackDecl?,
    val analyzedAt: Instant,
    val analyzerVersion: String,
    /** content_versions.analysis (jsonb) 원문 */
    val analysisJson: String,
    /** confidence=STORE 인 Capability 만 */
    val provides: List<Capability>,
)

data class TableCounts(
    val mcVersions: Long,
    val mcReleases: Long,
    val mcSnapshots: Long,
    val coreBuilds: Long,
    val coreBuildsByCore: Map<CoreKey, Long>,
    val content: Long,
    val contentVersions: Long,
    val analyzedContentVersions: Long,
    val contentDeps: Long,
    val javaRuntimes: Long,
)

/** `content_deps.target_capability` 텍스트 (0002 주석 및 `@SerialName` 과 동일). */
fun Capability.dbKey(): String = when (this) {
    Capability.CustomItemFramework -> "custom_item_framework"
    Capability.EconomyProvider -> "economy_provider"
    Capability.PermissionProvider -> "permission_provider"
    Capability.AntiCheat -> "anti_cheat"
    Capability.ChunkGenerator -> "chunk_generator"
    is Capability.Other -> "other:$key"
}

/** [dbKey] 의 역. 모르는 값이면 null. */
fun capabilityFromDbKey(s: String): Capability? = when {
    s == "custom_item_framework" -> Capability.CustomItemFramework
    s == "economy_provider" -> Capability.EconomyProvider
    s == "permission_provider" -> Capability.PermissionProvider
    s == "anti_cheat" -> Capability.AntiCheat
    s == "chunk_generator" -> Capability.ChunkGenerator
    s.startsWith("other:") && s.length > "other:".length -> Capability.Other(s.removePrefix("other:"))
    else -> null
}
