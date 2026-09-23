package kr.decacross.dcx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `.dcx` v1.1 레시피 (설계서 §7). 필드는 `dcx-1.1.schema.json` 과 1:1 — 스키마가 단일 진실 소스다.
 * TS 타입은 스키마에서 생성한다(web/src/types/dcx.ts). 손으로 두 곳을 맞추지 마라.
 *
 * # 불변식
 * - `dcx` 필드는 필수. 하위호환이 깨질 때 마이그레이션의 근거다 (설계서 §16).
 * - 재배포 권한이 없는 파일은 원본 URL + sha256 참조만 담는다. 파일을 동봉하지 않는다.
 */
@Serializable
public data class DcxRecipe(
    val dcx: String,
    val id: String,
    val name: String,
    val description: String? = null,
    val author: DcxAuthor? = null,
    val license: String? = null,
    val target: DcxTarget,
    val runtime: DcxRuntime? = null,
    val variables: List<DcxVariable> = emptyList(),
    val content: List<DcxContent> = emptyList(),
    val config: DcxConfig? = null,
    val network: DcxNetwork? = null,
    val compat: DcxCompat? = null,
    val signature: DcxSignature? = null,
)

@Serializable
public data class DcxAuthor(val handle: String, val verified: Boolean = false)

@Serializable
public data class DcxTarget(val minecraft: String, val core: DcxCore, val java: DcxJava? = null)

@Serializable
public data class DcxCore(val type: String, val build: String = "latest-stable")

@Serializable
public data class DcxJava(val feature: Int? = null, val distribution: String = "temurin", val image: String = "jre")

@Serializable
public data class DcxRuntime(
    val memory: DcxMemory? = null,
    val flags: String = "aikar",
    @SerialName("extra_args") val extraArgs: List<String> = emptyList(),
)

@Serializable
public data class DcxMemory(val min: String? = null, val max: String? = null, val auto: Boolean = true)

@Serializable
public data class DcxVariable(
    val key: String,
    val label: String,
    val type: String = "string",
    val default: JsonPrimitive? = null,
) {
    public val defaultText: String? get() = default?.contentOrNull
}

@Serializable
public data class DcxContent(
    val kind: String,
    val source: String? = null,
    val slug: String? = null,
    val version: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val license: String? = null,
    val trust: String? = null,
    @SerialName("when") val whenCond: String? = null,
    val path: String? = null,
    val bundled: Boolean? = null,
    val file: String? = null,
    @SerialName("pack_format") val packFormat: String? = null,
    val serve: String? = null,
    val target: String? = null,
) {
    /** 공식 저장소 밖(URL 직링크) 파일인가 — 설치 다이얼로그에서 도메인과 함께 명시 경고. */
    public val isExternal: Boolean get() = source == "url" || trust == "external"
}

@Serializable
public data class DcxConfig(
    @SerialName("server.properties") val serverProperties: Map<String, JsonPrimitive> = emptyMap(),
    val files: List<DcxFile> = emptyList(),
)

@Serializable
public data class DcxFile(val path: String, val bundled: Boolean? = null, val patch: JsonObject? = null)

@Serializable
public data class DcxNetwork(val expose: String = "tunnel", val port: Int = 25565)

@Serializable
public data class DcxCompat(
    @SerialName("resolved_at") val resolvedAt: String? = null,
    val engine: String? = null,
    val status: String? = null,
    @SerialName("verified_installs") val verifiedInstalls: Int? = null,
)

@Serializable
public data class DcxSignature(val alg: String, @SerialName("key_id") val keyId: String, val value: String)

public sealed interface DcxParseResult {
    public data class Ok(val recipe: DcxRecipe, val warningsKo: List<String> = emptyList()) : DcxParseResult

    public data class Invalid(val errorsKo: List<String>) : DcxParseResult
}

public val DcxJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}

private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
private val VAR_KEY_RE = Regex("^[A-Z][A-Z0-9_]{0,31}$")
private val SHA_RE = Regex("^[0-9a-f]{64}$")
private val MEM_RE = Regex("^[0-9]+[GgMm]$")
private val CORES = setOf("paper", "purpur", "folia", "spigot", "vanilla", "fabric", "neoforge", "forge")
private val KINDS = setOf("plugin", "mod", "script", "resourcepack", "datapack", "world", "modelpack")
private val SOURCES = setOf("modrinth", "hangar", "spigot", "url", "bundled")

/** JSON 텍스트 → 검증된 레시피. 스키마의 제약을 코드로 재검증한다 (KMP 에는 JSON Schema 검증기가 없다). */
public fun parseDcx(json: String): DcxParseResult {
    val recipe = try {
        DcxJson.decodeFromString(DcxRecipe.serializer(), json)
    } catch (e: Exception) {
        return DcxParseResult.Invalid(listOf("JSON 구조가 스키마와 맞지 않습니다: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}"))
    }
    return validateDcx(recipe)
}

public fun validateDcx(r: DcxRecipe): DcxParseResult {
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()
    if (r.dcx != "1.1") errors += "지원하지 않는 dcx 버전: '${r.dcx}' (1.1 필요)"
    if (!ID_RE.matches(r.id)) errors += "id 는 소문자·숫자·하이픈 2~64자: '${r.id}'"
    if (r.name.isBlank() || r.name.length > 80) errors += "name 은 1~80자"
    if (r.target.minecraft.isBlank()) errors += "target.minecraft 가 비어 있음"
    if (r.target.core.type !in CORES) errors += "target.core.type 이 잘못됨: '${r.target.core.type}'"
    r.target.java?.feature?.let { if (it < 8) errors += "target.java.feature 는 8 이상" }
    r.runtime?.memory?.let { m ->
        listOfNotNull(m.min, m.max).forEach { if (!MEM_RE.matches(it)) errors += "runtime.memory 형식 오류: '$it' (예: 4G, 2048M)" }
    }
    val varKeys = HashSet<String>()
    for (v in r.variables) {
        if (!VAR_KEY_RE.matches(v.key)) errors += "variables.key 형식 오류: '${v.key}' (대문자·숫자·_)"
        if (!varKeys.add(v.key)) errors += "variables.key 중복: '${v.key}'"
        if (v.type !in setOf("string", "number", "bool")) errors += "variables.type 오류: '${v.type}'"
    }
    r.content.forEachIndexed { i, c ->
        val at = "content[$i]"
        if (c.kind !in KINDS) errors += "$at.kind 오류: '${c.kind}'"
        c.source?.let { if (it !in SOURCES) errors += "$at.source 오류: '$it'" }
        when (c.source) {
            "modrinth", "hangar", "spigot" -> if (c.slug.isNullOrBlank()) errors += "$at: source=${c.source} 는 slug 필수"

            "url" -> {
                if (c.url.isNullOrBlank()) errors += "$at: source=url 은 url 필수"
                if (c.sha256 == null) errors += "$at: source=url 은 sha256 필수 (변조 방지)"
            }

            "bundled" -> if (c.file.isNullOrBlank() && c.path.isNullOrBlank()) errors += "$at: source=bundled 는 file 또는 path 필수"

            null -> if (c.bundled != true && c.path.isNullOrBlank()) errors += "$at: source 가 없으면 bundled path 여야 함"
        }
        c.sha256?.let { if (!SHA_RE.matches(it)) errors += "$at.sha256 형식 오류 (소문자 hex 64자)" }
        c.whenCond?.let { if (parseWhen(it) == null) errors += "$at.when 파싱 불가: '$it' (형식: KEY == value)" }
        if (c.source == "url" && c.trust != "external") warnings += "$at: URL 직링크는 trust=external 로 표시됩니다"
        if (c.source == "spigot") warnings += "$at: SpigotMC 는 자동 다운로드가 불가능해 사용자 수동 업로드가 필요합니다"
    }
    r.network?.let { n ->
        if (n.expose !in setOf("tunnel", "port", "local")) errors += "network.expose 오류: '${n.expose}'"
        if (n.port !in 1024..65535) errors += "network.port 범위 오류: ${n.port}"
    }
    r.signature?.let { if (it.alg != "ed25519") errors += "signature.alg 는 ed25519 만 지원" }
    // 템플릿 참조 검사: {{KEY}} 가 선언된 변수인가
    val declared = r.variables.map { it.key }.toSet()
    r.config?.serverProperties?.values?.forEach { p ->
        TEMPLATE_RE.findAll(p.contentOrNull.orEmpty()).forEach { m -> if (m.groupValues[1] !in declared) errors += "선언되지 않은 변수 참조: {{${m.groupValues[1]}}}" }
    }
    r.content.forEach { c -> c.whenCond?.let { parseWhen(it) }?.let { if (it.key !in declared) errors += "when 의 변수가 선언되지 않음: ${it.key}" } }
    return if (errors.isEmpty()) DcxParseResult.Ok(r, warnings) else DcxParseResult.Invalid(errors)
}

public fun DcxRecipe.toJson(): String = DcxJson.encodeToString(DcxRecipe.serializer(), this)

private val TEMPLATE_RE = Regex("\\{\\{\\s*([A-Z][A-Z0-9_]*)\\s*}}")
private val WHEN_RE = Regex("^\\s*([A-Z][A-Z0-9_]*)\\s*(==|!=)\\s*(.+?)\\s*$")

/** `KEY == value` / `KEY != value`. */
public data class WhenCond(val key: String, val equals: Boolean, val value: String)

public fun parseWhen(expr: String): WhenCond? =
    WHEN_RE.matchEntire(expr)?.let { m -> WhenCond(m.groupValues[1], m.groupValues[2] == "==", m.groupValues[3].trim('"', '\'')) }

/** 설치 시점에 변수를 확정한 결과: 조건이 맞는 콘텐츠 + 템플릿이 치환된 server.properties. */
public data class DcxEvaluated(
    val vars: Map<String, String>,
    val content: List<DcxContent>,
    val serverProperties: Map<String, String>,
)

/**
 * 변수 값을 적용한다. 주어지지 않은 변수는 기본값, 그것도 없으면 빈 문자열.
 * `when` 비교는 문자열 비교이되 bool 은 true/false 로 정규화한다.
 */
public fun DcxRecipe.evaluate(given: Map<String, String> = emptyMap()): DcxEvaluated {
    val vars = LinkedHashMap<String, String>()
    for (v in variables) {
        val raw = given[v.key] ?: v.defaultText ?: ""
        vars[v.key] = if (v.type == "bool") raw.trim().lowercase().let { it == "true" || it == "1" || it == "yes" }.toString() else raw
    }
    val chosen = content.filter { c ->
        val w = c.whenCond?.let(::parseWhen) ?: return@filter true
        val actual = vars[w.key] ?: ""
        val expected = if (variables.firstOrNull { it.key == w.key }?.type == "bool") w.value.lowercase().let { (it == "true" || it == "1" || it == "yes").toString() } else w.value
        if (w.equals) actual == expected else actual != expected
    }
    val props = config?.serverProperties.orEmpty().mapValues { (_, p) ->
        val text = p.contentOrNull ?: p.toString()
        TEMPLATE_RE.replace(text) { m -> vars[m.groupValues[1]] ?: "" }
    }
    return DcxEvaluated(vars, chosen, props)
}

/** 설치 다이얼로그용 요약 (설계서 §6.2). 외부 URL 도메인은 반드시 보여준다. */
@Serializable
public data class DcxSummary(
    val name: String,
    val authorHandle: String?,
    val authorVerified: Boolean,
    val mc: String,
    val core: String,
    val pluginCount: Int,
    val packCount: Int,
    val worldCount: Int,
    val externalUrls: List<String>,
)

public fun DcxRecipe.summary(): DcxSummary =
    DcxSummary(
        name = name,
        authorHandle = author?.handle,
        authorVerified = author?.verified ?: false,
        mc = target.minecraft,
        core = target.core.type,
        pluginCount = content.count { it.kind == "plugin" || it.kind == "mod" },
        packCount = content.count { it.kind == "resourcepack" || it.kind == "datapack" || it.kind == "modelpack" },
        worldCount = content.count { it.kind == "world" },
        externalUrls = content.filter { it.isExternal }.mapNotNull { it.url },
    )

/** JsonPrimitive 값을 문자열로 (server.properties 용). */
public fun JsonElement.asPropertyString(): String = (this as? JsonPrimitive)?.let { it.booleanOrNull?.toString() ?: it.contentOrNull } ?: toString()
