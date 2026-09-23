package kr.decacross.collector

import kr.decacross.compat.model.Source

/**
 * 재배포 허용 라이선스 허용목록 (SPDX id, 대문자 비교). 여기 없으면 전부 false 다 (불변식 5).
 * GPL/LGPL/AGPL 은 `-only` / `-or-later` 접미사를 벗겨서 본다.
 */
private val ALLOWED_SPDX: Set<String> = setOf(
    "MIT", "APACHE-2.0", "BSD-2-CLAUSE", "BSD-3-CLAUSE", "ISC", "MPL-2.0",
    "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0",
    "CC0-1.0", "UNLICENSE", "EPL-2.0", "ZLIB",
)

/**
 * 소스가 SPDX 가 아닌 이름으로 주는 경우의 별칭 (Hangar `license.type` 등). 허용목록 밖 라이선스로 이어지는
 * 별칭은 넣지 않는다. 버전 없는 "GPL"/"LGPL"/"AGPL" 은 어느 버전이든 허용목록 안이라 받는다.
 */
private val ALIASES: Map<String, String> = mapOf(
    "MIT LICENSE" to "MIT",
    "APACHE 2.0" to "APACHE-2.0",
    "APACHE-2" to "APACHE-2.0",
    "APACHE LICENSE 2.0" to "APACHE-2.0",
    "GPL" to "GPL-3.0",
    "GPLV2" to "GPL-2.0",
    "GPLV3" to "GPL-3.0",
    "GPL-2" to "GPL-2.0",
    "GPL-3" to "GPL-3.0",
    "LGPL" to "LGPL-3.0",
    "LGPLV3" to "LGPL-3.0",
    "LGPLV2.1" to "LGPL-2.1",
    "AGPL" to "AGPL-3.0",
    "AGPLV3" to "AGPL-3.0",
    "MPL" to "MPL-2.0",
    "MPL2" to "MPL-2.0",
    "THE UNLICENSE" to "UNLICENSE",
    "BSD-2" to "BSD-2-CLAUSE",
    "BSD-3" to "BSD-3-CLAUSE",
    "CC0" to "CC0-1.0",
    "EPL" to "EPL-2.0",
)

/** "GPL-3.0-only" / "GPL-3.0-or-later" → "GPL-3.0". 그 외는 그대로. */
private fun stripVariant(id: String): String = id.removeSuffix("-ONLY").removeSuffix("-OR-LATER")

/** 라이선스 문자열이 허용목록에 드는가. null/공백/ARR/custom/모르는 값은 전부 false. */
fun isOpenLicense(license: String?): Boolean {
    val raw = license?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
    val id = ALIASES[raw] ?: raw
    return stripVariant(id) in ALLOWED_SPDX
}

/**
 * 재배포 가능 판정. ★ 허용목록 라이선스 **그리고** 소스가 Modrinth/Hangar 일 때만 true.
 * SpigotMC 는 스크래핑하지 않으므로(정책) 이 함수에 들어올 일도 없고, 들어와도 false.
 */
fun isRedistributable(license: String?, source: Source): Boolean =
    (source == Source.MODRINTH || source == Source.HANGAR) && isOpenLicense(license)
