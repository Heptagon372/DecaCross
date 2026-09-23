package kr.decacross.collector.db

import kotlinx.serialization.json.Json
import kr.decacross.collector.ContentRaw
import kr.decacross.compat.db.CompatFixture
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.serial.CompatJson
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.json.jsonb
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant

// ★ 모든 DB 코드는 이 파일 한 곳에만 (prompts/02: "DB 접근은 한 곳에 모아라").
// 스키마는 infra/supabase/migrations/0001~0002 를 그대로 비춘다. 여기서 테이블을 만들지 않는다 (마이그레이션이 진실).
// 이 싱크는 DATABASE_URL 이 있을 때만 쓰이고 자동 테스트는 없다 (README 참고).

/** `timestamptz` ↔ [OffsetDateTime]. exposed-java-time 모듈 없이 쓰기 위한 최소 컬럼 타입. */
private object TimestampTz : ColumnType<OffsetDateTime>() {
    override fun sqlType(): String = "timestamptz"

    override fun valueFromDB(value: Any): OffsetDateTime = when (value) {
        is OffsetDateTime -> value
        is java.sql.Timestamp -> value.toInstant().atOffset(ZoneOffset.UTC)
        is java.time.Instant -> value.atOffset(ZoneOffset.UTC)
        else -> OffsetDateTime.parse(value.toString())
    }

    override fun notNullValueToDB(value: OffsetDateTime): Any = value
}

private fun Table.timestampTz(name: String): Column<OffsetDateTime> = registerColumn(name, TimestampTz)

private fun Instant.toOffset(): OffsetDateTime = toJavaInstant().atOffset(ZoneOffset.UTC)

private val packDeclJson: Json = CompatJson

private object McVersions : Table("mc_versions") {
    val id = long("id").autoIncrement()
    val label = text("label")
    val ordinal = integer("ordinal")
    val releasedAt = timestampTz("released_at")
    val isSnapshot = bool("is_snapshot")
    val javaMin = short("java_min")
    val javaRecommended = short("java_recommended")
    val rpFormat = text("rp_format").nullable()
    val dpFormat = text("dp_format").nullable()
    val protocol = integer("protocol").nullable()
    val updatedAt = timestampTz("updated_at")
    override val primaryKey = PrimaryKey(id)
}

private object Cores : Table("cores") {
    val id = long("id").autoIncrement()
    val key = text("key")
    override val primaryKey = PrimaryKey(id)
}

private object CoreBuilds : Table("core_builds") {
    val id = long("id").autoIncrement()
    val coreId = long("core_id")
    val mcVersionId = long("mc_version_id")
    val build = text("build")
    val channel = text("channel")
    val downloadUrl = text("download_url")
    val sha256 = text("sha256")
    val size = long("size")
    val collectedAt = timestampTz("collected_at")
    override val primaryKey = PrimaryKey(id)
}

private object Contents : Table("content") {
    val id = long("id").autoIncrement()
    val slug = text("slug")
    val name = text("name")
    val kind = text("kind")
    val sourceName = text("source")
    val sourceId = text("source_id").nullable()
    val license = text("license").nullable()
    val redistributable = bool("redistributable")
    val author = text("author").nullable()
    val downloads = long("downloads").nullable()
    val iconUrl = text("icon_url").nullable()
    val description = text("description").nullable()
    val pageUrl = text("page_url").nullable()
    val updatedAt = timestampTz("updated_at")
    override val primaryKey = PrimaryKey(id)
}

private object ContentVersions : Table("content_versions") {
    val id = long("id").autoIncrement()
    val contentId = long("content_id")
    val version = text("version")
    val fileUrl = text("file_url").nullable()
    val sha256 = text("sha256").nullable()
    val size = long("size").nullable()
    val javaMajor = short("java_major").nullable()
    val apiVersion = text("api_version").nullable()
    val loaders = array<String>("loaders")
    val mcOrdinalMin = integer("mc_ordinal_min").nullable()
    val mcOrdinalMax = integer("mc_ordinal_max").nullable()
    val packDecl = jsonb<PackDecl>("pack_decl", packDeclJson).nullable()
    override val primaryKey = PrimaryKey(id)
}

private object ContentDeps : Table("content_deps") {
    val id = long("id").autoIncrement()
    val contentVersionId = long("content_version_id")
    val kind = text("kind")
    val targetSlug = text("target_slug").nullable()
    val targetCapability = text("target_capability").nullable()
    val range = text("range")
    override val primaryKey = PrimaryKey(id)
}

/** `target_capability` 컬럼 표기: custom_item_framework | … | other:<key> (0002 마이그레이션 주석). */
private fun capabilityKey(cap: kr.decacross.compat.model.Capability): String = when (cap) {
    kr.decacross.compat.model.Capability.CustomItemFramework -> "custom_item_framework"
    kr.decacross.compat.model.Capability.EconomyProvider -> "economy_provider"
    kr.decacross.compat.model.Capability.PermissionProvider -> "permission_provider"
    kr.decacross.compat.model.Capability.AntiCheat -> "anti_cheat"
    kr.decacross.compat.model.Capability.ChunkGenerator -> "chunk_generator"
    is kr.decacross.compat.model.Capability.Other -> "other:${cap.key}"
}

/**
 * Postgres 싱크. [upsertAll] 은 픽스처 전체를 마이그레이션 스키마에 upsert 한다.
 * ★ mc_versions.ordinal 은 INSERT 에만 들어가고 UPDATE 목록에서 빠진다 — 서수 재할당 금지 (불변식 2).
 */
class PostgresSink(jdbcUrl: String) : Closeable {
    private val db: Database = Database.connect(jdbcUrl, driver = "org.postgresql.Driver")

    fun upsertAll(fixture: CompatFixture, contentMeta: Map<String, ContentRaw>) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        transaction(db) {
            // mc_versions
            for (v in fixture.mcVersions) {
                McVersions.upsert(
                    McVersions.label,
                    onUpdateExclude = listOf(McVersions.ordinal, McVersions.id),
                ) {
                    it[label] = v.label
                    it[ordinal] = v.ordinal.value
                    it[releasedAt] = v.releasedAt.toOffset()
                    it[isSnapshot] = v.isSnapshot
                    it[javaMin] = v.javaMin.toShort()
                    it[javaRecommended] = v.javaRecommended.toShort()
                    it[rpFormat] = v.rpFormat?.toString()
                    it[dpFormat] = v.dpFormat?.toString()
                    it[protocol] = v.protocol
                    it[updatedAt] = now
                }
            }
            val mcIds: Map<String, Long> = McVersions.selectAll().associate { it[McVersions.label] to it[McVersions.id] }
            val mcIdByOrdinal: Map<Int, Long> = McVersions.selectAll().associate { it[McVersions.ordinal] to it[McVersions.id] }
            val coreIds: Map<String, Long> = Cores.selectAll().associate { it[Cores.key] to it[Cores.id] }

            // core_builds
            for (b in fixture.coreBuilds) {
                val coreId = coreIds[b.core.name.lowercase()] ?: continue
                val mcId = mcIdByOrdinal[b.mc.value] ?: continue
                CoreBuilds.upsert(CoreBuilds.coreId, CoreBuilds.mcVersionId, CoreBuilds.build, onUpdateExclude = listOf(CoreBuilds.id)) {
                    it[CoreBuilds.coreId] = coreId
                    it[mcVersionId] = mcId
                    it[build] = b.build
                    it[channel] = b.channel.name
                    it[downloadUrl] = b.downloadUrl
                    it[sha256] = b.sha256
                    it[size] = b.size
                    it[collectedAt] = now
                }
            }

            // content
            for (c in fixture.content) {
                val meta = contentMeta[c.slug]
                Contents.upsert(Contents.sourceName, Contents.slug, onUpdateExclude = listOf(Contents.id)) {
                    it[slug] = c.slug
                    it[name] = c.name
                    it[kind] = c.kind.name
                    it[sourceName] = c.source.name
                    it[sourceId] = meta?.sourceId
                    it[license] = c.license
                    it[redistributable] = c.redistributable
                    it[author] = meta?.author
                    it[downloads] = meta?.downloads
                    it[iconUrl] = meta?.iconUrl
                    it[description] = meta?.description
                    it[pageUrl] = meta?.pageUrl
                    it[updatedAt] = now
                }
            }
            val contentIds: Map<String, Long> = Contents.selectAll().associate { it[Contents.slug] to it[Contents.id] }

            // content_versions + content_deps
            for (cv in fixture.contentVersions) {
                val contentId = contentIds[cv.slug] ?: continue
                ContentVersions.upsert(ContentVersions.contentId, ContentVersions.version, onUpdateExclude = listOf(ContentVersions.id)) {
                    it[ContentVersions.contentId] = contentId
                    it[version] = cv.version
                    it[fileUrl] = cv.fileUrl
                    it[sha256] = cv.sha256
                    it[size] = cv.size
                    it[javaMajor] = cv.javaMajor?.toShort()
                    it[apiVersion] = cv.apiVersion
                    it[loaders] = cv.loaders.map { l -> l.name }
                    it[mcOrdinalMin] = cv.mcMin?.value
                    it[mcOrdinalMax] = cv.mcMax?.value
                    it[packDecl] = cv.pack
                }
                val versionId = ContentVersions.selectAll()
                    .where { (ContentVersions.contentId eq contentId) and (ContentVersions.version eq cv.version) }
                    .firstOrNull()?.get(ContentVersions.id) ?: continue
                ContentDeps.deleteWhere { contentVersionId eq versionId }
                ContentDeps.batchInsert(cv.deps) { d ->
                    this[ContentDeps.contentVersionId] = versionId
                    this[ContentDeps.kind] = d.kind.name
                    this[ContentDeps.targetSlug] = (d.target as? DepTarget.Slug)?.value
                    this[ContentDeps.targetCapability] = (d.target as? DepTarget.Cap)?.let { capabilityKey(it.value) }
                    this[ContentDeps.range] = d.range
                }
            }
        }
        log.info("Postgres upsert 완료: mc={}, cores={}, content={}, versions={}", fixture.mcVersions.size, fixture.coreBuilds.size, fixture.content.size, fixture.contentVersions.size)
    }

    override fun close() {
        // Exposed 1.x 의 Database 는 명시적 close 가 없다 (커넥션은 트랜잭션마다 열고 닫는다).
    }

    private companion object {
        val log = LoggerFactory.getLogger(PostgresSink::class.java)
    }
}
