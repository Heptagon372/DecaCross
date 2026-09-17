package kr.decacross.analysis

import kr.decacross.compat.model.Capability
import java.nio.file.Path

/**
 * 추론 확신도.
 *
 * # 불변식 (prompt 02-4 "애매하면 저장하지 마라. 로그만 남겨라")
 * - [STORE] 만 DB 에 들어간다 (PROVIDES 행, `content_versions.analysis` 의 capabilities).
 * - [CANDIDATE] 는 **로그에만** 남긴다. DB 의 어떤 컬럼(jsonb 포함)에도 쓰지 않는다.
 */
public enum class EvidenceConfidence { STORE, CANDIDATE }

/**
 * Capability 추론 근거 하나.
 *
 * # 불변식 (prompt 02-4)
 * - 애매하면 [EvidenceConfidence.STORE] 를 주지 않는다. 잘못된 Provides 는 해결을 망친다.
 */
public data class CapabilityEvidence(
    val capability: Capability,
    val confidence: EvidenceConfidence,
    /** `SERVICE_PROVIDER` | `IDENTITY` */
    val rule: String,
    /** 사람이 읽을 근거 (클래스·메서드·우선순위 등). */
    val detail: String,
)

/**
 * 추론 규칙 입력.
 *
 * # 불변식
 * - [identities] 는 수집 데이터(플러그인 이름·패키지)라 코드에 하드코딩하지 않고 주입받는다.
 * - [serviceTypes]·[externalSupertypes] 는 Vault API 계약(내부 이름)이라 코드 상수를 기본값으로 둔다
 *   (JdkRemovedApis 와 같은 성격 — 설계서 "허용된 코드 상수" AE 목록, 사용자 확인 대상).
 */
public data class CapabilityRules(
    val identities: List<Identity> = emptyList(),
    val serviceTypes: Map<String, Capability> = DEFAULT_SERVICE_TYPES,
    /**
     * jar 밖(외부 API)에 정의된 상위 타입의 알려진 조상. C2(구현체가 서비스 타입을 구현하는가) 계층 탐색이
     * 외부 타입에서 멈추지 않게 한다. 내부 이름 → 그 타입이 구현/상속하는 서비스 타입들.
     */
    val externalSupertypes: Map<String, Set<String>> = DEFAULT_EXTERNAL_SUPERTYPES,
    /** false 면 EconomyProvider 는 규칙을 통과해도 CANDIDATE 로만 낸다 (설정 선택형 제공자 오탐 때문). */
    val storeEconomyProviders: Boolean = false,
) {
    /** "이 이름의 플러그인이 이 패키지 아래 클래스를 **정의**하면 이 Capability". */
    public data class Identity(
        val pluginName: String,
        /** 내부 이름 접두사 (`com/nexomc/nexo/`). */
        val definedPackagePrefix: String,
        val capability: Capability,
    )

    public companion object {
        /** Vault / VaultUnlocked 서비스 인터페이스(내부 이름) → Capability. Chat 은 Capability 가 아니라 제외. */
        public val DEFAULT_SERVICE_TYPES: Map<String, Capability> = mapOf(
            "net/milkbowl/vault/economy/Economy" to Capability.EconomyProvider,
            "net/milkbowl/vault/permission/Permission" to Capability.PermissionProvider,
            "net/milkbowl/vault2/economy/Economy" to Capability.EconomyProvider,
            "net/milkbowl/vault2/permission/Permission" to Capability.PermissionProvider,
            "net/milkbowl/vault2/permission/PermissionUnlocked" to Capability.PermissionProvider,
        )

        /** VaultAPI `AbstractEconomy implements Economy` (MilkBowl/VaultAPI master 소스로 확인, 2026-09-17). */
        public val DEFAULT_EXTERNAL_SUPERTYPES: Map<String, Set<String>> = mapOf(
            "net/milkbowl/vault/economy/AbstractEconomy" to setOf("net/milkbowl/vault/economy/Economy"),
        )
    }
}

/**
 * jar 에서 Capability 를 추론한다 (서비스 등록 호출 + 정체성 규칙). ChunkGenerator·AntiCheat 는 추론하지 않는다.
 *
 * 모듈 내부 전용 — 예외 없는 공개 경계는 [analyzeJar] 다 (CLAUDE.md: 라이브러리 모듈 throw 최소화).
 *
 * @param meta primary descriptor. 없으면 IDENTITY 규칙은 적용되지 않고 SERVICE_PROVIDER 도 CANDIDATE 로 낮춘다 (C7).
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때.
 */
internal fun inferCapabilities(jar: Path, meta: JarMeta?, rules: CapabilityRules): List<CapabilityEvidence> = TODO("WP-JA")
