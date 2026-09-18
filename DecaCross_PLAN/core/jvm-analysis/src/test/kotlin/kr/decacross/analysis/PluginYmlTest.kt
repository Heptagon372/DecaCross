package kr.decacross.analysis

import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.pluginYml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** plugin.yml — Bukkit `PluginDescriptionFile` 시맨틱 (설계 §7.3 표). */
class PluginYmlTest {
    private val base = "name: A\nversion: 1\nmain: a.A\n"

    private fun parsed(text: String): JarMeta = when (val result = parsePluginYml(text)) {
        is DescriptorParse.Parsed -> result.meta
        is DescriptorParse.Invalid -> fail("Invalid 가 아니어야 함: ${result.problem}")
    }

    private fun invalid(text: String): InvalidDescriptor = when (val result = parsePluginYml(text)) {
        is DescriptorParse.Parsed -> fail("Invalid 여야 함: ${result.meta}")
        is DescriptorParse.Invalid -> result.problem
    }

    private fun JarMeta.hasLoadError(fragment: String): Boolean = loadErrors.any { it.contains(fragment) }

    @Test
    fun py01_minimal_defaults() {
        val meta = parsed(base)
        assertEquals("A", meta.name)
        assertEquals("1", meta.version)
        assertEquals("a.A", meta.main)
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, meta.descriptor)
        assertTrue(meta.deps.isEmpty())
        assertEquals("POSTWORLD", meta.load)
        assertTrue(meta.warnings.any { it.contains("api-version 미지정") }, meta.warnings.toString())
        assertTrue(meta.loadErrors.isEmpty(), meta.loadErrors.toString())
        assertFalse(meta.foliaSupported)
    }

    @Test
    fun py02_dependOrder() {
        val meta = parsed(base + "depend: [Vault, WorldEdit]\n")
        assertEquals(listOf("Vault", "WorldEdit"), meta.depend)
        assertEquals(
            listOf(MetaDep("Vault", MetaDepKind.REQUIRED), MetaDep("WorldEdit", MetaDepKind.REQUIRED)),
            meta.deps,
        )
    }

    @Test
    fun py03_dependScalar_wrongType() {
        val meta = parsed(base + "depend: Vault\n")
        assertTrue(meta.hasLoadError("depend is of wrong type"), meta.loadErrors.toString())
        assertTrue(meta.depend.isEmpty())
    }

    @Test
    fun py04_dependNullElement() {
        val meta = parsed(base + "depend: [Vault, ~]\n")
        assertTrue(meta.hasLoadError("invalid depend format"), meta.loadErrors.toString())
        assertEquals(listOf("Vault"), meta.depend)
    }

    @Test
    fun py05_dependNullOrEmpty() {
        for (line in listOf("depend:\n", "depend: []\n")) {
            val meta = parsed(base + line)
            assertTrue(meta.depend.isEmpty(), line)
            assertTrue(meta.loadErrors.isEmpty(), "$line → ${meta.loadErrors}")
        }
    }

    @Test
    fun py06_softdependSpaces() {
        val meta = parsed(base + "softdepend: [My Plugin]\n")
        assertEquals(listOf("My_Plugin"), meta.softDepend)
        assertEquals(listOf(MetaDep("My_Plugin", MetaDepKind.OPTIONAL)), meta.deps)
    }

    @Test
    fun py07_nameWithSpace() {
        val meta = parsed("name: My Plugin\nversion: 1\nmain: a.A\n")
        assertEquals("My_Plugin", meta.name)
        assertEquals("My Plugin", meta.rawName)
        assertTrue(meta.hasLoadError("Restricted name"), meta.loadErrors.toString())
    }

    @Test
    fun py08_reservedNames() {
        for (name in listOf("paper", "Bukkit")) {
            val meta = parsed("name: $name\nversion: 1\nmain: a.A\n")
            assertTrue(meta.hasLoadError("Restricted name"), "$name → ${meta.loadErrors}")
        }
    }

    @Test
    fun py09_invalidChars() {
        val meta = parsed("name: Foo$\nversion: 1\nmain: a.A\n")
        assertTrue(meta.hasLoadError("contains invalid characters"), meta.loadErrors.toString())
    }

    @Test
    fun py10_numericName() {
        val meta = parsed("name: 123\nversion: 1\nmain: a.A\n")
        assertEquals("123", meta.name)
        assertTrue(meta.loadErrors.isEmpty(), meta.loadErrors.toString())
    }

    @Test
    fun py11_versionScalars() {
        val cases = listOf("1.0" to "1.0", "010" to "8", "1_000" to "1000", "1:30" to "90", "yes" to "true")
        for ((literal, expected) in cases) {
            val meta = parsed("name: A\nversion: $literal\nmain: a.A\n")
            assertEquals(expected, meta.version, "version: $literal")
            assertEquals(literal, meta.rawVersion, "rawVersion: $literal")
        }
    }

    @Test
    fun py11b_versionDate_usesRawText() {
        val meta = parsed("name: A\nversion: 2024-01-01\nmain: a.A\n")
        assertEquals("2024-01-01", meta.version)
        assertEquals("2024-01-01", meta.rawVersion)
        assertTrue(meta.warnings.any { it.contains("날짜") }, meta.warnings.toString())
    }

    @Test
    fun py12_versionMissingOrNull() {
        for (text in listOf("name: A\nmain: a.A\n", "name: A\nversion: ~\nmain: a.A\n", "name: A\nversion:\nmain: a.A\n")) {
            val meta = parsed(text)
            assertTrue(meta.hasLoadError("version is not defined"), "$text → ${meta.loadErrors}")
            assertNull(meta.version)
            assertNull(meta.rawVersion)
        }
    }

    @Test
    fun py13_mainInBukkitNamespace() {
        val meta = parsed("name: A\nversion: 1\nmain: org.bukkit.X\n")
        assertTrue(meta.hasLoadError("org.bukkit namespace"), meta.loadErrors.toString())
        assertTrue(parsed("name: A\nversion: 1\n").hasLoadError("main is not defined"))
    }

    @Test
    fun py14_apiVersion() {
        val ok = listOf("1.20" to "1.20", "'1.20.6'" to "1.20.6", "26.1" to "26.1")
        for ((literal, expected) in ok) {
            val meta = parsed(base + "api-version: $literal\n")
            assertEquals(expected, meta.apiVersion, literal)
            assertTrue(meta.loadErrors.isEmpty(), "$literal → ${meta.loadErrors}")
            assertFalse(meta.warnings.any { it.contains("api-version") }, literal)
        }
        val bare = parsed(base + "api-version: 26\n")
        assertEquals("26", bare.apiVersion)
        assertTrue(bare.hasLoadError("api-version '26' is not a valid version"), bare.loadErrors.toString())

        val none = parsed(base + "api-version: none\n")
        assertTrue(none.loadErrors.isEmpty())
        assertTrue(none.warnings.any { it.contains("api-version 미지정") })

        val absent = parsed(base)
        assertNull(absent.apiVersion)
        assertTrue(absent.warnings.any { it.contains("api-version 미지정") })
    }

    @Test
    fun py15_load() {
        assertEquals("POSTWORLD", parsed(base + "load: post-world\n").load)
        assertEquals("STARTUP", parsed(base + "load: startup\n").load)
        assertTrue(parsed(base + "load: later\n").hasLoadError("load is not a valid choice"))
        assertTrue(parsed(base + "load: 1\n").hasLoadError("load is of wrong type"))
    }

    @Test
    fun py16_provides() {
        assertEquals(listOf("Old_Name"), parsed(base + "provides: [Old Name]\n").provides)
        assertTrue(parsed(base + "provides: Old\n").hasLoadError("provides is of wrong type"))
    }

    @Test
    fun py17_libraries() {
        val valid = parsed(base + "libraries: ['com.google.guava:guava:33.0.0-jre']\n")
        assertEquals(listOf("com.google.guava:guava:33.0.0-jre"), valid.libraries)
        assertTrue(valid.loadErrors.isEmpty(), valid.loadErrors.toString())

        assertTrue(parsed(base + "libraries: com.google.guava:guava:33.0.0-jre\n").hasLoadError("libraries are of wrong type"))
        assertTrue(parsed(base + "libraries: [foo]\n").hasLoadError("not a valid maven coordinate"))
    }

    @Test
    fun py18_skipLibraries() {
        val meta = parsed(base + "libraries: ['a:b:1']\npaper-skip-libraries: TRUE\n")
        assertTrue(meta.skipLibrariesOnPaper)
        assertEquals(listOf("a:b:1"), meta.libraries)
    }

    @Test
    fun py19_foliaSupported() {
        assertTrue(parsed(base + "folia-supported: yes\n").foliaSupported)
        assertTrue(parsed(base + "folia-supported: \"True\"\n").foliaSupported)
        assertFalse(parsed(base).foliaSupported)
    }

    @Test
    fun py20_duplicateKey_lastWins() {
        val meta = parsed(base + "depend: [A]\ndepend: [B]\n")
        assertEquals(listOf("B"), meta.depend)
    }

    @Test
    fun py21_unknownTag() {
        val problem = invalid(base + "x: !!foo bar\n")
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, problem.descriptor)
    }

    @Test
    fun py22_emptyOrListRoot() {
        for (text in listOf("", "- a\n")) {
            val problem = invalid(text)
            assertTrue(problem.reason.startsWith("Plugin description file is empty or not properly structured"), problem.reason)
        }
        assertEquals("name is not defined", invalid("version: 1\nmain: a.A\n").reason)
    }

    @Test
    fun py23_loadbeforeNotDep() {
        val meta = parsed(base + "loadbefore: [Vault]\n")
        assertEquals(listOf("Vault"), meta.loadBefore)
        assertTrue(meta.deps.isEmpty())
    }

    @Test
    fun py24_loadbeforeAndDepend() {
        val meta = parsed(base + "depend: [X]\nloadbefore: [X]\n")
        assertEquals(listOf(MetaDep("X", MetaDepKind.REQUIRED)), meta.deps)
        assertTrue(meta.warnings.any { it.contains("loadbefore 와 depend/softdepend 동시 선언") && it.contains("X") }, meta.warnings.toString())
    }

    @Test
    fun py25_bom() {
        val meta = parsed("\uFEFF" + base)
        assertEquals("A", meta.name)
    }

    @Test
    fun py25b_bomInJar_isStripped() {
        val jar = jar(pluginYml("\uFEFF" + base))
        assertEquals("A", readJarMeta(jar)?.name)
    }

    @Test
    fun py26_awarenessTag() {
        // 설계 표의 `[!@UTF8]` 는 SnakeYAML 에서 태그 URI 가 `]` 까지 삼켜 문법 오류다 (Bukkit 도 같다).
        // Bukkit 문서의 블록 형식과, 흐름 형식이면 `]` 앞 공백을 둔 형식으로 검증한다.
        for (text in listOf("awareness:\n  - !@UTF8\n", "awareness: [!@UTF8 ]\n")) {
            val meta = parsed(base + text)
            assertTrue(meta.loadErrors.isEmpty(), "$text → ${meta.loadErrors}")
        }
        // `!@` 가 아닌 미지 태그는 여전히 실패
        assertTrue(parsePluginYml(base + "awareness:\n  - !UTF8\n") is DescriptorParse.Invalid)
    }

    @Test
    fun py27_setTagAccepted() {
        val meta = parsed(base + "depend: !!set {Vault: null}\n")
        assertEquals(listOf("Vault"), meta.depend)
        assertTrue(meta.loadErrors.isEmpty(), meta.loadErrors.toString())
    }

    @Test
    fun py28_duplicatesKept_depsDeduped() {
        val meta = parsed(base + "depend: [A, A]\nsoftdepend: [A]\n")
        assertEquals(listOf("A", "A"), meta.depend)
        assertEquals(listOf("A"), meta.softDepend)
        assertEquals(listOf(MetaDep("A", MetaDepKind.REQUIRED)), meta.deps)
    }
}
