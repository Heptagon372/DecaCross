package kr.decacross.compat.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackFormatTest {
    @Test
    fun parse_acceptsIntegerAndMajorMinor() {
        assertEquals(PackFormat(34), PackFormat.parse("34"))
        assertEquals(PackFormat(88, 0), PackFormat.parse("88.0"))
        assertEquals(PackFormat(101, 1), PackFormat.parse("101.1"))
        assertEquals(PackFormat(121, 0), PackFormat.parse(" 121.0 "))
        assertEquals(PackFormat(0), PackFormat.parse("0"))
    }

    @Test
    fun parse_rejectsGarbage() {
        listOf("", " ", "-1", "abc", "1.2.3", "34.", ".1", "88,0", "1e3", "+3", "34.0.0").forEach {
            assertNull(PackFormat.parse(it), "'$it' 는 거부해야 한다")
        }
    }

    @Test
    fun ordering_isMajorThenMinor_notNumericFloat() {
        // 정수 비교라면 101.10 < 101.9 가 되어 버린다. major/minor 로는 101.10 > 101.9 이다.
        assertTrue(PackFormat(101, 10) > PackFormat(101, 9))
        assertTrue(PackFormat(88, 0) < PackFormat(101, 1))
        assertTrue(PackFormat(34) < PackFormat(48))
    }

    @Test
    fun toString_roundTrips() {
        listOf("34", "88.1", "101.10").forEach { s ->
            val pf = PackFormat.parse(s)
            assertEquals(s, pf.toString())
        }
        assertEquals("88", PackFormat(88, 0).toString(), "minor 0 은 생략")
    }

    @Test
    fun serialization_isAlwaysString() {
        val json = Json.encodeToString(PackFormat.serializer(), PackFormat(101, 1))
        assertEquals("\"101.1\"", json)
        assertEquals(PackFormat(101, 1), Json.decodeFromString(PackFormat.serializer(), "\"101.1\""))
    }

    @Test
    fun single_minorIsNonBreaking() {
        val decl = PackDecl.Single(PackFormat(88, 0))
        assertTrue(decl.isCompatibleWith(PackFormat(88, 0)))
        assertTrue(decl.isCompatibleWith(PackFormat(88, 3)), "게임 마이너가 더 높으면 호환")
        assertFalse(PackDecl.Single(PackFormat(88, 1)).isCompatibleWith(PackFormat(88, 0)), "팩이 요구하는 마이너보다 낮으면 불가")
        assertFalse(decl.isCompatibleWith(PackFormat(89, 0)), "major 불일치")
    }

    @Test
    fun range_isInclusiveOnBothEnds() {
        val decl = PackDecl.Range(PackFormat(48), PackFormat(61))
        assertTrue(decl.isCompatibleWith(PackFormat(48)))
        assertTrue(decl.isCompatibleWith(PackFormat(61)))
        assertTrue(decl.isCompatibleWith(PackFormat(55)))
        assertFalse(decl.isCompatibleWith(PackFormat(47)))
        assertFalse(decl.isCompatibleWith(PackFormat(61, 1)), "max 를 넘는 마이너는 구간 밖")
    }

    @Test
    fun supported_isIntersectionOfSingles() {
        val decl = PackDecl.Supported(listOf(PackFormat(34), PackFormat(48), PackFormat(101, 1)))
        assertTrue(decl.isCompatibleWith(PackFormat(48)))
        assertTrue(decl.isCompatibleWith(PackFormat(101, 4)))
        assertFalse(decl.isCompatibleWith(PackFormat(101, 0)))
        assertFalse(decl.isCompatibleWith(PackFormat(61)))
        assertFalse(PackDecl.Supported(emptyList()).isCompatibleWith(PackFormat(48)))
    }

    /** 불변식 4: 같은 MC 버전에서 리소스팩·데이터팩 포맷은 다르다. 두 값을 섞어 쓰면 이 테스트가 잡는다. */
    @Test
    fun resourcePackAndDataPack_formatsDiffer_on1_21() {
        val rp = PackFormat(34)
        val dp = PackFormat(48)
        assertFalse(PackDecl.Single(rp).isCompatibleWith(dp))
        assertFalse(PackDecl.Single(dp).isCompatibleWith(rp))
    }
}
