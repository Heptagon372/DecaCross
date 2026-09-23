package kr.decacross.compat.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RamTest {
    /** I5: hostOverheadMb 가 반영된다 — 데몬+UI 사용량만큼 권장값이 줄어든다. */
    @Test
    fun invariant_ramAccountsHost() {
        val base = recommendedRamMb(systemTotalMb = 16 * 1024, hostOverheadMb = 0)
        val withHost = recommendedRamMb(systemTotalMb = 16 * 1024, hostOverheadMb = 576)
        assertTrue(withHost < base, "host overhead 가 차감되어야 한다: $base → $withHost")
        assertEquals(8192 - 2048, base, "16GB: min(8192, cap) - 0 - 2048")
        assertEquals(5120, withHost, "(8192 - 576 - 2048) = 5568 → 512 단위 내림 = 5120")
    }

    @Test
    fun floorsToMinimum_onSmallMachines() {
        assertEquals(1024, recommendedRamMb(systemTotalMb = 4 * 1024, hostOverheadMb = 300))
    }

    @Test
    fun capApplies_onBigMachines() {
        assertEquals(10 * 1024 - 2048, recommendedRamMb(systemTotalMb = 64 * 1024, hostOverheadMb = 0))
    }
}
