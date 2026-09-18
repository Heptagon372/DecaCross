package kr.decacross.collector.sources.modrinth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.analysis.CapabilityEvidence
import kr.decacross.analysis.EvidenceConfidence
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.content.contentSettings
import kr.decacross.collector.content.fakeAnalysis
import kr.decacross.collector.content.resourceText
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.AnalysisRecord
import kr.decacross.collector.store.CollectorStore
import kr.decacross.collector.store.ContentRow
import kr.decacross.collector.store.ContentUpsertResult
import kr.decacross.collector.store.ContentVersionRow
import kr.decacross.collector.store.DepRow
import kr.decacross.collector.store.VersionMeta
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.countFiles
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.Source
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val MR = "https://api.modrinth.com"
private const val FACETS_ENC =
    "%5B%5B%22project_type%3Aplugin%22%5D%2C%5B%22categories%3Apaper%22%2C%22categories%3Aspigot%22%2C" +
        "%22categories%3Abukkit%22%2C%22categories%3Apurpur%22%2C%22categories%3Afolia%22%5D%5D"
private const val LOADERS_ENC = "%5B%22paper%22%2C%22spigot%22%2C%22bukkit%22%2C%22purpur%22%2C%22folia%22%5D"

private fun searchUrl(offset: Int, limit: Int = 100) = "$MR/v2/search?facets=$FACETS_ENC&index=downloads&limit=$limit&offset=$offset"

private fun versionsUrl(projectId: String) = "$MR/v2/project/$projectId/version?include_changelog=false&loaders=$LOADERS_ENC"

private fun bulkUrl(vararg ids: String) = "$MR/v2/projects?ids=" + ids.joinToString("%2C", "%5B", "%5D") { "%22$it%22" }

/** 픽스처 검색 hit 의 project_id → slug. */
private val FIXTURE: Map<String, String> = linkedMapOf(
    "fALzjamp" to "chunky",
    "Vebnzrzj" to "luckperms",
    "hXiIvTyT" to "essentialsx",
    "U6d1TJQm" to "string-dupers-return",
    "wTfH1dkt" to "better-boat-movement",
)

private val LUCKPERMS_PROJECT = """[{"id":"Vebnzrzj","slug":"luckperms","loaders":["bukkit","bungeecord","fabric","folia","forge","neoforge","paper","spigot","velocity"]}]"""

private fun fixtureVersions(slug: String): List<MrVersion> =
    CollectorJson.decodeFromString(ListSerializer(MrVersion.serializer()), resourceText("/c3/mr_versions_$slug.json"))

/** 픽스처 검색 페이지 + 5개 버전 목록 + (essentialsx 의존 대상) 일괄 조회. */
private fun FakeHttp.onFixture(bulk: FakeResponse = FakeResponse.Body(LUCKPERMS_PROJECT)): FakeHttp {
    onJson(searchUrl(0), resourceText("/c3/mr_search_top100.json"))
    for ((id, slug) in FIXTURE) onJson(versionsUrl(id), resourceText("/c3/mr_versions_$slug.json"))
    on(bulkUrl("Vebnzrzj"), bulk)
    return this
}

private fun hit(id: String, slug: String = id.lowercase(), downloads: Long = 0, license: String? = "MIT") =
    MrHit(project_id = id, slug = slug, title = "Title $slug", author = "author", description = "desc", downloads = downloads, license = license)

private fun searchJson(vararg hits: MrHit): String = CollectorJson.encodeToString(MrSearch.serializer(), MrSearch(hits.toList(), hits.size))

private fun jarFile(name: String, bytes: ByteArray, primary: Boolean = true, sha1: String = FakeHttp.hex(DigestAlgo.SHA1, bytes)) =
    MrFile(url = "https://cdn.modrinth.com/data/test/$name", filename = name, primary = primary, size = bytes.size.toLong(), hashes = mapOf("sha1" to sha1))

private fun version(
    id: String,
    projectId: String,
    number: String = id,
    type: String = "release",
    date: String = "2026-01-01T00:00:00Z",
    downloads: Long = 10,
    files: List<MrFile> = listOf(jarFile("$id.jar", "jar-$id".encodeToByteArray())),
    deps: List<MrDep> = emptyList(),
    loaders: List<String> = listOf("paper", "spigot"),
) = MrVersion(id, projectId, number, type, date, downloads, loaders, listOf("1.21"), files, deps)

private fun versionsJson(vararg v: MrVersion): String = CollectorJson.encodeToString(ListSerializer(MrVersion.serializer()), v.toList())

private fun RecordingStore.contentOf(slug: String): Pair<Long, ContentRow>? = contents.entries.firstOrNull { it.value.slug == slug }?.toPair()

private fun RecordingStore.versionsOf(slug: String): Map<String, Pair<Long, VersionMeta>> =
    contentOf(slug)?.let { versions[it.first] }.orEmpty()

/** 메타데이터 기록과 분석 기록의 순서를 남기는 저장소. */
private class TracingStore(private val inner: RecordingStore, private val events: MutableList<String>) : CollectorStore by inner {
    override suspend fun upsertContentVersionsMeta(contentId: Long, items: List<VersionMeta>): Map<String, Long> {
        synchronized(events) { events += "meta:$contentId" }
        return inner.upsertContentVersionsMeta(contentId, items)
    }

    override suspend fun recordAnalysis(contentVersionId: Long, record: AnalysisRecord) {
        synchronized(events) { events += "record:$contentVersionId" }
        inner.recordAnalysis(contentVersionId, record)
    }
}

class ModrinthSourceTest {
    private val dir = newTempDir()
    private val okAnalyzer = JarAnalyzer { JarAnalysisResult.Ok(fakeAnalysis()) }

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun metadataOnly() = contentSettings(dir) { it.copy(analyzePerProject = 0) }

    @Test
    fun rankByBukkitFamilyDownloads_notProjectTotal() = runTest {
        val hits = CollectorJson.decodeFromString(MrSearch.serializer(), resourceText("/c3/mr_search_top100.json")).hits.associateBy { it.slug }
        val bukkitSum = FIXTURE.values.associateWith { slug -> fixtureVersions(slug).sumOf { it.downloads } }
        // 전제: 프로젝트 전체 다운로드는 luckperms > essentialsx 인데 Bukkit 계열 버전 합은 essentialsx > luckperms
        assertTrue(assertNotNull(hits["luckperms"]).downloads > assertNotNull(hits["essentialsx"]).downloads)
        assertTrue(assertNotNull(bukkitSum["essentialsx"]) > assertNotNull(bukkitSum["luckperms"]))

        val store = RecordingStore()
        val http = FakeHttp(dir).onFixture()
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir) { it.copy(modrinthTop = 2, analyzePerProject = 0) }))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(setOf("chunky", "essentialsx"), store.contents.values.map { it.slug }.toSet())
        assertEquals(bukkitSum["essentialsx"], assertNotNull(store.contentOf("essentialsx")).second.downloads)
        assertEquals(bukkitSum["chunky"], assertNotNull(store.contentOf("chunky")).second.downloads)
        assertEquals(2L, report.counters["project.kept"])
    }

    @Test
    fun urlsEncoded_facetsAndLoaders() = runTest {
        val http = FakeHttp(dir).onFixture()
        ModrinthSource().collect(testContext(http, RecordingStore(), metadataOnly()))

        val expected = listOf(searchUrl(0)) + FIXTURE.keys.map { versionsUrl(it) } + bulkUrl("Vebnzrzj")
        assertEquals(expected, http.requests.map { it.url })
        assertEquals(
            "https://api.modrinth.com/v2/search?facets=%5B%5B%22project_type%3Aplugin%22%5D%2C%5B%22categories%3Apaper%22%2C" +
                "%22categories%3Aspigot%22%2C%22categories%3Abukkit%22%2C%22categories%3Apurpur%22%2C%22categories%3Afolia%22%5D%5D" +
                "&index=downloads&limit=100&offset=0",
            http.requests.first().url,
        )
        assertEquals(
            "https://api.modrinth.com/v2/project/hXiIvTyT/version?include_changelog=false" +
                "&loaders=%5B%22paper%22%2C%22spigot%22%2C%22bukkit%22%2C%22purpur%22%2C%22folia%22%5D",
            versionsUrl("hXiIvTyT"),
        )
        for (r in http.requests) {
            assertTrue(r.url.none { it in "[]\" " }, "인코딩되지 않은 문자: ${r.url}")
        }
    }

    @Test
    fun hitsDedupedAcrossPages() = runTest {
        val page0 = (0 until 100).map { hit("p%03d".format(it), downloads = 1_000L - it) }
        // 두 요청 사이에 순위가 밀려 p099 가 다음 페이지에도 나온다
        val page1 = listOf(hit("p099", downloads = 901)) + (100 until 159).map { hit("p%03d".format(it), downloads = 1_000L - it) }
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(*page0.toTypedArray()))
            .onJson(searchUrl(100), searchJson(*page1.toTypedArray()))
        for (h in page0 + page1) http.onJson(versionsUrl(h.project_id), "[]")

        val report = ModrinthSource().collect(testContext(http, RecordingStore(), metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(listOf(searchUrl(0), searchUrl(100)), http.requests.map { it.url }.filter { "/v2/search" in it })
        val versionRequests = http.requests.map { it.url }.filter { "/version?" in it }
        assertEquals(159, versionRequests.size)
        assertEquals(159, versionRequests.toSet().size)
        assertEquals(1L, report.counters["search.duplicateHits"])
        assertEquals(159L, report.counters["search.hits"])
    }

    @Test
    fun hitsReportProjectTypeMod_notFiltered() = runTest {
        val raw = CollectorJson.parseToJsonElement(resourceText("/c3/mr_search_top100.json")).jsonObject
        val types = assertNotNull(raw["hits"]).jsonArray.map { it.jsonObject["project_type"]?.jsonPrimitive?.content }
        assertEquals(List(5) { "mod" }, types) // 전제: plugin facet 으로 찾아도 hit 은 "mod"

        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(FakeHttp(dir).onFixture(), store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(FIXTURE.values.toSet(), store.contents.values.map { it.slug }.toSet())
        assertTrue(store.contents.values.all { it.kind == ContentKind.PLUGIN && it.source == Source.MODRINTH })
    }

    @Test
    fun duplicateVersionNumber_newestWins() = runTest {
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("PROJ1", "dup-plugin", 100)))
            .onJson(
                versionsUrl("PROJ1"),
                versionsJson(
                    // 오래된 것이 먼저
                    version("old", "PROJ1", number = "1.0", date = "2025-01-01T00:00:00Z"),
                    version("new", "PROJ1", number = "1.0", date = "2025-06-01T00:00:00Z"),
                    // 최신이 먼저
                    version("keep", "PROJ1", number = "0.9", date = "2024-06-01T00:00:00Z"),
                    version("stale", "PROJ1", number = "0.9", date = "2024-01-01T00:00:00Z"),
                ),
            )
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val stored = store.versionsOf("dup-plugin")
        assertEquals(setOf("1.0", "0.9"), stored.keys)
        assertEquals("new", stored["1.0"]?.second?.row?.sourceVersionId)
        assertEquals("keep", stored["0.9"]?.second?.row?.sourceVersionId)
        assertEquals(Instant.parse("2025-06-01T00:00:00Z"), stored["1.0"]?.second?.row?.publishedAt)
        assertEquals(2L, report.counters["versions.duplicateDropped"])
    }

    @Test
    fun nonJarPrimary_skipped() = runTest {
        val bytes = "x".encodeToByteArray()
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("PROJ1", "files-plugin", 100)))
            .onJson(
                versionsUrl("PROJ1"),
                versionsJson(
                    // primary 가 zip → jar 가 아닌 버전 (보조 jar 가 있어도)
                    version("v1", "PROJ1", files = listOf(jarFile("bundle.zip", bytes), jarFile("extra.jar", bytes, primary = false)), date = "2026-03-01T00:00:00Z"),
                    // primary 없음 → 첫 파일(jar)
                    version("v2", "PROJ1", files = listOf(jarFile("Plugin.JAR", bytes, primary = false), jarFile("sources.zip", bytes, primary = false))),
                    // 파일 없음
                    version("v3", "PROJ1", files = emptyList()),
                ),
            )
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, metadataOnly()))

        val stored = store.versionsOf("files-plugin")
        assertEquals(setOf("v2"), stored.keys)
        assertEquals("https://cdn.modrinth.com/data/test/Plugin.JAR", stored["v2"]?.second?.row?.fileUrl)
        assertEquals(setOf(LoaderFamily.BUKKIT), stored["v2"]?.second?.row?.loaders)
        assertEquals(2L, report.counters["versions.nonJarSkipped"])
    }

    @Test
    fun deps_requiredOptional_dropIncompatibleEmbedded_nonBukkit_self_confirmedAbsent() = runTest {
        val deps = listOf(
            MrDep(project_id = "A", dependency_type = "optional"),
            MrDep(project_id = "A", dependency_type = "required"), // 같은 대상 → REQUIRE 우선
            MrDep(project_id = "B", dependency_type = "optional"),
            MrDep(project_id = "D", dependency_type = "incompatible"),
            MrDep(project_id = "E", dependency_type = "embedded"),
            MrDep(project_id = "F", dependency_type = "required"), // Fabric 전용 대상
            MrDep(project_id = "P1", dependency_type = "required"), // 자기 자신
            MrDep(project_id = "Z", dependency_type = "required"), // 응답에 없음 → 확정적 부재
            MrDep(file_name = "external-lib.jar", dependency_type = "required"), // project_id 없음
        )
        val bulk = listOf(
            MrProject("A", "alpha", listOf("paper")),
            MrProject("B", "beta", listOf("fabric", "spigot")),
            MrProject("F", "fabric-lib", listOf("fabric", "quilt")),
            MrProject("P1", "main", listOf("paper")),
        )
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("P1", "main", 100)))
            .onJson(versionsUrl("P1"), versionsJson(version("v1", "P1", number = "1.0", deps = deps)))
            .onJson(bulkUrl("A", "B", "F", "P1", "Z"), CollectorJson.encodeToString(ListSerializer(MrProject.serializer()), bulk))
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val versionId = assertNotNull(store.versionsOf("main")["1.0"]).first
        assertEquals(listOf(DepRow(DepKind.REQUIRE, "alpha", null), DepRow(DepKind.OPTIONAL, "beta", null)), store.deps[versionId]?.toList())
        assertEquals(1L, report.counters["deps.dropped.incompatible"])
        assertEquals(1L, report.counters["deps.dropped.embedded"])
        assertEquals(1L, report.counters["deps.nonBukkitTarget"])
        assertEquals(1L, report.counters["deps.self"])
        assertEquals(1L, report.counters["deps.unresolved"])
        assertEquals(1L, report.counters["deps.noProjectId"])
        assertNull(report.counters["deps.deferred"])
    }

    @Test
    fun bulkBatchHttpFailure_projectDeferred_existingEdgesKept() = runTest {
        val store = RecordingStore()
        val seededContent = store.upsertContent(
            ContentRow(Source.MODRINTH, "hXiIvTyT", "essentialsx", "EssentialsX", ContentKind.PLUGIN, "GPL-3.0-only", false, "mdcfe", 1, null, null, null),
        )
        assertIs<ContentUpsertResult.Stored>(seededContent)
        val seededDeps = listOf(DepRow(DepKind.OPTIONAL, "luckperms", null), DepRow(DepKind.REQUIRE, "vault-seed", null))
        val seededRow = ContentVersionRow("2.22.0", "seed-id", "release", "https://cdn.modrinth.com/seed.jar", null, 1, setOf(LoaderFamily.BUKKIT), null, null, null)
        store.upsertContentVersionsMeta(seededContent.id, listOf(VersionMeta(seededRow, seededDeps)))

        val http = FakeHttp(dir).onFixture(bulk = FakeResponse.Status(500, "boom"))
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(SourceStatus.PARTIAL, report.status, report.toString())
        assertEquals(1L, report.counters["deps.deferred"])
        val stored = store.versionsOf("essentialsx")
        assertEquals(setOf("2.22.0"), stored.keys)
        val (versionId, meta) = assertNotNull(stored["2.22.0"])
        assertEquals(seededRow, meta.row)
        assertEquals(seededDeps, store.deps[versionId]?.toList())
        // 미룬 프로젝트는 분석하지 않는다. 다른 프로젝트는 정상 기록·분석 시도
        assertTrue(http.requests.none { it.method == "DOWNLOAD" && "EssentialsX" in it.url })
        assertEquals(13, store.versionsOf("chunky").size)
        assertTrue(http.requests.any { it.method == "DOWNLOAD" && it.url.endsWith("/Chunky-Bukkit-1.5.3.jar") })
    }

    @Test
    fun analysis_onlySelectedRelease_sha1Expected() = runTest {
        val good = "good-jar-bytes".encodeToByteArray()
        val beta = version("b", "SEL", number = "2.0-beta", type = "beta", date = "2026-03-01T00:00:00Z", files = listOf(jarFile("sel-2.0-beta.jar", good)))
        val release = version("r", "SEL", number = "1.9", date = "2026-02-01T00:00:00Z", files = listOf(jarFile("sel-1.9.jar", good)))
        val older = version("o", "SEL", number = "1.8", date = "2026-01-01T00:00:00Z", files = listOf(jarFile("sel-1.8.jar", good)))
        // 플랫폼 sha1 이 받은 바이트와 다르다 → 다운로드 검증 실패
        val mismatch = version("m", "MIS", number = "1.0", files = listOf(jarFile("mis-1.0.jar", good, sha1 = FakeHttp.hex(DigestAlgo.SHA1, "other".encodeToByteArray()))))
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("SEL", "selected", 100), hit("MIS", "mismatch", 50)))
            .onJson(versionsUrl("SEL"), versionsJson(beta, release, older))
            .onJson(versionsUrl("MIS"), versionsJson(mismatch))
        for (v in listOf(beta, release, older, mismatch)) http.on(v.files.single().url, FakeResponse.Body(good))
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir), analyzer = okAnalyzer))

        assertEquals(
            listOf("https://cdn.modrinth.com/data/test/sel-1.9.jar", "https://cdn.modrinth.com/data/test/mis-1.0.jar"),
            http.requests.filter { it.method == "DOWNLOAD" }.map { it.url },
        )
        val releaseId = assertNotNull(store.versionsOf("selected")["1.9"]).first
        assertEquals(setOf(releaseId), store.analyses.keys)
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, good), store.analyses[releaseId]?.sha256)
        assertEquals(1L, report.counters["analysis.recorded"])
        assertEquals(1L, report.counters["analysis.downloadFailed"])
        assertEquals(SourceStatus.PARTIAL, report.status)
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun twoPass_metadataWrittenBeforeAnyDownload() = runTest {
        val events = ArrayList<String>()
        val a = version("a1", "PA", files = listOf(jarFile("a.jar", "aaa".encodeToByteArray())))
        val b = version("b1", "PB", files = listOf(jarFile("b.jar", "bbb".encodeToByteArray())))
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("PA", "plugin-a", 100), hit("PB", "plugin-b", 50)))
            .onJson(versionsUrl("PA"), versionsJson(a))
            .onJson(versionsUrl("PB"), versionsJson(b))
        for ((v, bytes) in listOf(a to "aaa", b to "bbb")) {
            val url = v.files.single().url
            http.on(url) {
                synchronized(events) { events += "download:$url" }
                FakeResponse.Body(bytes.encodeToByteArray())
            }
        }
        val inner = RecordingStore()
        val ctx = CollectContext(RunMode.ONCE, http, TracingStore(inner, events), contentSettings(dir), okAnalyzer, FixedClock())
        val report = ModrinthSource().collect(ctx)

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val metas = events.withIndex().filter { it.value.startsWith("meta:") }
        val downloads = events.withIndex().filter { it.value.startsWith("download:") }
        assertEquals(2, metas.size, events.toString())
        assertEquals(2, downloads.size, events.toString())
        assertTrue(metas.last().index < downloads.first().index, events.toString())
        assertEquals(2, inner.analyses.size)
    }

    @Test
    fun badDate_nullPublishedAt_noThrow() = runTest {
        val http = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("PD", "dates", 100)))
            .onJson(
                versionsUrl("PD"),
                versionsJson(
                    version("v1", "PD", number = "1.0", date = "not-a-date"),
                    version("v2", "PD", number = "1.1", date = "2026-13-01T00:00:00Z"),
                    version("v3", "PD", number = "1.2", date = "2026-02-01T10:00:00.123456Z"),
                ),
            )
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val stored = store.versionsOf("dates")
        assertEquals(setOf("1.0", "1.1", "1.2"), stored.keys)
        assertNull(stored["1.0"]?.second?.row?.publishedAt)
        assertNull(stored["1.1"]?.second?.row?.publishedAt)
        assertEquals(Instant.parse("2026-02-01T10:00:00.123456Z"), stored["1.2"]?.second?.row?.publishedAt)
        assertEquals(2L, report.counters["dates.unparseable"])
        assertEquals(1, report.warnings.count { "게시 시각" in it })
    }

    @Test
    fun analyzePerProject0_noDownloads() = runTest {
        val http = FakeHttp(dir).onFixture()
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertTrue(http.requests.none { it.method == "DOWNLOAD" })
        assertTrue(store.analyses.isEmpty())
        assertEquals(5, store.contents.size)
        assertTrue(store.versions.values.sumOf { it.size } > 0)
    }

    @Test
    fun slugConflict_skipsProject() = runTest {
        val store = RecordingStore()
        store.upsertContent(ContentRow(Source.MODRINTH, "SOMEONE-ELSE", "chunky", "Other", ContentKind.PLUGIN, null, false, null, null, null, null, null))
        val http = FakeHttp(dir).onFixture()
        http.on("https://cdn.modrinth.com/data/fALzjamp/versions/MdY6JATr/Chunky-Bukkit-1.5.3.jar", FakeResponse.Body("never".encodeToByteArray()))
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(1L, report.counters["content.slugConflict"])
        assertTrue(report.warnings.any { "slug 충돌" in it })
        assertNull(store.contentIdOf(Source.MODRINTH, "fALzjamp"))
        assertTrue(http.requests.none { it.method == "DOWNLOAD" && "Chunky" in it.url })
        // 다른 프로젝트는 영향 없음
        assertNotNull(store.contentIdOf(Source.MODRINTH, "hXiIvTyT"))
    }

    @Test
    fun redistributable_alwaysFalse_withShippedPolicy() = runTest {
        val store = RecordingStore()
        ModrinthSource().collect(testContext(FakeHttp(dir).onFixture(), store, metadataOnly()))

        val bySlug = store.contents.values.associateBy { it.slug }
        assertEquals(FIXTURE.values.toSet(), bySlug.keys)
        assertTrue(bySlug.values.none { it.redistributable })
        // 라이선스 원문은 그대로 저장된다 (MIT 는 허용 목록에 있어도 스위치가 꺼져 있어 false)
        assertEquals("MIT", bySlug["luckperms"]?.license)
        assertEquals("GPL-3.0-only", bySlug["essentialsx"]?.license)
        assertEquals("LicenseRef-All-Rights-Reserved", bySlug["string-dupers-return"]?.license)
        assertEquals("https://modrinth.com/project/chunky", bySlug["chunky"]?.pageUrl)
        assertFalse(bySlug.values.any { it.pageUrl?.startsWith("https://modrinth.com/project/") != true })
    }

    @Test
    fun searchFailure_failed() = runTest {
        val store = RecordingStore()
        val http = FakeHttp(dir).on(searchUrl(0), FakeResponse.Status(500, "down"))
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(SourceStatus.FAILED, report.status)
        assertNotNull(report.error)
        assertEquals(1, http.requests.size)
        assertTrue(store.contents.isEmpty())
    }

    @Test
    fun sameVersionNumber_newUpload_reanalyzedNextCycle() = runTest {
        // 회귀 (C3-R1 / INV-1 / SC-2): Paper·Folia 빌드를 같은 version_number 로 몇 분 간격으로 올리는 흔한 경우.
        // D27 이 최신 업로드를 고르면 file_url 이 바뀌므로 예전 jar 의 분석(java_major·PROVIDES)이 남으면 안 된다.
        val oldBytes = "old-upload-jar".encodeToByteArray()
        val newBytes = "new-upload-jar-with-other-bytes".encodeToByteArray()
        val oldUpload = version("up-old", "SW", number = "1.0", date = "2026-05-01T00:00:00Z", files = listOf(jarFile("sw-1.0-paper.jar", oldBytes)))
        val newUpload = version("up-new", "SW", number = "1.0", date = "2026-05-01T00:10:00Z", files = listOf(jarFile("sw-1.0-folia.jar", newBytes)))
        val permission = CapabilityEvidence(Capability.PermissionProvider, EvidenceConfidence.STORE, "SERVICE_PROVIDER", "test")
        val analyzed = ArrayList<String>()
        val analyzer = JarAnalyzer { path ->
            val bytes = Files.readAllBytes(path)
            analyzed += bytes.decodeToString()
            // 예전 jar 만 PROVIDES 를 낸다 → 새 jar 분석 뒤 PROVIDES 가 남으면 안 된다
            if (bytes.contentEquals(oldBytes)) JarAnalysisResult.Ok(fakeAnalysis(capabilities = listOf(permission), javaFeature = 8)) else JarAnalysisResult.Ok(fakeAnalysis(javaFeature = 21))
        }

        fun http(vararg versions: MrVersion) = FakeHttp(dir)
            .onJson(searchUrl(0), searchJson(hit("SW", "switcher", 100)))
            .onJson(versionsUrl("SW"), versionsJson(*versions))
            .on(oldUpload.files.single().url, FakeResponse.Body(oldBytes))
            .on(newUpload.files.single().url, FakeResponse.Body(newBytes))

        val store = RecordingStore()

        // 1 사이클: 예전 업로드만 있다
        val first = ModrinthSource().collect(testContext(http(oldUpload), store, contentSettings(dir), analyzer = analyzer))
        assertEquals(1L, first.counters["analysis.recorded"], first.toString())
        val (vid, _) = assertNotNull(store.versionsOf("switcher")["1.0"])
        assertEquals(listOf(DepRow(DepKind.PROVIDES, null, Capability.PermissionProvider)), store.deps[vid]?.toList())

        // 2 사이클: 같은 version_number 로 새 업로드 → 같은 행이 새 파일을 가리키고, 다시 받아서 분석한다
        val http2 = http(oldUpload, newUpload)
        val second = ModrinthSource().collect(testContext(http2, store, contentSettings(dir), analyzer = analyzer))
        assertEquals(null, second.counters["analysis.upToDate"], second.toString())
        assertEquals(1L, second.counters["analysis.recorded"], second.toString())
        assertEquals(listOf(newUpload.files.single().url), http2.requests.filter { it.method == "DOWNLOAD" }.map { it.url })
        val (vid2, meta) = assertNotNull(store.versionsOf("switcher")["1.0"])
        assertEquals(vid, vid2)
        assertEquals("up-new", meta.row.sourceVersionId)
        val record = assertNotNull(store.analyses[vid])
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, newBytes), record.sha256)
        assertEquals(21, record.javaMajor)
        assertEquals(emptyList(), store.deps[vid].orEmpty().filter { it.kind == DepKind.PROVIDES }, "예전 jar 의 PROVIDES 는 지워졌다")
        assertEquals(listOf("old-upload-jar", "new-upload-jar-with-other-bytes"), analyzed)

        // 3 사이클: 변화 없음 → 요청 없이 upToDate
        val http3 = http(oldUpload, newUpload)
        val third = ModrinthSource().collect(testContext(http3, store, contentSettings(dir), analyzer = analyzer))
        assertEquals(1L, third.counters["analysis.upToDate"], third.toString())
        assertTrue(http3.requests.none { it.method == "DOWNLOAD" })
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun noJarLeft() = runTest {
        val projects = listOf("PA", "PB", "PC")
        val http = FakeHttp(dir).onJson(searchUrl(0), searchJson(*projects.mapIndexed { i, id -> hit(id, downloads = 100L - i) }.toTypedArray()))
        for (id in projects) {
            val bytes = "jar-of-$id".encodeToByteArray()
            val v = version("${id}v", id, files = listOf(jarFile("$id.jar", bytes)))
            http.onJson(versionsUrl(id), versionsJson(v))
            http.on(v.files.single().url, FakeResponse.Body(bytes))
        }
        var calls = 0
        val analyzer = JarAnalyzer {
            calls++
            when (calls) {
                1 -> JarAnalysisResult.Ok(fakeAnalysis())
                2 -> throw StackOverflowError("analyzer bug")
                else -> JarAnalysisResult.Unreadable("not a zip")
            }
        }
        val store = RecordingStore()
        val report = ModrinthSource().collect(testContext(http, store, contentSettings(dir), analyzer = analyzer))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(3, calls)
        assertEquals(3, store.analyses.size)
        assertEquals(1L, report.counters["analysis.crashed"])
        assertEquals(0, countFiles(dir))
    }
}
