package kr.decacross.analysis

import kr.decacross.compat.model.Capability
import java.nio.file.Path

// ── Capability 추론 ────────────────────────────────────────────────────────
// 배타 제약(같은 Capability 제공자는 하나만)을 위한 PROVIDES 를 jar 에서 읽어낸다.
// ★ 애매하면 저장하지 않는다. 잘못된 PROVIDES 는 해결(resolve)을 망친다 — notes 에만 남긴다.

/**
 * 추론 결과. [caps] 는 확신하는 것만, [notes] 는 근거와 "애매해서 뺀 것".
 *
 * # 불변식
 * - [caps] 에 들어간 항목은 전부 [notes] 에 근거 한 줄이 있다 (왜 그렇게 판정했는지 추적 가능).
 * - 이름·패키지 휴리스틱과 바이트코드 증거가 충돌하면 바이트코드가 이긴다.
 */
public data class CapabilityInference(
    val caps: Set<Capability>,
    val notes: List<String>,
)

/** 이름 정규화: 소문자, 영숫자만. `Multiverse-Core` → `multiversecore`. */
private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

private val CUSTOM_ITEM_NAMES = setOf("itemsadder", "oraxen", "nexo", "modelengine")
private val CUSTOM_ITEM_PACKAGES = listOf("dev/lone/itemsadder", "io/th0rgal/oraxen", "com/nexomc/nexo", "com/ticxo/modelengine")
private val PERMISSION_NAMES = setOf("luckperms", "permissionsex", "groupmanager", "essentialsgroupmanager")
private val ANTI_CHEAT_NAMES = setOf("vulcan", "matrix", "matrixanticheat", "spartan", "grim", "grimac", "grimanticheat", "nocheatplus", "ncp", "aac", "advancedanticheat")
private val WORLD_GEN_NAMES = setOf("terra", "terraformgenerator", "iris", "epicworldgenerator", "ewg")

private const val SERVICES_MANAGER = "org/bukkit/plugin/ServicesManager"
private const val VAULT_ECONOMY = "net/milkbowl/vault/economy/Economy"
private const val VAULT_PERMISSION = "net/milkbowl/vault/permission/Permission"
private const val VAULT_CHAT = "net/milkbowl/vault/chat/Chat"

/**
 * jar 의 이름·메인 패키지·바이트코드에서 [Capability] 를 추론한다.
 *
 * 규칙:
 * - ItemsAdder / Oraxen / Nexo / ModelEngine (이름 또는 메인 클래스 패키지) → [Capability.CustomItemFramework]
 * - `ServicesManager.register(Economy.class, …)` 호출이 보이면 → [Capability.EconomyProvider],
 *   `Permission.class` 면 → [Capability.PermissionProvider] (같은 메서드 안에 `LDC Type` 과 `INVOKE*` 가 함께 있어야 한다)
 * - LuckPerms / PermissionsEx / GroupManager (이름) → [Capability.PermissionProvider]
 * - Vulcan / Matrix / Spartan / Grim / NoCheatPlus / AAC (이름) → [Capability.AntiCheat]
 * - Terra / TerraformGenerator / Iris / EpicWorldGenerator (이름) → [Capability.ChunkGenerator]
 * - 이름이 암시만 하는 경우(`…Economy`, `…AntiCheat` 등)는 아무것도 넣지 않고 note 만 남긴다.
 *
 * [meta] 를 생략하면 jar 에서 읽는다. jar 를 열 수 없으면 빈 결과 + note.
 */
public fun inferCapabilities(jar: Path, meta: JarMeta? = readJarMeta(jar)): CapabilityInference {
    val caps = LinkedHashSet<Capability>()
    val notes = ArrayList<String>()
    val name = meta?.name?.let(::norm) ?: ""
    val mainPkg = meta?.main?.replace('.', '/')?.lowercase() ?: ""

    // 1) 이름·패키지 규칙 (정확 일치만 — 부분 일치는 note)
    if (name in CUSTOM_ITEM_NAMES || CUSTOM_ITEM_PACKAGES.any { mainPkg.startsWith(it) }) {
        caps += Capability.CustomItemFramework
        notes += "CustomItemFramework: 이름/패키지 일치 (${meta?.name} / ${meta?.main})"
    }
    if (name in PERMISSION_NAMES) {
        caps += Capability.PermissionProvider
        notes += "PermissionProvider: 이름 일치 (${meta?.name})"
    }
    if (name in ANTI_CHEAT_NAMES) {
        caps += Capability.AntiCheat
        notes += "AntiCheat: 이름 일치 (${meta?.name})"
    }
    if (name in WORLD_GEN_NAMES) {
        caps += Capability.ChunkGenerator
        notes += "ChunkGenerator: 이름 일치 (${meta?.name})"
    }

    // 2) 바이트코드 증거: Vault 서비스 등록
    val scan = runCatching { JarIndexCache.pluginScan(jar) }.getOrNull()
    if (scan == null) {
        notes += "jar 를 스캔하지 못함 — 바이트코드 규칙 생략"
    } else {
        val registeredTypes = vaultRegistrations(scan)
        if (VAULT_ECONOMY in registeredTypes) {
            caps += Capability.EconomyProvider
            notes += "EconomyProvider: ServicesManager.register(Economy) 호출 발견"
        }
        if (VAULT_PERMISSION in registeredTypes) {
            caps += Capability.PermissionProvider
            notes += "PermissionProvider: ServicesManager.register(Permission) 호출 발견"
        }
        if (VAULT_CHAT in registeredTypes) notes += "Vault Chat 등록 발견 — 대응 Capability 없음, 기록만"
        registeredTypes.filter { it != VAULT_ECONOMY && it != VAULT_PERMISSION && it != VAULT_CHAT }
            .forEach { notes += "ServicesManager.register($it) — 알려진 Capability 아님" }
    }

    // 3) 애매한 것 — 저장하지 않고 note 만
    if (name == "vault") notes += "Vault 자체는 API 다 — 제공자가 아니므로 Capability 없음"
    if (Capability.EconomyProvider !in caps && (name.contains("economy") || name.contains("money") || name.endsWith("eco"))) {
        notes += "이름이 경제 플러그인을 암시하지만 Vault Economy 등록이 보이지 않음 — 저장 안 함"
    }
    if (Capability.AntiCheat !in caps && (name.contains("anticheat") || name.contains("antihack"))) {
        notes += "이름이 안티치트를 암시하지만 목록에 없음 — 저장 안 함"
    }
    if (Capability.ChunkGenerator !in caps && (name.contains("worldgen") || name.contains("generator"))) {
        notes += "이름이 월드 생성기를 암시하지만 목록에 없음 — 저장 안 함"
    }
    if (Capability.PermissionProvider !in caps && Capability.AntiCheat !in caps && name.contains("perm")) {
        notes += "이름이 권한 플러그인을 암시하지만 목록·등록 호출 모두 없음 — 저장 안 함"
    }
    return CapabilityInference(caps, notes)
}

/**
 * `ServicesManager.register` 를 호출하는 메서드 안의 `LDC Type` 상수 집합. Bukkit 의 register 시그니처는
 * `register(Class<T>, T, Plugin, ServicePriority)` 라 서비스 클래스가 항상 `LDC Type` 으로 스택에 올라온다.
 */
private fun vaultRegistrations(scan: PluginScan): Set<String> {
    val out = LinkedHashSet<String>()
    for (cls in scan.classes.values) {
        val byMethod = cls.refs.groupBy { it.from }
        for ((_, refs) in byMethod) {
            val registers = refs.any { it is MethodRef && it.owner == SERVICES_MANAGER && it.name == "register" }
            if (!registers) continue
            refs.filter { it.context == RefContext.LDC_TYPE }.forEach { out += it.owner }
        }
    }
    return out
}
