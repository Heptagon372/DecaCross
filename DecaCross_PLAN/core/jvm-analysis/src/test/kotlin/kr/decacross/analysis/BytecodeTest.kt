package kr.decacross.analysis

import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BytecodeTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
    }

    @AfterTest
    fun tearDown() = deleteTree(dir)

    @Test
    fun `max class major wins`() {
        val jar = JarBuilder()
            .clazz(classBytes("a/Eight", version = Opcodes.V1_8))
            .clazz(classBytes("a/Seventeen", version = Opcodes.V17))
            .clazz(classBytes("a/TwentyOne", version = Opcodes.V21))
            .clazz(classBytes("a/TwentyFive", version = Opcodes.V25))
            .write(dir.resolve("mixed.jar"))
        assertEquals(25, requiredJavaFeature(jar))
        val profile = classVersionProfile(jar)
        assertEquals(69, profile?.baseMax)
        assertEquals(4, profile?.classCount)
    }

    @Test
    fun `multi-release directories are ignored for the minimum but reported per version`() {
        val jar = JarBuilder()
            .apply { multiRelease = true }
            .clazz(classBytes("a/Base", version = Opcodes.V17))
            .clazz(classBytes("a/Base", version = Opcodes.V21), versionDir = 21)
            .clazz(classBytes("a/Newer", version = Opcodes.V25), versionDir = 25)
            .write(dir.resolve("mr.jar"))
        assertEquals(17, requiredJavaFeature(jar))
        val profile = classVersionProfile(jar)
        assertEquals(mapOf(21 to 65, 25 to 69), profile?.versionedMax)
    }

    @Test
    fun `module-info and non-class entries are skipped`() {
        val jar = JarBuilder()
            .clazz(classBytes("a/Only", version = Opcodes.V1_8))
            .raw("module-info.class", classBytes("module-info", version = Opcodes.V25))
            .raw("META-INF/signed/Foo.class", classBytes("Foo", version = Opcodes.V25))
            .text("a/notaclass.txt", "x")
            .write(dir.resolve("mi.jar"))
        assertEquals(8, requiredJavaFeature(jar))
    }

    @Test
    fun `no classes or unreadable jar gives null`() {
        val empty = JarBuilder().text("plugin.yml", "name: X").write(dir.resolve("empty.jar"))
        assertNull(requiredJavaFeature(empty))
        val bogus = dir.resolve("bogus.jar").also { Files.write(it, byteArrayOf(0, 1, 2)) }
        assertNull(requiredJavaFeature(bogus))
    }

    @Test
    fun `major to feature table from spec`() {
        assertEquals(8, javaFeatureOfClassMajor(52))
        assertEquals(16, javaFeatureOfClassMajor(60))
        assertEquals(17, javaFeatureOfClassMajor(61))
        assertEquals(21, javaFeatureOfClassMajor(65))
        assertEquals(25, javaFeatureOfClassMajor(69))
    }
}
