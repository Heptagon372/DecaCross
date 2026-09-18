package kr.decacross.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** fabric.mod.json v1 (설계 §7.3). */
class FabricModJsonTest {
    private fun parsed(text: String): JarMeta = when (val result = parseFabricModJson(text)) {
        is DescriptorParse.Parsed -> result.meta
        is DescriptorParse.Invalid -> fail("Invalid 가 아니어야 함: ${result.problem}")
    }

    private fun assertInvalid(text: String): InvalidDescriptor = when (val result = parseFabricModJson(text)) {
        is DescriptorParse.Parsed -> fail("Invalid 여야 함: ${result.meta}")
        is DescriptorParse.Invalid -> result.problem.also { assertEquals(JarMeta.Descriptor.FABRIC_MOD_JSON, it.descriptor) }
    }

    private fun mod(extra: String = ""): String = """{"schemaVersion": 1, "id": "examplemod", "version": "1.0.0"$extra}"""

    @Test
    fun fb00_viewFields() {
        val meta = parsed(mod())
        assertEquals("examplemod", meta.name)
        assertEquals("examplemod", meta.rawName)
        assertEquals("1.0.0", meta.version)
        assertNull(meta.apiVersion)
        assertNull(meta.main)
        assertEquals(JarMeta.Descriptor.FABRIC_MOD_JSON, meta.descriptor)
        assertTrue(meta.loadErrors.isEmpty())
    }

    @Test
    fun fb01_depends() {
        val meta = parsed(mod(""", "depends": {"fabricloader": ">=0.15.0", "minecraft": "~1.20.1"}"""))
        assertEquals(
            listOf(
                MetaDep("fabricloader", MetaDepKind.REQUIRED, versionPredicates = listOf(">=0.15.0")),
                MetaDep("minecraft", MetaDepKind.REQUIRED, versionPredicates = listOf("~1.20.1")),
            ),
            meta.deps,
        )
        assertEquals(listOf("fabricloader", "minecraft"), meta.depend)
    }

    @Test
    fun fb02_orArray() {
        val meta = parsed(mod(""", "depends": {"minecraft": ["1.20.x", "1.21.x"]}"""))
        assertEquals(listOf("1.20.x", "1.21.x"), meta.deps.single().versionPredicates)
    }

    @Test
    fun fb03_any() {
        val meta = parsed(mod(""", "depends": {"fabric-api": "*"}"""))
        assertEquals(listOf("*"), meta.deps.single().versionPredicates)
    }

    @Test
    fun fb04_kinds() {
        val meta = parsed(
            mod(""", "recommends": {"a": "*"}, "suggests": {"b": "*"}, "conflicts": {"c": "*"}, "breaks": {"d": "*"}"""),
        )
        assertEquals(
            listOf(MetaDepKind.OPTIONAL, MetaDepKind.OPTIONAL, MetaDepKind.DISCOURAGED, MetaDepKind.INCOMPATIBLE),
            meta.deps.map { it.kind },
        )
        assertEquals(listOf("a", "b"), meta.softDepend)
        assertTrue(meta.depend.isEmpty())
    }

    @Test
    fun fb05_badValue() {
        val problem = assertInvalid(mod(""", "depends": {"x": 5}"""))
        assertEquals("Dependency version range must be a string or string array", problem.reason)
        assertInvalid(mod(""", "depends": {"x": ["1", 2]}"""))
        assertInvalid(mod(""", "depends": ["x"]"""))
    }

    @Test
    fun fb06_noSchemaVersion() {
        val problem = assertInvalid("""{"id": "examplemod", "version": "1.0.0"}""")
        assertEquals("schemaVersion 0 (v0) 미지원", problem.reason)
        assertInvalid("""{"schemaVersion": 2, "id": "examplemod", "version": "1.0.0"}""")
    }

    @Test
    fun fb07_schemaVersionLast() {
        val meta = parsed("""{"id": "examplemod", "version": "1.0.0", "provides": ["example"], "schemaVersion": 1}""")
        assertEquals("examplemod", meta.name)
        assertEquals(listOf("example"), meta.provides)
    }

    @Test
    fun fb08_jars() {
        val meta = parsed(mod(""", "jars": [{"file": "META-INF/jars/lib.jar"}]"""))
        assertEquals(listOf("META-INF/jars/lib.jar"), meta.nestedJars)
        assertInvalid(mod(""", "jars": [{}]"""))
    }

    @Test
    fun fb09_badId() {
        assertInvalid("""{"schemaVersion": 1, "id": "A", "version": "1.0.0"}""")
        assertInvalid("""{"schemaVersion": 1, "id": "examplemod", "version": 1}""")
    }

    @Test
    fun fb10_syntaxOrRootInvalid() {
        assertInvalid("{")
        assertInvalid("[1]")
    }
}
