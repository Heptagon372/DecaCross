package kr.decacross.compat.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    private fun lv(s: String) = LooseVersion(s)

    @Test
    fun looseVersion_ordersNumericallyAndHandlesPrerelease() {
        assertTrue(lv("1.10.0") > lv("1.9.9"))
        assertTrue(lv("2.21.0") > lv("2.20.1"))
        assertTrue(lv("1.0.0-rc1") < lv("1.0.0"))
        assertTrue(lv("1.0.0-SNAPSHOT") < lv("1.0.0"))
        assertEquals(0, lv("1.0").compareTo(lv("1.0.0")), "빠진 자리는 0")
        assertTrue(lv("v5.4.0") > lv("5.1.0"))
        assertTrue(lv("1.19.4-R0.1-SNAPSHOT") < lv("1.19.4"))
        assertEquals(0, lv("build-62").compareTo(lv("build-62")))
        assertEquals(0, lv("1.0.0+build.5").compareTo(lv("1.0.0")))
    }

    @Test
    fun versionRange_parsesCommonForms() {
        val sem = { s: String -> Ver.Sem(lv(s)) }
        assertTrue(VersionRange.parse("*").contains(sem("0.0.1")))
        assertTrue(VersionRange.parse(">=1.7").contains(sem("1.7.3")))
        assertFalse(VersionRange.parse(">=1.7").contains(sem("1.6")))
        assertTrue(VersionRange.parse("^1.2.3").contains(sem("1.9.0")))
        assertFalse(VersionRange.parse("^1.2.3").contains(sem("2.0.0")))
        assertTrue(VersionRange.parse("~1.2.3").contains(sem("1.2.9")))
        assertFalse(VersionRange.parse("~1.2.3").contains(sem("1.3.0")))
        assertTrue(VersionRange.parse(">=1.0 <2.0").contains(sem("1.5")))
        assertFalse(VersionRange.parse(">=1.0 <2.0").contains(sem("2.0")))
        assertTrue(VersionRange.parse("1.21.x").contains(sem("1.21.8")))
        assertFalse(VersionRange.parse("1.21.x").contains(sem("1.22")))
        assertTrue(VersionRange.parse("5.1.0").contains(sem("5.1.0")))
        assertFalse(VersionRange.parse("5.1.0").contains(sem("5.1.1")))
        assertTrue(VersionRange.parse("garbage!!").contains(sem("9.9")), "해석 불가면 관대하게 전체")
    }

    @Test
    fun build_encodesMcAndNumber() {
        val b = Ver.Build.of(kr.decacross.compat.model.McOrdinal(1940), 60)
        assertEquals(1940, b.mcOrdinal.value)
        assertEquals(60, b.buildNumber)
        assertTrue(Ver.Build.of(kr.decacross.compat.model.McOrdinal(1950), 1) > Ver.Build.of(kr.decacross.compat.model.McOrdinal(1940), 999))
    }
}
