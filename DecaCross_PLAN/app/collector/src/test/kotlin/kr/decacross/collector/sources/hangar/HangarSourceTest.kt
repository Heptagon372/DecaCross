package kr.decacross.collector.sources.hangar

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.content.CapabilityRulesLoader
import kr.decacross.collector.content.VersionSelection
import kr.decacross.collector.content.contentSettings
import kr.decacross.collector.content.effectiveAnalyzerVersion
import kr.decacross.collector.content.encSeg
import kr.decacross.collector.content.fakeAnalysis
import kr.decacross.collector.content.mcRow
import kr.decacross.collector.content.resourceText
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.AnalysisRecord
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
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Source
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val HG = "https://hangar.papermc.io"
private const val EMPTY_RESULT = """{"result":[]}"""

private const val VIA = 12L
private const val ESSENTIALS = 23L
private const val GEYSER = 14L
private const val HUSK = 463L
private const val CORE_PROTECT = 10L

/** 픽스처 버전 파일의 키 (프로젝트 이름). */
private val FIXTURE_VERSIONS: Map<Long, String> = mapOf(VIA to "ViaBackwards", ESSENTIALS to "Essentials", HUSK to "HuskHomes")

private fun listUrl(offset: Int, limit: Int = 50) = "$HG/api/v1/projects?platform=PAPER&sort=-downloads&limit=$limit&offset=$offset"

private fun versionsUrl(id: Long, offset: Int = 0) = "$HG/api/v1/projects/$id/versions?platform=PAPER&limit=25&offset=$offset"

private fun projectUrl(id: Long) = "$HG/api/v1/projects/$id"

private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

/** 픽스처 프로젝트 목록 (ids 가 있으면 그 프로젝트만). */
private fun projectsJson(ids: Set<Long>? = null): String {
    val root = CollectorJson.parseToJsonElement(resourceText("/c3/hangar_top100.json")).jsonObject
    val result = root.getValue("result").jsonArray.filter { ids == null || it.jsonObject.getValue("id").jsonPrimitive.long in ids }
    return root.with("result", JsonArray(result)).toString()
}

/** 픽스처 버전 페이지. [patch] 로 버전 객체를 고칠 수 있다. */
private fun versionsJson(name: String, patch: (JsonObject) -> JsonObject = { it }): String {
    val page = CollectorJson.parseToJsonElement(resourceText("/c3/hangar_top100_versions.json")).jsonObject.getValue(name).jsonObject
    return page.with("result", JsonArray(page.getValue("result").jsonArray.map { patch(it.jsonObject) })).toString()
}

private fun fixtureVersions(name: String): List<HgVersion> = CollectorJson.decodeFromString(HgVersions.serializer(), versionsJson(name)).result

/** 버전 객체의 PAPER fileInfo 를 바꾼다. */
private fun patchFileInfo(v: JsonObject, sha256: String, size: Long): JsonObject {
    val downloads = v.getValue("downloads").jsonObject
    val paper = downloads.getValue("PAPER").jsonObject
    val info = paper.getValue("fileInfo").jsonObject.with("sha256Hash", JsonPrimitive(sha256)).with("sizeBytes", JsonPrimitive(size))
    return v.with("downloads", downloads.with("PAPER", paper.with("fileInfo", info)))
}

private const val VIAVERSION_PROJECT = """{"id":31,"name":"ViaVersion","namespace":{"owner":"ViaVersion","slug":"ViaVersion"},"stats":{"downloads":1}}"""

/** 픽스처 목록(한 페이지) + 빈 두 번째 페이지 + 버전 목록 + ViaVersion(31) 단건 조회. ViaRewind(112)는 등록하지 않는다(404). */
private fun FakeHttp.onHangarFixture(ids: Set<Long>? = null): FakeHttp {
    onJson(listUrl(0), projectsJson(ids))
    onJson(listUrl(50), EMPTY_RESULT)
    for (id in listOf(VIA, ESSENTIALS, GEYSER, HUSK, CORE_PROTECT)) {
        onJson(versionsUrl(id), FIXTURE_VERSIONS[id]?.let { versionsJson(it) } ?: EMPTY_RESULT)
    }
    onJson(projectUrl(31), VIAVERSION_PROJECT)
    return this
}

private fun projectJson(id: Long, slug: String, owner: String = "Owner", downloads: Long = 1): HgProject =
    HgProject(id = id, name = slug, namespace = HgNamespace(owner, slug), stats = HgStats(downloads))

private fun projectsPage(vararg projects: HgProject): String = CollectorJson.encodeToString(HgProjects.serializer(), HgProjects(projects.toList()))

private fun hostedDownload(id: Long) = HgDownload(HgFileInfo("v$id.jar", 10, "ab".repeat(32)), null, "https://hangarcdn.papermc.io/plugins/o/p/versions/v$id/PAPER/v$id.jar")

private fun externalDownload() = HgDownload(fileInfo = null, externalUrl = "https://github.com/owner/project/releases", downloadUrl = null)

private fun hv(
    id: Long,
    channel: String = "Release",
    flags: List<String> = emptyList(),
    created: String = "2026-01-01T00:00:00Z",
    hosted: Boolean = true,
    name: String = "v$id",
) = HgVersion(id, name, created, HgChannel(channel, flags), downloads = mapOf("PAPER" to if (hosted) hostedDownload(id) else externalDownload()))

private fun versionsPage(versions: List<HgVersion>): String = CollectorJson.encodeToString(HgVersions.serializer(), HgVersions(versions))

private fun RecordingStore.hangarVersions(id: Long): Map<String, Pair<Long, VersionMeta>> =
    contentIdOf(Source.HANGAR, id.toString())?.let { versions[it] }.orEmpty()

class HangarSourceTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun metadataOnly() = contentSettings(dir) { it.copy(analyzePerProject = 0) }

    @Test
    fun sortDescending_param_andLimit50_twoPages_dedupedById() = runTest {
        // 두 요청 사이에 순위가 밀려 ViaBackwards(12)가 두 번째 페이지에도 나온다
        val secondPage = projectsPage(projectJson(VIA, "ViaBackwards", "ViaVersion", downloads = 1), projectJson(999, "NewPlugin", downloads = 5))
        val http = FakeHttp(dir)
            .onJson(listUrl(0), projectsJson())
            .onJson(listUrl(50), secondPage)
        for (id in listOf(VIA, ESSENTIALS, GEYSER, HUSK, CORE_PROTECT, 999L)) http.onJson(versionsUrl(id), EMPTY_RESULT)
        val store = RecordingStore()
        val report = HangarSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val listRequests = http.requests.map { it.url }.filter { "/api/v1/projects?" in it }
        assertEquals(listOf(listUrl(0), listUrl(50)), listRequests)
        assertEquals("https://hangar.papermc.io/api/v1/projects?platform=PAPER&sort=-downloads&limit=50&offset=0", listRequests.first())
        assertEquals(6, store.contents.values.count { it.source == Source.HANGAR })
        assertEquals(735_427L, store.contents.values.single { it.sourceId == "12" }.downloads)
        assertEquals(1, http.requests.count { it.url == versionsUrl(VIA) })
        assertEquals(1L, report.counters["projects.duplicate"])
    }

    @Test
    fun licenseMapping_pageUrl() = runTest {
        val store = RecordingStore()
        val report = HangarSource().collect(testContext(FakeHttp(dir).onHangarFixture(), store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val byId = store.contents.values.associateBy { it.sourceId }
        val via = assertNotNull(byId["12"])
        assertEquals(ContentRow(Source.HANGAR, "12", "ViaBackwards", "ViaBackwards", ContentKind.PLUGIN, "GPL", false, "ViaVersion", 735_427, via.iconUrl, via.description, "https://hangar.papermc.io/ViaVersion/ViaBackwards"), via)
        assertEquals("https://hangarcdn.papermc.io/avatars/project/12.webp?v=1", via.iconUrl)
        assertEquals("MIT", byId["14"]?.license)
        assertEquals("Apache-2.0", byId["463"]?.license)
        assertEquals("Artistic License 2.0", byId["10"]?.license)
        assertEquals("GPL", byId["23"]?.license)
        assertTrue(byId.values.none { it.redistributable })
        assertEquals("https://hangar.papermc.io/William278/HuskHomes", byId["463"]?.pageUrl)
        assertEquals("Some%20Owner", encSeg("Some Owner"))
        assertEquals("a%2Bb", encSeg("a+b"))
    }

    @Test
    fun external_notAnalyzed_fileUrlNull() = runTest {
        val http = FakeHttp(dir).onHangarFixture(setOf(ESSENTIALS))
        val store = RecordingStore()
        val report = HangarSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val stored = store.hangarVersions(ESSENTIALS)
        assertEquals(7, stored.size)
        for ((_, meta) in stored.values) {
            assertNull(meta.row.fileUrl)
            assertNull(meta.row.sha256)
            assertNull(meta.row.size)
            assertEquals(setOf(LoaderFamily.BUKKIT), meta.row.loaders)
            assertEquals("Release", meta.row.channel)
            assertEquals(emptyList(), meta.deps)
        }
        assertEquals("25952", stored["2.22.0"]?.second?.row?.sourceVersionId)
        assertEquals(7L, report.counters["versions.external"])
        assertEquals(7L, report.counters["deps.external"])
        assertEquals(1L, report.counters["analysis.externalSkipped"])
        assertTrue(http.requests.none { it.method == "DOWNLOAD" })
        assertTrue(store.analyses.isEmpty())
    }

    @Test
    fun sha256Prefilled_andExpectedOnDownload() = runTest {
        val husk = fixtureVersions("HuskHomes")
        val release = husk.single { it.name == "4.11" }
        val releaseUrl = assertNotNull(release.paperDownload()?.downloadUrl)
        val fake = "fake-huskhomes-jar".encodeToByteArray()

        // A) 플랫폼 sha256 이 메타데이터에 미리 들어가고, 다운로드 때 기대 해시로 쓰인다 → 다른 바이트면 검증 실패
        val httpA = FakeHttp(dir).onHangarFixture(setOf(HUSK)).on(releaseUrl, FakeResponse.Body(fake))
        val storeA = RecordingStore()
        val reportA = HangarSource().collect(testContext(httpA, storeA, contentSettings(dir), analyzer = { JarAnalysisResult.Ok(fakeAnalysis()) }))

        val storedA = storeA.hangarVersions(HUSK)
        assertEquals(6, storedA.size)
        for (v in husk) {
            val row = assertNotNull(storedA[v.name]).second.row
            assertEquals(v.paperDownload()?.fileInfo?.sha256Hash?.lowercase(), row.sha256)
            assertEquals(v.paperDownload()?.fileInfo?.sizeBytes, row.size)
            assertEquals(v.paperDownload()?.downloadUrl, row.fileUrl)
        }
        assertEquals(listOf(releaseUrl), httpA.requests.filter { it.method == "DOWNLOAD" }.map { it.url })
        assertEquals(1L, reportA.counters["analysis.downloadFailed"])
        assertTrue(storeA.analyses.isEmpty())
        assertEquals(SourceStatus.PARTIAL, reportA.status)
        assertEquals(0, countFiles(dir))

        // B) 기대 해시(대문자로 와도 소문자로 정규화)와 받은 바이트가 맞으면 기록
        val fakeSha = FakeHttp.hex(DigestAlgo.SHA256, fake)
        val patched = versionsJson("HuskHomes") { v ->
            if (v.getValue("name").jsonPrimitive.content == "4.11") patchFileInfo(v, fakeSha.uppercase(), fake.size.toLong()) else v
        }
        val httpB = FakeHttp(dir).onHangarFixture(setOf(HUSK)).onJson(versionsUrl(HUSK), patched).on(releaseUrl, FakeResponse.Body(fake))
        val storeB = RecordingStore()
        val reportB = HangarSource().collect(testContext(httpB, storeB, contentSettings(dir), analyzer = { JarAnalysisResult.Ok(fakeAnalysis()) }))

        assertEquals(SourceStatus.OK, reportB.status, reportB.toString())
        val (releaseId, meta) = assertNotNull(storeB.hangarVersions(HUSK)["4.11"])
        assertEquals(fakeSha, meta.row.sha256)
        assertEquals(fakeSha, storeB.analyses[releaseId]?.sha256)
        assertEquals(setOf(releaseId), storeB.analyses.keys)
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun dedupeAcrossSources_bySha256() = runTest {
        val release = fixtureVersions("HuskHomes").single { it.name == "4.11" }
        val sha = assertNotNull(release.paperDownload()?.fileInfo?.sha256Hash).lowercase()
        val clock = FixedClock(Instant.parse("2026-09-18T00:00:00Z"))
        val store = RecordingStore()
        // 같은 jar 를 Modrinth 쪽에서 이미 같은 유효 분석기 버전으로 분석했다
        val mr = store.upsertContent(ContentRow(Source.MODRINTH, "MRID", "huskhomes", "HuskHomes", ContentKind.PLUGIN, "Apache-2.0", false, null, null, null, null, null))
        assertIs<ContentUpsertResult.Stored>(mr)
        val mrIds = store.upsertContentVersionsMeta(
            mr.id,
            listOf(VersionMeta(ContentVersionRow("4.11", "mrv", "release", "https://cdn.modrinth.com/x.jar", null, 1, setOf(LoaderFamily.BUKKIT), null, null, null), emptyList())),
        )
        val seeded = AnalysisRecord(sha, 1234, 17, "1.20", null, Instant.parse("2026-09-01T00:00:00Z"), effectiveAnalyzerVersion(CapabilityRulesLoader.load()), "{}", listOf(Capability.PermissionProvider))
        store.recordAnalysis(assertNotNull(mrIds["4.11"]), seeded)

        val http = FakeHttp(dir).onHangarFixture(setOf(HUSK))
        val report = HangarSource().collect(testContext(http, store, contentSettings(dir), clock = clock))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertTrue(http.requests.none { it.method == "DOWNLOAD" })
        val hangarId = assertNotNull(store.hangarVersions(HUSK)["4.11"]).first
        assertEquals(seeded.copy(analyzedAt = clock.now), store.analyses[hangarId])
        assertEquals(listOf(DepRow(DepKind.PROVIDES, null, Capability.PermissionProvider)), store.deps[hangarId]?.toList())
        assertEquals(1L, report.counters["analysis.dedupedBySha256"])
    }

    @Test
    fun deps_projectIdResolved_externalDropped_404Dropped() = runTest {
        val http = FakeHttp(dir).onHangarFixture(setOf(VIA, ESSENTIALS))
        val store = RecordingStore()
        val report = HangarSource().collect(testContext(http, store, metadataOnly()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val via = store.hangarVersions(VIA)
        assertEquals(14, via.size)
        for ((versionId, meta) in via.values) {
            assertEquals(listOf(DepRow(DepKind.REQUIRE, "ViaVersion", null)), meta.deps)
            assertEquals(listOf(DepRow(DepKind.REQUIRE, "ViaVersion", null)), store.deps[versionId]?.toList())
        }
        assertTrue(store.hangarVersions(ESSENTIALS).values.all { it.second.deps.isEmpty() })
        // 404(ViaRewind) → 확정적 부재로 버림, 외부(Vault) → 버림. 조회는 대상마다 한 번(캐시)
        assertEquals(14L, report.counters["deps.unresolved"])
        assertEquals(7L, report.counters["deps.external"])
        assertEquals(1, http.requests.count { it.url == projectUrl(112) })
        assertEquals(1, http.requests.count { it.url == projectUrl(31) })
        val slugs = store.deps.values.flatten().mapNotNull { it.targetSlug }.toSet()
        assertEquals(setOf("ViaVersion"), slugs)
    }

    @Test
    fun deps_lookupFailure_projectDeferred_noNameFallback() = runTest {
        val store = RecordingStore()
        val seeded = store.upsertContent(ContentRow(Source.HANGAR, "12", "ViaBackwards", "ViaBackwards", ContentKind.PLUGIN, "GPL", false, "ViaVersion", 1, null, null, null))
        assertIs<ContentUpsertResult.Stored>(seeded)
        val seededRow = ContentVersionRow("5.11.0", "27686", "Release", "https://hangarcdn.papermc.io/seed.jar", null, 1, setOf(LoaderFamily.BUKKIT), null, null, null)
        val seededDeps = listOf(DepRow(DepKind.REQUIRE, "ViaVersion", null))
        store.upsertContentVersionsMeta(seeded.id, listOf(VersionMeta(seededRow, seededDeps)))

        val http = FakeHttp(dir).onHangarFixture(setOf(VIA)).on(projectUrl(112), FakeResponse.Status(503, "busy"))
        val report = HangarSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(SourceStatus.PARTIAL, report.status, report.toString())
        assertEquals(1L, report.counters["deps.deferred"])
        val stored = store.hangarVersions(VIA)
        assertEquals(setOf("5.11.0"), stored.keys)
        val (versionId, meta) = assertNotNull(stored["5.11.0"])
        assertEquals(seededRow, meta.row)
        assertEquals(seededDeps, store.deps[versionId]?.toList())
        // 플러그인 이름("ViaRewind")을 slug 로 대신 쓰지 않는다
        assertTrue(store.deps.values.flatten().none { it.targetSlug == "ViaRewind" })
        assertTrue(http.requests.none { it.method == "DOWNLOAD" })
    }

    @Test
    fun platformDependencies_stringOrder_mappedByOrdinal() = runTest {
        val store = RecordingStore()
        // 테스트용 서수 (1.20.5 는 일부러 없음)
        val labels = listOf(
            "1.20.1" to 1810, "1.20.2" to 1820, "1.20.3" to 1830, "1.20.4" to 1840, "1.20.6" to 1860,
            "1.21" to 1870, "1.21.1" to 1880, "1.21.2" to 1890, "1.21.4" to 1910, "1.21.5" to 1920,
            "1.21.6" to 1930, "1.21.8" to 1950, "1.21.11" to 1980, "26.1.1" to 2000, "26.1.2" to 2010, "26.2" to 2020,
        )
        store.mc += labels.map { (label, ordinal) -> mcRow(label, ordinal) }
        val report = HangarSource().collect(testContext(FakeHttp(dir).onHangarFixture(setOf(HUSK)), store, metadataOnly()))

        val stored = store.hangarVersions(HUSK)
        val release = assertNotNull(stored["4.11"]).second.row
        // 문자열 최댓값은 "1.21.8" 이지만 서수 최댓값은 1.21.11
        assertEquals(McOrdinal(1810), release.mcOrdinalMin)
        assertEquals(McOrdinal(1980), release.mcOrdinalMax)
        val alpha = assertNotNull(stored["4.11-7a2d09a"]).second.row
        assertEquals(McOrdinal(1810), alpha.mcOrdinalMin)
        assertEquals(McOrdinal(2020), alpha.mcOrdinalMax)
        assertEquals(6L, report.counters["mc.unknownLabels"])
    }

    @Test
    fun selection_releaseNotUnstable_fallbacks() = runTest {
        val unstable = listOf(FLAG_UNSTABLE)
        // 1) Release 채널 + UNSTABLE 없음 → 더 새로운 Alpha·외부 Release 를 제치고 선택
        val a = listOf(
            hv(1, "Alpha", unstable, "2026-03-01T00:00:00Z"),
            hv(2, "Release", created = "2026-01-01T00:00:00Z"),
            hv(3, "Release", created = "2026-04-01T00:00:00Z", hosted = false),
            hv(4, "release", created = "2025-12-01T00:00:00Z"),
        )
        assertEquals(listOf(2L), VersionSelection.hangar(a, 1).map { it.id })
        assertEquals(listOf(2L, 4L, 1L), VersionSelection.hangar(a, 5).map { it.id })
        // 2) Release 가 없거나 UNSTABLE 이면 → UNSTABLE 없는 최신
        val b = listOf(
            hv(1, "Alpha", unstable, "2026-03-01T00:00:00Z"),
            hv(5, "Beta", created = "2026-02-01T00:00:00Z"),
            hv(6, "Release", unstable, "2026-02-15T00:00:00Z"),
        )
        assertEquals(listOf(5L), VersionSelection.hangar(b, 1).map { it.id })
        // 3) 전부 UNSTABLE → 최신
        val c = listOf(hv(7, "Snapshot", unstable, "2026-01-01T00:00:00Z"), hv(8, "Alpha", unstable, "2026-02-01T00:00:00Z"))
        assertEquals(listOf(8L), VersionSelection.hangar(c, 1).map { it.id })
        // 외부 링크뿐이면 아무것도 고르지 않는다
        assertEquals(emptyList(), VersionSelection.hangar(listOf(hv(9, hosted = false)), 1))
        assertEquals(emptyList(), VersionSelection.hangar(a, 0))

        // 소스에서: HuskHomes 는 더 새로운 Alpha(UNSTABLE) 대신 4.11(Release) 을 받는다
        val release = fixtureVersions("HuskHomes").single { it.name == "4.11" }
        val http = FakeHttp(dir).onHangarFixture(setOf(HUSK))
        HangarSource().collect(testContext(http, RecordingStore(), contentSettings(dir), analyzer = JarAnalyzer { JarAnalysisResult.Ok(fakeAnalysis()) }))
        assertEquals(listOf(release.paperDownload()?.downloadUrl), http.requests.filter { it.method == "DOWNLOAD" }.map { it.url })
    }

    @Test
    fun once_singlePage_loop_paginatesUntilKnown() = runTest {
        val id = 777L
        // 페이지 p 의 버전 id: 3000 - 25p - i (최신순), 외부 링크 버전이라 다운로드는 없다
        fun page(p: Int, size: Int = 25) = versionsPage(
            (0 until size).map { i ->
                val vid = 3000L - 25 * p - i
                hv(vid, created = Instant.parse("2026-06-01T00:00:00Z").minus(kotlin.time.Duration.parse("${vid % 1000 + 1}h")).toString(), hosted = false, name = "1.$vid")
            },
        )

        fun http() = FakeHttp(dir)
            .onJson(listUrl(0), projectsPage(projectJson(id, "Paged")))
            .onJson(listUrl(50), EMPTY_RESULT)
            .onJson(versionsUrl(id, 0), page(0))
            .onJson(versionsUrl(id, 25), page(1))
            .onJson(versionsUrl(id, 50), page(2))
            .onJson(versionsUrl(id, 75), page(3, size = 10))

        fun FakeHttp.versionOffsets() = requests.map { it.url }.filter { "/projects/$id/versions" in it }.map { it.substringAfterLast("offset=").toInt() }

        // --once: 첫 페이지만
        val once = http()
        val onceStore = RecordingStore()
        HangarSource().collect(testContext(once, onceStore, metadataOnly(), mode = RunMode.ONCE))
        assertEquals(listOf(0), once.versionOffsets())
        assertEquals(25, onceStore.hangarVersions(id).size)

        // --loop: 이미 저장된 sourceVersionId 가 나온 페이지에서 멈춘다
        val loopKnown = http()
        val knownStore = RecordingStore()
        val seeded = knownStore.upsertContent(ContentRow(Source.HANGAR, "777", "Paged", "Paged", ContentKind.PLUGIN, null, false, "Owner", 1, null, null, null))
        assertIs<ContentUpsertResult.Stored>(seeded)
        knownStore.upsertContentVersionsMeta(
            seeded.id,
            listOf(VersionMeta(ContentVersionRow("1.2965", "2965", "Release", null, null, null, setOf(LoaderFamily.BUKKIT), null, null, null), emptyList())),
        )
        HangarSource().collect(testContext(loopKnown, knownStore, metadataOnly(), mode = RunMode.LOOP))
        assertEquals(listOf(0, 25), loopKnown.versionOffsets())
        assertEquals(50, knownStore.hangarVersions(id).size)

        // --loop: versionsPerProject 에 닿으면 멈춘다
        val loopCap = http()
        HangarSource().collect(testContext(loopCap, RecordingStore(), contentSettings(dir) { it.copy(analyzePerProject = 0, versionsPerProject = 50) }, mode = RunMode.LOOP))
        assertEquals(listOf(0, 25), loopCap.versionOffsets())

        // --loop: 가득 차지 않은 페이지에서 멈춘다
        val loopAll = http()
        val allStore = RecordingStore()
        val report = HangarSource().collect(testContext(loopAll, allStore, metadataOnly(), mode = RunMode.LOOP))
        assertEquals(listOf(0, 25, 50, 75), loopAll.versionOffsets())
        assertEquals(85, allStore.hangarVersions(id).size)
        assertEquals(SourceStatus.OK, report.status, report.toString())
    }

    @Test
    fun badCreatedAt_nullPublishedAt_noThrow() = runTest {
        val id = 778L
        val http = FakeHttp(dir)
            .onJson(listUrl(0), projectsPage(projectJson(id, "Dates")))
            .onJson(listUrl(50), EMPTY_RESULT)
            .onJson(
                versionsUrl(id),
                versionsPage(listOf(hv(1, created = "yesterday", hosted = false, name = "1.0"), hv(2, created = "2026-08-18T17:39:11.472583Z", hosted = false, name = "1.1"))),
            )
        val store = RecordingStore()
        val report = HangarSource().collect(testContext(http, store, contentSettings(dir)))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val stored = store.hangarVersions(id)
        assertNull(stored["1.0"]?.second?.row?.publishedAt)
        assertEquals(Instant.parse("2026-08-18T17:39:11.472583Z"), stored["1.1"]?.second?.row?.publishedAt)
        assertEquals(1L, report.counters["dates.unparseable"])
        assertEquals(1, report.warnings.count { "게시 시각" in it })
    }

    @Test
    fun sameVersion_platformSha256Changed_reanalyzed_staleAnalysisNotCopied() = runTest {
        // 회귀 (C3-R1 / INV-1): 같은 Hangar 버전의 파일이 바뀌어 플랫폼 sha256 이 달라지면 예전 분석을 새 sha256 에 붙이지 않고 다시 분석한다
        val id = 779L
        val url = "https://hangarcdn.papermc.io/plugins/o/swap/versions/1.0/PAPER/swap.jar"
        val oldBytes = "hangar-old-jar".encodeToByteArray()
        val newBytes = "hangar-new-jar-bytes".encodeToByteArray()

        fun versionWith(bytes: ByteArray) = HgVersion(
            id = 9001,
            name = "1.0",
            createdAt = "2026-05-01T00:00:00Z",
            channel = HgChannel("Release"),
            downloads = mapOf("PAPER" to HgDownload(HgFileInfo("swap.jar", bytes.size.toLong(), FakeHttp.hex(DigestAlgo.SHA256, bytes)), null, url)),
        )

        fun http(bytes: ByteArray) = FakeHttp(dir)
            .onJson(listUrl(0), projectsPage(projectJson(id, "Swap")))
            .onJson(listUrl(50), EMPTY_RESULT)
            .onJson(versionsUrl(id), versionsPage(listOf(versionWith(bytes))))
            .on(url, FakeResponse.Body(bytes))

        val analyzer = JarAnalyzer { path ->
            val feature = if (Files.readAllBytes(path).contentEquals(oldBytes)) 8 else 21
            JarAnalysisResult.Ok(fakeAnalysis(javaFeature = feature))
        }
        val store = RecordingStore()

        val first = HangarSource().collect(testContext(http(oldBytes), store, contentSettings(dir), analyzer = analyzer))
        assertEquals(1L, first.counters["analysis.recorded"], first.toString())
        val vid = assertNotNull(store.hangarVersions(id)["1.0"]).first
        assertEquals(8, store.analyses[vid]?.javaMajor)

        val http2 = http(newBytes)
        val second = HangarSource().collect(testContext(http2, store, contentSettings(dir), analyzer = analyzer))
        assertEquals(null, second.counters["analysis.upToDate"], second.toString())
        assertEquals(null, second.counters["analysis.dedupedBySha256"], "예전 분석이 새 sha256 으로 복사되면 안 된다: $second")
        assertEquals(1L, second.counters["analysis.recorded"], second.toString())
        assertEquals(listOf(url), http2.requests.filter { it.method == "DOWNLOAD" }.map { it.url })
        val record = assertNotNull(store.analyses[vid])
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, newBytes), record.sha256)
        assertEquals(21, record.javaMajor)
        assertEquals(0, countFiles(dir))
    }
}
