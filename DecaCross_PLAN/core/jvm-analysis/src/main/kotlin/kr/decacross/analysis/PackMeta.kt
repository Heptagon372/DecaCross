package kr.decacross.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import java.nio.file.Path
import java.util.zip.ZipFile

/** pack.mcmeta 해석 결과. 예외를 던지지 않는다. */
public sealed interface PackMcmetaResult {
    public data class Ok(val meta: PackMcmeta) : PackMcmetaResult

    /** 스키마 위반 (min 만 있음, min > max, 음수, 소수, 빈 리스트 등). 선언을 저장하지 않는다. */
    public data class Invalid(val reason: String) : PackMcmetaResult
}

/**
 * pack.mcmeta 의 major 구간 (`supported_formats`, overlay `formats`). 양 끝 포함.
 *
 * # 불변식 (CLAUDE.md 불변식 3 — pack format 은 `Int` 가 아니라 [PackFormat])
 * - [min] 의 minor 는 0, [max] 의 minor 는 `Int.MAX_VALUE` 로 정규화한다 ("그 major 의 모든 minor").
 * - `min <= max`. 파서만 만든다.
 */
public data class PackMajorRange(val min: PackFormat, val max: PackFormat)

/**
 * pack.mcmeta 의 호환 선언 원문을 정규화한 값.
 *
 * # 불변식
 * - pack format 은 전부 [PackFormat] 이다. `Int` 로 들고 다니지 않는다 (불변식 3).
 * - [packFormat] 의 minor 는 0 이다 (`pack_format` 은 정수만 허용).
 * - [maxFormat] 이 정수나 1원소 리스트로 선언되면 minor 는 `Int.MAX_VALUE` 로 정규화한다 ("모든 minor").
 * - [minFormat] 이 정수나 1원소 리스트로 선언되면 minor 는 0 이다.
 * - [supportedFormats] 는 major 구간(양 끝 포함)이다. 목록이 아니다.
 * - JSON 으로 내보낼 때는 [PackFormat] 의 문자열 형태만 쓴다 (JSON 숫자 금지).
 */
public data class PackMcmeta(
    val packFormat: PackFormat?,
    val supportedFormats: PackMajorRange?,
    val minFormat: PackFormat?,
    val maxFormat: PackFormat?,
    val overlays: List<Overlay>,
    val notes: List<String>,
) {
    /** overlays.entries[] — 호환 판정에는 쓰지 않고 데이터로만 보관한다. */
    public data class Overlay(
        val directory: String,
        val formats: PackMajorRange?,
        val minFormat: PackFormat?,
        val maxFormat: PackFormat?,
    )
}

/** pack.mcmeta JSON 텍스트를 해석한다. */
public fun parsePackMcmeta(json: String): PackMcmetaResult {
    val root = try {
        Json.parseToJsonElement(json)
    } catch (e: IllegalArgumentException) {
        // kotlinx SerializationException 은 IllegalArgumentException 의 하위 타입이다
        return PackMcmetaResult.Invalid("JSON 문법 오류: ${e.message}")
    }
    val rootObject = root as? JsonObject ?: return PackMcmetaResult.Invalid("루트가 객체가 아님")
    val pack = rootObject["pack"] as? JsonObject ?: return PackMcmetaResult.Invalid("pack 객체가 없음")

    val reader = PackReader()
    val packFormat = pack["pack_format"]?.let { element ->
        reader.nonNegativeInt(element)?.let { PackFormat(it, 0) } ?: reader.bad("pack_format 이 0 이상의 정수가 아님: $element")
    }
    val supportedFormats = pack["supported_formats"]?.let { reader.inclusiveRange(it, "supported_formats") }
    val bounds = reader.minMax(pack, "pack")
    reader.error?.let { return PackMcmetaResult.Invalid(it) }

    val overlays = rootObject["overlays"]?.let { reader.overlays(it) } ?: emptyList()
    reader.error?.let { return PackMcmetaResult.Invalid(it) }

    val notes = ArrayList<String>()
    if (packFormat != null) {
        // modernDecl 이 쓰는 구간과 pack_format 의 major 가 어긋나면 1.20.2 전후 게임이 다르게 판정한다
        val modernRange = bounds?.let { it.first.major..it.second.major }
            ?: supportedFormats?.let { it.min.major..it.max.major }
        if (modernRange != null && packFormat.major !in modernRange) notes += "legacy/modern major 불일치"
    }

    return PackMcmetaResult.Ok(
        PackMcmeta(
            packFormat = packFormat,
            supportedFormats = supportedFormats,
            minFormat = bounds?.first,
            maxFormat = bounds?.second,
            overlays = overlays,
            notes = notes,
        ),
    )
}

/**
 * 1.20.2+ 게임이 쓰는 선언. min/max → `Range`, supported_formats → `Range(min, max)` ([PackMajorRange] 그대로),
 * pack_format 만 → `Single`. 해당 없으면 null.
 */
public fun PackMcmeta.modernDecl(): PackDecl? {
    val min = minFormat
    val max = maxFormat
    if (min != null && max != null) return PackDecl.Range(min, max)
    supportedFormats?.let { return PackDecl.Range(it.min, it.max) }
    return packFormat?.let { PackDecl.Single(it) }
}

/** 1.20.2 이전 게임이 쓰는 선언 (`pack_format` 만). 없으면 null. */
public fun PackMcmeta.legacyDecl(): PackDecl.Single? = packFormat?.let { PackDecl.Single(it) }

/**
 * jar 루트의 `pack.mcmeta` 를 읽는다. 없으면 null.
 * 모듈 내부 전용 — 예외 없는 공개 경계는 [analyzeJar] 다 (CLAUDE.md: 라이브러리 모듈 throw 최소화).
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때.
 */
internal fun readPackMcmeta(jar: Path): PackMcmetaResult? = withZipFile(jar) { zip -> readPackMcmeta(zip) }

/**
 * 이미 열린 zip 의 루트 `pack.mcmeta`. 없으면 null, 1 MiB 초과면 [PackMcmetaResult.Invalid].
 *
 * @throws java.io.IOException 항목 데이터가 손상됐을 때.
 */
internal fun readPackMcmeta(zip: ZipFile): PackMcmetaResult? = when (val read = zip.readRootEntry("pack.mcmeta", DESCRIPTOR_MAX_BYTES)) {
    RootEntryRead.Absent -> null
    RootEntryRead.TooLarge -> PackMcmetaResult.Invalid("pack.mcmeta 크기 상한(${DESCRIPTOR_MAX_BYTES / 1024} KiB) 초과 — 읽지 않음")
    is RootEntryRead.Bytes -> parsePackMcmeta(decodeDescriptorText(read.bytes))
}

// ── 파서 내부 (설계 §7.5) ─────────────────────────────────────────────────
// 정수는 "문자열이 아닌 JSON 원시값 + toIntOrNull ≥ 0" 만 받는다. 게임 코덱보다 의도적으로 엄격하다:
// 88.0·true 같은 모양은 추측하지 않고 Invalid 로 보고한다. 정수는 읽는 즉시 PackFormat 으로 바꾼다 (불변식 3).

/** 첫 위반 사유만 기억하는 읽기 도우미. 예외를 쓰지 않는다. */
private class PackReader {
    var error: String? = null
        private set

    /** 위반을 기록하고 null 을 돌려준다 (첫 사유만 남긴다). */
    fun <T> bad(reason: String): T? {
        if (error == null) error = reason
        return null
    }

    fun nonNegativeInt(element: JsonElement): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toIntOrNull()?.takeIf { it >= 0 }
    }

    /** `supported_formats` / overlay `formats`: 정수, `[a, b]`, `{min_inclusive, max_inclusive}` (a ≤ b). */
    fun inclusiveRange(element: JsonElement, where: String): PackMajorRange? {
        val bounds: Pair<Int, Int>? = when (element) {
            is JsonPrimitive -> nonNegativeInt(element)?.let { it to it }

            is JsonArray -> if (element.size == 2) pairOf(nonNegativeInt(element[0]), nonNegativeInt(element[1])) else null

            is JsonObject -> {
                val min = element["min_inclusive"]?.let { nonNegativeInt(it) }
                val max = element["max_inclusive"]?.let { nonNegativeInt(it) }
                pairOf(min, max)
            }
        }
        if (bounds == null || bounds.first > bounds.second) return bad("$where 형식 오류: $element")
        return PackMajorRange(PackFormat(bounds.first, 0), PackFormat(bounds.second, Int.MAX_VALUE))
    }

    /**
     * `min_format` / `max_format`: 정수 n 또는 `[n]` → min (n,0) / max (n,MAX), `[a, b]` → (a,b).
     * 둘 중 하나만 있으면 위반, min > max 면 위반. 둘 다 없으면 null.
     */
    fun minMax(obj: JsonObject, where: String): Pair<PackFormat, PackFormat>? {
        val minElement = obj["min_format"]
        val maxElement = obj["max_format"]
        if (minElement == null && maxElement == null) return null
        if (minElement == null || maxElement == null) return bad("$where: min_format 과 max_format 은 함께 선언해야 함")
        val min = formatBound(minElement, isMax = false) ?: return bad("$where.min_format 형식 오류: $minElement")
        val max = formatBound(maxElement, isMax = true) ?: return bad("$where.max_format 형식 오류: $maxElement")
        if (min > max) return bad("$where: min_format($min) > max_format($max)")
        return min to max
    }

    private fun formatBound(element: JsonElement, isMax: Boolean): PackFormat? {
        val openMinor = if (isMax) Int.MAX_VALUE else 0
        return when (element) {
            is JsonPrimitive -> nonNegativeInt(element)?.let { PackFormat(it, openMinor) }

            is JsonArray -> when (element.size) {
                1 -> nonNegativeInt(element[0])?.let { PackFormat(it, openMinor) }
                2 -> pairOf(nonNegativeInt(element[0]), nonNegativeInt(element[1]))?.let { PackFormat(it.first, it.second) }
                else -> null
            }

            is JsonObject -> null
        }
    }

    /** `overlays.entries[]`. 데이터로만 보관한다. */
    fun overlays(element: JsonElement): List<PackMcmeta.Overlay>? {
        val entries = ((element as? JsonObject)?.get("entries") as? JsonArray) ?: return bad("overlays.entries 가 배열이 아님")
        val out = ArrayList<PackMcmeta.Overlay>()
        for ((index, entryElement) in entries.withIndex()) {
            val where = "overlays.entries[$index]"
            val entry = entryElement as? JsonObject ?: return bad("$where 가 객체가 아님")
            val directory = (entry["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return bad("$where.directory 가 문자열이 아님")
            val formats = entry["formats"]?.let { inclusiveRange(it, "$where.formats") ?: return null }
            val bounds = minMax(entry, where)
            if (error != null) return null
            out += PackMcmeta.Overlay(directory, formats, bounds?.first, bounds?.second)
        }
        return out
    }

    private fun pairOf(first: Int?, second: Int?): Pair<Int, Int>? = if (first != null && second != null) first to second else null
}
