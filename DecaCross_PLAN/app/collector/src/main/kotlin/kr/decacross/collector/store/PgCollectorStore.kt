package kr.decacross.collector.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.serial.CompatJson
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Properties

/**
 * [CollectorStore] 의 Postgres 구현 (plain JDBC). SQL 마이그레이션이 스키마의 단일 진실 소스다.
 *
 * # 불변식
 * - `mc_versions.ordinal` 을 바꾸는 UPDATE 는 이 파일에 없다 (서수는 INSERT 로만 기록, CLAUDE.md 불변식 2).
 * - 모든 JDBC 호출은 `Dispatchers.IO` 에서 실행한다.
 * - 연결 풀은 [POOL_SIZE] 개. 빌린 연결은 반드시 돌려준다 (취소돼도).
 * - 실행 락은 풀과 별개인 전용 연결이 [close] 까지 쥔다 (세션 advisory lock).
 * - 열 때 스키마(0001~0003 + 0008)가 없으면 [IllegalStateException] — 앱 계층이므로 던져도 된다.
 */
class PgCollectorStore private constructor(
    private val target: DbTarget.Url,
    override val isDevDatabase: Boolean,
    initial: List<Connection>,
) : CollectorStore {
    private val idle = ArrayDeque<Connection>(initial)
    private val poolLock = Any()
    private val permits = Semaphore(initial.size)
    private val runLockMutex = Mutex()
    private var runLockConnection: Connection? = null

    @Volatile
    private var closed = false

    override suspend fun tryAcquireRunLock(): Boolean = runLockMutex.withLock {
        if (runLockConnection != null) return@withLock true
        withContext(Dispatchers.IO) {
            val conn = connect(target)
            val acquired = try {
                conn.prepared("select pg_try_advisory_lock(hashtext('$RUN_LOCK_KEY'))") { ps ->
                    ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            } catch (e: SQLException) {
                conn.closeQuietly()
                throw e
            }
            if (acquired) runLockConnection = conn else conn.closeQuietly()
            acquired
        }
    }

    // ── mc_versions ──

    override suspend fun mcIndex(): List<McRow> = withConnection { c ->
        c.prepared(
            "select id, label, ordinal, released_at, is_snapshot, java_min, java_recommended, rp_format, dp_format, protocol, " +
                "client_jar_url, client_jar_sha1 from mc_versions order by ordinal",
        ) { ps ->
            ps.executeQuery().mapRows { rs ->
                McRow(
                    id = rs.getLong(1),
                    label = rs.getString(2),
                    ordinal = McOrdinal(rs.getInt(3)),
                    releasedAt = requireNotNull(rs.getInstant(4)) { "mc_versions.released_at 은 not null" },
                    isSnapshot = rs.getBoolean(5),
                    javaMin = rs.getInt(6),
                    javaRecommended = rs.getInt(7),
                    rpFormat = rs.getString(8)?.let(PackFormat::parse),
                    dpFormat = rs.getString(9)?.let(PackFormat::parse),
                    protocol = rs.getIntOrNull(10),
                    clientJarUrl = rs.getString(11),
                    clientJarSha1 = rs.getString(12),
                )
            }
        }
    }

    override suspend fun insertMcVersions(rows: List<NewMcVersion>): McInsertResult {
        if (rows.isEmpty()) return McInsertResult.Inserted(0)
        val dupLabel = rows.groupingBy { it.label }.eachCount().entries.firstOrNull { it.value > 1 }
        if (dupLabel != null) return McInsertResult.Conflict("batch 안에서 label 중복: ${dupLabel.key}")
        val dupOrdinal = rows.groupingBy { it.ordinal.value }.eachCount().entries.firstOrNull { it.value > 1 }
        if (dupOrdinal != null) return McInsertResult.Conflict("batch 안에서 ordinal 중복: ${dupOrdinal.key}")
        return withConnection { c ->
            try {
                c.inTransaction { tx ->
                    tx.prepared("select pg_advisory_xact_lock(hashtext('$ORDINAL_LOCK_KEY'))") { it.executeQuery().close() }
                    val existing = tx.prepared("select label, ordinal from mc_versions where label = any(?) or ordinal = any(?) order by ordinal") { ps ->
                        ps.setArray(1, tx.textArray(rows.map { it.label }))
                        ps.setArray(2, tx.intArray(rows.map { it.ordinal.value }))
                        ps.executeQuery().mapRows { rs -> "${rs.getString(1)}=${rs.getInt(2)}" }
                    }
                    if (existing.isNotEmpty()) {
                        throw PlanConflict("label/ordinal exists: ${existing.take(10).joinToString(", ")}")
                    }
                    tx.prepared(
                        "insert into mc_versions (label, ordinal, released_at, is_snapshot, java_min, java_recommended, client_jar_url, client_jar_sha1) " +
                            "values (?,?,?,?,?,?,?,?)",
                    ) { ps ->
                        for (r in rows) {
                            ps.setString(1, r.label)
                            ps.setInt(2, r.ordinal.value)
                            ps.setInstant(3, r.releasedAt)
                            ps.setBoolean(4, r.isSnapshot)
                            ps.setShort(5, r.javaMin.toShort())
                            ps.setShort(6, r.javaRecommended.toShort())
                            ps.setStringOrNull(7, r.clientJarUrl)
                            ps.setStringOrNull(8, r.clientJarSha1)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    McInsertResult.Inserted(rows.size)
                }
            } catch (e: PlanConflict) {
                McInsertResult.Conflict(e.message ?: "conflict")
            } catch (e: SQLException) {
                if (e.isUniqueViolationDeep()) McInsertResult.Conflict("unique_violation: ${e.message}") else throw e
            }
        }
    }

    override suspend fun updateMcFacts(label: String, facts: McFacts): Boolean = withConnection { c ->
        c.prepared("update mc_versions set rp_format = ?, dp_format = ?, protocol = ?, updated_at = now() where label = ?") { ps ->
            ps.setStringOrNull(1, facts.rpFormat?.toString())
            ps.setStringOrNull(2, facts.dpFormat?.toString())
            ps.setIntOrNull(3, facts.protocol)
            ps.setString(4, label)
            ps.executeUpdate() == 1
        }
    }

    // ── core_builds ──

    override suspend fun coreBuildKeys(core: CoreKey): Map<String, Set<String>> = withConnection { c ->
        c.prepared(
            "select m.label, b.build from core_builds b join cores c on c.id = b.core_id join mc_versions m on m.id = b.mc_version_id where c.key = ?",
        ) { ps ->
            ps.setString(1, core.dbKey())
            val out = LinkedHashMap<String, MutableSet<String>>()
            ps.executeQuery().mapRows { rs -> rs.getString(1) to rs.getString(2) }.forEach { (label, build) ->
                out.getOrPut(label) { LinkedHashSet() } += build
            }
            out
        }
    }

    override suspend fun upsertCoreBuilds(core: CoreKey, rows: List<CoreBuildRow>): UpsertCount {
        if (rows.isEmpty()) return UpsertCount()
        return withConnection { c ->
            c.inTransaction { tx ->
                val labelIds = tx.prepared("select label, id from mc_versions where label = any(?)") { ps ->
                    ps.setArray(1, tx.textArray(rows.map { it.mcLabel }.distinct()))
                    ps.executeQuery().mapRows { rs -> rs.getString(1) to rs.getLong(2) }.toMap()
                }
                val coreId = tx.queryScalarLong("select id from cores where key = ?") { it.setString(1, core.dbKey()) }
                    ?: throw IllegalStateException("cores 에 '${core.dbKey()}' 가 없다 (0001 시드 누락)")
                var inserted = 0
                var updated = 0
                var unchanged = 0
                var skipped = 0
                val known = rows.filter { r -> (r.mcLabel in labelIds).also { if (!it) skipped++ } }
                for (chunk in known.chunked(CHUNK_ROWS)) {
                    val oldSha = tx.prepared(
                        "select mc_version_id, build, sha256 from core_builds where core_id = ? and mc_version_id = any(?) and build = any(?)",
                    ) { ps ->
                        ps.setLong(1, coreId)
                        ps.setArray(2, tx.createArrayOf("int8", chunk.mapNotNull { labelIds[it.mcLabel] }.distinct().toTypedArray()))
                        ps.setArray(3, tx.textArray(chunk.map { it.build }.distinct()))
                        ps.executeQuery().mapRows { rs -> (rs.getLong(1) to rs.getString(2)) to rs.getString(3) }.toMap()
                    }
                    tx.prepared(UPSERT_CORE_BUILD) { ps ->
                        for (r in chunk) {
                            val mcId = labelIds.getValue(r.mcLabel)
                            ps.setLong(1, coreId)
                            ps.setLong(2, mcId)
                            ps.setString(3, r.build)
                            ps.setString(4, r.channel.name)
                            ps.setString(5, r.downloadUrl)
                            ps.setString(6, r.sha256)
                            ps.setLong(7, r.size)
                            ps.setInstant(8, r.publishedAt)
                            ps.executeQuery().use { rs ->
                                when {
                                    !rs.next() -> unchanged++

                                    rs.getBoolean(1) -> inserted++

                                    else -> {
                                        updated++
                                        val previous = oldSha[mcId to r.build]
                                        if (previous != null && previous != r.sha256) {
                                            log.warn("core_builds sha256 changed: {} {} {} ({} → {})", core.dbKey(), r.mcLabel, r.build, previous, r.sha256)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                UpsertCount(inserted = inserted, updated = updated, unchanged = unchanged, skipped = skipped)
            }
        }
    }

    // ── java_runtimes ──

    override suspend fun javaFeaturesInUse(): Set<Int> = withConnection { c ->
        c.prepared("select distinct v from (select java_min v from mc_versions union select java_recommended from mc_versions) t") { ps ->
            ps.executeQuery().mapRows { it.getInt(1) }.toSet()
        }
    }

    override suspend fun upsertJavaRuntimes(rows: List<JavaRuntimeRow>): UpsertCount {
        if (rows.isEmpty()) return UpsertCount()
        return withConnection { c ->
            c.inTransaction { tx ->
                var inserted = 0
                var updated = 0
                var unchanged = 0
                tx.prepared(UPSERT_JAVA_RUNTIME) { ps ->
                    for (r in rows) {
                        ps.setString(1, r.distribution.name.lowercase())
                        ps.setShort(2, r.feature.toShort())
                        ps.setString(3, r.os.name.lowercase())
                        ps.setString(4, r.arch.name.lowercase())
                        ps.setString(5, r.imageType.name.lowercase())
                        ps.setString(6, r.releaseName)
                        ps.setString(7, r.openjdkVersion)
                        ps.setString(8, r.packageName)
                        ps.setString(9, r.downloadUrl)
                        ps.setString(10, r.sha256)
                        ps.setLong(11, r.size)
                        ps.setInstant(12, r.publishedAt)
                        ps.executeQuery().use { rs ->
                            when {
                                !rs.next() -> unchanged++
                                rs.getBoolean(1) -> inserted++
                                else -> updated++
                            }
                        }
                    }
                }
                UpsertCount(inserted = inserted, updated = updated, unchanged = unchanged)
            }
        }
    }

    // ── content ──

    override suspend fun upsertContent(row: ContentRow): ContentUpsertResult = withConnection { c ->
        try {
            c.inTransaction { tx ->
                tx.prepared(UPSERT_CONTENT) { ps ->
                    ps.setString(1, row.slug)
                    ps.setString(2, row.name)
                    ps.setString(3, row.kind.name)
                    ps.setString(4, row.source.name)
                    ps.setString(5, row.sourceId)
                    ps.setStringOrNull(6, row.license)
                    ps.setBoolean(7, row.redistributable)
                    ps.setStringOrNull(8, row.author)
                    ps.setLongOrNull(9, row.downloads)
                    ps.setStringOrNull(10, row.iconUrl)
                    ps.setStringOrNull(11, row.description)
                    ps.setStringOrNull(12, row.pageUrl)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "content upsert 가 행을 돌려주지 않았다" }
                        ContentUpsertResult.Stored(rs.getLong(1), inserted = rs.getBoolean(2))
                    }
                }
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolationDeep()) {
                ContentUpsertResult.SlugConflict("${row.source}/${row.slug} 는 다른 source_id 가 사용 중 (source_id ${row.sourceId}): ${e.message}")
            } else {
                throw e
            }
        }
    }

    override suspend fun contentVersions(contentId: Long): List<StoredVersion> = withConnection { c ->
        c.prepared("select id, version, source_version_id, sha256, analyzer_version from content_versions where content_id = ? order by id") { ps ->
            ps.setLong(1, contentId)
            ps.executeQuery().mapRows { rs ->
                StoredVersion(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
            }
        }
    }

    override suspend fun upsertContentVersionsMeta(contentId: Long, items: List<VersionMeta>): Map<String, Long> {
        if (items.isEmpty()) return emptyMap()
        return withConnection { c ->
            c.inTransaction { tx ->
                resetReplacedFiles(tx, contentId, items)
                val out = LinkedHashMap<String, Long>()
                tx.prepared(UPSERT_CONTENT_VERSION) { upsert ->
                    tx.prepared("delete from content_deps where content_version_id = ? and kind in ('REQUIRE','OPTIONAL')") { delete ->
                        tx.prepared(INSERT_DEP) { insertDep ->
                            for (item in items) {
                                val r = item.row
                                upsert.setLong(1, contentId)
                                upsert.setString(2, r.version)
                                upsert.setStringOrNull(3, r.fileUrl)
                                upsert.setStringOrNull(4, r.sha256)
                                upsert.setLongOrNull(5, r.size)
                                upsert.setArray(6, tx.textArray(r.loaders.map { it.name }.sorted()))
                                upsert.setIntOrNull(7, r.mcOrdinalMin?.value)
                                upsert.setIntOrNull(8, r.mcOrdinalMax?.value)
                                upsert.setInstant(9, r.publishedAt)
                                upsert.setStringOrNull(10, r.sourceVersionId)
                                upsert.setStringOrNull(11, r.channel)
                                val versionId = upsert.executeQuery().use { rs ->
                                    check(rs.next()) { "content_versions upsert 가 행을 돌려주지 않았다" }
                                    rs.getLong(1)
                                }
                                delete.setLong(1, versionId)
                                delete.executeUpdate()
                                if (item.deps.isNotEmpty()) {
                                    for (d in item.deps) {
                                        insertDep.setLong(1, versionId)
                                        insertDep.setString(2, d.kind.name)
                                        insertDep.setStringOrNull(3, d.targetSlug)
                                        insertDep.setStringOrNull(4, d.targetCapability?.dbKey())
                                        insertDep.setString(5, d.range)
                                        insertDep.addBatch()
                                    }
                                    insertDep.executeBatch()
                                }
                                out[r.version] = versionId
                            }
                        }
                    }
                }
                out
            }
        }
    }

    /**
     * [upsertContentVersionsMeta] 1단계 (같은 트랜잭션): 다른 파일을 가리키게 될 기존 행의 분석 결과 컬럼과 PROVIDES 를 지운다.
     * sha256/size 도 비우므로 뒤이은 upsert 의 `coalesce(excluded, 기존)` 이 새 값을 그대로 쓴다.
     * 한 호출 안에 같은 version 이 여러 번 오면 마지막 항목이 최종 메타이므로 그것과 비교한다.
     */
    private fun resetReplacedFiles(tx: Connection, contentId: Long, items: List<VersionMeta>) {
        val incoming = items.associateBy { it.row.version }
        val replaced = tx.prepared(
            "select id, version, source_version_id, file_url, sha256 from content_versions where content_id = ? and version = any(?) for update",
        ) { ps ->
            ps.setLong(1, contentId)
            ps.setArray(2, tx.textArray(incoming.keys))
            ps.executeQuery().mapRows { rs ->
                val row = incoming[rs.getString(2)]
                if (row != null && replacesStoredFile(rs.getString(3), rs.getString(4), rs.getString(5), row.row)) rs.getLong(1) else null
            }.filterNotNull()
        }
        if (replaced.isEmpty()) return
        tx.prepared(RESET_ANALYSIS) { ps ->
            ps.setArray(1, tx.createArrayOf("int8", replaced.toTypedArray()))
            ps.executeUpdate()
        }
        tx.prepared("delete from content_deps where content_version_id = any(?) and kind = 'PROVIDES'") { ps ->
            ps.setArray(1, tx.createArrayOf("int8", replaced.toTypedArray()))
            ps.executeUpdate()
        }
        log.info("content_versions 파일 교체로 분석 무효화: content {} 버전 id {}", contentId, replaced)
    }

    override suspend fun recordAnalysis(contentVersionId: Long, record: AnalysisRecord) {
        withConnection { c ->
            c.inTransaction { tx ->
                tx.prepared(
                    "update content_versions set sha256 = ?, size = ?, java_major = ?, api_version = ?, pack_decl = ?::jsonb, analyzed_at = ?, " +
                        "analyzer_version = ?, analysis = ?::jsonb where id = ?",
                ) { ps ->
                    ps.setString(1, record.sha256)
                    ps.setLong(2, record.size)
                    ps.setShortOrNull(3, record.javaMajor)
                    ps.setStringOrNull(4, record.apiVersion)
                    ps.setStringOrNull(5, record.packDecl?.let { CompatJson.encodeToString(PackDecl.serializer(), it) })
                    ps.setInstant(6, record.analyzedAt)
                    ps.setString(7, record.analyzerVersion)
                    ps.setString(8, record.analysisJson)
                    ps.setLong(9, contentVersionId)
                    ps.executeUpdate()
                }
                tx.prepared("delete from content_deps where content_version_id = ? and kind = 'PROVIDES'") { ps ->
                    ps.setLong(1, contentVersionId)
                    ps.executeUpdate()
                }
                val provides = record.provides.distinct()
                if (provides.isNotEmpty()) {
                    tx.prepared(INSERT_DEP) { ps ->
                        for (cap in provides) {
                            ps.setLong(1, contentVersionId)
                            ps.setString(2, DepKind.PROVIDES.name)
                            ps.setStringOrNull(3, null)
                            ps.setString(4, cap.dbKey())
                            ps.setString(5, "*")
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
            }
        }
    }

    override suspend fun findAnalysisBySha256(sha256: String, analyzerVersion: String): AnalysisRecord? = withConnection { c ->
        val found = c.prepared(
            "select id, sha256, size, java_major, api_version, pack_decl::text, analyzed_at, analyzer_version, analysis::text " +
                "from content_versions where sha256 = lower(?) and analyzer_version = ? and analysis is not null order by analyzed_at desc limit 1",
        ) { ps ->
            ps.setString(1, sha256)
            ps.setString(2, analyzerVersion)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    rs.getLong(1) to AnalysisRecord(
                        sha256 = rs.getString(2),
                        size = rs.getLongOrNull(3) ?: 0L,
                        javaMajor = rs.getIntOrNull(4),
                        apiVersion = rs.getString(5),
                        packDecl = rs.getString(6)?.let { CompatJson.decodeFromString(PackDecl.serializer(), it) },
                        analyzedAt = requireNotNull(rs.getInstant(7)) { "analysis 가 있는 행의 analyzed_at 이 null" },
                        analyzerVersion = rs.getString(8),
                        analysisJson = rs.getString(9),
                        provides = emptyList(),
                    )
                }
            }
        } ?: return@withConnection null
        val provides = c.prepared("select target_capability from content_deps where content_version_id = ? and kind = 'PROVIDES' order by id") { ps ->
            ps.setLong(1, found.first)
            ps.executeQuery().mapRows { rs -> rs.getString(1) }
        }.mapNotNull { key ->
            val cap = key?.let(::capabilityFromDbKey)
            if (cap == null) log.warn("알 수 없는 target_capability: {}", key)
            cap
        }
        found.second.copy(provides = provides)
    }

    // ── collector_state ──

    override suspend fun getState(key: String): String? = withConnection { c ->
        c.prepared("select value::text from collector_state where key = ?") { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override suspend fun putState(key: String, jsonValue: String) {
        withConnection { c ->
            c.prepared(
                "insert into collector_state (key, value) values (?, ?::jsonb) on conflict (key) do update set value = excluded.value, updated_at = now()",
            ) { ps ->
                ps.setString(1, key)
                ps.setString(2, jsonValue)
                ps.executeUpdate()
            }
        }
    }

    // ── 보고 ──

    override suspend fun counts(): TableCounts = withConnection { c ->
        val byCore = c.prepared("select c.key, count(*) from core_builds b join cores c on c.id = b.core_id group by c.key") { ps ->
            ps.executeQuery().mapRows { rs -> rs.getString(1) to rs.getLong(2) }
        }.mapNotNull { (key, n) -> CoreKey.entries.firstOrNull { it.dbKey() == key }?.let { it to n } }.toMap()
        c.prepared(
            "select (select count(*) from mc_versions), (select count(*) from mc_versions where not is_snapshot), " +
                "(select count(*) from mc_versions where is_snapshot), (select count(*) from core_builds), (select count(*) from content), " +
                "(select count(*) from content_versions), (select count(*) from content_versions where analyzed_at is not null), " +
                "(select count(*) from content_deps), (select count(*) from java_runtimes)",
        ) { ps ->
            ps.executeQuery().use { rs ->
                check(rs.next())
                TableCounts(
                    mcVersions = rs.getLong(1),
                    mcReleases = rs.getLong(2),
                    mcSnapshots = rs.getLong(3),
                    coreBuilds = rs.getLong(4),
                    coreBuildsByCore = byCore,
                    content = rs.getLong(5),
                    contentVersions = rs.getLong(6),
                    analyzedContentVersions = rs.getLong(7),
                    contentDeps = rs.getLong(8),
                    javaRuntimes = rs.getLong(9),
                )
            }
        }
    }

    /** 읽기 전용 검사(SanityChecks)용 연결 대여. 이 안에서 쓰기를 하지 마라. */
    internal suspend fun <T> read(block: (Connection) -> T): T = withConnection(block)

    override fun close() {
        if (closed) return
        closed = true
        runLockConnection?.closeQuietly()
        runLockConnection = null
        synchronized(poolLock) {
            idle.forEach { it.closeQuietly() }
            idle.clear()
        }
    }

    // ── 연결 풀 ──

    private suspend fun <T> withConnection(block: (Connection) -> T): T = permits.withPermit {
        check(!closed) { "PgCollectorStore 가 이미 닫혔다" }
        withContext(Dispatchers.IO) {
            val borrowed = synchronized(poolLock) { idle.pollFirst() } ?: connect(target)
            var conn = borrowed
            try {
                if (!isUsable(conn)) {
                    conn.closeQuietly()
                    conn = connect(target)
                }
                block(conn)
            } finally {
                val returned = conn
                synchronized(poolLock) { if (closed) returned.closeQuietly() else idle.addLast(returned) }
            }
        }
    }

    private fun isUsable(conn: Connection): Boolean = try {
        !conn.isClosed && conn.isValid(VALIDATION_TIMEOUT_SEC)
    } catch (e: SQLException) {
        false
    }

    /** 스키마가 이 수집기가 기대하는 모양인지 확인한다. */
    private fun verifySchema(conn: Connection) {
        val missing = REQUIRED_TABLES.filter { t ->
            conn.prepared("select to_regclass(?)") { ps ->
                ps.setString(1, "public.$t")
                ps.executeQuery().use { rs -> !rs.next() || rs.getString(1) == null }
            }
        }.toMutableList()
        val hasAnalysis = conn.prepared(
            "select 1 from information_schema.columns where table_schema = 'public' and table_name = 'content_versions' and column_name = 'analysis'",
        ) { ps -> ps.executeQuery().use { it.next() } }
        if (!hasAnalysis) missing += "content_versions.analysis"
        check(missing.isEmpty()) { "마이그레이션 미적용: ${missing.joinToString(", ")} (--migrate 또는 개발 DB 로 실행)" }
    }

    private class PlanConflict(message: String) : RuntimeException(message)

    companion object {
        private val log = LoggerFactory.getLogger(PgCollectorStore::class.java)

        const val POOL_SIZE: Int = 4
        private const val VALIDATION_TIMEOUT_SEC = 2
        private const val CHUNK_ROWS = 500
        internal const val RUN_LOCK_KEY: String = "decacross.collector.run"
        internal const val ORDINAL_LOCK_KEY: String = "decacross.mc_versions.ordinal"

        internal val REQUIRED_TABLES: List<String> = listOf(
            "mc_versions", "cores", "core_builds", "content", "content_versions", "content_deps", "java_runtimes", "collector_state",
        )

        private const val UPSERT_CORE_BUILD: String = """
            insert into core_builds (core_id, mc_version_id, build, channel, download_url, sha256, size, published_at)
            values (?,?,?,?,?,?,?,?)
            on conflict (core_id, mc_version_id, build) do update
              set channel = excluded.channel, download_url = excluded.download_url, sha256 = excluded.sha256,
                  size = excluded.size, published_at = excluded.published_at, collected_at = now()
              where (core_builds.channel, core_builds.download_url, core_builds.sha256, core_builds.size)
                    is distinct from (excluded.channel, excluded.download_url, excluded.sha256, excluded.size)
            returning (xmax = 0) as inserted
        """

        private const val UPSERT_JAVA_RUNTIME: String = """
            insert into java_runtimes (distribution, feature, os, arch, image_type, release_name, openjdk_version, package_name,
                                       download_url, sha256, size, published_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?)
            on conflict (distribution, feature, os, arch, image_type, release_name) do update
              set openjdk_version = excluded.openjdk_version, package_name = excluded.package_name, download_url = excluded.download_url,
                  sha256 = excluded.sha256, size = excluded.size, published_at = excluded.published_at, collected_at = now()
              where (java_runtimes.openjdk_version, java_runtimes.package_name, java_runtimes.download_url, java_runtimes.sha256,
                     java_runtimes.size, java_runtimes.published_at)
                    is distinct from (excluded.openjdk_version, excluded.package_name, excluded.download_url, excluded.sha256,
                                      excluded.size, excluded.published_at)
            returning (xmax = 0) as inserted
        """

        private const val UPSERT_CONTENT: String = """
            insert into content (slug, name, kind, source, source_id, license, redistributable, author, downloads, icon_url, description, page_url)
            values (?,?,?,?,?,?,?,?,?,?,?,?)
            on conflict (source, source_id) where source_id is not null do update
              set slug = excluded.slug, name = excluded.name, kind = excluded.kind, license = excluded.license,
                  redistributable = excluded.redistributable, author = excluded.author, downloads = excluded.downloads,
                  icon_url = excluded.icon_url, description = excluded.description, page_url = excluded.page_url, updated_at = now()
            returning id, (xmax = 0) as inserted
        """

        private const val UPSERT_CONTENT_VERSION: String = """
            insert into content_versions (content_id, version, file_url, sha256, size, loaders, mc_ordinal_min, mc_ordinal_max,
                                          published_at, source_version_id, channel)
            values (?,?,?,?,?,?,?,?,?,?,?)
            on conflict (content_id, version) do update
              set file_url = excluded.file_url,
                  sha256 = coalesce(excluded.sha256, content_versions.sha256),
                  size = coalesce(excluded.size, content_versions.size),
                  loaders = excluded.loaders, mc_ordinal_min = excluded.mc_ordinal_min, mc_ordinal_max = excluded.mc_ordinal_max,
                  published_at = excluded.published_at, source_version_id = excluded.source_version_id, channel = excluded.channel
            returning id
        """

        /** 다른 파일로 바뀐 행: 예전 jar 에서 잰 값 전부를 비운다 (메타 컬럼은 뒤이은 upsert 가 채운다). */
        private const val RESET_ANALYSIS: String = """
            update content_versions
              set sha256 = null, size = null, java_major = null, api_version = null, pack_decl = null,
                  analyzed_at = null, analyzer_version = null, analysis = null
              where id = any(?)
        """

        private const val INSERT_DEP: String =
            "insert into content_deps (content_version_id, kind, target_slug, target_capability, range) values (?,?,?,?,?)"

        /**
         * 외부(또는 개발) DB 에 연결하고 스키마를 확인한다.
         * @throws IllegalStateException 스키마(0001~0003, 0008)가 없을 때
         * @throws SQLException 연결 실패
         */
        fun open(target: DbTarget.Url, isDevDatabase: Boolean): PgCollectorStore {
            val connections = ArrayList<Connection>(POOL_SIZE)
            try {
                repeat(POOL_SIZE) { connections += connect(target) }
                val store = PgCollectorStore(target, isDevDatabase, connections)
                store.verifySchema(connections.first())
                return store
            } catch (e: Exception) {
                connections.forEach { it.closeQuietly() }
                throw e
            }
        }

        /** 풀 밖 단발 연결 (마이그레이션 등). 호출자가 닫는다. */
        fun connect(target: DbTarget.Url): Connection {
            val props = Properties()
            props.setProperty("ApplicationName", "decacross-collector")
            props.setProperty("reWriteBatchedInserts", "true")
            target.user?.let { props.setProperty("user", it) }
            target.password?.let { props.setProperty("password", it) }
            return DriverManager.getConnection(target.jdbcUrl, props)
        }

        private fun CoreKey.dbKey(): String = name.lowercase()

        private fun Connection.closeQuietly() {
            try {
                close()
            } catch (e: SQLException) {
                log.debug("연결 닫기 실패: {}", e.toString())
            }
        }

        /** batch 실행은 BatchUpdateException 의 next 에 원인을 싣는다. */
        private fun SQLException.isUniqueViolationDeep(): Boolean {
            var cur: SQLException? = this
            var depth = 0
            while (cur != null && depth < 10) {
                if (cur.isUniqueViolation()) return true
                cur = cur.nextException
                depth++
            }
            return (cause as? SQLException)?.isUniqueViolation() == true
        }
    }
}
