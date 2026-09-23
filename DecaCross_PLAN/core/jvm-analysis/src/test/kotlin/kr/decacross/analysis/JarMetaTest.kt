package kr.decacross.analysis

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarMetaTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
    }

    @AfterTest
    fun tearDown() = deleteTree(dir)

    @Test
    fun `plugin yml with lists single strings and name normalization`() {
        val yml = """
            name: My Plugin
            version: "2.1.0"
            main: com.example.MyPlugin
            api-version: 1.21
            description: does things
            author: Alice
            authors: [Bob, Carol]
            depend: [Vault, WorldEdit]
            softdepend: PlaceholderAPI
            loadbefore:
              - Essentials
            libraries:
              - com.google.code.gson:gson:2.11.0
            provides: [MyPluginAPI]
        """.trimIndent()
        val jar = JarBuilder().text("plugin.yml", yml).write(dir.resolve("p.jar"))
        val m = assertNotNull(readJarMeta(jar))
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, m.descriptor)
        assertEquals("My_Plugin", m.name)
        assertEquals("2.1.0", m.version)
        assertEquals("1.21", m.apiVersion)
        assertEquals("com.example.MyPlugin", m.main)
        assertEquals(listOf("Vault", "WorldEdit"), m.depend)
        assertEquals(listOf("PlaceholderAPI"), m.softDepend)
        assertEquals(listOf("Essentials"), m.loadBefore)
        assertEquals(listOf("com.google.code.gson:gson:2.11.0"), m.libraries)
        assertEquals(listOf("MyPluginAPI"), m.provides)
        assertEquals(listOf("Alice", "Bob", "Carol"), m.authors)
        assertEquals("does things", m.description)
    }

    @Test
    fun `paper plugin yml wins over plugin yml and maps dependencies`() {
        val paper = """
            name: PaperThing
            version: 1.0
            main: com.example.PaperThing
            api-version: '1.21'
            dependencies:
              server:
                Vault:
                  required: false
                  load: BEFORE
                WorldGuard:
                  load: AFTER
                LuckPerms: {}
              bootstrap:
                Boot:
                  required: true
        """.trimIndent()
        val jar = JarBuilder().text("plugin.yml", pluginYml("Legacy", "com.example.Legacy")).text("paper-plugin.yml", paper).write(dir.resolve("pp.jar"))
        val m = assertNotNull(readJarMeta(jar))
        assertEquals(JarMeta.Descriptor.PAPER_PLUGIN_YML, m.descriptor)
        assertEquals("PaperThing", m.name)
        assertEquals(listOf("WorldGuard", "LuckPerms", "Boot"), m.depend)
        assertEquals(listOf("Vault"), m.softDepend)
        assertEquals(listOf("WorldGuard"), m.loadBefore)
    }

    @Test
    fun `fabric mod json`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "examplemod",
              "version": "1.2.3",
              "description": "An example",
              "authors": ["Me", {"name": "You"}],
              "entrypoints": {"main": ["com.example.ExampleMod"]},
              "depends": {"fabricloader": ">=0.16.0", "minecraft": ["1.21.x", "~1.21"], "fabric-api": "*"},
              "recommends": {"modmenu": "*"},
              "breaks": {"optifabric": "*"},
              "provides": ["example-api"]
            }
        """.trimIndent()
        val jar = JarBuilder().text("fabric.mod.json", json).write(dir.resolve("f.jar"))
        val m = assertNotNull(readJarMeta(jar))
        assertEquals(JarMeta.Descriptor.FABRIC_MOD_JSON, m.descriptor)
        assertEquals("examplemod", m.name)
        assertEquals("1.2.3", m.version)
        assertEquals("com.example.ExampleMod", m.main)
        assertEquals(listOf("fabricloader", "minecraft", "fabric-api"), m.depend)
        assertEquals("1.21.x || ~1.21", m.dependRanges["minecraft"])
        assertEquals(">=0.16.0", m.dependRanges["fabricloader"])
        assertEquals(listOf("modmenu"), m.softDepend)
        assertEquals(listOf("optifabric"), m.breaks)
        assertEquals(listOf("Me", "You"), m.authors)
        assertEquals(listOf("example-api"), m.provides)
    }

    @Test
    fun `mods toml minimal`() {
        val toml = """
            modLoader="javafml" #mandatory
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="examplemod"
            version="1.0.0"
            displayName="Example"
            description='''
            multi
            '''
            [[dependencies.examplemod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            [[dependencies.examplemod]]
            modId="jei"
            mandatory=false
        """.trimIndent()
        val jar = JarBuilder().text("META-INF/mods.toml", toml).write(dir.resolve("m.jar"))
        val m = assertNotNull(readJarMeta(jar))
        assertEquals(JarMeta.Descriptor.MODS_TOML, m.descriptor)
        assertEquals("examplemod", m.name)
        assertEquals("1.0.0", m.version)
        assertEquals(listOf("forge"), m.depend)
        assertEquals(listOf("jei"), m.softDepend)
        assertEquals("[47,)", m.dependRanges["forge"])
        assertTrue(m.notes.any { "description" in it }, "multiline value should be noted: ${m.notes}")
    }

    @Test
    fun `no descriptor or unsafe yaml gives null`() {
        val none = JarBuilder().clazz(classBytes("a/B")).write(dir.resolve("none.jar"))
        assertNull(readJarMeta(none))
        // SafeConstructor: 임의 객체 태그는 예외 → null
        val evil = JarBuilder().text("plugin.yml", "name: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader []]").write(dir.resolve("evil.jar"))
        assertNull(readJarMeta(evil))
        val noName = JarBuilder().text("plugin.yml", "main: a.B").write(dir.resolve("noname.jar"))
        assertNull(readJarMeta(noName))
    }
}
