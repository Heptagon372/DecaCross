package kr.decacross.analysis

import kr.decacross.compat.model.Capability
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.zip.ZipFile

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

        /**
         * 어떤 규칙으로도 추론하지 않는 Capability. 청크 생성기·안티치트는 정적 신호로 판단하지 않는다 (설계 §7.6, D34).
         * 규칙 데이터를 만드는 쪽(수집기 로더)은 이 Capability 를 가리키는 규칙을 기동 시 거부해야 한다.
         */
        public val NEVER_INFERRED: Set<Capability> = setOf(Capability.ChunkGenerator, Capability.AntiCheat)

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
internal fun inferCapabilities(jar: Path, meta: JarMeta?, rules: CapabilityRules): List<CapabilityEvidence> =
    withZipFile(jar) { zip -> inferCapabilities(zip, meta, rules, ArrayList()) }

// ── 구현 (설계 §7.6) ──────────────────────────────────────────────────────

/** C3 를 만족하는 우선순위. `Lowest` 는 대체(fallback) 제공자라 제외한다. */
private val PROVIDER_PRIORITIES: Set<String> = setOf("Low", "Normal", "High", "Highest")

/** C4: jar 가 이 패키지 아래 class 를 정의하면 Vault API 자체(또는 셰이드)를 싣고 있는 것이다 (설계 AE-6). */
private val VAULT_PACKAGE_PREFIXES: List<String> = listOf("net/milkbowl/vault/", "net/milkbowl/vault2/")

private const val SERVICES_MANAGER: String = "org/bukkit/plugin/ServicesManager"

/** 상속 계층 탐색 깊이 상한. */
private const val HIERARCHY_DEPTH_MAX: Int = 20

/** pass 1 에서 읽은 jar 정의 타입 하나. */
private class DefinedType(val superName: String?, val interfaces: List<String>, val isInterface: Boolean)

/**
 * 이미 열린 zip 에서 Capability 를 추론한다. 판단 근거가 되지 못한 사정(ASM 실패, 이름만 일치 등)은 [notes] 에 남긴다.
 *
 * @throws java.io.IOException 항목 데이터가 손상됐을 때.
 */
internal fun inferCapabilities(
    zip: ZipFile,
    meta: JarMeta?,
    rules: CapabilityRules,
    notes: MutableList<String>,
): List<CapabilityEvidence> {
    // pass 1 — 헤더만: 정의 타입·계층·ServicesManager 참조 후보 (외부 + 1단계 중첩, META-INF 제외)
    val hierarchy = HashMap<String, DefinedType>()
    val candidates = ArrayList<Pair<String, ByteArray>>()
    // 크기 상한 note 는 같은 순회 규칙을 쓰는 BytecodeProfile.notes 에 이미 남으므로 여기서는 버린다
    ZipWalker(zip, ArrayList()).forEachClass(
        selectOuter = ::isCapabilityScanClass,
        selectNested = ::isCapabilityScanClass,
        onClass = { archive, name, bytes ->
            val header = readClassHeader(bytes)
            if (header != null) {
                hierarchy[header.thisName] = DefinedType(
                    superName = header.superName,
                    interfaces = header.interfaces,
                    isInterface = header.access and Opcodes.ACC_INTERFACE != 0,
                )
                // 후보만 바이트를 남긴다. 나머지는 헤더만 쓰고 버린다
                if (header.utf8.any { it in SERVICES_MANAGER_OWNERS }) {
                    candidates += (if (archive == null) name else "$archive!/$name") to bytes
                }
            }
        },
    )

    // pass 2 — 후보만 ASM tree + Analyzer<SourceValue>
    val scan = ServiceCallScan()
    for ((location, bytes) in candidates) scanServiceCalls(location, bytes, scan, notes)

    val evidence = ArrayList<CapabilityEvidence>()
    for ((serviceType, capability) in rules.serviceTypes) {
        serviceEvidence(serviceType, capability, scan, hierarchy, meta, rules)?.let { evidence += it }
    }
    evidence += identityEvidence(hierarchy.keys, meta, rules, notes)
    // 규칙 데이터(수집기 리소스)가 잘못돼도 추론 금지 Capability 는 내지 않는다 (설계 §7.6 "Never emitted", D34)
    val (forbidden, allowed) = evidence.partition { it.capability in CapabilityRules.NEVER_INFERRED }
    for (capability in forbidden.map { it.capability }.distinct()) notes += "추론 금지 Capability 규칙 무시: $capability"
    return mergePerCapability(allowed)
}

private fun isCapabilityScanClass(entryName: String): Boolean =
    !entryName.startsWith("META-INF/") && entryName.substringAfterLast('/') != "module-info.class"

/** 서비스 타입 하나에 대한 규칙 C1~C7. C1 이 거짓이면 근거 자체가 없다. */
private fun serviceEvidence(
    serviceType: String,
    capability: Capability,
    scan: ServiceCallScan,
    hierarchy: Map<String, DefinedType>,
    meta: JarMeta?,
    rules: CapabilityRules,
): CapabilityEvidence? {
    val calls = scan.registerCalls.filter { it.service == serviceType }
    // C1
    if (calls.isEmpty()) return null

    val failed = ArrayList<String>()
    // C2: 구현체가 jar 정의 class 이고 서비스 타입을 구현한다
    val implementing = calls.filter { call ->
        val impl = call.impl
        impl != null && hierarchy[impl]?.isInterface == false && implementsService(impl, serviceType, hierarchy, rules)
    }
    if (implementing.isEmpty()) failed += "C2"
    // C3: 그 호출의 우선순위가 Low..Highest (Lowest = fallback, null = 설정 의존)
    val chosen = implementing.firstOrNull { it.priority in PROVIDER_PRIORITIES }
    if (implementing.isNotEmpty() && chosen == null) failed += "C3"
    // C4: 서비스 API 자체를 싣고 있지 않다
    val definesApi = serviceType in hierarchy ||
        SERVICES_MANAGER in hierarchy ||
        hierarchy.keys.any { name -> VAULT_PACKAGE_PREFIXES.any { name.startsWith(it) } }
    if (definesApi) failed += "C4"
    // C5: 같은 서비스를 소비하지 않는다 (브리지·어댑터 플러그인 배제)
    if (serviceType in scan.consumed) failed += "C5"
    // C6: 모든 구현체가 추적되고, 서로 다른 구현체가 1개 이하
    if (calls.any { it.impl == null } || calls.mapNotNull { it.impl }.distinct().size > 1) failed += "C6"
    // C7: descriptor 가 있다
    if (meta == null) failed += "C7"

    val call = chosen
    if (failed.isNotEmpty() || call == null) {
        val sample = calls.first()
        return CapabilityEvidence(
            capability = capability,
            confidence = EvidenceConfidence.CANDIDATE,
            rule = "SERVICE_PROVIDER",
            detail = "failed: ${failed.joinToString(",")} — $serviceType by ${sample.impl ?: "?"} at ${sample.where} priority ${sample.priority ?: "?"}",
        )
    }
    // 설정 선택형 경제 제공자 오탐 때문에 기본은 CANDIDATE (설계 D33 / SCP-14)
    val confidence = if (capability == Capability.EconomyProvider && !rules.storeEconomyProviders) {
        EvidenceConfidence.CANDIDATE
    } else {
        EvidenceConfidence.STORE
    }
    return CapabilityEvidence(
        capability = capability,
        confidence = confidence,
        rule = "SERVICE_PROVIDER",
        detail = "$serviceType by ${call.impl} at ${call.where} priority ${call.priority}",
    )
}

/**
 * [start] 가 [serviceType] 을 구현하는가. jar 정의 타입을 따라 super·interfaces 로 올라간다 (깊이 ≤ 20).
 * 외부 타입에 닿으면 멈추되, 그 타입이 [serviceType] 자체이거나 [CapabilityRules.externalSupertypes] 에 알려진 조상이면 참.
 */
private fun implementsService(
    start: String,
    serviceType: String,
    hierarchy: Map<String, DefinedType>,
    rules: CapabilityRules,
): Boolean {
    val visited = HashSet<String>()
    fun walk(type: String?, depth: Int): Boolean {
        if (type == null || depth > HIERARCHY_DEPTH_MAX || !visited.add(type)) return false
        if (type == serviceType) return true
        if (rules.externalSupertypes[type]?.contains(serviceType) == true) return true
        val defined = hierarchy[type] ?: return false
        return defined.interfaces.any { walk(it, depth + 1) } || walk(defined.superName, depth + 1)
    }
    return walk(start, 0)
}

/** 정체성 규칙: 이름이 정확히 같고 그 패키지 아래 class 를 **정의**할 때만 STORE. */
private fun identityEvidence(
    defined: Set<String>,
    meta: JarMeta?,
    rules: CapabilityRules,
    notes: MutableList<String>,
): List<CapabilityEvidence> {
    val name = meta?.name ?: return emptyList()
    val out = ArrayList<CapabilityEvidence>()
    for (identity in rules.identities) {
        if (identity.pluginName != name) continue
        if (defined.none { it.startsWith(identity.definedPackagePrefix) }) {
            notes += "이름 일치하나 패키지 미정의: $name"
            continue
        }
        out += CapabilityEvidence(
            capability = identity.capability,
            confidence = EvidenceConfidence.STORE,
            rule = "IDENTITY",
            detail = "plugin '$name' defines ${identity.definedPackagePrefix}",
        )
    }
    return out
}

/** Capability 당 근거 하나. STORE 가 CANDIDATE 를 이기고, 같은 확신도면 먼저 나온 것을 남긴다. */
private fun mergePerCapability(evidence: List<CapabilityEvidence>): List<CapabilityEvidence> {
    val merged = LinkedHashMap<Capability, CapabilityEvidence>()
    for (item in evidence) {
        val existing = merged[item.capability]
        val upgrades = existing?.confidence == EvidenceConfidence.CANDIDATE && item.confidence == EvidenceConfidence.STORE
        if (existing == null || upgrades) merged[item.capability] = item
    }
    return merged.values.toList()
}
