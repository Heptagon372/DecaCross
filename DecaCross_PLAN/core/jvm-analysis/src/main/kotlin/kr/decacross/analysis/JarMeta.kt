package kr.decacross.analysis

import java.nio.file.Path

// ── jar descriptor 메타데이터 ─────────────────────────────────────────────
// plugin.yml / paper-plugin.yml / fabric.mod.json / (neoforge.)mods.toml.
// Bukkit·Paper 로더의 실제 시맨틱을 재현한다. 추측 금지 — 근거는 docs 설계(WP-JA 표)와 Paper 소스.

/**
 * descriptor 하나에서 읽은 메타데이터의 정규화 뷰.
 *
 * # 불변식
 * - [depend]/[softDepend]/[loadBefore]/[provides]/[loadAfter] 항목은 **플러그인 이름**이다 (공백 → `_`).
 *   Modrinth/Hangar slug 가 아니다 (예: EssentialsX 의 이름은 `Essentials`, VaultUnlocked 의 이름은 `Vault`).
 * - [depend]/[softDepend]/[loadBefore]/[provides] 는 Bukkit 처럼 선언 순서·중복을 그대로 보존한다. 중복 제거는 [deps] 만 한다.
 * - [loadErrors] 가 비어 있지 않으면 해당 로더는 이 플러그인을 로드하지 않는다.
 * - [deps] 는 descriptor 종류와 무관한 정규화 의존성 목록이다. `loadbefore` 는 의존성이 아니므로 들어가지 않는다.
 */
public data class JarMeta(
    /** 정규화된 이름 (Bukkit: 공백→`_`). Fabric/Forge 는 modId. */
    val name: String,
    /** 로더가 보는 문자열 (YAML 스칼라를 Bukkit 규칙으로 해석한 뒤 toString). */
    val version: String?,
    /**
     * `api-version` 원문 해석값. plugin.yml 은 float 미해석("1.20"), paper-plugin.yml 은 float 해석("1.2").
     * ★ 로더 계약 문자열이다. MC 버전 비교(McOrdinal, 불변식 1)에 쓰지 마라.
     */
    val apiVersion: String?,
    val main: String?,
    val depend: List<String> = emptyList(),
    val softDepend: List<String> = emptyList(),
    val loadBefore: List<String> = emptyList(),
    /**
     * Maven 좌표 원문 (`g:a:v`). Paper 는 첫 기동 때 네트워크로 받는다.
     * `paper-skip-libraries: true` 여도 비우지 않는다 (Paper 는 비운다) — 대신 [skipLibrariesOnPaper] 로 표시한다.
     */
    val libraries: List<String> = emptyList(),
    val descriptor: Descriptor,
    /** 공백 치환 전 원문 이름. */
    val rawName: String = name,
    /** YAML 원문 스칼라 텍스트 (8진수·Date 등 변환 전). */
    val rawVersion: String? = version,
    /** 다른 플러그인의 depend/softdepend 를 대신 만족시키는 별칭. */
    val provides: List<String> = emptyList(),
    /** 이 플러그인보다 먼저 로드되어야 하는 대상 (paper-plugin.yml `load: BEFORE`). 의존성은 아니다. */
    val loadAfter: List<String> = emptyList(),
    /** `STARTUP` | `POSTWORLD`. Bukkit 기본값은 `POSTWORLD`. */
    val load: String? = null,
    /** Folia 는 이 값이 true 가 아니면 로드를 거부한다. */
    val foliaSupported: Boolean = false,
    /** `paper-skip-libraries: true` — Paper 에서만 [libraries] 를 무시한다 (Spigot 은 여전히 받는다). */
    val skipLibrariesOnPaper: Boolean = false,
    val deps: List<MetaDep> = emptyList(),
    /** fabric.mod.json `jars[].file` (Jar-in-Jar). */
    val nestedJars: List<String> = emptyList(),
    /** 로더가 로드를 거부할 사유 (예: "depend is of wrong type"). 비어 있어야 설치 가능. */
    val loadErrors: List<String> = emptyList(),
    /** 로드는 되지만 주의할 점 (예: api-version 미지정 → 레거시 모드). */
    val warnings: List<String> = emptyList(),
) {
    /** descriptor 파일 종류. */
    public enum class Descriptor { PLUGIN_YML, PAPER_PLUGIN_YML, FABRIC_MOD_JSON, MODS_TOML, NEOFORGE_MODS_TOML }
}

/** 정규화 의존성 종류. 순서(loadbefore 등)는 의존성이 아니다. */
public enum class MetaDepKind { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED }

/** 대상과의 로드 순서 관계. */
public enum class LoadOrder { BEFORE, AFTER, NONE }

/** paper-plugin.yml 의존성 단계. */
public enum class DepPhase { BOOTSTRAP, SERVER }

/** Forge/NeoForge 의존성 적용 측. 서버 해석에서는 CLIENT 를 제외한다. */
public enum class DepSide { BOTH, CLIENT, SERVER }

/**
 * descriptor 에 선언된 의존성 하나.
 *
 * # 불변식
 * - [target] 은 플러그인 이름 / modId 다. slug 가 아니다.
 */
public data class MetaDep(
    val target: String,
    val kind: MetaDepKind,
    /** null = 버전 제약 없음(Bukkit/Paper). Fabric 은 OR 목록, Forge/NeoForge 는 Maven range 1개. */
    val versionPredicates: List<String>? = null,
    val order: LoadOrder = LoadOrder.NONE,
    val phase: DepPhase = DepPhase.SERVER,
    val side: DepSide = DepSide.BOTH,
)

/** 해석 자체가 불가능한 descriptor (루트가 맵이 아님, YAML/JSON 문법 오류, name 없음 등). */
public data class InvalidDescriptor(val descriptor: JarMeta.Descriptor, val reason: String)

/**
 * jar 하나에 공존하는 descriptor 전부.
 *
 * # 불변식
 * - [all] 은 Paper 기준 우선순위 순서다: paper-plugin.yml > plugin.yml > fabric.mod.json > neoforge.mods.toml > mods.toml.
 * - [invalid] 에는 **존재했지만** 해석하지 못한 descriptor 가 들어간다 (없는 파일은 어디에도 없다).
 */
public data class JarDescriptors(
    val all: List<JarMeta>,
    val invalid: List<InvalidDescriptor> = emptyList(),
) {
    /**
     * 로더가 실제로 고르는 descriptor. 우선순위가 가장 높은 **존재하는** descriptor 가 해석 불가([invalid])면 null —
     * Paper `PluginFileType.guessType` 은 파일 존재로 종류를 고르고, 깨졌다고 plugin.yml 로 되돌아가지 않는다.
     * 존재하는 descriptor 가 하나도 없으면 null.
     */
    public val primary: JarMeta?
        get() {
            for (kind in PRIORITY) {
                all.firstOrNull { it.descriptor == kind }?.let { return it }
                if (invalid.any { it.descriptor == kind }) return null
            }
            return null
        }

    private companion object {
        val PRIORITY: List<JarMeta.Descriptor> = listOf(
            JarMeta.Descriptor.PAPER_PLUGIN_YML,
            JarMeta.Descriptor.PLUGIN_YML,
            JarMeta.Descriptor.FABRIC_MOD_JSON,
            JarMeta.Descriptor.NEOFORGE_MODS_TOML,
            JarMeta.Descriptor.MODS_TOML,
        )
    }
}

/** 텍스트 하나를 descriptor 로 해석한 결과. 예외를 던지지 않는다. */
public sealed interface DescriptorParse {
    public data class Parsed(val meta: JarMeta) : DescriptorParse

    public data class Invalid(val problem: InvalidDescriptor) : DescriptorParse
}

/**
 * jar 안의 모든 descriptor 를 읽는다 (루트 `plugin.yml`, `paper-plugin.yml`, `fabric.mod.json`,
 * `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`).
 * 모듈 내부 전용 — 예외 없는 공개 경계는 [analyzeJar] 다 (CLAUDE.md: 라이브러리 모듈 throw 최소화).
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때.
 */
internal fun readJarDescriptors(jar: Path): JarDescriptors = TODO("WP-JA")

/**
 * 명세 §6 시그니처. [JarDescriptors.primary]. descriptor 가 없거나 우선 descriptor 가 깨졌으면 null.
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때. (예외 없는 경계는 [analyzeJar])
 */
public fun readJarMeta(jar: Path): JarMeta? = readJarDescriptors(jar).primary

/** plugin.yml 텍스트 (Bukkit `PluginDescriptionFile` 시맨틱, float 미해석 리졸버). */
public fun parsePluginYml(text: String): DescriptorParse = TODO("WP-JA")

/**
 * paper-plugin.yml 텍스트 (Configurate 시맨틱, 기본 리졸버 — float 해석). 레거시 형식도 받는다.
 * `api-version` 하한 비교(1.19)는 Paper `ApiVersion` 을 흉내 낸 **로더 계약 검사**다 — MC 버전 비교가 아니다 (불변식 1).
 */
public fun parsePaperPluginYml(text: String): DescriptorParse = TODO("WP-JA")

/** fabric.mod.json 텍스트 (schemaVersion 1). */
public fun parseFabricModJson(text: String): DescriptorParse = TODO("WP-JA")
