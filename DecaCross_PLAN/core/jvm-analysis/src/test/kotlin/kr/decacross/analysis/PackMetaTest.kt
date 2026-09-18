package kr.decacross.analysis

import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.textEntry
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** pack.mcmeta (설계 §7.5). 호환 판정은 `PackDecl.isCompatibleWith` 로 확인한다. */
class PackMetaTest {
    private val max = Int.MAX_VALUE

    private fun ok(json: String): PackMcmeta = when (val result = parsePackMcmeta(json)) {
        is PackMcmetaResult.Ok -> result.meta
        is PackMcmetaResult.Invalid -> fail("Invalid 가 아니어야 함: ${result.reason} ← $json")
    }

    private fun assertInvalid(json: String) {
        assertIs<PackMcmetaResult.Invalid>(parsePackMcmeta(json), json)
    }

    private fun pack(body: String): String = """{"pack": {"description": "test"$body}}"""

    private fun PackDecl.accepts(vararg formats: PackFormat): Boolean = formats.all { isCompatibleWith(it) }

    private fun PackDecl.rejects(vararg formats: PackFormat): Boolean = formats.none { isCompatibleWith(it) }

    @Test
    fun pk01_packFormatOnly() {
        val meta = ok(pack(""", "pack_format": 34"""))
        assertEquals(PackDecl.Single(PackFormat(34)), meta.modernDecl())
        assertEquals(PackDecl.Single(PackFormat(34)), meta.legacyDecl())
    }

    @Test
    fun pk02_vanilla1202Bundle() {
        val meta = ok("""{"pack":{"pack_format":18,"description":"The default data for Minecraft"}}""")
        assertEquals(PackDecl.Single(PackFormat(18)), meta.modernDecl())
        assertTrue(meta.notes.isEmpty())
    }

    @Test
    fun pk03_supportedArray() {
        val meta = ok(pack(""", "pack_format": 18, "supported_formats": [16, 34]"""))
        assertEquals(PackDecl.Range(PackFormat(16, 0), PackFormat(34, max)), meta.modernDecl())
        assertEquals(PackDecl.Single(PackFormat(18)), meta.legacyDecl())
    }

    @Test
    fun pk04_supportedObject() {
        val meta = ok(pack(""", "pack_format": 18, "supported_formats": {"min_inclusive": 16, "max_inclusive": 34}"""))
        assertEquals(PackDecl.Range(PackFormat(16, 0), PackFormat(34, max)), meta.modernDecl())
        assertEquals(PackDecl.Single(PackFormat(18)), meta.legacyDecl())
    }

    @Test
    fun pk05_supportedInt() {
        val meta = ok(pack(""", "supported_formats": 34"""))
        assertEquals(PackDecl.Range(PackFormat(34, 0), PackFormat(34, max)), meta.modernDecl())
        assertEquals(PackMajorRange(PackFormat(34, 0), PackFormat(34, Int.MAX_VALUE)), meta.supportedFormats)
        assertNull(meta.legacyDecl())
    }

    @Test
    fun pk06_vanilla26_3() {
        val meta = ok(pack(""", "min_format": 121, "max_format": 121"""))
        val decl = meta.modernDecl() ?: fail("modernDecl 없음")
        assertTrue(decl.accepts(PackFormat(121, 0), PackFormat(121, 7)))
        assertTrue(decl.rejects(PackFormat(122, 0), PackFormat(120, 9)))
        assertEquals(PackFormat(121, 0), meta.minFormat)
        assertEquals(PackFormat(121, max), meta.maxFormat)
    }

    @Test
    fun pk07_minMinorMaxMajorOnly() {
        val decl = ok(pack(""", "min_format": [88, 1], "max_format": [97]""")).modernDecl() ?: fail("modernDecl 없음")
        assertTrue(decl.rejects(PackFormat(88, 0)))
        assertTrue(decl.accepts(PackFormat(88, 1), PackFormat(97, 5)))
        assertTrue(decl.rejects(PackFormat(98, 0)))
    }

    @Test
    fun pk08_explicitMaxMinor() {
        val decl = ok(pack(""", "min_format": [88], "max_format": [88, 0]""")).modernDecl() ?: fail("modernDecl 없음")
        assertTrue(decl.accepts(PackFormat(88, 0)))
        assertTrue(decl.rejects(PackFormat(88, 1)))
    }

    @Test
    fun pk09_onlyOneOfMinMax() {
        assertInvalid(pack(""", "min_format": 88"""))
        assertInvalid(pack(""", "max_format": 88"""))
    }

    @Test
    fun pk10_badShapes() {
        for (shape in listOf("[]", "[1,2,3]", "[88,-1]", "\"88\"", "88.5", "-1", "true", "null")) {
            assertInvalid(pack(""", "min_format": $shape, "max_format": 99"""))
        }
        for (shape in listOf("\"34\"", "34.0", "-1", "[16]", "[34, 16]", "{\"min_inclusive\": 16}")) {
            assertInvalid(pack(""", "supported_formats": $shape"""))
        }
        assertInvalid(pack(""", "pack_format": "34""""))
        assertInvalid(pack(""", "pack_format": 34.5"""))
    }

    @Test
    fun pk11_minAboveMax() {
        assertInvalid(pack(""", "min_format": 90, "max_format": 88"""))
        assertInvalid(pack(""", "min_format": [88, 2], "max_format": [88, 1]"""))
    }

    @Test
    fun pk12_noPack() {
        assertInvalid("""{"overlays": {"entries": []}}""")
        assertInvalid("""{"pack": 34}""")
        assertInvalid("[1]")
        assertInvalid("{not json")
    }

    @Test
    fun pk13_overlaysParsed_declUnaffected() {
        val meta = ok(
            """
            {
              "pack": {"pack_format": 34, "description": "x"},
              "overlays": {"entries": [
                {"directory": "old", "formats": [18, 33]},
                {"directory": "new", "min_format": [88, 0], "max_format": 99}
              ]}
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                PackMcmeta.Overlay("old", PackMajorRange(PackFormat(18, 0), PackFormat(33, max)), null, null),
                PackMcmeta.Overlay("new", null, PackFormat(88, 0), PackFormat(99, max)),
            ),
            meta.overlays,
        )
        assertEquals(PackDecl.Single(PackFormat(34)), meta.modernDecl())
        assertInvalid("""{"pack": {"pack_format": 34}, "overlays": {"entries": [{"formats": 18}]}}""")
        assertInvalid("""{"pack": {"pack_format": 34}, "overlays": {"entries": [{"directory": "a", "min_format": 5}]}}""")
    }

    @Test
    fun pk14_textComponentDescription() {
        val meta = ok("""{"pack": {"pack_format": 34, "description": {"text": "Hi", "color": "gold", "extra": [{"text": "!"}]}}}""")
        assertEquals(PackFormat(34), meta.packFormat)
    }

    @Test
    fun pk15_legacyModernMismatchNote() {
        val mismatch = ok(pack(""", "pack_format": 15, "min_format": 88, "max_format": 99"""))
        assertTrue(mismatch.notes.contains("legacy/modern major 불일치"), mismatch.notes.toString())
        val supportedMismatch = ok(pack(""", "pack_format": 40, "supported_formats": [16, 34]"""))
        assertTrue(supportedMismatch.notes.contains("legacy/modern major 불일치"), supportedMismatch.notes.toString())
        val consistent = ok(pack(""", "pack_format": 90, "min_format": 88, "max_format": 99"""))
        assertFalse(consistent.notes.contains("legacy/modern major 불일치"))
    }

    @Test
    fun pk16_maxMinorRoundTrip() {
        val value = PackFormat(88, Int.MAX_VALUE)
        assertEquals(value, PackFormat.parse(value.toString()))
    }

    @Test
    fun pk17_packFormatTyped() {
        val meta = ok(pack(""", "pack_format": 34"""))
        val format: PackFormat? = meta.packFormat
        assertEquals(PackFormat(34), format)
        assertIs<PackFormat>(format)
    }

    @Test
    fun readPackMcmeta_absent_null() {
        assertNull(readPackMcmeta(jar("a/A.class" to classBytes("a/A"))))
        val present = readPackMcmeta(jar(textEntry("pack.mcmeta", pack(""", "pack_format": 34"""))))
        assertIs<PackMcmetaResult.Ok>(present)
        val tooLarge = readPackMcmeta(jar(textEntry("pack.mcmeta", pack(""", "pack_format": 34, "x": "${"y".repeat(DESCRIPTOR_MAX_BYTES)}""""))))
        assertIs<PackMcmetaResult.Invalid>(tooLarge)
    }
}
