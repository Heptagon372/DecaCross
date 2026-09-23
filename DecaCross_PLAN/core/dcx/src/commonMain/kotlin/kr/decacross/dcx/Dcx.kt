package kr.decacross.dcx

/**
 * `.dcx` v1.1 레시피 (설계서 §7). 필드는 `dcx-1.1.schema.json` 과 1:1 — 스키마가 단일 진실 소스다.
 * TS 타입은 스키마에서 생성한다(08). 손으로 두 곳을 맞추지 마라.
 */
public sealed interface DcxParseResult {
    public data class Ok(val recipe: DcxRecipe) : DcxParseResult

    public data class Invalid(val errorsKo: List<String>) : DcxParseResult
}

/** 08 단계에서 스키마와 1:1 로 확정한다. */
public data class DcxRecipe(
    val dcx: String,
    val id: String,
    val name: String,
)

public fun parseDcx(json: String): DcxParseResult = TODO("08")
