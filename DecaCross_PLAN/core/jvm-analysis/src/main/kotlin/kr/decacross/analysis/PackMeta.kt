package kr.decacross.analysis

import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import java.nio.file.Path

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
public fun parsePackMcmeta(json: String): PackMcmetaResult = TODO("WP-JA")

/**
 * 1.20.2+ 게임이 쓰는 선언. min/max → `Range`, supported_formats → `Range(min, max)` ([PackMajorRange] 그대로),
 * pack_format 만 → `Single`. 해당 없으면 null.
 */
public fun PackMcmeta.modernDecl(): PackDecl? = TODO("WP-JA")

/** 1.20.2 이전 게임이 쓰는 선언 (`pack_format` 만). 없으면 null. */
public fun PackMcmeta.legacyDecl(): PackDecl.Single? = TODO("WP-JA")

/**
 * jar 루트의 `pack.mcmeta` 를 읽는다. 없으면 null.
 * 모듈 내부 전용 — 예외 없는 공개 경계는 [analyzeJar] 다 (CLAUDE.md: 라이브러리 모듈 throw 최소화).
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때.
 */
internal fun readPackMcmeta(jar: Path): PackMcmetaResult? = TODO("WP-JA")
