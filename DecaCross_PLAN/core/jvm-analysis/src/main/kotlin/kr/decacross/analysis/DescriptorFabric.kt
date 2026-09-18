package kr.decacross.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── fabric.mod.json v1 (설계 §7.3) ─────────────────────────────────────────
// 규칙 상수(modId 정규식, schemaVersion 1)는 Fabric Loader 메타데이터 명세 계약이다 (설계 AE-5).

/** Fabric Loader `MetadataVerifier` 의 modId 규칙. */
private val FABRIC_MOD_ID = Regex("^[a-z][a-z0-9-_]{1,63}$")

/** 의존성 블록 키 → 정규화 종류 (이 순서대로 처리한다). */
private val FABRIC_DEPENDENCY_KINDS: List<Pair<String, MetaDepKind>> = listOf(
    "depends" to MetaDepKind.REQUIRED,
    "recommends" to MetaDepKind.OPTIONAL,
    "suggests" to MetaDepKind.OPTIONAL,
    "conflicts" to MetaDepKind.DISCOURAGED,
    "breaks" to MetaDepKind.INCOMPATIBLE,
)

private const val BAD_VERSION_RANGE = "Dependency version range must be a string or string array"

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** fabric.mod.json 텍스트 → [DescriptorParse]. 예외를 던지지 않는다. */
internal fun parseFabricModJsonText(text: String): DescriptorParse {
    val kind = JarMeta.Descriptor.FABRIC_MOD_JSON
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        // kotlinx SerializationException 은 IllegalArgumentException 의 하위 타입이다
        return DescriptorParse.Invalid(InvalidDescriptor(kind, "fabric.mod.json JSON 문법 오류: ${e.message}"))
    }
    val reader = FabricReader()
    val meta = reader.read(root)
    val error = reader.error
    return if (meta != null && error == null) {
        DescriptorParse.Parsed(meta)
    } else {
        DescriptorParse.Invalid(InvalidDescriptor(kind, error ?: "fabric.mod.json 해석 실패"))
    }
}

/** 첫 위반 사유만 기억하는 읽기 도우미. 예외를 쓰지 않는다. */
private class FabricReader {
    var error: String? = null
        private set

    private fun <T> bad(reason: String): T? {
        if (error == null) error = reason
        return null
    }

    fun read(root: JsonElement): JarMeta? {
        val obj = root as? JsonObject ?: return bad("fabric.mod.json 루트가 객체가 아님")

        val schemaVersion = obj["schemaVersion"] ?: return bad("schemaVersion 0 (v0) 미지원")
        val schemaNumber = (schemaVersion as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (schemaNumber != 1) return bad("schemaVersion $schemaVersion 미지원 (1 만 지원)")

        val id = obj["id"]?.stringOrNull() ?: return bad("id 가 문자열이 아님")
        if (!FABRIC_MOD_ID.matches(id)) return bad("id '$id' 가 modId 규칙(${FABRIC_MOD_ID.pattern})에 맞지 않음")

        val version = obj["version"]?.stringOrNull() ?: return bad("version 이 문자열이 아님")

        val deps = ArrayList<MetaDep>()
        for ((key, depKind) in FABRIC_DEPENDENCY_KINDS) {
            val block = obj[key] ?: continue
            val entries = block as? JsonObject ?: return bad("$key 가 객체가 아님")
            for ((target, value) in entries) {
                val predicates = versionPredicates(value) ?: return null
                deps += MetaDep(target = target, kind = depKind, versionPredicates = predicates)
            }
        }

        val provides = obj["provides"]?.let { stringArray(it, "provides") ?: return null } ?: emptyList()
        val nestedJars = obj["jars"]?.let { nestedJarFiles(it) ?: return null } ?: emptyList()

        return JarMeta(
            name = id,
            version = version,
            apiVersion = null,
            main = null,
            depend = deps.filter { it.kind == MetaDepKind.REQUIRED }.map { it.target },
            softDepend = deps.filter { it.kind == MetaDepKind.OPTIONAL }.map { it.target },
            descriptor = JarMeta.Descriptor.FABRIC_MOD_JSON,
            rawName = id,
            rawVersion = version,
            provides = provides,
            deps = deps,
            nestedJars = nestedJars,
        )
    }

    /** 버전 조건: 문자열 → `[s]`, 문자열 배열 → 그 목록 (OR 의미). */
    private fun versionPredicates(value: JsonElement): List<String>? {
        value.stringOrNull()?.let { return listOf(it) }
        val array = value as? JsonArray ?: return bad(BAD_VERSION_RANGE)
        val out = ArrayList<String>(array.size)
        for (element in array) out += element.stringOrNull() ?: return bad(BAD_VERSION_RANGE)
        return out
    }

    private fun stringArray(value: JsonElement, key: String): List<String>? {
        val array = value as? JsonArray ?: return bad("$key 가 배열이 아님")
        val out = ArrayList<String>(array.size)
        for (element in array) out += element.stringOrNull() ?: return bad("$key 원소가 문자열이 아님")
        return out
    }

    /** `jars: [{file}]` (Jar-in-Jar). */
    private fun nestedJarFiles(value: JsonElement): List<String>? {
        val array = value as? JsonArray ?: return bad("jars 가 배열이 아님")
        val out = ArrayList<String>(array.size)
        for (element in array) {
            val jarObject = element as? JsonObject ?: return bad("jars 원소가 객체가 아님")
            out += jarObject["file"]?.stringOrNull() ?: return bad("jars 원소에 file 이 없음")
        }
        return out
    }
}
