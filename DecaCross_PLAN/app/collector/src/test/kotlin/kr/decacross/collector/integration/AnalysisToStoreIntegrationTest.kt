package kr.decacross.collector.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kr.decacross.analysis.ANALYZER_VERSION
import kr.decacross.collector.content.AnalysisTarget
import kr.decacross.collector.content.CapabilityRulesLoader
import kr.decacross.collector.content.ContentPipeline
import kr.decacross.collector.content.DownloadBudget
import kr.decacross.collector.content.effectiveAnalyzerVersion
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.JvmJarAnalyzer
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.ContentRow
import kr.decacross.collector.store.ContentUpsertResult
import kr.decacross.collector.store.ContentVersionRow
import kr.decacross.collector.store.PgCollectorStore
import kr.decacross.collector.store.TestPg
import kr.decacross.collector.store.VersionMeta
import kr.decacross.collector.store.getIntOrNull
import kr.decacross.collector.store.getLongOrNull
import kr.decacross.collector.store.mapRows
import kr.decacross.collector.store.prepared
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.countFiles
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Source
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 분석 → 저장 실제 사슬 통합 테스트 (DESIGN §11.2).
 *
 * 격리된 작업 패키지에서는 돌릴 수 없던 조합을 처음으로 함께 돌린다:
 * FakeHttp 다운로드 → 실제 [JvmJarAnalyzer] (`CapabilityRulesLoader.load()` 규칙) → [ContentPipeline] → [PgCollectorStore] (임베디드 PG).
 *
 * # 불변식
 * - jar 는 메모리에서 만들고 FakeHttp 가 임시 디렉터리에만 쓴다. 레포 안에 jar 파일을 두지 않는다 (A8).
 * - 분석 뒤 임시 디렉터리에 파일이 남지 않는다 (분석한 jar 는 즉시 폐기).
 */
class AnalysisToStoreIntegrationTest {
    private val tempDir = newTempDir("decacross-it-")
    private val opened = ArrayList<PgCollectorStore>()
    private val clock = FixedClock(Instant.parse("2026-09-17T12:00:00Z"))
    private val jarUrl = "https://cdn.modrinth.com/data/ITEST/versions/V1/ITest-1.jar"
    private val jarBytes = pluginJarBytes()

    @BeforeTest
    fun setUp() {
        // TestPg 불변식: runTest/runBlocking 밖에서 기동·마이그레이션
        TestPg.resetAndMigrate()
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
        deleteTree(tempDir)
    }

    private fun open(): PgCollectorStore = TestPg.openStore().also { opened += it }

    private class Chain(val http: FakeHttp, val report: ReportBuilder, val pipeline: ContentPipeline, val effective: String)

    /** 운영 소스(ModrinthSource/HangarSource)와 같은 방식으로 파이프라인을 조립한다. */
    private fun chain(store: PgCollectorStore): Chain {
        val http = FakeHttp(tempDir).on(jarUrl, FakeResponse.Body(jarBytes))
        val settings = testSettings(tempDir)
        val rules = CapabilityRulesLoader.load()
        val effective = effectiveAnalyzerVersion(rules)
        val ctx = CollectContext(RunMode.ONCE, http, store, settings, JvmJarAnalyzer(rules), clock)
        val report = ReportBuilder(SourceId.MODRINTH)
        val budget = DownloadBudget(settings.content.maxDownloadBytesPerCycle, settings.content.maxJarBytes)
        return Chain(http, report, ContentPipeline(ctx, report, budget, effective), effective)
    }

    private class Seeded(val contentId: Long, val versionIds: Map<String, Long>)

    /** content 1행 + content_versions 행들을 저장소 API 로 넣는다. */
    private suspend fun seedContent(store: PgCollectorStore, vararg versions: String): Seeded {
        val content = ContentRow(
            source = Source.MODRINTH,
            sourceId = "ITEST",
            slug = "itest",
            name = "ITest",
            kind = ContentKind.PLUGIN,
            license = "MIT",
            redistributable = false,
            author = "it",
            downloads = 1,
            iconUrl = null,
            description = null,
            pageUrl = "https://modrinth.com/plugin/itest",
        )
        val contentId = assertIs<ContentUpsertResult.Stored>(store.upsertContent(content)).id
        val metas = versions.map { v ->
            VersionMeta(
                ContentVersionRow(
                    version = v,
                    sourceVersionId = "V-$v",
                    channel = "release",
                    fileUrl = jarUrl,
                    sha256 = null,
                    size = jarBytes.size.toLong(),
                    loaders = setOf(LoaderFamily.BUKKIT),
                    mcOrdinalMin = McOrdinal(1860),
                    mcOrdinalMax = McOrdinal(1860),
                    publishedAt = clock.now,
                ),
                emptyList(),
            )
        }
        return Seeded(contentId, store.upsertContentVersionsMeta(contentId, metas))
    }

    private data class AnalysisRow(
        val sha256: String?,
        val size: Long?,
        val javaMajor: Int?,
        val apiVersion: String?,
        val analyzerVersion: String?,
        val analysis: String?,
        val supportedFormats: String?,
    )

    private fun analysisRow(versionId: Long): AnalysisRow = TestPg.connection().use { c ->
        c.prepared(
            "select sha256, size, java_major, api_version, analyzer_version, analysis::text, " +
                "(analysis -> 'packMeta' -> 'supportedFormats')::text from content_versions where id = ?",
        ) { ps ->
            ps.setLong(1, versionId)
            ps.executeQuery().mapRows { rs ->
                AnalysisRow(rs.getString(1), rs.getLongOrNull(2), rs.getIntOrNull(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))
            }.single()
        }
    }

    private fun depRows(versionId: Long): List<List<String?>> = TestPg.connection().use { c ->
        c.prepared("select kind, target_slug, target_capability, range from content_deps where content_version_id = ? order by id") { ps ->
            ps.setLong(1, versionId)
            ps.executeQuery().mapRows { rs -> (1..4).map { rs.getString(it) } }
        }
    }

    @Test
    fun realJar_analyzedAndStored_jarDeleted(): Unit = runBlocking {
        val store = open()
        val versionId = seedContent(store, "1").versionIds.getValue("1")
        val chain = chain(store)
        val sha1 = FakeHttp.hex(DigestAlgo.SHA1, jarBytes)

        chain.pipeline.analyze(
            AnalysisTarget(versionId, storedAnalyzerVersion = null, url = jarUrl, size = jarBytes.size.toLong(), knownSha256 = null, expected = mapOf(DigestAlgo.SHA1 to sha1)),
        )

        assertEquals(1L, chain.report.count("analysis.recorded"), "보고: ${chain.report.build(SourceStatus.OK)}")
        assertEquals(1, chain.http.requests.count { it.method == "DOWNLOAD" })

        val row = analysisRow(versionId)
        // 유효 분석기 버전 (D53) = ANALYZER_VERSION + 규칙 지문
        assertTrue(chain.effective.startsWith("$ANALYZER_VERSION+rules."), chain.effective)
        assertEquals(chain.effective, row.analyzerVersion)
        assertEquals(17, row.javaMajor)
        assertEquals("1.20", row.apiVersion)
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, jarBytes), row.sha256)
        assertEquals(jarBytes.size.toLong(), row.size)

        // PROVIDES permission_provider 정확히 1행 (plugin.yml 에 depend 가 없으므로 다른 의존성 행도 없다)
        assertEquals(listOf(listOf("PROVIDES", null, "permission_provider", "*")), depRows(versionId))

        // analysis jsonb: pack format 은 문자열 텍스트 (불변식 3), CANDIDATE 근거 없음 (D33)
        val analysis = checkNotNull(row.analysis) { "analysis jsonb 가 비었다" }
        assertEquals(json("""{"min":"16","max":"34.2147483647"}"""), json(checkNotNull(row.supportedFormats) { "packMeta.supportedFormats 없음: $analysis" }))
        assertFalse(analysis.contains("CANDIDATE"), analysis)
        assertTrue(analysis.contains("permission_provider"), analysis)

        // 분석한 jar 는 즉시 폐기
        assertEquals(0, countFiles(tempDir))
    }

    @Test
    fun secondPass_upToDateNoRequest_andSha256DedupeCopiesProvides(): Unit = runBlocking {
        val store = open()
        val seeded = seedContent(store, "1", "1-copy")
        val ids = seeded.versionIds
        val chain = chain(store)
        val sha256 = FakeHttp.hex(DigestAlgo.SHA256, jarBytes)
        fun target(id: Long, stored: String?, knownSha256: String?) =
            AnalysisTarget(id, stored, jarUrl, jarBytes.size.toLong(), knownSha256, mapOf(DigestAlgo.SHA256 to sha256))

        chain.pipeline.analyze(target(ids.getValue("1"), stored = null, knownSha256 = sha256))
        assertEquals(1, chain.http.requests.size)

        // 같은 유효 버전으로 이미 분석됨 → 요청 없음
        val stored = store.contentVersions(seeded.contentId).single { it.version == "1" }
        assertEquals(chain.effective, stored.analyzerVersion)
        assertEquals(sha256, stored.sha256)
        chain.pipeline.analyze(target(stored.id, stored.analyzerVersion, knownSha256 = sha256))
        assertEquals(1L, chain.report.count("analysis.upToDate"))

        // 같은 jar(sha256) 의 다른 버전 → PG 에서 찾아 복사, 다운로드 없음
        chain.pipeline.analyze(target(ids.getValue("1-copy"), stored = null, knownSha256 = sha256.uppercase()))
        assertEquals(1L, chain.report.count("analysis.dedupedBySha256"))
        assertEquals(1, chain.http.requests.size)
        val copy = analysisRow(ids.getValue("1-copy"))
        assertEquals(chain.effective, copy.analyzerVersion)
        assertEquals(17, copy.javaMajor)
        assertEquals(listOf(listOf("PROVIDES", null, "permission_provider", "*")), depRows(ids.getValue("1-copy")))
        assertEquals(0, countFiles(tempDir))
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private companion object {
        const val SERVICES_MANAGER = "org/bukkit/plugin/ServicesManager"
        const val SERVICE_PRIORITY = "org/bukkit/plugin/ServicePriority"
        const val VAULT_PERMISSION = "net/milkbowl/vault/permission/Permission"

        /**
         * 통합 테스트용 플러그인 jar 바이트 (메모리):
         * - `plugin.yml` (`name: ITest`, `api-version: '1.20'`)
         * - `it/Main` (V17): `onEnable` 이 `ServicesManager.register(Permission, new Perm(this), this, ServicePriority.Normal)`
         * - `it/Perm` (V17) `extends net/milkbowl/vault/permission/Permission`
         * - 루트 `pack.mcmeta`: `supported_formats: [16, 34]`
         */
        fun pluginJarBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("plugin.yml", "name: ITest\nversion: 1\nmain: it.Main\napi-version: '1.20'\n".encodeToByteArray())
                entry("it/Main.class", mainClass())
                entry("it/Perm.class", permClass())
                entry("pack.mcmeta", """{"pack":{"description":"ITest","supported_formats":[16,34]}}""".encodeToByteArray())
            }
            return out.toByteArray()
        }

        private fun mainClass(): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "it/Main", null, "org/bukkit/plugin/java/JavaPlugin", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "it/Main", "getServer", "()Lorg/bukkit/Server;", false)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/bukkit/Server", "getServicesManager", "()L$SERVICES_MANAGER;", true)
                visitLdcInsn(Type.getObjectType(VAULT_PERMISSION))
                visitTypeInsn(Opcodes.NEW, "it/Perm")
                visitInsn(Opcodes.DUP)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "it/Perm", "<init>", "(Lorg/bukkit/plugin/Plugin;)V", false)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETSTATIC, SERVICE_PRIORITY, "Normal", "L$SERVICE_PRIORITY;")
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    SERVICES_MANAGER,
                    "register",
                    "(Ljava/lang/Class;Ljava/lang/Object;Lorg/bukkit/plugin/Plugin;L$SERVICE_PRIORITY;)V",
                    true,
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        private fun permClass(): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "it/Perm", null, VAULT_PERMISSION, null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lorg/bukkit/plugin/Plugin;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, VAULT_PERMISSION, "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }
    }
}
