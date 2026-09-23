package kr.decacross.collector

import kr.decacross.compat.model.Source
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedistributableTest {
    @Test
    fun allowlistedLicenses_areOpen() {
        listOf(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0",
            "GPL-2.0", "GPL-3.0", "GPL-3.0-only", "GPL-2.0-or-later", "LGPL-2.1", "LGPL-3.0-only", "AGPL-3.0",
            "CC0-1.0", "Unlicense", "EPL-2.0", "Zlib", " mit ", "gpl-3.0-or-later",
        ).forEach { assertTrue(isOpenLicense(it), "허용이어야 함: $it") }
    }

    @Test
    fun hangarStyleAliases_areOpen() {
        listOf("GPL", "LGPL", "AGPL", "Apache 2.0", "MIT License", "Unlicense").forEach {
            assertTrue(isOpenLicense(it), "허용이어야 함: $it")
        }
    }

    @Test
    fun arrCustomUnknownMissing_areNotOpen() {
        listOf(null, "", "  ", "ARR", "All Rights Reserved", "LicenseRef-Custom", "Custom", "Unspecified", "Proprietary", "CC-BY-NC-4.0", "GPL-1.0", "Apache-1.1")
            .forEach { assertFalse(isOpenLicense(it), "거부여야 함: $it") }
    }

    @Test
    fun redistributable_requiresOpenLicenseAndTrustedSource() {
        assertTrue(isRedistributable("MIT", Source.MODRINTH))
        assertTrue(isRedistributable("GPL-3.0", Source.HANGAR))
        assertFalse(isRedistributable("MIT", Source.SPIGOT))
        assertFalse(isRedistributable("MIT", Source.URL))
        assertFalse(isRedistributable("MIT", Source.USER_UPLOAD))
        assertFalse(isRedistributable("ARR", Source.MODRINTH))
        assertFalse(isRedistributable(null, Source.HANGAR))
    }
}
