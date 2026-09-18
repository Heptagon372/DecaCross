package kr.decacross.collector.content

import kr.decacross.analysis.ANALYZER_VERSION
import kr.decacross.analysis.CapabilityRules
import kr.decacross.collector.store.dbKey
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 유효 분석기 버전 (D53, SCP-23): `"$ANALYZER_VERSION+rules.<sha256(정규화 규칙) 앞 8 hex>"`.
 *
 * 정규화 규칙 = 아래 줄들을 정렬해 `\n` 으로 이은 UTF-8 텍스트.
 * - `economy=<storeEconomyProviders>`
 * - `service <내부 이름>=<capability dbKey>` (serviceTypes 항목마다)
 * - `external <내부 이름>=<정렬한 상위 타입들, 쉼표 구분>` (externalSupertypes 항목마다)
 * - `identity <pluginName>|<definedPackagePrefix>|<capability dbKey>` (identities 항목마다)
 *
 * # 불변식
 * - 규칙 리소스나 코드 상수 규칙표가 바뀌면 값이 바뀐다 → 저장된 버전과 다르면 재분석된다.
 * - 항목 순서(리소스 안의 identities 순서 등)는 값에 영향을 주지 않는다.
 */
internal fun effectiveAnalyzerVersion(rules: CapabilityRules): String {
    val lines = ArrayList<String>()
    lines += "economy=${rules.storeEconomyProviders}"
    for ((internalName, capability) in rules.serviceTypes) {
        lines += "service $internalName=${capability.dbKey()}"
    }
    for ((internalName, supertypes) in rules.externalSupertypes) {
        lines += "external $internalName=${supertypes.sorted().joinToString(",")}"
    }
    for (identity in rules.identities) {
        lines += "identity ${identity.pluginName}|${identity.definedPackagePrefix}|${identity.capability.dbKey()}"
    }
    val canonical = lines.sorted().joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray())
    return "$ANALYZER_VERSION+rules.${HexFormat.of().formatHex(digest).take(8)}"
}
