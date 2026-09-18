package kr.decacross.collector.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Os
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.model.Source
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * 두 저장소(메모리 `RecordingStore` / SQL [PgCollectorStore])가 공유해야 하는 **관찰 가능한 의미**.
 * 둘이 갈리면 testkit 에 대한 BLOCKER 로 보고한다 — C1 의 SQL 로 덮지 않는다.
 * [CollectorStore] 인터페이스로 관찰 가능한 것만 검사한다 (JSON 텍스트는 파싱해서 비교: jsonb 는 공백을 정규화한다).
 */
abstract class CollectorStoreContract {
    protected abstract fun newStore(): CollectorStore

    private var opened: CollectorStore? = null

    protected val store: CollectorStore
        get() = requireNotNull(opened) { "@BeforeTest 가 저장소를 열지 않았다" }

    @BeforeTest
    fun openStore() {
        opened = newStore()
    }

    @AfterTest
    fun closeStore() {
        opened?.close()
        opened = null
    }

    // ── 픽스처 ──

    protected val t0: Instant = Instant.parse("2026-01-02T03:04:05Z")

    protected fun mc(label: String, ordinal: Int, snapshot: Boolean = false, java: Int = 21, recommended: Int = java): NewMcVersion =
        NewMcVersion(label, McOrdinal(ordinal), t0 + ordinal.days, snapshot, java, recommended, "https://piston-data.test/$label/client.jar", "ab".repeat(20))

    protected fun build(label: String, build: String, channel: Channel = Channel.STABLE, sha: String = "a".repeat(64), size: Long = 100, publishedAt: Instant? = t0): CoreBuildRow =
        CoreBuildRow(label, build, channel, "https://fill.test/$label/$build.jar", sha, size, publishedAt)

    protected fun content(sourceId: String, slug: String, source: Source = Source.MODRINTH): ContentRow =
        ContentRow(source, sourceId, slug, "Name $slug", ContentKind.PLUGIN, "MIT", false, "author", 42, null, "desc", "https://modrinth.test/plugin/$slug")

    protected fun versionRow(version: String, sha256: String?, size: Long?): ContentVersionRow = ContentVersionRow(
        version = version,
        sourceVersionId = "sv-$version",
        channel = "release",
        fileUrl = "https://cdn.modrinth.test/$version.jar",
        sha256 = sha256,
        size = size,
        loaders = setOf(LoaderFamily.BUKKIT),
        mcOrdinalMin = McOrdinal(1000),
        mcOrdinalMax = McOrdinal(1010),
        publishedAt = t0,
    )

    protected fun analysis(sha: String, provides: List<Capability>, analyzerVersion: String = "0.1.0+rules.deadbeef", packDecl: PackDecl? = null): AnalysisRecord =
        AnalysisRecord(sha, 1234, 17, "1.20", packDecl, t0, analyzerVersion, """{"descriptor":"plugin.yml","capabilities":["x"]}""", provides)

    protected fun runtime(release: String, size: Long = 50_000_000, publishedAt: Instant? = t0): JavaRuntimeRow = JavaRuntimeRow(
        feature = 21,
        os = Os.WINDOWS,
        arch = Arch.X64,
        imageType = ImageType.JRE,
        releaseName = release,
        openjdkVersion = "21.0.12.1+1-LTS",
        packageName = "OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip",
        downloadUrl = "https://github.test/temurin/$release.zip",
        sha256 = "c".repeat(64),
        size = size,
        publishedAt = publishedAt,
    )

    protected fun json(text: String?) = text?.let { Json.parseToJsonElement(it) }

    protected suspend fun storedContentId(row: ContentRow): Long = assertIs<ContentUpsertResult.Stored>(store.upsertContent(row)).id

    // ── mc_versions ──

    @Test
    fun contract_insertMcVersions_allOrNothing(): Unit = runBlocking {
        assertEquals(McInsertResult.Inserted(2), store.insertMcVersions(listOf(mc("1.20", 1000), mc("1.21", 1010))))
        assertIs<McInsertResult.Conflict>(store.insertMcVersions(listOf(mc("1.22", 1020), mc("1.21", 1030))), "label 충돌")
        assertIs<McInsertResult.Conflict>(store.insertMcVersions(listOf(mc("1.22", 1020), mc("24w01a", 1010, snapshot = true))), "ordinal 충돌")
        assertIs<McInsertResult.Conflict>(store.insertMcVersions(listOf(mc("1.22", 1020), mc("1.22", 1040))), "batch 안 label 중복")
        assertIs<McInsertResult.Conflict>(store.insertMcVersions(listOf(mc("1.22", 1020), mc("1.23", 1020))), "batch 안 ordinal 중복")
        assertEquals(listOf("1.20", "1.21"), store.mcIndex().map { it.label }, "충돌한 batch 는 한 행도 남기지 않는다")
        assertEquals(McInsertResult.Inserted(0), store.insertMcVersions(emptyList()))
        assertEquals(McInsertResult.Inserted(1), store.insertMcVersions(listOf(mc("1.20.1", 1001, snapshot = true))))
        val index = store.mcIndex()
        assertEquals(listOf(1000, 1001, 1010), index.map { it.ordinal.value }, "ordinal 오름차순")
        val row = index.first { it.label == "1.21" }
        assertEquals(t0 + 1010.days, row.releasedAt)
        assertFalse(row.isSnapshot)
        assertEquals(21, row.javaMin)
        assertNull(row.rpFormat)
        assertEquals("https://piston-data.test/1.21/client.jar", row.clientJarUrl)
        assertEquals("ab".repeat(20), row.clientJarSha1)
    }

    @Test
    fun contract_updateMcFacts_neverChangesOrdinal(): Unit = runBlocking {
        store.insertMcVersions(listOf(mc("26.3", 2020)))
        assertTrue(store.updateMcFacts("26.3", McFacts(PackFormat(97, 1), PackFormat(121), 775)))
        val row = store.mcIndex().single()
        assertEquals(McOrdinal(2020), row.ordinal)
        assertEquals(PackFormat(97, 1), row.rpFormat)
        assertEquals(PackFormat(121), row.dpFormat)
        assertEquals(775, row.protocol)
        assertTrue(store.updateMcFacts("26.3", McFacts(null, null, null)), "null 은 null 로 기록")
        val cleared = store.mcIndex().single()
        assertNull(cleared.rpFormat)
        assertNull(cleared.protocol)
        assertEquals(McOrdinal(2020), cleared.ordinal)
        assertFalse(store.updateMcFacts("no-such-label", McFacts(PackFormat(1), null, null)))
    }

    // ── core_builds ──

    @Test
    fun contract_upsertCoreBuilds_counts(): Unit = runBlocking {
        store.insertMcVersions(listOf(mc("1.21.8", 1940)))
        assertEquals(UpsertCount(inserted = 2, skipped = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60"), build("1.21.8", "61"), build("9.9.9", "1"))))
        assertEquals(UpsertCount(unchanged = 2), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60"), build("1.21.8", "61"))))
        assertEquals(UpsertCount(unchanged = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60", publishedAt = t0 + 1.days))), "published_at 만 다르면 unchanged")
        assertEquals(UpsertCount(unchanged = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60", publishedAt = null))))
        assertEquals(UpsertCount(updated = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60", size = 101))))
        assertEquals(UpsertCount(updated = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "61", sha = "b".repeat(64)))))
        assertEquals(UpsertCount(inserted = 1), store.upsertCoreBuilds(CoreKey.FOLIA, listOf(build("1.21.8", "60"))), "코어가 다르면 다른 행")
        assertEquals(UpsertCount(), store.upsertCoreBuilds(CoreKey.PAPER, emptyList()))
        assertEquals(mapOf("1.21.8" to setOf("60", "61")), store.coreBuildKeys(CoreKey.PAPER))
        assertEquals(mapOf("1.21.8" to setOf("60")), store.coreBuildKeys(CoreKey.FOLIA))
        assertEquals(emptyMap(), store.coreBuildKeys(CoreKey.PURPUR))
    }

    @Test
    fun contract_upsertCoreBuilds_channelPromotion(): Unit = runBlocking {
        store.insertMcVersions(listOf(mc("1.21.9", 1950)))
        assertEquals(UpsertCount(inserted = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.9", "5", channel = Channel.EXPERIMENTAL))))
        assertEquals(UpsertCount(updated = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.9", "5", channel = Channel.STABLE))))
        assertEquals(UpsertCount(unchanged = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.9", "5", channel = Channel.STABLE))))
    }

    // ── java_runtimes ──

    @Test
    fun contract_javaRuntimes_upsert_andFeaturesInUse(): Unit = runBlocking {
        assertEquals(emptySet(), store.javaFeaturesInUse())
        store.insertMcVersions(listOf(mc("1.16.5", 1500, java = 8), mc("1.18", 1600, java = 17, recommended = 21), mc("1.21", 1860, java = 21)))
        assertEquals(setOf(8, 17, 21), store.javaFeaturesInUse())
        assertEquals(UpsertCount(inserted = 2), store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1"), runtime("jdk-21.0.11+10"))))
        assertEquals(UpsertCount(unchanged = 1), store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1"))))
        assertEquals(UpsertCount(updated = 1), store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1", size = 50_000_001))))
        assertEquals(UpsertCount(updated = 1), store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1", size = 50_000_001, publishedAt = t0 + 1.days))))
        assertEquals(UpsertCount(inserted = 1), store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1").copy(arch = Arch.AARCH64))))
        assertEquals(3L, store.counts().javaRuntimes)
    }

    // ── content ──

    @Test
    fun contract_upsertContent_bySourceId_slugConflict(): Unit = runBlocking {
        val first = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("P1", "essentialsx")))
        assertTrue(first.inserted)
        val again = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("P1", "essentialsx")))
        assertEquals(first.id, again.id)
        assertFalse(again.inserted)
        val renamed = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("P1", "essentials-x")))
        assertEquals(first.id, renamed.id, "slug 가 바뀌어도 source_id 가 같으면 같은 행")
        assertIs<ContentUpsertResult.SlugConflict>(store.upsertContent(content("P2", "essentials-x")), "같은 source 의 다른 source_id 가 slug 사용 중")
        val otherSource = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("P2", "essentials-x", Source.HANGAR)))
        assertNotEquals(first.id, otherSource.id, "source 가 다르면 slug 가 같아도 된다")
        assertEquals(2L, store.counts().content)
    }

    @Test
    fun contract_versionsMeta_keepsSha256WhenNull_replacesRequireOptionalOnly(): Unit = runBlocking {
        val cid = storedContentId(content("P1", "alpha"))
        val sha = "d".repeat(64)
        val deps = listOf(DepRow(DepKind.REQUIRE, "vault", null), DepRow(DepKind.OPTIONAL, "placeholderapi", null))
        val ids = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("1.0.0", sha, 777), deps), VersionMeta(versionRow("0.9.0", null, null), emptyList())))
        assertEquals(setOf("1.0.0", "0.9.0"), ids.keys)
        val vid = ids.getValue("1.0.0")
        store.recordAnalysis(vid, analysis(sha, listOf(Capability.PermissionProvider)))
        assertEquals(3L, store.counts().contentDeps)

        // 두 번째 메타 upsert: sha256/size null → 기존 유지, REQUIRE/OPTIONAL 교체, PROVIDES 유지
        val again = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("1.0.0", null, null), listOf(DepRow(DepKind.REQUIRE, "luckperms", null)))))
        assertEquals(vid, again.getValue("1.0.0"), "같은 (content, version) 은 같은 id")
        val stored = store.contentVersions(cid).associateBy { it.version }
        assertEquals(sha, stored.getValue("1.0.0").sha256)
        assertEquals("sv-1.0.0", stored.getValue("1.0.0").sourceVersionId)
        assertEquals("0.1.0+rules.deadbeef", stored.getValue("1.0.0").analyzerVersion, "메타 upsert 는 분석 결과를 건드리지 않는다")
        assertNull(stored.getValue("0.9.0").sha256)
        assertNull(stored.getValue("0.9.0").analyzerVersion)
        assertEquals(2L, store.counts().contentDeps, "REQUIRE 1 + PROVIDES 1")
        val found = store.findAnalysisBySha256(sha, "0.1.0+rules.deadbeef")
        assertEquals(listOf(Capability.PermissionProvider), found?.provides)

        // 새 sha256 이 오면 바뀐다
        store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("0.9.0", "e".repeat(64), 5), emptyList())))
        assertEquals("e".repeat(64), store.contentVersions(cid).first { it.version == "0.9.0" }.sha256)
        assertEquals(2L, store.counts().contentVersions)
    }

    @Test
    fun contract_versionsMeta_fileReplaced_clearsAnalysisAndProvides(): Unit = runBlocking {
        // 회귀 (C3-R1 / INV-1 / SC-2): 같은 (content, version) 이 다른 파일을 가리키게 되면 예전 분석이 남으면 안 된다
        val cid = storedContentId(content("P1", "alpha"))
        val require = listOf(DepRow(DepKind.REQUIRE, "vault", null))
        val shaA = "a".repeat(64)
        val shaKeep = "b".repeat(64)
        val ids = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("1.0", null, 100), require), VersionMeta(versionRow("2.0", null, 200), emptyList())))
        store.recordAnalysis(ids.getValue("1.0"), analysis(shaA, listOf(Capability.PermissionProvider)))
        store.recordAnalysis(ids.getValue("2.0"), analysis(shaKeep, listOf(Capability.EconomyProvider)))
        assertEquals(3L, store.counts().contentDeps)

        // Modrinth: 같은 version_number 의 새 업로드 (source_version_id·file_url·size 가 바뀌고 sha256 은 null)
        val uploadB = versionRow("1.0", null, 111).copy(sourceVersionId = "sv-1.0-b", fileUrl = "https://cdn.modrinth.test/1.0-b.jar")
        val again = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(uploadB, require), VersionMeta(versionRow("2.0", null, 200), emptyList())))
        assertEquals(ids, again, "행 id 는 그대로")
        val stored = store.contentVersions(cid).associateBy { it.version }
        val replaced = stored.getValue("1.0")
        assertEquals("sv-1.0-b", replaced.sourceVersionId)
        assertNull(replaced.analyzerVersion, "다른 파일 → 분석 무효 → 다음 패스가 다시 분석한다")
        assertNull(replaced.sha256, "예전 jar 의 sha256 을 새 file_url 에 붙이지 않는다")
        assertNull(store.findAnalysisBySha256(shaA, "0.1.0+rules.deadbeef"), "무효화된 분석은 sha256 중복 복사 원본이 되지 않는다")
        assertEquals("0.1.0+rules.deadbeef", stored.getValue("2.0").analyzerVersion, "같은 파일은 분석 유지")
        assertEquals(shaKeep, stored.getValue("2.0").sha256)
        assertEquals(2L, store.counts().contentDeps, "1.0 REQUIRE 1 + 2.0 PROVIDES 1 (1.0 의 PROVIDES 삭제)")
        assertEquals(1L, store.counts().analyzedContentVersions)

        // 새 파일을 분석해 기록하면 다시 정상
        store.recordAnalysis(replaced.id, analysis("c".repeat(64), listOf(Capability.CustomItemFramework)))
        assertEquals("c".repeat(64), store.contentVersions(cid).first { it.version == "1.0" }.sha256)

        // Hangar: 업로드 ID 는 같은데 플랫폼 sha256 이 이미 기록된 값과 다르다 → 무효화하고 새 sha256
        val shaH = "d".repeat(64)
        val hid = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("3.0", shaH, 30), emptyList()))).getValue("3.0")
        store.recordAnalysis(hid, analysis(shaH, listOf(Capability.PermissionProvider)))
        store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("3.0", shaH, 30), emptyList())))
        assertEquals("0.1.0+rules.deadbeef", store.contentVersions(cid).first { it.version == "3.0" }.analyzerVersion, "같은 sha256 → 유지")
        val shaH2 = "e".repeat(64)
        store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("3.0", shaH2, 31), emptyList())))
        val h = store.contentVersions(cid).first { it.version == "3.0" }
        assertNull(h.analyzerVersion)
        assertEquals(shaH2, h.sha256)
        assertNull(store.findAnalysisBySha256(shaH2, "0.1.0+rules.deadbeef"), "새 sha256 에 예전 분석이 붙지 않는다")
        assertNull(store.findAnalysisBySha256(shaH, "0.1.0+rules.deadbeef"))
    }

    @Test
    fun contract_recordAnalysis_replacesProvidesOnly(): Unit = runBlocking {
        val cid = storedContentId(content("P1", "alpha"))
        val vid = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("2.0", null, null), listOf(DepRow(DepKind.REQUIRE, "vault", null))))).getValue("2.0")
        val sha = "f".repeat(64)
        store.recordAnalysis(vid, analysis(sha, listOf(Capability.PermissionProvider, Capability.EconomyProvider, Capability.PermissionProvider)))
        assertEquals(3L, store.counts().contentDeps, "REQUIRE 1 + PROVIDES 2 (중복 제거)")
        store.recordAnalysis(vid, analysis(sha, listOf(Capability.CustomItemFramework), packDecl = PackDecl.Range(PackFormat(34), PackFormat(48, 1))))
        assertEquals(2L, store.counts().contentDeps)
        val found = assertIs<AnalysisRecord>(store.findAnalysisBySha256(sha, "0.1.0+rules.deadbeef"))
        assertEquals(setOf<Capability>(Capability.CustomItemFramework), found.provides.toSet())
        assertEquals(PackDecl.Range(PackFormat(34), PackFormat(48, 1)), found.packDecl)
        assertEquals(1L, store.counts().analyzedContentVersions)
        store.recordAnalysis(vid, analysis(sha, emptyList()))
        assertEquals(1L, store.counts().contentDeps, "PROVIDES 없음 → REQUIRE 만")
    }

    @Test
    fun contract_findAnalysisBySha256(): Unit = runBlocking {
        val cid = storedContentId(content("P1", "alpha"))
        val vid = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("3.0", null, null), emptyList()))).getValue("3.0")
        val sha = "0123456789abcdef".repeat(4)
        assertNull(store.findAnalysisBySha256(sha, "0.1.0+rules.deadbeef"))
        val record = analysis(sha, listOf(Capability.Other("worldedit")))
        store.recordAnalysis(vid, record)
        val found = assertIs<AnalysisRecord>(store.findAnalysisBySha256(sha, "0.1.0+rules.deadbeef"))
        assertEquals(record.sha256, found.sha256)
        assertEquals(record.size, found.size)
        assertEquals(record.javaMajor, found.javaMajor)
        assertEquals(record.apiVersion, found.apiVersion)
        assertEquals(record.analyzedAt, found.analyzedAt)
        assertEquals(record.analyzerVersion, found.analyzerVersion)
        assertEquals(json(record.analysisJson), json(found.analysisJson))
        assertEquals(listOf<Capability>(Capability.Other("worldedit")), found.provides)
        assertNull(store.findAnalysisBySha256(sha, "0.2.0+rules.00000000"), "분석기 버전이 다르면 없다")
        assertNull(store.findAnalysisBySha256("9".repeat(64), "0.1.0+rules.deadbeef"))
        assertEquals(sha, store.contentVersions(cid).single().sha256, "분석이 sha256 을 기록한다")
    }

    // ── collector_state ──

    @Test
    fun contract_state_roundtrip(): Unit = runBlocking {
        assertNull(store.getState("mojang.manifest"))
        store.putState("mojang.manifest", """{"etag":"0x8DF","lastModified":null}""")
        assertEquals(json("""{"etag":"0x8DF","lastModified":null}"""), json(store.getState("mojang.manifest")))
        store.putState("mojang.manifest", """{"etag":"0x8E0"}""")
        assertEquals(json("""{"etag":"0x8E0"}"""), json(store.getState("mojang.manifest")))
        store.putState("mojang.jarmeta.1.21", """{"status":"FOUND","rp":"34","dp":"48","protocol":767}""")
        assertEquals(json("""{"dp":"48","protocol":767,"rp":"34","status":"FOUND"}"""), json(store.getState("mojang.jarmeta.1.21")))
    }

    // ── 보고 ──

    @Test
    fun contract_counts(): Unit = runBlocking {
        val empty = store.counts()
        assertEquals(TableCounts(0, 0, 0, 0, emptyMap(), 0, 0, 0, 0, 0), empty)
        store.insertMcVersions(listOf(mc("1.21", 1860), mc("24w14a", 1861, snapshot = true), mc("1.21.1", 1870)))
        store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21", "1"), build("1.21.1", "2")))
        store.upsertCoreBuilds(CoreKey.FOLIA, listOf(build("1.21.1", "3")))
        val cid = storedContentId(content("P1", "alpha"))
        val ids = store.upsertContentVersionsMeta(cid, listOf(VersionMeta(versionRow("1", null, null), listOf(DepRow(DepKind.REQUIRE, "x", null))), VersionMeta(versionRow("2", null, null), emptyList())))
        store.recordAnalysis(ids.getValue("1"), analysis("1".repeat(64), listOf(Capability.EconomyProvider)))
        store.upsertJavaRuntimes(listOf(runtime("jdk-21.0.12.1+1")))
        val c = store.counts()
        assertEquals(TableCounts(3, 2, 1, 3, mapOf(CoreKey.PAPER to 2L, CoreKey.FOLIA to 1L), 1, 2, 1, 2, 1), c)
    }
}
