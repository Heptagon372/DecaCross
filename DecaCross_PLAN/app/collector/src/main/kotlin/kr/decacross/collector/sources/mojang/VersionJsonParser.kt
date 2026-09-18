package kr.decacross.collector.sources.mojang

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.decacross.collector.CollectorJson
import kr.decacross.compat.model.PackFormat

/**
 * jar 안 `version.json` / `pack.mcmeta` 에서 읽은 사실.
 *
 * [era]: `E4` (pack_version 객체, major/minor 분리) | `E3` (pack_version 객체 resource/data) | `E2` (pack_version 정수)
 * | `E1` (pack.mcmeta + `data/` 디렉터리) | `E0` (팩 포맷 없음).
 */
internal data class JarFacts(
    val era: String,
    val rp: PackFormat?,
    val dp: PackFormat?,
    val protocol: Int?,
    val worldVersion: Int?,
    val seriesId: String?,
)

internal const val ERA_E0: String = "E0"

/**
 * 시대별 규칙으로 rp/dp 포맷을 뽑는다 (AE-8: E4 → E3 → E2 → E1(`data/` 있을 때) → E0).
 *
 * # 불변식
 * - 절대 던지지 않는다. 깨진 JSON·음수·소수·문자열 숫자는 null 로 본다 ([PackFormat] 의 `require` 예외를 부르지 않는다).
 * - `pack_version` 이 E4/E3/E2 모양(객체에 `resource_major`/`resource`, 또는 JSON 숫자)인데 값이 유효하지 않으면
 *   E0(null)이다. 아래 시대(E1 의 pack.mcmeta)로 내려가 추측하지 않는다 — 그 jar 는 E2 이후라 pack.mcmeta 값이 rp/dp 가 아니다.
 * - 규칙 모양에 맞지 않는 `pack_version`(키 없는 객체·문자열·null·불리언)은 없는 것으로 보고 E1 규칙으로 넘어간다.
 * - 1.6.x client jar 는 `pack.mcmeta`(pack_format 1)가 있지만 `data/` 가 없으므로 E0 이다 (D3).
 * - label 은 항상 매니페스트 id 를 쓴다. version.json 의 `id` 는 보지 않는다.
 */
internal fun parseJarFacts(versionJson: String?, packMcmeta: String?, hasDataDir: Boolean): JarFacts {
    val vj = parseObject(versionJson)
    val pm = parseObject(packMcmeta)

    val protocol = vj?.let { nonNegativeInt(it["protocol_version"]) }
    val worldVersion = vj?.let { nonNegativeInt(it["world_version"]) }
    val seriesId = vj?.let { stringOrNull(it["series_id"]) }

    fun facts(era: String, rp: PackFormat?, dp: PackFormat?) = JarFacts(era, rp, dp, protocol, worldVersion, seriesId)

    val packVersion = vj?.get("pack_version")
    if (packVersion is JsonObject) {
        if ("resource_major" in packVersion) {
            val rp = packFormat(packVersion["resource_major"], packVersion["resource_minor"])
            val dp = packFormat(packVersion["data_major"], packVersion["data_minor"])
            return if (rp != null && dp != null) facts("E4", rp, dp) else facts(ERA_E0, null, null)
        } else if ("resource" in packVersion) {
            val rp = packFormat(packVersion["resource"], null)
            val dp = packFormat(packVersion["data"], null)
            return if (rp != null && dp != null) facts("E3", rp, dp) else facts(ERA_E0, null, null)
        }
    } else if (isJsonNumber(packVersion)) {
        val n = nonNegativeInt(packVersion) ?: return facts(ERA_E0, null, null)
        return facts("E2", PackFormat(n), PackFormat(n))
    }

    if (hasDataDir) {
        val pack = pm?.get("pack") as? JsonObject
        val n = nonNegativeInt(pack?.get("pack_format"))
        if (n != null) return facts("E1", PackFormat(n), PackFormat(n))
    }
    return facts(ERA_E0, null, null)
}

private fun parseObject(text: String?): JsonObject? {
    if (text == null) return null
    return try {
        CollectorJson.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** 따옴표 없는 JSON 숫자 리터럴 (정수·소수·지수·음수). null·불리언·문자열은 아니다. */
private fun isJsonNumber(el: JsonElement?): Boolean {
    val p = el as? JsonPrimitive ?: return false
    return !p.isString && p.content.toBigDecimalOrNull() != null
}

/** JSON 정수(문자열 아님, 소수 아님)이고 0 이상이면 그 값. */
private fun nonNegativeInt(el: JsonElement?): Int? {
    val p = el as? JsonPrimitive ?: return null
    if (p.isString) return null
    val n = p.content.toIntOrNull() ?: return null
    return n.takeIf { it >= 0 }
}

private fun stringOrNull(el: JsonElement?): String? {
    val p = el as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/** major 필수, minor 는 [minorEl] 이 null(키 없음)일 때만 0. 둘 다 음수가 아닌 정수여야 한다. */
private fun packFormat(majorEl: JsonElement?, minorEl: JsonElement?): PackFormat? {
    val major = nonNegativeInt(majorEl) ?: return null
    val minor = if (minorEl == null) 0 else nonNegativeInt(minorEl) ?: return null
    return PackFormat(major, minor)
}
