package kr.decacross.analysis

import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.pluginYml
import kr.decacross.analysis.testutil.tempFile
import kr.decacross.analysis.testutil.textEntry
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `readJarDescriptors` — 루트 descriptor 탐색·크기 상한·TOML 미지원 (설계 §7.3). */
class JarDescriptorsTest {
    @Test
    fun modsToml_reportedInvalidPhase2() {
        val jar = jar(
            textEntry("META-INF/mods.toml", "modLoader=\"javafml\"\n"),
            textEntry("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n"),
        )
        val descriptors = readJarDescriptors(jar)
        assertTrue(descriptors.all.isEmpty())
        assertEquals(
            listOf(
                InvalidDescriptor(JarMeta.Descriptor.NEOFORGE_MODS_TOML, "TOML descriptor 는 Phase 2 (파서 미도입)"),
                InvalidDescriptor(JarMeta.Descriptor.MODS_TOML, "TOML descriptor 는 Phase 2 (파서 미도입)"),
            ),
            descriptors.invalid,
        )
        assertNull(descriptors.primary)
    }

    @Test
    fun noDescriptor_emptyAll() {
        val jar = jar("a/A.class" to classBytes("a/A"))
        val descriptors = readJarDescriptors(jar)
        assertTrue(descriptors.all.isEmpty())
        assertTrue(descriptors.invalid.isEmpty())
        assertNull(descriptors.primary)
        assertNull(readJarMeta(jar))
    }

    @Test
    fun notAZip_throwsIOException() {
        val notZip = tempFile("hello, not a zip".toByteArray())
        assertFailsWith<IOException> { readJarDescriptors(notZip) }
        assertFailsWith<IOException> { readJarMeta(notZip) }
    }

    @Test
    fun oversizedDescriptor_skippedWithInvalid() {
        val big = "name: A\nversion: 1\nmain: a.A\n# " + "x".repeat(DESCRIPTOR_MAX_BYTES) + "\n"
        val jar = jar(pluginYml(big))
        val descriptors = readJarDescriptors(jar)
        assertTrue(descriptors.all.isEmpty())
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, descriptors.invalid.single().descriptor)
        assertNull(descriptors.primary)
    }

    @Test
    fun nestedDescriptor_ignored_onlyRootCounts() {
        val jar = jar(textEntry("sub/plugin.yml", "name: A\nversion: 1\nmain: a.A\n"))
        assertTrue(readJarDescriptors(jar).all.isEmpty())
    }

    @Test
    fun fabricAndPluginYml_priorityOrder() {
        val jar = jar(
            textEntry("fabric.mod.json", """{"schemaVersion": 1, "id": "examplemod", "version": "1"}"""),
            pluginYml("name: A\nversion: 1\nmain: a.A\n"),
        )
        val descriptors = readJarDescriptors(jar)
        assertEquals(listOf(JarMeta.Descriptor.PLUGIN_YML, JarMeta.Descriptor.FABRIC_MOD_JSON), descriptors.all.map { it.descriptor })
        assertEquals(JarMeta.Descriptor.PLUGIN_YML, descriptors.primary?.descriptor)
    }
}
