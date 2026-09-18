package kr.decacross.analysis

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.StringReader
import java.util.Date
import java.util.Locale

// ── plugin.yml / paper-plugin.yml (설계 §7.3) ─────────────────────────────
// plugin.yml   : Bukkit PluginDescriptionFile 시맨틱 (float 리졸버 없음 — SPIGOT-7370).
// paper-plugin : Configurate 시맨틱 (SnakeYAML 기본 리졸버 — float 해석).
// 로더 규칙 상수(예약 이름·정규식·제한 네임스페이스·api-version 하한)는 로더 계약이다 (설계 AE-4).
// 수집 데이터가 아니므로 코드에 둔다.

/** Bukkit `PluginDescriptionFile.VALID_NAME` / Paper `PluginConfigConstraints.PluginName`. */
private val VALID_PLUGIN_NAME = Regex("^[A-Za-z0-9 _.-]+$")

/** Paper `PluginConfigConstraints.RESERVED_KEYS`. 소문자 비교. */
private val RESERVED_PLUGIN_NAMES: Set<String> = setOf("bukkit", "minecraft", "mojang", "spigot", "paper")

/** Paper `MavenLibraryResolver` 좌표 정규식. */
private val MAVEN_COORDINATE = Regex("^([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?:([^: ]+)$")

/** Paper `PluginConfigConstraints.PluginNameSpace` 가 거부하는 네임스페이스. */
private val RESTRICTED_NAMESPACES: List<String> =
    listOf("net.minecraft.", "org.bukkit.", "io.papermc.paper.", "com.destroystokoyo.paper.")

/** `PluginLoadOrder` 값. */
private val LOAD_ORDERS: Set<String> = setOf("STARTUP", "POSTWORLD")

private const val DEFAULT_LOAD_ORDER: String = "POSTWORLD"

/** paper-plugin.yml `api-version` 하한 (Paper `PaperPluginMeta.MINIMUM`). MC 버전 비교가 아니다 (불변식 1). */
private val PAPER_MIN_API_VERSION: List<Int> = listOf(1, 19, 0)

private const val NOT_STRUCTURED = "Plugin description file is empty or not properly structured"
private const val API_VERSION_MISSING_WARNING = "api-version 미지정 — 레거시 모드"
private const val LEGACY_PAPER_WARNING = "레거시 paper-plugin.yml 형식"

// ── SnakeYAML 설정 ────────────────────────────────────────────────────────

/** Bukkit `PluginDescriptionResolver` 복제. FLOAT 가 없어서 `1.20` 이 문자열로 남는다. */
private class BukkitResolver : Resolver() {
    override fun addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, Resolver.BOOL, "yYnNtTfFoO")
        addImplicitResolver(Tag.INT, Resolver.INT, "-+0123456789")
        addImplicitResolver(Tag.MERGE, Resolver.MERGE, "<")
        addImplicitResolver(Tag.NULL, Resolver.NULL, "~nN\u0000")
        addImplicitResolver(Tag.NULL, Resolver.EMPTY, null)
        addImplicitResolver(Tag.TIMESTAMP, Resolver.TIMESTAMP, "0123456789")
    }
}

/** Bukkit awareness 태그(`!@UTF8` 등)의 자리표시자. 값 자체는 쓰지 않는다. */
private data class AwarenessPlaceholder(val tag: String)

/** Bukkit `PluginDescriptionFile` 의 SafeConstructor: `!@` 태그만 허용하고 나머지 미지 태그는 실패시킨다. */
private class BukkitConstructor : SafeConstructor(LoaderOptions()) {
    init {
        yamlConstructors[null] = object : AbstractConstruct() {
            override fun construct(node: Node): Any {
                if (!node.tag.startsWith("!@")) return SafeConstructor.undefinedConstructor.construct(node)
                return AwarenessPlaceholder(node.tag.value)
            }
        }
    }
}

/** plugin.yml 용 Yaml. 스레드 안전하지 않으므로 호출마다 만든다. */
private fun bukkitYaml(): Yaml {
    val dumper = DumperOptions()
    return Yaml(BukkitConstructor(), Representer(dumper), dumper, LoaderOptions(), BukkitResolver())
}

/** paper-plugin.yml 용 Yaml (기본 리졸버). */
private fun configurateYaml(): Yaml = Yaml(SafeConstructor(LoaderOptions()))

/** YAML 로드 결과: 루트 값 또는 실패 사유. 예외를 밖으로 내보내지 않는다. */
private sealed interface YamlLoad {
    data class Loaded(val root: Any?, val scalarTexts: Map<String, String>) : YamlLoad

    data class Failed(val message: String) : YamlLoad
}

/**
 * [text] 를 로드하고, 루트 맵의 스칼라 값 원문 텍스트(변환 전)도 compose 로 모은다.
 * 중복 키는 SnakeYAML 과 같게 마지막 값이 이긴다.
 */
private fun loadYaml(yaml: Yaml, text: String): YamlLoad {
    val root: Any? = try {
        yaml.load<Any?>(text)
    } catch (e: RuntimeException) {
        // YAMLException(문법·미지 태그·다중 문서) 외에 생성자 내부 RuntimeException 도 descriptor 오류로 본다
        rethrowIfCancellation(e)
        return YamlLoad.Failed(e.message ?: e.javaClass.simpleName)
    } catch (e: StackOverflowError) {
        return YamlLoad.Failed("YAML 중첩이 너무 깊음")
    }
    val scalars = HashMap<String, String>()
    try {
        val node = yaml.compose(StringReader(text))
        if (node is MappingNode) {
            for (tuple in node.value) {
                val key = tuple.keyNode as? ScalarNode ?: continue
                val value = tuple.valueNode
                if (value is ScalarNode) scalars[key.value] = value.value else scalars.remove(key.value)
            }
        }
    } catch (e: RuntimeException) {
        rethrowIfCancellation(e)
        // load 가 성공했으면 compose 도 성공한다. 방어적으로 원문 없이 계속한다.
    }
    return YamlLoad.Loaded(root, scalars)
}

private fun describeRoot(root: Any?): String = if (root == null) "null" else "${root.javaClass.simpleName}: $root".take(200)

private fun invalid(kind: JarMeta.Descriptor, reason: String): DescriptorParse = DescriptorParse.Invalid(InvalidDescriptor(kind, reason))

/** 이름 규칙 (plugin.yml·paper-plugin.yml 공통). 위반은 [loadErrors] 에 쌓는다. */
private fun checkPluginName(raw: String, loadErrors: MutableList<String>) {
    if (!VALID_PLUGIN_NAME.matches(raw)) loadErrors += "name '$raw' contains invalid characters."
    if (raw.indexOf(' ') != -1) loadErrors += "Restricted name, cannot use 0x20 (space character) in a plugin name."
    if (raw.lowercase(Locale.ROOT) in RESERVED_PLUGIN_NAMES) loadErrors += "Restricted name, cannot use $raw as a plugin name."
}

/** `load` 규칙 (Bukkit `PluginLoadOrder.valueOf(upper.replaceAll("\\W", ""))`). */
private fun parseLoadOrder(value: Any?, loadErrors: MutableList<String>): String? {
    if (value == null) return DEFAULT_LOAD_ORDER
    if (value !is String) {
        loadErrors += "load is of wrong type"
        return null
    }
    val normalized = value.uppercase(Locale.ROOT).replace(Regex("\\W"), "")
    if (normalized !in LOAD_ORDERS) {
        loadErrors += "load is not a valid choice"
        return null
    }
    return normalized
}

private fun isTrueString(value: Any?): Boolean = value?.toString().equals("true", ignoreCase = true)

// ── plugin.yml ────────────────────────────────────────────────────────────

/** Bukkit `makePluginNameList`: Iterable 이 아니면 wrong type, null 원소는 invalid format. 순서·중복 보존. */
private fun bukkitNameList(
    map: Map<*, *>,
    key: String,
    loadErrors: MutableList<String>,
    wrongTypeMessage: String = "$key is of wrong type",
    transform: (String) -> String = { it.replace(' ', '_') },
): List<String> {
    val value = map[key] ?: return emptyList()
    if (value !is Iterable<*>) {
        loadErrors += wrongTypeMessage
        return emptyList()
    }
    val out = ArrayList<String>()
    for (element in value) {
        if (element == null) {
            loadErrors += "invalid $key format"
            continue
        }
        out += transform(element.toString())
    }
    return out
}

/** plugin.yml 텍스트 → [DescriptorParse]. 설계 §7.3 표를 그대로 따른다. */
internal fun parsePluginYmlText(text: String): DescriptorParse {
    val kind = JarMeta.Descriptor.PLUGIN_YML
    val loaded = when (val load = loadYaml(bukkitYaml(), text)) {
        is YamlLoad.Failed -> return invalid(kind, "$NOT_STRUCTURED: ${load.message}")
        is YamlLoad.Loaded -> load
    }
    val map = loaded.root as? Map<*, *> ?: return invalid(kind, "$NOT_STRUCTURED: ${describeRoot(loaded.root)}")
    val loadErrors = ArrayList<String>()
    val warnings = ArrayList<String>()

    val rawName = map["name"]?.toString() ?: return invalid(kind, "name is not defined")
    checkPluginName(rawName, loadErrors)
    val name = rawName.replace(' ', '_')

    val provides = bukkitNameList(map, "provides", loadErrors)

    val versionValue = map["version"]
    val version: String?
    val rawVersion: String?
    when (versionValue) {
        null -> {
            version = null
            rawVersion = null
            loadErrors += "version is not defined"
        }

        is Date -> {
            // TIMESTAMP 리졸버가 날짜로 바꾼 값 — 로더는 Date.toString() 을 쓰지만 원문이 더 정확하다
            val raw = loaded.scalarTexts["version"] ?: versionValue.toString()
            version = raw
            rawVersion = raw
            warnings += "version 이 날짜로 해석됨 — 원문 사용"
        }

        else -> {
            version = versionValue.toString()
            rawVersion = loaded.scalarTexts["version"] ?: version
        }
    }

    val mainValue = map["main"]
    val main = mainValue?.toString()
    if (main == null) {
        loadErrors += "main is not defined"
    } else if (main.startsWith("org.bukkit.")) {
        loadErrors += "main may not be within the org.bukkit namespace"
    }

    val depend = bukkitNameList(map, "depend", loadErrors)
    val softDepend = bukkitNameList(map, "softdepend", loadErrors)
    val loadBefore = bukkitNameList(map, "loadbefore", loadErrors)
    val load = parseLoadOrder(map["load"], loadErrors)

    val apiVersion = map["api-version"]?.toString()
    if (apiVersion == null || apiVersion.isBlank() || apiVersion.equals("none", ignoreCase = true)) {
        warnings += API_VERSION_MISSING_WARNING
    } else {
        val parts = apiVersion.split('.')
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) {
            loadErrors += "api-version '$apiVersion' is not a valid version"
        }
    }

    val libraries = bukkitNameList(
        map,
        "libraries",
        loadErrors,
        wrongTypeMessage = "libraries are of wrong type",
        transform = { it },
    )
    for (library in libraries) {
        if (!MAVEN_COORDINATE.matches(library)) loadErrors += "libraries entry '$library' is not a valid maven coordinate"
    }
    val skipLibraries = isTrueString(map["paper-skip-libraries"])
    val foliaSupported = isTrueString(map["folia-supported"])

    val deps = LinkedHashMap<String, MetaDep>()
    for (target in depend) deps.putIfAbsent(target, MetaDep(target, MetaDepKind.REQUIRED))
    for (target in softDepend) deps.putIfAbsent(target, MetaDep(target, MetaDepKind.OPTIONAL))

    val overlap = loadBefore.filter { it in depend || it in softDepend }.distinct()
    if (overlap.isNotEmpty()) warnings += "loadbefore 와 depend/softdepend 동시 선언: ${overlap.joinToString(", ")}"

    return DescriptorParse.Parsed(
        JarMeta(
            name = name,
            version = version,
            apiVersion = apiVersion,
            main = main,
            depend = depend,
            softDepend = softDepend,
            loadBefore = loadBefore,
            libraries = libraries,
            descriptor = kind,
            rawName = rawName,
            rawVersion = rawVersion,
            provides = provides,
            load = load,
            foliaSupported = foliaSupported,
            skipLibrariesOnPaper = skipLibraries,
            deps = deps.values.toList(),
            loadErrors = loadErrors,
            warnings = warnings,
        ),
    )
}

// ── paper-plugin.yml ──────────────────────────────────────────────────────

/** paper-plugin.yml 의존성 설정의 로드 순서 (Paper `DependencyConfiguration.LoadOrder`). */
private enum class PaperLoad { BEFORE, AFTER, OMIT }

/** 한 단계(bootstrap/server)의 의존성 항목. */
private class PhaseEntry(var load: PaperLoad, var required: Boolean)

/** 대상 이름 → 단계별 항목 (선언 순서 유지). */
private class PaperDependencies {
    val byTarget = LinkedHashMap<String, MutableMap<DepPhase, PhaseEntry>>()

    fun entry(target: String, phase: DepPhase): PhaseEntry? = byTarget[target]?.get(phase)

    fun put(target: String, phase: DepPhase, entry: PhaseEntry) {
        byTarget.getOrPut(target) { LinkedHashMap() }[phase] = entry
    }
}

private fun parsePaperLoad(value: Any?, where: String, loadErrors: MutableList<String>): PaperLoad {
    if (value == null) return PaperLoad.OMIT
    val normalized = value.toString().uppercase(Locale.ROOT).replace('-', '_')
    return PaperLoad.entries.firstOrNull { it.name == normalized } ?: run {
        loadErrors += "$where.load is not a valid choice"
        PaperLoad.OMIT
    }
}

private fun parseBoolean(value: Any?, default: Boolean, where: String, loadErrors: MutableList<String>): Boolean = when {
    value == null -> default

    value is Boolean -> value

    value.toString().equals("true", ignoreCase = true) -> true

    value.toString().equals("false", ignoreCase = true) -> false

    else -> {
        loadErrors += "$where is of wrong type"
        default
    }
}

private fun parsePhase(key: Any?): DepPhase? = when (key?.toString()?.uppercase(Locale.ROOT)) {
    "BOOTSTRAP" -> DepPhase.BOOTSTRAP
    "SERVER" -> DepPhase.SERVER
    else -> null
}

/** 현행 형식: `{bootstrap|server: {<Name>: {load, required = true, join-classpath = true}}}`. */
private fun readModernDependencies(value: Map<*, *>, loadErrors: MutableList<String>): PaperDependencies {
    val out = PaperDependencies()
    for ((phaseKey, targets) in value) {
        val phase = parsePhase(phaseKey)
        if (phase == null) {
            loadErrors += "dependencies.$phaseKey is not a valid phase"
            continue
        }
        if (targets == null) continue
        if (targets !is Map<*, *>) {
            loadErrors += "dependencies.$phaseKey is of wrong type"
            continue
        }
        for ((targetKey, config) in targets) {
            val target = targetKey?.toString() ?: continue
            val where = "dependencies.$phaseKey.$target"
            val configMap: Map<*, *> = when (config) {
                null -> emptyMap<Any, Any>()

                is Map<*, *> -> config

                else -> {
                    loadErrors += "$where is of wrong type"
                    continue
                }
            }
            val load = parsePaperLoad(configMap["load"], where, loadErrors)
            val required = parseBoolean(configMap["required"], true, "$where.required", loadErrors)
            parseBoolean(configMap["join-classpath"], true, "$where.join-classpath", loadErrors)
            out.put(target, phase, PhaseEntry(load, required))
        }
    }
    return out
}

/** 레거시 형식의 `{name, ...}` 목록. 잘못된 원소는 loadErrors 에 남기고 건너뛴다. */
private fun legacyItems(map: Map<*, *>, key: String, loadErrors: MutableList<String>): List<Map<*, *>> {
    val value = map[key] ?: return emptyList()
    if (value !is List<*>) {
        loadErrors += "$key is of wrong type"
        return emptyList()
    }
    val out = ArrayList<Map<*, *>>()
    for (item in value) {
        if (item !is Map<*, *> || item["name"] == null) {
            loadErrors += "$key entry name is required"
            continue
        }
        out += item
    }
    return out
}

/**
 * 레거시 형식 (Paper `LegacyPaperMeta`): `dependencies: [{name, required = false, bootstrap = false}]`,
 * `load-before: [{name, bootstrap}]` → 대상이 뒤에 로드(AFTER), `load-after: [{name, bootstrap}]` → 대상이 먼저(BEFORE).
 * `required` 기본값 false 는 원시 boolean 기본값을 따른 소스 해석이다 (런타임 미검증 — 설계 §7.3).
 */
private fun readLegacyDependencies(map: Map<*, *>, loadErrors: MutableList<String>): PaperDependencies {
    val out = PaperDependencies()
    fun phaseOf(item: Map<*, *>, key: String): DepPhase =
        if (parseBoolean(item["bootstrap"], false, "$key.bootstrap", loadErrors)) DepPhase.BOOTSTRAP else DepPhase.SERVER

    for (item in legacyItems(map, "dependencies", loadErrors)) {
        val target = item["name"].toString()
        val required = parseBoolean(item["required"], false, "dependencies.required", loadErrors)
        out.put(target, phaseOf(item, "dependencies"), PhaseEntry(PaperLoad.OMIT, required))
    }
    for ((key, load) in listOf("load-after" to PaperLoad.BEFORE, "load-before" to PaperLoad.AFTER)) {
        for (item in legacyItems(map, key, loadErrors)) {
            val target = item["name"].toString()
            val phase = phaseOf(item, key)
            val existing = out.entry(target, phase)
            if (existing != null) {
                existing.load = load
            } else {
                out.put(target, phase, PhaseEntry(load, required = false))
            }
        }
    }
    return out
}

/** paper-plugin.yml 텍스트 → [DescriptorParse]. 설계 §7.3 "Configurate semantics". */
internal fun parsePaperPluginYmlText(text: String): DescriptorParse {
    val kind = JarMeta.Descriptor.PAPER_PLUGIN_YML
    val loaded = when (val load = loadYaml(configurateYaml(), text)) {
        is YamlLoad.Failed -> return invalid(kind, "paper-plugin.yml 을 해석할 수 없음: ${load.message}")
        is YamlLoad.Loaded -> load
    }
    val map = loaded.root as? Map<*, *> ?: return invalid(kind, "paper-plugin.yml 루트가 맵이 아님: ${describeRoot(loaded.root)}")
    val loadErrors = ArrayList<String>()
    val warnings = ArrayList<String>()

    val rawName = map["name"]?.toString()
    if (rawName == null) loadErrors += "name is required" else checkPluginName(rawName, loadErrors)

    val main = map["main"]?.toString()
    if (main == null) loadErrors += "main is required"
    for ((key, value) in listOf("main" to main, "bootstrapper" to map["bootstrapper"]?.toString(), "loader" to map["loader"]?.toString())) {
        if (value != null && RESTRICTED_NAMESPACES.any { value.startsWith(it) }) loadErrors += "$key uses a restricted namespace"
    }

    val version = map["version"]?.toString()
    if (version == null) loadErrors += "version is required"
    val rawVersion = if (version == null) null else loaded.scalarTexts["version"] ?: version

    val apiVersion = map["api-version"]?.toString()
    if (apiVersion == null) {
        loadErrors += "api-version is required"
    } else {
        val rawApiVersion = loaded.scalarTexts["api-version"]
        if (rawApiVersion != null && rawApiVersion != apiVersion) {
            warnings += "api-version 이 float 로 해석됨 (따옴표 필요): '$rawApiVersion' → '$apiVersion'"
        }
        checkPaperApiVersion(apiVersion, loadErrors)
    }

    val provides = bukkitNameList(map, "provides", loadErrors, transform = { it })
    val load = parseLoadOrder(map["load"], loadErrors)
    val foliaSupported = isTrueString(map["folia-supported"])

    val dependenciesValue = map["dependencies"]
    val dependencies = when {
        dependenciesValue is Map<*, *> -> readModernDependencies(dependenciesValue, loadErrors)

        dependenciesValue is List<*> || map.containsKey("load-before") || map.containsKey("load-after") -> {
            warnings += LEGACY_PAPER_WARNING
            readLegacyDependencies(map, loadErrors)
        }

        dependenciesValue == null -> PaperDependencies()

        else -> {
            loadErrors += "dependencies is of wrong type"
            PaperDependencies()
        }
    }

    val deps = ArrayList<MetaDep>()
    val loadAfter = ArrayList<String>()
    val loadBefore = ArrayList<String>()
    for ((target, phases) in dependencies.byTarget) {
        val bootstrap = phases[DepPhase.BOOTSTRAP]
        val server = phases[DepPhase.SERVER]
        val required = bootstrap?.required == true || server?.required == true
        val phase = if (bootstrap?.required == true) DepPhase.BOOTSTRAP else DepPhase.SERVER
        // Paper getLoadBeforePlugins/getLoadAfterPlugins 는 SERVER 의존성만 본다
        val order = when (server?.load) {
            PaperLoad.BEFORE -> LoadOrder.BEFORE
            PaperLoad.AFTER -> LoadOrder.AFTER
            PaperLoad.OMIT, null -> LoadOrder.NONE
        }
        if (order == LoadOrder.BEFORE) loadAfter += target
        if (order == LoadOrder.AFTER) loadBefore += target
        deps += MetaDep(
            target = target,
            kind = if (required) MetaDepKind.REQUIRED else MetaDepKind.OPTIONAL,
            order = order,
            phase = phase,
        )
    }

    return DescriptorParse.Parsed(
        JarMeta(
            name = rawName ?: "",
            version = version,
            apiVersion = apiVersion,
            main = main,
            depend = deps.filter { it.kind == MetaDepKind.REQUIRED }.map { it.target },
            softDepend = deps.filter { it.kind == MetaDepKind.OPTIONAL }.map { it.target },
            loadBefore = loadBefore,
            libraries = emptyList(),
            descriptor = kind,
            rawName = rawName ?: "",
            rawVersion = rawVersion,
            provides = provides,
            loadAfter = loadAfter,
            load = load,
            foliaSupported = foliaSupported,
            deps = deps,
            loadErrors = loadErrors,
            warnings = warnings,
        ),
    )
}

/**
 * Paper `ApiVersion.getOrCreateVersion` + `isOlderThan(1.19)` 흉내. **로더 계약 검사**이며
 * MC 버전 비교에 재사용하지 마라 (불변식 1 — MC 버전 비교는 McOrdinal).
 */
private fun checkPaperApiVersion(apiVersion: String, loadErrors: MutableList<String>) {
    if (apiVersion.isBlank() || apiVersion.equals("none", ignoreCase = true)) {
        // ApiVersion.NONE 은 모든 버전보다 오래됐다
        loadErrors += "api-version $apiVersion is too old for a paper plugin"
        return
    }
    val parts = apiVersion.split('.')
    val numbers = parts.map { it.toIntOrNull() }
    if (parts.size !in 2..3 || numbers.any { it == null }) {
        loadErrors += "api-version '$apiVersion' is not a valid version"
        return
    }
    val triple = numbers.filterNotNull() + if (parts.size == 2) listOf(0) else emptyList()
    val comparison = triple.zip(PAPER_MIN_API_VERSION).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
    if (comparison < 0) loadErrors += "api-version $apiVersion is too old for a paper plugin"
}
