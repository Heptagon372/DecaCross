package kr.decacross.analysis

import kr.decacross.analysis.testutil.RegisterSpec
import kr.decacross.analysis.testutil.VAULT_PERMISSION
import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.implClass
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.method
import kr.decacross.analysis.testutil.pluginYml
import kr.decacross.analysis.testutil.registerCallClass
import kr.decacross.analysis.testutil.tempFile
import kr.decacross.analysis.testutil.withVersion
import kr.decacross.compat.model.Capability
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** `analyzeJar` 공개 경계 — 예외 없음, 파일 핸들 반환, 성능 (설계 §7.7, §7.9). */
class AnalyzeJarTest {
    private val mainName = "com/example/plugin/Main"
    private val permsImpl = "com/example/plugin/PermsImpl"

    private fun analysisOf(jar: Path, rules: CapabilityRules = CapabilityRules()): JarAnalysis =
        assertIs<JarAnalysisResult.Ok>(analyzeJar(jar, rules)).analysis

    @Test
    fun fullPluginJar() {
        val jar = jar(
            pluginYml("name: PermsPlugin\nversion: 2.0\nmain: com.example.plugin.Main\napi-version: '1.20'\ndepend: [Vault]\n"),
            "$mainName.class" to withVersion(registerCallClass(RegisterSpec(VAULT_PERMISSION, permsImpl)), major = 61),
            "$permsImpl.class" to withVersion(implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)), major = 61),
        )
        val analysis = analysisOf(jar)
        assertEquals("PermsPlugin", analysis.descriptors.primary?.name)
        assertEquals(listOf(MetaDep("Vault", MetaDepKind.REQUIRED)), analysis.descriptors.primary?.deps)
        assertEquals(17, analysis.bytecode.requiredJavaFeature)
        val permission = analysis.capabilities.single { it.capability == Capability.PermissionProvider }
        assertEquals(EvidenceConfidence.STORE, permission.confidence)
        assertNull(analysis.packMeta)
        assertTrue(analysis.notes.isEmpty(), analysis.notes.toString())
    }

    @Test
    fun notAZip_unreadable() {
        val notZip = tempFile("definitely not a zip".toByteArray())
        assertIs<JarAnalysisResult.Unreadable>(analyzeJar(notZip, CapabilityRules()))
        val empty = tempFile(ByteArray(0))
        assertIs<JarAnalysisResult.Unreadable>(analyzeJar(empty, CapabilityRules()))
        val missing = notZip.resolveSibling("decacross-missing-${System.nanoTime()}.jar")
        assertIs<JarAnalysisResult.Unreadable>(analyzeJar(missing, CapabilityRules()))
    }

    @Test
    fun fileHandleReleased_canDeleteImmediately() {
        val jar = jar(pluginYml("name: A\nversion: 1\nmain: a.A\n"), "a/A.class" to classBytes("a/A"))
        assertIs<JarAnalysisResult.Ok>(analyzeJar(jar, CapabilityRules()))
        Files.delete(jar)
        assertFalse(Files.exists(jar))

        val notZip = tempFile("not a zip".toByteArray())
        assertIs<JarAnalysisResult.Unreadable>(analyzeJar(notZip, CapabilityRules()))
        Files.delete(notZip)
        assertFalse(Files.exists(notZip))
    }

    @Test
    fun packMcmeta_andDescriptorProblems_reportedWithoutThrowing() {
        val jar = jar(
            "pack.mcmeta" to """{"pack": {"pack_format": 34}}""".toByteArray(),
            "paper-plugin.yml" to "[: broken".toByteArray(),
        )
        val analysis = analysisOf(jar)
        assertIs<PackMcmetaResult.Ok>(analysis.packMeta)
        assertNull(analysis.descriptors.primary)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, analysis.descriptors.invalid.single().descriptor)
    }

    @Test
    fun asmUnsupportedMajor_candidateNoted_notThrown() {
        // 헤더는 읽히지만 ASM 이 모르는 major 인 ServicesManager 호출 후보 → note 후 건너뛴다
        val tooNew = withVersion(registerCallClass(RegisterSpec(VAULT_PERMISSION, permsImpl)), major = 255)
        val analysis = analysisOf(
            jar(pluginYml("name: A\nversion: 1\nmain: a.A\n"), "$mainName.class" to tooNew),
        )
        assertTrue(analysis.capabilities.isEmpty(), analysis.capabilities.toString())
        assertTrue(analysis.notes.any { it.contains("ASM") }, analysis.notes.toString())
        assertEquals(211, analysis.bytecode.requiredJavaFeature)
    }

    @Test
    fun perf_2000Classes_under3s() {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += pluginYml("name: Big\nversion: 1\nmain: big.Main\n")
        repeat(2000) { i ->
            val name = "big/pkg${i % 40}/C$i"
            entries += "$name.class" to classBytes(name, interfaces = listOf("java/lang/Runnable")) {
                for (m in 0 until 8) {
                    method("m$m", "(Ljava/lang/String;I)Ljava/lang/String;") {
                        visitVarInsn(Opcodes.ALOAD, 1)
                        visitLdcInsn("constant-$i-$m")
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                        visitInsn(Opcodes.ARETURN)
                    }
                }
            }
        }
        val jar = jar(*entries.toTypedArray())
        // 워밍업 1회 후 측정 (공유 머신이라 기준은 넉넉하게)
        analysisOf(jar)
        val (analysis, elapsed) = measureTimedValue { analysisOf(jar) }
        assertEquals(8, analysis.bytecode.requiredJavaFeature)
        assertEquals(2000, analysis.bytecode.majorHistogram[52])
        assertTrue(elapsed.inWholeMilliseconds < 3000, "analyzeJar 2000 classes: $elapsed")
    }
}
