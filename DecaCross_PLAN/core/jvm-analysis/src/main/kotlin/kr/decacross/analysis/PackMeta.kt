package kr.decacross.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

// ── pack.mcmeta ───────────────────────────────────────────────────────────
// 리소스팩/데이터팩 포맷 선언. 불변식 3: Int 가 아니라 PackFormat(major, minor). 1.21.9 부터 "88.0" 형태.

/** `pack.mcmeta` 의 `pack` 섹션. [decl] 은 compat-engine 과 같은 타입이다 (명세 §2 단일 진실 소스). */
public data class PackMeta(
    val decl: PackDecl,
    val description: String?,
    /** 파싱 중 무시하거나 추정한 부분 */
    val notes: List<String> = emptyList(),
)

/**
 * zip/jar 또는 디렉터리에서 `pack.mcmeta` 를 읽는다. 루트에 없으면 폴더째 압축한 흔한 실수(`MyPack/pack.mcmeta`)를
 * 한 단계까지 허용한다. 파일이 없거나 `pack` 섹션이 없으면 null.
 */
public fun readPackMeta(path: Path): PackMeta? {
    val text = readPackMcmetaText(path) ?: return null
    return parsePackMcmeta(text)
}

/** [readPackMeta] 의 [PackDecl] 만. */
public fun readPackDecl(path: Path): PackDecl? = readPackMeta(path)?.decl

private fun readPackMcmetaText(path: Path): String? {
    if (Files.isDirectory(path)) {
        val f = path.resolve("pack.mcmeta")
        return if (Files.isRegularFile(f)) runCatching { Files.readString(f) }.getOrNull() else null
    }
    if (!Files.isRegularFile(path)) return null
    val zip = runCatching { ZipFile(path.toFile()) }.getOrNull() ?: return null
    return zip.use { z ->
        val entry = z.getEntry("pack.mcmeta")
            ?: z.entries().asSequence().firstOrNull { e ->
                !e.isDirectory && e.name.endsWith("/pack.mcmeta") && e.name.count { it == '/' } == 1
            }
            ?: return@use null
        runCatching { z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * `pack.mcmeta` 본문 파싱. 우선순위:
 * 1. `min_format` / `max_format` (1.21.9+; int 또는 `[major, minor]`) → [PackDecl.Range]
 * 2. `supported_formats` (1.20.2+; `[a, b]` 목록 또는 `{min_inclusive, max_inclusive}`) → [PackDecl.Range] / [PackDecl.Supported]
 * 3. `pack_format` (int 또는 `"88.0"` 문자열, 1.21.9+ 는 소수 허용) → [PackDecl.Single]
 *
 * `pack` 섹션이 없거나 셋 다 없으면 null. 한쪽만 있는 `min_format` 은 `pack_format` 으로 보충한다.
 */
public fun parsePackMcmeta(text: String): PackMeta? {
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val pack = root["pack"] as? JsonObject ?: return null
    val notes = ArrayList<String>()
    val description = when (val d = pack["description"]) {
        is JsonPrimitive -> d.contentOrNull
        is JsonObject -> (d["text"] as? JsonPrimitive)?.contentOrNull
        is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("")
        else -> null
    }
    val single = pack["pack_format"]?.let { packFormatOf(it, "pack_format", notes) }
    val min = pack["min_format"]?.let { packFormatOf(it, "min_format", notes) }
    val max = pack["max_format"]?.let { packFormatOf(it, "max_format", notes) }

    val decl: PackDecl? = when {
        min != null || max != null -> {
            val lo = min ?: single
            val hi = max ?: single
            when {
                lo != null && hi != null && lo <= hi -> PackDecl.Range(lo, hi)

                lo != null && hi != null -> {
                    notes += "min_format($lo) > max_format($hi) — 단일값으로 강등"
                    PackDecl.Single(lo)
                }

                else -> {
                    notes += "min_format/max_format 한쪽만 있고 pack_format 도 없음"
                    (lo ?: hi)?.let { PackDecl.Single(it) }
                }
            }
        }

        pack["supported_formats"] != null -> supportedFormats(pack.getValue("supported_formats"), single, notes)

        else -> single?.let { PackDecl.Single(it) }
    }
    return decl?.let { PackMeta(it, description, notes) }
}

private fun supportedFormats(v: JsonElement, single: PackFormat?, notes: MutableList<String>): PackDecl? = when (v) {
    is JsonObject -> {
        val lo = v["min_inclusive"]?.let { packFormatOf(it, "supported_formats.min_inclusive", notes) }
        val hi = v["max_inclusive"]?.let { packFormatOf(it, "supported_formats.max_inclusive", notes) }
        if (lo != null && hi != null && lo <= hi) PackDecl.Range(lo, hi) else single?.let { PackDecl.Single(it) }
    }

    is JsonArray -> {
        val formats = v.mapNotNull { packFormatOf(it, "supported_formats[]", notes) }
        when {
            // `[a, b]` 두 원소는 Mojang 정의상 [min, max] 구간이다
            formats.size == 2 && v.size == 2 && formats[0] <= formats[1] -> PackDecl.Range(formats[0], formats[1])

            formats.isNotEmpty() -> PackDecl.Supported(formats)

            else -> single?.let { PackDecl.Single(it) }
        }
    }

    is JsonPrimitive -> packFormatOf(v, "supported_formats", notes)?.let { PackDecl.Single(it) } ?: single?.let { PackDecl.Single(it) }

    else -> single?.let { PackDecl.Single(it) }
}

/** int(`34`), 소수(`88.0`), 문자열(`"88.0"`), `[major, minor]` 배열 전부 [PackFormat] 으로. */
private fun packFormatOf(v: JsonElement, field: String, notes: MutableList<String>): PackFormat? = when (v) {
    is JsonPrimitive -> v.contentOrNull?.let { PackFormat.parse(it) }.also { if (it == null) notes += "$field 를 읽지 못함: $v" }

    is JsonArray -> {
        val nums = v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        if (nums.size in 1..2 && nums.all { it >= 0 }) {
            PackFormat(nums[0], nums.getOrElse(1) { 0 })
        } else {
            notes += "$field 배열 형태를 읽지 못함: $v"
            null
        }
    }

    else -> {
        notes += "$field 형태를 읽지 못함: $v"
        null
    }
}
