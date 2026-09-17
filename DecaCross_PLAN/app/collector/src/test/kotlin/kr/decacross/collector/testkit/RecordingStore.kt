package kr.decacross.collector.testkit

import kr.decacross.collector.store.AnalysisRecord
import kr.decacross.collector.store.CollectorStore
import kr.decacross.collector.store.ContentRow
import kr.decacross.collector.store.ContentUpsertResult
import kr.decacross.collector.store.CoreBuildRow
import kr.decacross.collector.store.DepRow
import kr.decacross.collector.store.JavaRuntimeRow
import kr.decacross.collector.store.McFacts
import kr.decacross.collector.store.McInsertResult
import kr.decacross.collector.store.McRow
import kr.decacross.collector.store.NewMcVersion
import kr.decacross.collector.store.StoredVersion
import kr.decacross.collector.store.TableCounts
import kr.decacross.collector.store.UpsertCount
import kr.decacross.collector.store.VersionMeta
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.Source

/**
 * 메모리 [CollectorStore]. PgCollectorStore 와 같은 **관찰 가능한 의미**를 가진 참조 구현이다
 * (서수 UPDATE 없음, 충돌 시 전체 롤백, sha256 null 이면 기존 값 유지, PROVIDES 분리 등).
 * WP0 소유 — 수정하지 말 것. 테스트 데이터는 공개 필드로 직접 넣어도 된다.
 */
class RecordingStore(
    override val isDevDatabase: Boolean = true,
) : CollectorStore {
    private val lock = Any()
    private var nextId = 1L

    val mc = ArrayList<McRow>()
    val mcFactsUpdates = ArrayList<Pair<String, McFacts>>()
    val coreBuilds = LinkedHashMap<Triple<CoreKey, String, String>, CoreBuildRow>()
    val javaRuntimes = LinkedHashMap<List<Any>, JavaRuntimeRow>()
    val contents = LinkedHashMap<Long, ContentRow>()
    val versions = LinkedHashMap<Long, MutableMap<String, Pair<Long, VersionMeta>>>()
    val deps = LinkedHashMap<Long, MutableList<DepRow>>()
    val analyses = LinkedHashMap<Long, AnalysisRecord>()
    val state = LinkedHashMap<String, String>()
    var closed = false
        private set

    private fun id(): Long = nextId++

    override suspend fun tryAcquireRunLock(): Boolean = true

    override suspend fun mcIndex(): List<McRow> = synchronized(lock) { mc.sortedBy { it.ordinal } }

    override suspend fun insertMcVersions(rows: List<NewMcVersion>): McInsertResult = synchronized(lock) {
        val labels = mc.mapTo(HashSet()) { it.label }
        val ordinals = mc.mapTo(HashSet()) { it.ordinal }
        val seenLabels = HashSet<String>()
        val seenOrdinals = HashSet<Int>()
        for (r in rows) {
            if (r.label in labels || !seenLabels.add(r.label)) return McInsertResult.Conflict("label ${r.label}")
            if (r.ordinal in ordinals || !seenOrdinals.add(r.ordinal.value)) return McInsertResult.Conflict("ordinal ${r.ordinal.value}")
        }
        for (r in rows) {
            mc += McRow(id(), r.label, r.ordinal, r.releasedAt, r.isSnapshot, r.javaMin, r.javaRecommended, null, null, null, r.clientJarUrl, r.clientJarSha1)
        }
        McInsertResult.Inserted(rows.size)
    }

    override suspend fun updateMcFacts(label: String, facts: McFacts): Boolean = synchronized(lock) {
        val i = mc.indexOfFirst { it.label == label }
        if (i < 0) return false
        mc[i] = mc[i].copy(rpFormat = facts.rpFormat, dpFormat = facts.dpFormat, protocol = facts.protocol)
        mcFactsUpdates += label to facts
        true
    }

    override suspend fun coreBuildKeys(core: CoreKey): Map<String, Set<String>> = synchronized(lock) {
        coreBuilds.keys.filter { it.first == core }.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() }
    }

    override suspend fun upsertCoreBuilds(core: CoreKey, rows: List<CoreBuildRow>): UpsertCount = synchronized(lock) {
        val labels = mc.mapTo(HashSet()) { it.label }
        var ins = 0
        var upd = 0
        var same = 0
        var skip = 0
        for (r in rows) {
            if (r.mcLabel !in labels) {
                skip++
                continue
            }
            val key = Triple(core, r.mcLabel, r.build)
            val old = coreBuilds[key]
            when {
                old == null -> {
                    ins++
                    coreBuilds[key] = r
                }

                // SQL 의 `is distinct from (channel, url, sha256, size)` 와 같다: published_at 만 다르면 행을 건드리지 않는다
                old.copy(publishedAt = r.publishedAt) == r -> same++

                else -> {
                    upd++
                    coreBuilds[key] = r
                }
            }
        }
        UpsertCount(ins, upd, same, skip)
    }

    override suspend fun javaFeaturesInUse(): Set<Int> = synchronized(lock) { mc.flatMap { listOf(it.javaMin, it.javaRecommended) }.toSet() }

    override suspend fun upsertJavaRuntimes(rows: List<JavaRuntimeRow>): UpsertCount = synchronized(lock) {
        var ins = 0
        var upd = 0
        var same = 0
        for (r in rows) {
            val key = listOf(r.distribution, r.feature, r.os, r.arch, r.imageType, r.releaseName)
            val old = javaRuntimes[key]
            when {
                old == null -> ins++
                old == r -> same++
                else -> upd++
            }
            javaRuntimes[key] = r
        }
        UpsertCount(ins, upd, same, 0)
    }

    override suspend fun upsertContent(row: ContentRow): ContentUpsertResult = synchronized(lock) {
        val existing = contents.entries.firstOrNull { it.value.source == row.source && it.value.sourceId == row.sourceId }
        val slugOwner = contents.entries.firstOrNull { it.value.source == row.source && it.value.slug == row.slug }
        if (slugOwner != null && slugOwner.value.sourceId != row.sourceId) {
            return ContentUpsertResult.SlugConflict("${row.source}/${row.slug} 는 source_id ${slugOwner.value.sourceId} 가 사용 중")
        }
        if (existing != null) {
            contents[existing.key] = row
            ContentUpsertResult.Stored(existing.key, inserted = false)
        } else {
            val id = id()
            contents[id] = row
            ContentUpsertResult.Stored(id, inserted = true)
        }
    }

    override suspend fun contentVersions(contentId: Long): List<StoredVersion> = synchronized(lock) {
        versions[contentId].orEmpty().values.map { (vid, meta) ->
            val a = analyses[vid]
            StoredVersion(vid, meta.row.version, meta.row.sourceVersionId, a?.sha256 ?: meta.row.sha256, a?.analyzerVersion)
        }
    }

    override suspend fun upsertContentVersionsMeta(contentId: Long, items: List<VersionMeta>): Map<String, Long> = synchronized(lock) {
        require(contentId in contents) { "unknown content $contentId" }
        val byVersion = versions.getOrPut(contentId) { LinkedHashMap() }
        val out = LinkedHashMap<String, Long>()
        for (item in items) {
            val old = byVersion[item.row.version]
            val vid = old?.first ?: id()
            val merged = if (old == null) {
                item
            } else {
                item.copy(row = item.row.copy(sha256 = item.row.sha256 ?: old.second.row.sha256, size = item.row.size ?: old.second.row.size))
            }
            byVersion[item.row.version] = vid to merged
            val kept = deps[vid].orEmpty().filter { it.kind == DepKind.PROVIDES }
            deps[vid] = (kept + item.deps).toMutableList()
            out[item.row.version] = vid
        }
        out
    }

    override suspend fun recordAnalysis(contentVersionId: Long, record: AnalysisRecord) {
        synchronized(lock) {
            analyses[contentVersionId] = record
            val kept = deps[contentVersionId].orEmpty().filter { it.kind != DepKind.PROVIDES }
            deps[contentVersionId] = (kept + record.provides.distinct().map { DepRow(DepKind.PROVIDES, null, it) }).toMutableList()
        }
    }

    override suspend fun findAnalysisBySha256(sha256: String, analyzerVersion: String): AnalysisRecord? = synchronized(lock) {
        analyses.values.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) && it.analyzerVersion == analyzerVersion }
    }

    override suspend fun getState(key: String): String? = synchronized(lock) { state[key] }

    override suspend fun putState(key: String, jsonValue: String) {
        synchronized(lock) { state[key] = jsonValue }
    }

    override suspend fun counts(): TableCounts = synchronized(lock) {
        TableCounts(
            mcVersions = mc.size.toLong(),
            mcReleases = mc.count { !it.isSnapshot }.toLong(),
            mcSnapshots = mc.count { it.isSnapshot }.toLong(),
            coreBuilds = coreBuilds.size.toLong(),
            coreBuildsByCore = coreBuilds.keys.groupingBy { it.first }.eachCount().mapValues { it.value.toLong() },
            content = contents.size.toLong(),
            contentVersions = versions.values.sumOf { it.size }.toLong(),
            analyzedContentVersions = analyses.size.toLong(),
            contentDeps = deps.values.sumOf { it.size }.toLong(),
            javaRuntimes = javaRuntimes.size.toLong(),
        )
    }

    /** 테스트 편의: source 별 content id. */
    fun contentIdOf(source: Source, sourceId: String): Long? =
        synchronized(lock) { contents.entries.firstOrNull { it.value.source == source && it.value.sourceId == sourceId }?.key }

    override fun close() {
        closed = true
    }
}
