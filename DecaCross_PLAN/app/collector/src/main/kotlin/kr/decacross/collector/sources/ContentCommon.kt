package kr.decacross.collector.sources

import kr.decacross.collector.ContentRaw
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal

/** 콘텐츠 소스 한 번의 수집 결과. [notes] 는 건너뛴 것·표현 못 한 것 (보고서로 나간다). */
data class ContentCollection(
    val contents: List<ContentRaw> = emptyList(),
    val versions: List<ContentVersion> = emptyList(),
    val notes: List<String> = emptyList(),
)

/**
 * 저장소 로더 이름 → [LoaderFamily]. paper/spigot/purpur/bukkit/folia → BUKKIT, fabric/quilt → FABRIC,
 * forge/neoforge → FORGE. 프록시(velocity/bungeecord/waterfall)·datapack 등은 null (서버 플러그인이 아니다).
 */
fun loaderFamilyOf(loader: String): LoaderFamily? = when (loader.lowercase()) {
    "paper", "spigot", "purpur", "bukkit", "folia" -> LoaderFamily.BUKKIT
    "fabric", "quilt" -> LoaderFamily.FABRIC
    "forge", "neoforge" -> LoaderFamily.FORGE
    "minecraft", "vanilla" -> LoaderFamily.VANILLA
    else -> null
}

/** 저장소가 나열한 MC 라벨 중 우리 목록에 있는 것들의 서수 min/max. 하나도 없으면 (null, null). */
fun mcRangeOf(labels: Collection<String>, mcByLabel: Map<String, McOrdinal>): Pair<McOrdinal?, McOrdinal?> {
    val ords = labels.mapNotNull { mcByLabel[it] }
    return if (ords.isEmpty()) null to null else ords.min() to ords.max()
}

/**
 * 저장소 의존 종류 → [DepKind]. `incompatible` 은 엔진에 CONFLICT 가 없어 표현 불가(설계서 §3.3: 배타는 PROVIDES 로),
 * `embedded` 는 jar 에 포함된 것이라 의존이 아니다 → 둘 다 null (호출자가 로그).
 */
fun depKindOf(raw: String): DepKind? = when (raw.lowercase()) {
    "required" -> DepKind.REQUIRE
    "optional" -> DepKind.OPTIONAL
    else -> null
}
