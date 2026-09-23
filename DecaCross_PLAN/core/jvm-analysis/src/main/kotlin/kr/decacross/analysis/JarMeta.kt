package kr.decacross.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Path
import java.util.jar.JarFile

// ── jar 메타데이터 ─────────────────────────────────────────────────────────
// Bukkit 의 PluginDescriptionFile 시맨틱을 재현한다 (추측 금지 — 아래 각 필드 주석에 근거를 적었다).

/**
 * plugin.yml / paper-plugin.yml / fabric.mod.json / mods.toml 에서 읽은 메타데이터.
 *
 * # 불변식
 * - [name] 은 Bukkit 규칙대로 공백이 `_` 로 치환된 값이다 (PluginDescriptionFile 이 그렇게 정규화한다).
 * - [depend] / [softDepend] / [loadBefore] 는 선언 순서를 보존하고 중복을 제거한 목록이다.
 * - 값이 스칼라 하나로 쓰인 목록 필드(`softdepend: Vault`)도 1개짜리 목록으로 읽는다 — Bukkit 이 관대하게 받는 형태.
 */
public data class JarMeta(
    val name: String,
    val version: String?,
    val apiVersion: String?,
    val main: String?,
    /** 필수 의존. 없으면 플러그인이 로드되지 않는다 (UnknownDependencyException). */
    val depend: List<String> = emptyList(),
    /** 선택 의존. 있으면 먼저 로드되고, 없어도 로드된다. 정적 검증에서 이 플러그인 소속 참조는 경고다. */
    val softDepend: List<String> = emptyList(),
    /** 이 플러그인이 나열된 플러그인들보다 먼저 로드된다. */
    val loadBefore: List<String> = emptyList(),
    /** Bukkit 1.16.5+ `libraries:` — 서버가 Maven Central 에서 내려받아 클래스패스에 넣는 좌표. */
    val libraries: List<String> = emptyList(),
    val descriptor: Descriptor,
    /** `provides:` — 이 플러그인이 대신 제공하는 플러그인 이름들. */
    val provides: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val description: String? = null,
    /** Fabric `depends` 의 버전 범위(`minecraft: ">=1.21"`) 등 — id → 범위 문자열. */
    val dependRanges: Map<String, String> = emptyMap(),
    /** Fabric `breaks`. Bukkit 계열에는 대응 개념이 없다. */
    val breaks: List<String> = emptyList(),
    /** 파싱 중 무시하거나 추정한 부분. 조용히 넘기지 않는다. */
    val notes: List<String> = emptyList(),
) {
    public enum class Descriptor { PLUGIN_YML, PAPER_PLUGIN_YML, FABRIC_MOD_JSON, MODS_TOML }
}

/**
 * jar 안의 디스크립터를 찾아 [JarMeta] 로 읽는다. 우선순위: paper-plugin.yml → plugin.yml → fabric.mod.json → mods.toml.
 * (Paper 는 둘 다 있으면 paper-plugin.yml 을 쓴다.) 디스크립터가 없거나 jar 가 깨졌으면 null.
 */
public fun readJarMeta(jar: Path): JarMeta? {
    val opened = runCatching { JarFile(jar.toFile()) }.getOrNull() ?: return null
    return opened.use { jf ->
        fun text(entry: String): String? = jf.getJarEntry(entry)?.let { e ->
            runCatching { jf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }
        text("paper-plugin.yml")?.let { parsePaperPluginYml(it) }
            ?: text("plugin.yml")?.let { parsePluginYml(it) }
            ?: text("fabric.mod.json")?.let { parseFabricModJson(it) }
            ?: (text("META-INF/neoforge.mods.toml") ?: text("META-INF/mods.toml"))?.let { parseModsToml(it) }
    }
}

// ── plugin.yml ────────────────────────────────────────────────────────────

/** Bukkit `plugin.yml`. 필드 의미는 PluginDescriptionFile 문서를 따른다. 텍스트가 YAML 맵이 아니거나 name 이 없으면 null. */
public fun parsePluginYml(text: String): JarMeta? {
    val root = loadYamlMap(text) ?: return null
    val name = bukkitName(root["name"]) ?: return null
    val notes = ArrayList<String>()
    return JarMeta(
        name = name,
        version = scalar(root["version"]),
        apiVersion = scalar(root["api-version"]),
        main = scalar(root["main"]),
        depend = stringList(root["depend"], "depend", notes),
        softDepend = stringList(root["softdepend"], "softdepend", notes),
        loadBefore = stringList(root["loadbefore"], "loadbefore", notes),
        libraries = stringList(root["libraries"], "libraries", notes),
        descriptor = JarMeta.Descriptor.PLUGIN_YML,
        provides = stringList(root["provides"], "provides", notes),
        authors = authors(root, notes),
        description = scalar(root["description"]),
        notes = notes,
    )
}

/**
 * Paper `paper-plugin.yml`. `dependencies.server` / `dependencies.bootstrap` 의 각 항목은
 * `{ load: BEFORE|AFTER|OMIT, required: bool, join-classpath: bool }` 이다.
 * - `required: true`(기본값) → depend, `required: false` → softdepend
 * - `load: AFTER` = "그 플러그인이 나 다음에 로드" → Bukkit 의 loadbefore 와 같은 뜻
 */
public fun parsePaperPluginYml(text: String): JarMeta? {
    val root = loadYamlMap(text) ?: return null
    val name = bukkitName(root["name"]) ?: return null
    val notes = ArrayList<String>()
    val depend = LinkedHashSet<String>()
    val soft = LinkedHashSet<String>()
    val loadBefore = LinkedHashSet<String>()
    val deps = root["dependencies"] as? Map<*, *>
    for (section in listOf("server", "bootstrap")) {
        val map = deps?.get(section) as? Map<*, *> ?: continue
        for ((k, v) in map) {
            val depName = k?.toString() ?: continue
            val spec = v as? Map<*, *>
            if (v != null && spec == null) notes += "dependencies.$section.$depName 이 맵이 아님 — required=true 로 간주"
            val required = (spec?.get("required") as? Boolean) ?: true
            val load = spec?.get("load")?.toString()?.uppercase()
            if (required) depend += depName else soft += depName
            if (load == "AFTER") loadBefore += depName
        }
    }
    return JarMeta(
        name = name,
        version = scalar(root["version"]),
        apiVersion = scalar(root["api-version"]),
        main = scalar(root["main"]),
        depend = depend.toList(),
        softDepend = soft.toList(),
        loadBefore = loadBefore.toList(),
        descriptor = JarMeta.Descriptor.PAPER_PLUGIN_YML,
        provides = stringList(root["provides"], "provides", notes),
        authors = authors(root, notes),
        description = scalar(root["description"]),
        notes = notes,
    )
}

private fun authors(root: Map<*, *>, notes: MutableList<String>): List<String> {
    val list = stringList(root["authors"], "authors", notes)
    val single = scalar(root["author"])
    return if (single != null && single !in list) listOf(single) + list else list
}

/** snakeyaml: SafeConstructor 로 임의 객체 생성 차단, LoaderOptions 로 크기 상한. */
private fun loadYamlMap(text: String): Map<*, *>? {
    val opts = LoaderOptions().apply {
        codePointLimit = 4 * 1024 * 1024
        maxAliasesForCollections = 50
        isAllowDuplicateKeys = true // Bukkit 도 마지막 값을 쓴다
    }
    val loaded = runCatching { Yaml(SafeConstructor(opts)).load<Any?>(text) }.getOrNull()
    return loaded as? Map<*, *>
}

/** Bukkit: `name.replace(' ', '_')`. 이름이 없거나 빈 문자열이면 null. */
private fun bukkitName(v: Any?): String? = scalar(v)?.trim()?.takeIf { it.isNotEmpty() }?.replace(' ', '_')

private fun scalar(v: Any?): String? = when (v) {
    null -> null
    is Map<*, *>, is Collection<*> -> null
    else -> v.toString()
}

/** 목록 필드: 리스트면 각 원소, 스칼라면 1개짜리 목록. Bukkit 은 둘 다 받는다. 맵이면 무시하고 note. */
private fun stringList(v: Any?, field: String, notes: MutableList<String>): List<String> = when (v) {
    null -> emptyList()

    is Collection<*> -> v.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.distinct()

    is Map<*, *> -> {
        notes += "$field 가 맵 형태 — 무시"
        emptyList()
    }

    else -> listOf(v.toString().trim()).filter(String::isNotEmpty)
}

// ── fabric.mod.json ───────────────────────────────────────────────────────

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Fabric `fabric.mod.json` (schemaVersion 1). `depends` 의 값은 버전 범위 문자열 또는 그 배열이다.
 * `fabricloader` / `minecraft` 도 그대로 depend 에 들어간다 — 호출자가 MC 범위로 옮겨 쓴다.
 */
public fun parseFabricModJson(text: String): JarMeta? {
    val root = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val id = (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
    val notes = ArrayList<String>()
    val ranges = LinkedHashMap<String, String>()
    fun deps(key: String): List<String> {
        val obj = root[key] as? JsonObject ?: return emptyList()
        return obj.entries.map { (k, v) ->
            ranges[k] = rangeString(v)
            k
        }
    }
    val depend = deps("depends")
    val recommends = deps("recommends") + deps("suggests")
    val breaks = (root["breaks"] as? JsonObject)?.keys?.toList() ?: emptyList()
    val entrypoints = root["entrypoints"] as? JsonObject
    val main = entrypoints?.get("main")?.let { firstEntrypoint(it) }
    val authors = (root["authors"] as? JsonArray)?.mapNotNull {
        when (it) {
            is JsonPrimitive -> it.contentOrNull
            is JsonObject -> (it["name"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    } ?: emptyList()
    if (root["jars"] is JsonArray) notes += "중첩 jar(jars:) 선언 있음 — 내부 jar 는 별도로 분석해야 한다"
    return JarMeta(
        name = id,
        version = (root["version"] as? JsonPrimitive)?.contentOrNull,
        apiVersion = null,
        main = main,
        depend = depend,
        softDepend = recommends.distinct(),
        descriptor = JarMeta.Descriptor.FABRIC_MOD_JSON,
        provides = (root["provides"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
        authors = authors,
        description = (root["description"] as? JsonPrimitive)?.contentOrNull,
        dependRanges = ranges,
        breaks = breaks,
        notes = notes,
    )
}

private fun rangeString(v: JsonElement): String = when (v) {
    is JsonPrimitive -> v.contentOrNull ?: "*"
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" || ").ifEmpty { "*" }
    else -> "*"
}

private fun firstEntrypoint(v: JsonElement): String? = when (v) {
    is JsonPrimitive -> v.contentOrNull
    is JsonArray -> v.firstOrNull()?.let { firstEntrypoint(it) }
    is JsonObject -> (v["value"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

// ── mods.toml ─────────────────────────────────────────────────────────────

/**
 * Forge/NeoForge `META-INF/mods.toml` 최소 파서. Forge 는 Phase 1 범위 밖이라 TOML 라이브러리를 넣지 않고
 * `[[mods]]` 의 첫 항목과 `[[dependencies.<modId>]]` 만 줄 단위로 읽는다.
 *
 * TODO(Phase 2, Forge): 인라인 테이블·멀티라인 문자열·배열은 지원하지 않는다. 그런 파일은 notes 에 남긴다.
 */
public fun parseModsToml(text: String): JarMeta? {
    val notes = ArrayList<String>()
    var table = ""
    val mods = ArrayList<MutableMap<String, String>>()
    val deps = ArrayList<Pair<String, MutableMap<String, String>>>() // (소유 modId, 키→값)
    var current: MutableMap<String, String>? = null
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[[") && line.endsWith("]]")) {
            table = line.removeSurrounding("[[", "]]").trim()
            val fresh = LinkedHashMap<String, String>()
            current = when {
                table == "mods" -> fresh.also { mods += it }
                table.startsWith("dependencies.") -> fresh.also { deps += table.removePrefix("dependencies.").trim('"') to it }
                else -> null
            }
            continue
        }
        if (line.startsWith("[")) {
            table = line.trim('[', ']')
            current = null
            continue
        }
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val key = line.substring(0, eq).trim().trim('"')
        val value = tomlScalar(line.substring(eq + 1).trim())
        if (value == null) {
            if (current != null) notes += "mods.toml $table.$key: 지원하지 않는 값 형태 — 무시"
            continue
        }
        current?.put(key, value)
    }
    val first = mods.firstOrNull() ?: return null
    val id = first["modId"]?.takeIf { it.isNotEmpty() } ?: return null
    if (mods.size > 1) notes += "mods.toml 에 [[mods]] ${mods.size}개 — 첫 항목만 사용"
    val depend = LinkedHashSet<String>()
    val soft = LinkedHashSet<String>()
    val ranges = LinkedHashMap<String, String>()
    for ((owner, kv) in deps) {
        if (owner != id) continue
        val depId = kv["modId"] ?: continue
        val mandatory = kv["mandatory"]?.toBooleanStrictOrNull() ?: (kv["type"]?.lowercase() != "optional")
        if (mandatory) depend += depId else soft += depId
        kv["versionRange"]?.let { ranges[depId] = it }
    }
    return JarMeta(
        name = id,
        version = first["version"],
        apiVersion = null,
        main = null,
        depend = depend.toList(),
        softDepend = soft.toList(),
        descriptor = JarMeta.Descriptor.MODS_TOML,
        authors = listOfNotNull(first["authors"]),
        description = first["description"],
        dependRanges = ranges,
        notes = notes,
    )
}

/** `"str"` / `'str'` / true|false / 숫자만. 배열·인라인 테이블·멀티라인은 null. */
private fun tomlScalar(v: String): String? = when {
    v.startsWith("[") || v.startsWith("{") || v.startsWith("\"\"\"") || v.startsWith("'''") -> null

    v.length >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) ->
        v.substring(1, v.length - 1)

    v.isNotEmpty() && v.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '+' } -> v

    else -> null
}
