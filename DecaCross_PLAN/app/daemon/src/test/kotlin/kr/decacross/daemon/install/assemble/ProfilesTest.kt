package kr.decacross.daemon.install.assemble

import kr.decacross.daemon.install.FlagSelection
import kr.decacross.daemon.install.LaunchProfiles
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.PRETOUCH_FLAG
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.install.selectFlagProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** JVM 플래그 프로파일 선택 (DESIGN2 §2.10, AE-13, D-I19). */
class ProfilesTest {
    private val profiles = (loadLaunchProfiles() as? LaunchProfilesLoad.Loaded)?.profiles ?: fail("리소스 로드 실패")

    private fun selected(heapMb: Int, javaFeature: Int, preTouch: Boolean = true): FlagSelection.Selected =
        selectFlagProfile(profiles, heapMb, javaFeature, preTouch) as? FlagSelection.Selected
            ?: fail("프로파일이 선택돼야 한다: heap=$heapMb java=$javaFeature")

    @Test
    fun heapAndJavaRanges_pickTheDocumentedProfile() {
        assertEquals("aikar-base", selected(4096, 21).profile.id)
        assertEquals("aikar-base", selected(256, 8).profile.id)
        assertEquals("aikar-base", selected(12287, 21).profile.id)
        assertEquals("aikar-12g", selected(12288, 21).profile.id)
        assertEquals("aikar-12g", selected(32768, 25).profile.id)
    }

    @Test
    fun preTouchFalse_removesOnlyThatFlag() {
        val withPreTouch = selected(4096, 21, preTouch = true).flags
        val without = selected(4096, 21, preTouch = false).flags
        assertTrue(PRETOUCH_FLAG in withPreTouch)
        assertEquals(withPreTouch.filter { it != PRETOUCH_FLAG }, without)
        assertEquals(withPreTouch.size - 1, without.size)
    }

    @Test
    fun outOfRangeHeapOrJava_hasNoProfile() {
        val smallHeap = selectFlagProfile(profiles, 200, 21, true)
        assertTrue(smallHeap is FlagSelection.NoProfile, "$smallHeap")
        assertTrue(smallHeap.reasonKo.contains("200"), smallHeap.reasonKo)

        val futureJava = selectFlagProfile(profiles, 4096, 29, true)
        assertTrue(futureJava is FlagSelection.NoProfile, "$futureJava")
        assertTrue(futureJava.reasonKo.contains("29"), futureJava.reasonKo)
    }

    /** 겹치는 프로파일은 "아무거나 하나" 가 아니라 리소스 오류다 (§2.10). 실제 리소스에는 겹침이 없으므로 합성해서 확인한다. */
    @Test
    fun overlappingProfiles_areResourceErrors_notAnArbitraryPick() {
        val base = profiles.jvmFlagProfiles.first { it.id == "aikar-base" }
        val overlapping = LaunchProfiles(
            schema = profiles.schema,
            jvmFlagProfiles = listOf(base, base.copy(id = "aikar-base-copy")),
            console = profiles.console,
        )
        val selection = selectFlagProfile(overlapping, 4096, 21, preTouch = true)
        val noProfile = assertIs<FlagSelection.NoProfile>(selection, "$selection")
        assertTrue(noProfile.reasonKo.contains("aikar-base"), noProfile.reasonKo)
        assertTrue(noProfile.reasonKo.contains("aikar-base-copy"), noProfile.reasonKo)
    }

    @Test
    fun resourceRanges_neverOverlap_andAlwaysCoverSupportedCombinations() {
        var heapMb = 256
        while (heapMb <= 65536) {
            for (javaFeature in 8..28) {
                val selection = selectFlagProfile(profiles, heapMb, javaFeature, true)
                assertTrue(
                    selection is FlagSelection.Selected,
                    "heap=$heapMb java=$javaFeature 에 프로파일이 하나여야 한다: $selection",
                )
            }
            heapMb += 256
        }
    }

    @Test
    fun unlockExperimentalOptions_precedesExperimentalFlags() {
        for (profile in profiles.jvmFlagProfiles) {
            val unlock = profile.flags.indexOf("-XX:+UnlockExperimentalVMOptions")
            val firstExperimental = profile.flags.indexOfFirst { it.startsWith("-XX:G1NewSizePercent") }
            assertTrue(unlock >= 0 && firstExperimental > unlock, "${profile.id}: ${profile.flags}")
        }
    }
}
