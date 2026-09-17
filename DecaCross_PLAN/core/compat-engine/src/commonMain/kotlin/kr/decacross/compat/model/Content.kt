package kr.decacross.compat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 콘텐츠 ──────────────────────────────────────────────

@Serializable
public enum class ContentKind { PLUGIN, MOD, SCRIPT, RESOURCE_PACK, DATA_PACK, WORLD, MODEL_PACK }

@Serializable
public enum class Source { MODRINTH, HANGAR, SPIGOT, URL, USER_UPLOAD, BUNDLED }

@Serializable
public data class Content(
    val slug: String,
    val name: String,
    val kind: ContentKind,
    val source: Source,
    val license: String? = null,
    /**
     * # 불변식
     * false 면 우리가 파일을 미러링·재배포하지 않는다.
     * 라이선스를 확인하지 못했으면 반드시 false. 기본값이 안전한 쪽이어야 한다.
     */
    val redistributable: Boolean = false,
)

@Serializable
public data class ContentVersion(
    val slug: String,
    val version: String,
    val fileUrl: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    /** ASM 으로 읽은 class major version 에서 역산한 실측값. 메타데이터보다 우선 신뢰. */
    val javaMajor: Int? = null,
    val apiVersion: String? = null,
    val loaders: Set<LoaderFamily> = emptySet(),
    val mcMin: McOrdinal? = null,
    val mcMax: McOrdinal? = null,
    val pack: PackDecl? = null,
    val deps: List<Dep> = emptyList(),
)

/** ★ CONFLICT 없음 — 배타는 PROVIDES 로 표현한다 (설계서 §3.3 핵심 트릭 1). */
@Serializable
public enum class DepKind { REQUIRE, OPTIONAL, PROVIDES }

@Serializable
public sealed interface DepTarget {
    @Serializable
    @SerialName("slug")
    public data class Slug(val value: String) : DepTarget

    @Serializable
    @SerialName("cap")
    public data class Cap(val value: Capability) : DepTarget
}

@Serializable
public data class Dep(val kind: DepKind, val target: DepTarget, val range: String = "*")

/** 배타 제약을 표현하는 가상 패키지 키. 같은 Capability 제공자는 하나만 선택된다. */
@Serializable
public sealed interface Capability {
    /** ItemsAdder | Oraxen | Nexo */
    @Serializable
    @SerialName("custom_item_framework")
    public data object CustomItemFramework : Capability

    @Serializable
    @SerialName("economy_provider")
    public data object EconomyProvider : Capability

    @Serializable
    @SerialName("permission_provider")
    public data object PermissionProvider : Capability

    @Serializable
    @SerialName("anti_cheat")
    public data object AntiCheat : Capability

    @Serializable
    @SerialName("chunk_generator")
    public data object ChunkGenerator : Capability

    @Serializable
    @SerialName("other")
    public data class Other(val key: String) : Capability
}
