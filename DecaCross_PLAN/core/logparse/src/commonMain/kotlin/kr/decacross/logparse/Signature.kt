package kr.decacross.logparse

/**
 * 에러 시그니처. 규칙은 코드가 아니라 데이터다 — DB(error_signatures)에서 로드하고 모듈에는 시드 JSON 만 번들.
 */
public data class Signature(
    val key: String,
    val pattern: Regex,
    val category: String,
    val captures: List<String> = emptyList(),
)

public data class SignatureMatch(
    val signature: Signature,
    val captured: Map<String, String>,
    val line: LogLine,
)

public fun matchSignatures(line: LogLine, signatures: List<Signature>): SignatureMatch? = TODO("06")
