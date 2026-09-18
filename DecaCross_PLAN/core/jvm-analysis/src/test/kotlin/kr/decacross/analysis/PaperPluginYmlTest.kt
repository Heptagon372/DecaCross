package kr.decacross.analysis

import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.pluginYml
import kr.decacross.analysis.testutil.textEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** paper-plugin.yml — Configurate 시맨틱 (설계 §7.3). */
class PaperPluginYmlTest {
    private val base = "name: A\nversion: '1.0'\nmain: a.A\napi-version: '1.20'\n"

    private fun parsed(text: String): JarMeta = when (val result = parsePaperPluginYml(text)) {
        is DescriptorParse.Parsed -> result.meta
        is DescriptorParse.Invalid -> fail("Invalid 가 아니어야 함: ${result.problem}")
    }

    private fun JarMeta.dep(target: String): MetaDep = deps.firstOrNull { it.target == target } ?: fail("$target 의존성 없음: $deps")

    private fun JarMeta.hasLoadError(fragment: String): Boolean = loadErrors.any { it.contains(fragment) }

    @Test
    fun pp00_minimal_noErrors() {
        val meta = parsed(base)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, meta.descriptor)
        assertEquals("A", meta.name)
        assertEquals("1.20", meta.apiVersion)
        assertEquals("POSTWORLD", meta.load)
        assertTrue(meta.loadErrors.isEmpty(), meta.loadErrors.toString())
        assertTrue(meta.warnings.isEmpty(), meta.warnings.toString())
        assertTrue(meta.libraries.isEmpty())
    }

    @Test
    fun pp01_bothDescriptors_primaryIsPaper() {
        val jar = jar(
            textEntry("paper-plugin.yml", base),
            pluginYml("name: A\nversion: 1\nmain: a.A\n"),
        )
        val descriptors = readJarDescriptors(jar)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, descriptors.all[0].descriptor)
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, descriptors.all[1].descriptor)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, descriptors.primary?.descriptor)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, readJarMeta(jar)?.descriptor)
    }

    @Test
    fun pp02_serverDepDefaults() {
        val meta = parsed(base + "dependencies:\n  server:\n    Vault: {}\n    WorldEdit:\n")
        for (target in listOf("Vault", "WorldEdit")) {
            val dep = meta.dep(target)
            assertEquals(MetaDepKind.REQUIRED, dep.kind, target)
            assertEquals(LoadOrder.NONE, dep.order, target)
            assertEquals(DepPhase.SERVER, dep.phase, target)
        }
        assertEquals(listOf("Vault", "WorldEdit"), meta.depend)
        assertTrue(meta.loadBefore.isEmpty() && meta.loadAfter.isEmpty())
    }

    @Test
    fun pp03_optionalAfter() {
        val meta = parsed(base + "dependencies:\n  server:\n    X:\n      required: false\n      load: AFTER\n")
        val dep = meta.dep("X")
        assertEquals(MetaDepKind.OPTIONAL, dep.kind)
        assertEquals(LoadOrder.AFTER, dep.order)
        assertTrue("X" in meta.loadBefore, meta.loadBefore.toString())
        assertEquals(listOf("X"), meta.softDepend)
    }

    @Test
    fun pp03b_serverBefore_inLoadAfter() {
        val meta = parsed(base + "dependencies:\n  server:\n    X:\n      load: BEFORE\n")
        assertEquals(LoadOrder.BEFORE, meta.dep("X").order)
        assertEquals(listOf("X"), meta.loadAfter)
    }

    @Test
    fun pp04_bootstrapRequired_serverOptional() {
        val meta = parsed(
            base + "dependencies:\n  bootstrap:\n    X:\n      required: true\n  server:\n    X:\n      required: false\n",
        )
        val dep = meta.dep("X")
        assertEquals(MetaDepKind.REQUIRED, dep.kind)
        assertEquals(DepPhase.BOOTSTRAP, dep.phase)
        assertEquals(1, meta.deps.size)
    }

    @Test
    fun pp05_apiVersionFloat() {
        val meta = parsed("name: A\nversion: '1'\nmain: a.A\napi-version: 1.20\n")
        assertEquals("1.2", meta.apiVersion)
        assertTrue(meta.hasLoadError("too old"), meta.loadErrors.toString())
        assertTrue(meta.warnings.any { it.contains("float") }, meta.warnings.toString())
    }

    @Test
    fun pp06_apiVersionTooOld_orMissing() {
        val old = parsed("name: A\nversion: '1'\nmain: a.A\napi-version: '1.18'\n")
        assertTrue(old.hasLoadError("too old"), old.loadErrors.toString())
        val missing = parsed("name: A\nversion: '1'\nmain: a.A\n")
        assertNull(missing.apiVersion)
        assertTrue(missing.hasLoadError("api-version is required"), missing.loadErrors.toString())
    }

    @Test
    fun pp07_apiVersion26_10() {
        val meta = parsed("name: A\nversion: '1'\nmain: a.A\napi-version: 26.10\n")
        assertEquals("26.1", meta.apiVersion)
        assertTrue(meta.warnings.any { it.contains("float") }, meta.warnings.toString())
        assertTrue(meta.loadErrors.isEmpty(), meta.loadErrors.toString())
    }

    @Test
    fun pp08_legacyForm() {
        val meta = parsed(
            base +
                "dependencies:\n  - name: X\n    required: true\n" +
                "load-before:\n  - name: Y\n",
        )
        assertEquals(MetaDepKind.REQUIRED, meta.dep("X").kind)
        assertEquals(LoadOrder.AFTER, meta.dep("Y").order)
        assertEquals(MetaDepKind.OPTIONAL, meta.dep("Y").kind)
        assertEquals(listOf("Y"), meta.loadBefore)
        assertTrue(meta.warnings.any { it.contains("레거시 paper-plugin.yml 형식") }, meta.warnings.toString())
    }

    @Test
    fun pp08b_legacyLoadAfter_orderBefore() {
        val meta = parsed(base + "load-after:\n  - name: Z\n    bootstrap: false\n")
        assertEquals(LoadOrder.BEFORE, meta.dep("Z").order)
        assertEquals(listOf("Z"), meta.loadAfter)
    }

    @Test
    fun pp09_restrictedMain() {
        val meta = parsed("name: A\nversion: '1'\nmain: io.papermc.paper.Foo\napi-version: '1.20'\n")
        assertTrue(meta.hasLoadError("main uses a restricted namespace"), meta.loadErrors.toString())
        val loader = parsed(base + "loader: net.minecraft.Loader\n")
        assertTrue(loader.hasLoadError("loader uses a restricted namespace"), loader.loadErrors.toString())
    }

    @Test
    fun pp10_foliaAndProvides() {
        val meta = parsed(base + "folia-supported: true\nprovides: [OldName, Other]\nload: STARTUP\n")
        assertTrue(meta.foliaSupported)
        assertEquals(listOf("OldName", "Other"), meta.provides)
        assertEquals("STARTUP", meta.load)
        assertTrue(parsed(base + "folia-supported: 'true'\n").foliaSupported)
    }

    @Test
    fun pp11_invalidPaperYml_validPluginYml_primaryNull() {
        val jar = jar(
            textEntry("paper-plugin.yml", "- not\n- a map\n"),
            pluginYml("name: A\nversion: 1\nmain: a.A\n"),
        )
        val descriptors = readJarDescriptors(jar)
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, descriptors.invalid[0].descriptor)
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, descriptors.all[0].descriptor)
        assertNull(descriptors.primary)
        assertNull(readJarMeta(jar))
    }

    @Test
    fun pp12_bootstrapOnlyOrder_notInViews() {
        val meta = parsed(base + "dependencies:\n  bootstrap:\n    X:\n      load: BEFORE\n")
        val dep = meta.dep("X")
        assertTrue(meta.loadAfter.isEmpty(), meta.loadAfter.toString())
        assertEquals(LoadOrder.NONE, dep.order)
        assertEquals(DepPhase.BOOTSTRAP, dep.phase)
    }

    @Test
    fun pp13_legacyRequiredDefaultsFalse() {
        val meta = parsed(base + "dependencies:\n  - name: X\n")
        assertEquals(MetaDepKind.OPTIONAL, meta.dep("X").kind)
    }

    @Test
    fun pp14_requiredKeysMissing() {
        val meta = parsed("api-version: '1.20'\n")
        for (key in listOf("name", "main", "version")) {
            assertTrue(meta.hasLoadError("$key is required"), "$key → ${meta.loadErrors}")
        }
    }
}
