package kr.decacross.analysis

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadeConflictTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
    }

    @AfterTest
    fun tearDown() = deleteTree(dir)

    private fun gson(marker: String): ByteArray = classBytes("com/google/gson/Gson") {
        defaultCtor()
        field(marker, "I")
    }

    @Test
    fun `f_unrelocated gson in two plugins is HIGH`() {
        val a = JarBuilder().text("plugin.yml", pluginYml("A", "com.a.Main")).clazz(classBytes("com/a/Main")).clazz(gson("a")).write(dir.resolve("A.jar"))
        val b = JarBuilder().text("plugin.yml", pluginYml("B", "com.b.Main")).clazz(classBytes("com/b/Main")).clazz(gson("b")).write(dir.resolve("B.jar"))
        val c = JarBuilder().text("plugin.yml", pluginYml("C", "com.c.Main")).clazz(classBytes("com/c/Main")).write(dir.resolve("C.jar"))
        val conflicts = detectShadeConflicts(listOf(a, b, c))
        assertEquals(1, conflicts.size, conflicts.toString())
        val g = conflicts.single()
        assertEquals("com/google/gson/Gson", g.classPath)
        assertEquals(listOf("A.jar", "B.jar"), g.providers)
        assertEquals(Severity.HIGH, g.severity)
        assertEquals("com/google/gson", g.knownLibrary)
    }

    @Test
    fun `relocated gson is not a conflict`() {
        val a = JarBuilder().text("plugin.yml", pluginYml("A", "com.a.Main")).clazz(classBytes("com/a/Main")).clazz(classBytes("com/a/libs/gson/Gson")).write(dir.resolve("A.jar"))
        val b = JarBuilder().text("plugin.yml", pluginYml("B", "com.b.Main")).clazz(classBytes("com/b/Main")).clazz(classBytes("com/b/shaded/gson/Gson")).write(dir.resolve("B.jar"))
        assertTrue(detectShadeConflicts(listOf(a, b)).isEmpty())
    }

    @Test
    fun `same class under a plugin's own package is excluded for that plugin`() {
        // B 가 A 의 패키지를 통째로 복사한 경우: A 쪽은 자기 코드라 후보에서 빠지고, 제공자는 B 하나 → 충돌 아님
        val a = JarBuilder().text("plugin.yml", pluginYml("A", "com.a.Main")).clazz(classBytes("com/a/Main")).clazz(classBytes("com/a/Util")).write(dir.resolve("A.jar"))
        val b = JarBuilder().text("plugin.yml", pluginYml("B", "com.b.Main")).clazz(classBytes("com/b/Main")).clazz(classBytes("com/a/Util")).write(dir.resolve("B.jar"))
        assertTrue(detectShadeConflicts(listOf(a, b)).isEmpty())
    }

    @Test
    fun `unknown library is MEDIUM and identical bytes are LOW`() {
        val same = classBytes("org/bar/Same") { defaultCtor() }
        val a = JarBuilder().text("plugin.yml", pluginYml("A", "com.a.Main")).clazz(classBytes("org/foo/Util") { field("a", "I") }).clazz(same).write(dir.resolve("A.jar"))
        val b = JarBuilder().text("plugin.yml", pluginYml("B", "com.b.Main")).clazz(classBytes("org/foo/Util") { field("b", "I") }).clazz(same).write(dir.resolve("B.jar"))
        val conflicts = detectShadeConflicts(listOf(a, b)).associateBy { it.classPath }
        assertEquals(Severity.MEDIUM, conflicts.getValue("org/foo/Util").severity)
        assertEquals(Severity.LOW, conflicts.getValue("org/bar/Same").severity)
        assertTrue(conflicts.getValue("org/bar/Same").identicalBytes)
    }

    @Test
    fun `sorted by severity then path and META-INF ignored`() {
        val a = JarBuilder().clazz(classBytes("org/foo/Util") { field("a", "I") }).clazz(gson("a")).raw("META-INF/versions/9/module-info.class", byteArrayOf(1)).write(dir.resolve("A.jar"))
        val b = JarBuilder().clazz(classBytes("org/foo/Util") { field("b", "I") }).clazz(gson("b")).raw("META-INF/versions/9/module-info.class", byteArrayOf(1)).write(dir.resolve("B.jar"))
        val conflicts = detectShadeConflicts(listOf(a, b))
        assertEquals(listOf("com/google/gson/Gson", "org/foo/Util"), conflicts.map { it.classPath })
        assertEquals(listOf(Severity.HIGH, Severity.MEDIUM), conflicts.map { it.severity })
    }

    @Test
    fun `fewer than two jars is empty`() {
        val a = JarBuilder().clazz(gson("a")).write(dir.resolve("A.jar"))
        assertTrue(detectShadeConflicts(listOf(a)).isEmpty())
        assertTrue(detectShadeConflicts(emptyList()).isEmpty())
    }
}
