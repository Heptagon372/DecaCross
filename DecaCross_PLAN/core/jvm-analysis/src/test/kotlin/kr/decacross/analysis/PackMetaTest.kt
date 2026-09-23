package kr.decacross.analysis

import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackMetaTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
    }

    @AfterTest
    fun tearDown() = deleteTree(dir)

    private fun decl(json: String): PackDecl? = parsePackMcmeta(json)?.decl

    @Test
    fun `pack_format int and decimal`() {
        assertEquals(PackDecl.Single(PackFormat(34)), decl("""{"pack":{"pack_format":34,"description":"x"}}"""))
        assertEquals(PackDecl.Single(PackFormat(88, 0)), decl("""{"pack":{"pack_format":88.0}}"""))
        assertEquals(PackDecl.Single(PackFormat(101, 1)), decl("""{"pack":{"pack_format":"101.1"}}"""))
    }

    @Test
    fun `supported_formats list and object`() {
        assertEquals(PackDecl.Range(PackFormat(15), PackFormat(34)), decl("""{"pack":{"pack_format":34,"supported_formats":[15,34]}}"""))
        assertEquals(PackDecl.Range(PackFormat(15), PackFormat(34)), decl("""{"pack":{"pack_format":34,"supported_formats":{"min_inclusive":15,"max_inclusive":34}}}"""))
        assertEquals(PackDecl.Supported(listOf(PackFormat(15), PackFormat(22), PackFormat(34))), decl("""{"pack":{"pack_format":34,"supported_formats":[15,22,34]}}"""))
    }

    @Test
    fun `min_format max_format int or major-minor array`() {
        assertEquals(PackDecl.Range(PackFormat(88, 0), PackFormat(101, 1)), decl("""{"pack":{"min_format":[88,0],"max_format":[101,1]}}"""))
        assertEquals(PackDecl.Range(PackFormat(64), PackFormat(88)), decl("""{"pack":{"pack_format":64,"min_format":64,"max_format":88}}"""))
        // min 만 있으면 pack_format 으로 보충
        assertEquals(PackDecl.Range(PackFormat(64), PackFormat(64)), decl("""{"pack":{"pack_format":64,"min_format":64}}"""))
    }

    @Test
    fun `description forms and missing pack section`() {
        assertEquals("Hello", parsePackMcmeta("""{"pack":{"pack_format":1,"description":{"text":"Hello"}}}""")?.description)
        assertEquals("AB", parsePackMcmeta("""{"pack":{"pack_format":1,"description":["A",{"text":"B"}]}}""")?.description)
        assertNull(parsePackMcmeta("""{"nope":1}"""))
        assertNull(parsePackMcmeta("""{"pack":{"description":"no format"}}"""))
        assertNull(parsePackMcmeta("not json"))
    }

    @Test
    fun `reads from zip root nested folder and directory`() {
        val text = """{"pack":{"pack_format":48}}"""
        val zip = JarBuilder().text("pack.mcmeta", text).write(dir.resolve("pack.zip"))
        assertEquals(PackDecl.Single(PackFormat(48)), readPackDecl(zip))
        val nested = JarBuilder().text("MyPack/pack.mcmeta", text).text("MyPack/assets/x.json", "{}").write(dir.resolve("nested.zip"))
        assertEquals(PackDecl.Single(PackFormat(48)), readPackDecl(nested))
        val folder = dir.resolve("folder").also {
            Files.createDirectories(it)
            Files.writeString(it.resolve("pack.mcmeta"), text)
        }
        assertEquals(PackDecl.Single(PackFormat(48)), readPackDecl(folder))
        assertNull(readPackDecl(dir.resolve("missing.zip")))
    }
}
