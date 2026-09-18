package kr.decacross.collector.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** SQL 수준 검사 (컬럼 텍스트·제약·jsonb·락). 의미 계약은 [CollectorStoreContract]. */
class PgCollectorStoreTest {
    private val opened = ArrayList<PgCollectorStore>()
    private val t0 = Instant.parse("2026-03-04T05:06:07Z")

    @BeforeTest
    fun setUp() {
        TestPg.resetAndMigrate()
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun open(): PgCollectorStore = TestPg.openStore().also { opened += it }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> = TestPg.connection().use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).mapRows(map) }
    }

    private fun mc(label: String, ordinal: Int, java: Int = 21, recommended: Int = java) =
        NewMcVersion(label, McOrdinal(ordinal), t0 + ordinal.days, false, java, recommended, null, null)

    private fun build(label: String, build: String, channel: Channel = Channel.STABLE, sha: String = "a".repeat(64)) =
        CoreBuildRow(label, build, channel, "https://fill-data.papermc.io/v1/objects/$sha/paper-$label-$build.jar", sha, 51_000_000, t0)

    private fun content(sourceId: String, slug: String) =
        ContentRow(Source.HANGAR, sourceId, slug, "Name", ContentKind.PLUGIN, "Apache-2.0", false, "a", 1, null, null, "https://hangar.papermc.io/a/$slug")

    private fun meta(version: String, sha: String? = null, deps: List<DepRow> = emptyList()) = VersionMeta(
        ContentVersionRow(version, "hv-$version", "Release", "https://hangarcdn.papermc.io/$version.jar", sha, 10, setOf(LoaderFamily.BUKKIT, LoaderFamily.FABRIC), McOrdinal(1860), McOrdinal(1940), t0),
        deps,
    )

    @Test
    fun schemaCheck_failsWithout0008() {
        val dir = newTempDir("decacross-mig-")
        try {
            TestPg.resetAndMigrate(TestPg.copyMigrations(dir) { !it.startsWith("0008") })
            val e = assertFailsWith<IllegalStateException> { TestPg.openStore() }
            assertTrue(e.message.orEmpty().contains("마이그레이션 미적용"), e.message)
            assertTrue(e.message.orEmpty().contains("java_runtimes"), e.message)
            assertTrue(e.message.orEmpty().contains("content_versions.analysis"), e.message)
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun insertMcVersions_allOrNothing_labelOrOrdinalConflict(): Unit = runBlocking {
        val store = open()
        assertEquals(McInsertResult.Inserted(2), store.insertMcVersions(listOf(mc("1.21", 1860), mc("1.21.1", 1870))))
        val conflict = assertIs<McInsertResult.Conflict>(store.insertMcVersions(listOf(mc("1.21.2", 1880), mc("1.21.1-dup", 1870))))
        assertTrue(conflict.detail.contains("1.21.1=1870"), conflict.detail)
        assertEquals(listOf("1.21", "1.21.1"), query("select label from mc_versions order by ordinal") { it.getString(1) })
        assertEquals(t0 + 1860.days, query("select released_at from mc_versions where label = '1.21'") { it.getInstant(1) }.single(), "timestamptz 왕복")
    }

    @Test
    fun updateMcFacts_neverChangesOrdinal_packFormatText(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("26.3", 2020), mc("1.21", 1860)))
        assertTrue(store.updateMcFacts("26.3", McFacts(PackFormat(97, 1), PackFormat(121), 775)))
        assertTrue(store.updateMcFacts("1.21", McFacts(PackFormat(34), PackFormat(48), 767)))
        val rows = query("select label, ordinal, rp_format, dp_format, protocol from mc_versions order by ordinal") {
            listOf(it.getString(1), it.getInt(2).toString(), it.getString(3), it.getString(4), it.getInt(5).toString())
        }
        assertEquals(listOf(listOf("1.21", "1860", "34", "48", "767"), listOf("26.3", "2020", "97.1", "121", "775")), rows)
        // 불변식 2: 서수를 바꾸는 SQL 이 구현에 없다
        val source = Path.of("src/main/kotlin/kr/decacross/collector/store/PgCollectorStore.kt")
        if (Files.exists(source)) assertFalse(Regex("""set\s+ordinal""", RegexOption.IGNORE_CASE).containsMatchIn(Files.readString(source)))
    }

    @Test
    fun upsertCoreBuilds_insertUpdateUnchanged_skipUnknownLabel(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("1.21.8", 1940)))
        assertEquals(UpsertCount(inserted = 1, skipped = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60"), build("1.21.9-pre1", "1"))))
        assertEquals(UpsertCount(unchanged = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60"))))
        assertEquals(UpsertCount(updated = 1), store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.8", "60", sha = "b".repeat(64)))))
        val rows = query("select c.key, b.build, b.channel, b.sha256, b.size from core_builds b join cores c on c.id = b.core_id") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5).toString())
        }
        assertEquals(listOf(listOf("paper", "60", "STABLE", "b".repeat(64), "51000000")), rows)
        // 청크 경계(500) 를 넘는 입력
        val many = (1..1_203).map { build("1.21.8", "b$it") }
        assertEquals(UpsertCount(inserted = 1_203), store.upsertCoreBuilds(CoreKey.PAPER, many))
        assertEquals(UpsertCount(unchanged = 1_203), store.upsertCoreBuilds(CoreKey.PAPER, many))
    }

    @Test
    fun upsertCoreBuilds_channelPromotion_updates(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("1.21.10", 1960)))
        store.upsertCoreBuilds(CoreKey.FOLIA, listOf(build("1.21.10", "3", Channel.EXPERIMENTAL)))
        assertEquals(listOf("EXPERIMENTAL"), query("select channel from core_builds") { it.getString(1) })
        assertEquals(UpsertCount(updated = 1), store.upsertCoreBuilds(CoreKey.FOLIA, listOf(build("1.21.10", "3", Channel.STABLE))))
        assertEquals(listOf("STABLE"), query("select channel from core_builds") { it.getString(1) })
    }

    @Test
    fun coreBuildKeys_byLabel(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("1.21.7", 1930), mc("1.21.8", 1940)))
        store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21.7", "30"), build("1.21.8", "60"), build("1.21.8", "61")))
        store.upsertCoreBuilds(CoreKey.FOLIA, listOf(build("1.21.8", "7")))
        assertEquals(mapOf("1.21.7" to setOf("30"), "1.21.8" to setOf("60", "61")), store.coreBuildKeys(CoreKey.PAPER))
        assertEquals(mapOf("1.21.8" to setOf("7")), store.coreBuildKeys(CoreKey.FOLIA))
    }

    @Test
    fun javaRuntimes_upsert_andCheckConstraint(): Unit = runBlocking {
        val store = open()
        val row = JavaRuntimeRow(
            feature = 8,
            os = Os.MAC,
            arch = Arch.AARCH64,
            imageType = ImageType.JDK,
            releaseName = "jdk8u504-b01",
            openjdkVersion = "1.8.0_504-b01",
            packageName = "OpenJDK8U-jdk_aarch64_mac_hotspot_8u504b01.tar.gz",
            downloadUrl = "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/x.tar.gz",
            sha256 = "0".repeat(64),
            size = 100_000_000,
            publishedAt = t0,
        )
        assertEquals(UpsertCount(inserted = 1), store.upsertJavaRuntimes(listOf(row)))
        assertEquals(
            listOf(listOf("temurin", "8", "mac", "aarch64", "jdk", "jdk8u504-b01")),
            query("select distribution, feature, os, arch, image_type, release_name from java_runtimes") { rs -> (1..6).map { rs.getString(it) } },
        )
        assertFailsWith<SQLException> { store.upsertJavaRuntimes(listOf(row.copy(releaseName = "bad", sha256 = "NOT-HEX"))) }
        assertEquals(1, query("select count(*) from java_runtimes") { it.getInt(1) }.single(), "CHECK 위반 행은 들어가지 않는다")
    }

    @Test
    fun javaFeaturesInUse_distinct(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("1.12.2", 1400, java = 8), mc("1.17", 1700, java = 16), mc("1.18", 1710, java = 17, recommended = 21), mc("1.21", 1860, java = 21)))
        assertEquals(setOf(8, 16, 17, 21), store.javaFeaturesInUse())
    }

    @Test
    fun upsertContent_bySourceId_slugRenameSameRow(): Unit = runBlocking {
        val store = open()
        val a = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("1234", "viaversion")))
        val b = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("1234", "ViaVersion-renamed").copy(downloads = 99, license = null)))
        assertEquals(a.id, b.id)
        assertFalse(b.inserted)
        assertEquals(
            listOf(listOf("ViaVersion-renamed", "HANGAR", "1234", null, "99", "false")),
            query("select slug, source, source_id, license, downloads, redistributable::text from content") { rs -> (1..6).map { rs.getString(it) } },
        )
    }

    @Test
    fun upsertContent_slugConflict_reported(): Unit = runBlocking {
        val store = open()
        store.upsertContent(content("1", "same"))
        val conflict = assertIs<ContentUpsertResult.SlugConflict>(store.upsertContent(content("2", "same")))
        assertTrue(conflict.detail.contains("same"))
        assertEquals(1, query("select count(*) from content") { it.getInt(1) }.single())
        // 충돌 뒤에도 같은 연결로 계속 쓸 수 있다 (트랜잭션이 롤백됐다)
        assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("2", "other")))
    }

    @Test
    fun versionsMeta_keepsAnalysisAndSha256WhenNull_replacesRequireOptionalOnly(): Unit = runBlocking {
        val store = open()
        val cid = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("77", "alpha"))).id
        val sha = "9".repeat(64)
        val vid = store.upsertContentVersionsMeta(cid, listOf(meta("1.0", sha, listOf(DepRow(DepKind.REQUIRE, "vault", null), DepRow(DepKind.OPTIONAL, "papi", null, ">=2"))))).getValue("1.0")
        store.recordAnalysis(vid, AnalysisRecord(sha, 10, 17, "1.20", null, t0, "0.1.0+rules.aaaaaaaa", """{"a":1}""", listOf(Capability.PermissionProvider)))
        store.upsertContentVersionsMeta(cid, listOf(meta("1.0", null, listOf(DepRow(DepKind.REQUIRE, "luckperms", null)))))
        val row = query("select sha256, size, java_major, api_version, analyzer_version, analysis ->> 'a', loaders::text, source_version_id, channel from content_versions") { rs ->
            (1..9).map { rs.getString(it) }
        }.single()
        assertEquals(listOf(sha, "10", "17", "1.20", "0.1.0+rules.aaaaaaaa", "1", "{BUKKIT,FABRIC}", "hv-1.0", "Release"), row)
        val deps = query("select kind, coalesce(target_slug, target_capability), range from content_deps order by kind, id") { rs -> (1..3).map { rs.getString(it) } }
        assertEquals(listOf(listOf("PROVIDES", "permission_provider", "*"), listOf("REQUIRE", "luckperms", "*")), deps)
    }

    @Test
    fun versionsMeta_sourceVersionChanged_clearsEveryAnalysisColumn_inOneTransaction(): Unit = runBlocking {
        // 회귀 (C3-R1): 분석 결과 컬럼 전부와 PROVIDES 가 지워지고, 메타 컬럼은 새 값이 된다
        val store = open()
        val cid = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("80", "delta"))).id
        val sha = "6".repeat(64)
        val vid = store.upsertContentVersionsMeta(cid, listOf(meta("1.0", sha, listOf(DepRow(DepKind.REQUIRE, "vault", null))))).getValue("1.0")
        store.recordAnalysis(vid, AnalysisRecord(sha, 10, 17, "1.20", PackDecl.Single(PackFormat(34)), t0, "0.1.0+rules.cccccccc", """{"a":1}""", listOf(Capability.PermissionProvider)))
        val moved = meta("1.0", null, listOf(DepRow(DepKind.REQUIRE, "vault", null)))
        store.upsertContentVersionsMeta(cid, listOf(moved.copy(row = moved.row.copy(sourceVersionId = "hv-1.0-new", fileUrl = "https://hangarcdn.papermc.io/1.0-new.jar", size = 12))))
        val row = query(
            "select id, sha256, size, java_major, api_version, pack_decl::text, analyzed_at, analyzer_version, analysis::text, source_version_id, file_url from content_versions",
        ) { rs -> (1..11).map { rs.getString(it) } }.single()
        assertEquals(listOf(vid.toString(), null, "12", null, null, null, null, null, null, "hv-1.0-new", "https://hangarcdn.papermc.io/1.0-new.jar"), row)
        val deps = query("select kind, coalesce(target_slug, target_capability) from content_deps order by kind, id") { rs -> (1..2).map { rs.getString(it) } }
        assertEquals(listOf(listOf("REQUIRE", "vault")), deps)
    }

    @Test
    fun recordAnalysis_replacesProvidesOnly_packDeclJson(): Unit = runBlocking {
        val store = open()
        val cid = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("78", "beta"))).id
        val vid = store.upsertContentVersionsMeta(cid, listOf(meta("2.0", deps = listOf(DepRow(DepKind.REQUIRE, "vault", null))))).getValue("2.0")
        val decl = PackDecl.Range(PackFormat(34), PackFormat(88, 1))
        val record = AnalysisRecord("8".repeat(64), 2048, 21, null, decl, t0, "0.1.0+rules.bbbbbbbb", """{"capabilities":[{"cap":"economy_provider","confidence":"STORE"}]}""", listOf(Capability.EconomyProvider, Capability.Other("x")))
        store.recordAnalysis(vid, record)
        store.recordAnalysis(vid, record.copy(provides = listOf(Capability.ChunkGenerator)))
        val packDecl = query("select pack_decl::text, analyzed_at from content_versions") { it.getString(1) }.single()
        val parsed = Json.parseToJsonElement(packDecl) as JsonObject
        assertEquals("range", parsed.getValue("type").jsonPrimitive.content)
        assertEquals("88.1", parsed.getValue("max").jsonPrimitive.content, "PackFormat 는 문자열로 저장")
        val deps = query("select kind, coalesce(target_slug, target_capability) from content_deps order by kind") { rs -> (1..2).map { rs.getString(it) } }
        assertEquals(listOf(listOf("PROVIDES", "chunk_generator"), listOf("REQUIRE", "vault")), deps)
        val found = assertIs<AnalysisRecord>(store.findAnalysisBySha256("8".repeat(64), "0.1.0+rules.bbbbbbbb"))
        assertEquals(decl, found.packDecl)
        assertNull(found.apiVersion)
        assertEquals(21, found.javaMajor)
        assertEquals(t0, found.analyzedAt)
    }

    @Test
    fun findAnalysisBySha256_returnsProvides(): Unit = runBlocking {
        val store = open()
        val cid = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content("79", "gamma"))).id
        val ids = store.upsertContentVersionsMeta(cid, listOf(meta("1"), meta("2")))
        val sha = "7".repeat(64)
        val older = AnalysisRecord(sha, 5, 8, "1.13", null, t0, "v+rules.1", """{"n":1}""", listOf(Capability.PermissionProvider))
        store.recordAnalysis(ids.getValue("1"), older)
        store.recordAnalysis(ids.getValue("2"), older.copy(analyzedAt = t0 + 1.days, analysisJson = """{"n":2}""", provides = listOf(Capability.CustomItemFramework, Capability.AntiCheat)))
        val found = assertIs<AnalysisRecord>(store.findAnalysisBySha256(sha.uppercase(), "v+rules.1"))
        assertEquals(t0 + 1.days, found.analyzedAt, "가장 최근 분석")
        assertEquals(setOf(Capability.CustomItemFramework, Capability.AntiCheat), found.provides.toSet())
        assertEquals(Json.parseToJsonElement("""{"n":2}"""), Json.parseToJsonElement(found.analysisJson))
    }

    @Test
    fun state_roundtrip(): Unit = runBlocking {
        val store = open()
        store.putState("fill.paper.versions", """{"etag":"W/\"abc\"","supported":["1.21.8","1.21.9"]}""")
        store.putState("purpur.budget.2026-09-17", "12345")
        assertEquals(Json.parseToJsonElement("""{"etag":"W/\"abc\"","supported":["1.21.8","1.21.9"]}"""), Json.parseToJsonElement(store.getState("fill.paper.versions").orEmpty()))
        assertEquals("12345", store.getState("purpur.budget.2026-09-17"))
        assertFailsWith<SQLException> { store.putState("bad", "not json") }
        assertNull(store.getState("bad"))
        // 실패 뒤에도 풀의 연결이 계속 쓸 수 있다
        store.putState("ok", "true")
        assertEquals("true", store.getState("ok"))
    }

    @Test
    fun counts_consistent(): Unit = runBlocking {
        val store = open()
        store.insertMcVersions(listOf(mc("1.21", 1860), mc("1.21.1", 1870)))
        store.upsertCoreBuilds(CoreKey.PAPER, listOf(build("1.21", "1"), build("1.21.1", "1")))
        store.upsertCoreBuilds(CoreKey.PURPUR, listOf(build("1.21.1", "2300")))
        val c = store.counts()
        assertEquals(2, c.mcVersions)
        assertEquals(2, c.mcReleases)
        assertEquals(0, c.mcSnapshots)
        assertEquals(3, c.coreBuilds)
        assertEquals(mapOf(CoreKey.PAPER to 2L, CoreKey.PURPUR to 1L), c.coreBuildsByCore)
        assertEquals(c.coreBuilds, c.coreBuildsByCore.values.sum())
    }

    @Test
    fun runLock_secondStoreCannotAcquire(): Unit = runBlocking {
        val first = open()
        val second = open()
        assertTrue(first.tryAcquireRunLock())
        assertTrue(first.tryAcquireRunLock(), "같은 저장소는 다시 물어도 true")
        assertFalse(second.tryAcquireRunLock(), "다른 수집기는 락을 못 잡는다")
        first.close()
        assertTrue(second.tryAcquireRunLock(), "첫 저장소가 닫히면 락이 풀린다")
    }
}
