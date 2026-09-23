package kr.decacross.daemon.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaRuntimeTest {
    @Test
    fun parseFeature_handlesOldAndNewSchemes() {
        assertEquals(25, JavaRuntime.parseFeature("java version \"25.0.2\" 2026-01-20 LTS"))
        assertEquals(21, JavaRuntime.parseFeature("openjdk version \"21.0.4\" 2024-07-16"))
        assertEquals(8, JavaRuntime.parseFeature("openjdk version \"1.8.0_402\""))
        assertEquals(17, JavaRuntime.parseFeature("openjdk version \"17\" 2021-09-14"))
        assertEquals(null, JavaRuntime.parseFeature("garbage"))
    }

    @Test
    fun findSystemJava_findsTheCurrentJvm() {
        val info = assertNotNull(JavaRuntime.findSystemJava())
        assertTrue(info.feature >= 17, "toolchain 은 25 여야 하지만 최소 17 이상: $info")
        assertTrue(info.exe.isAbsolute)
    }
}
