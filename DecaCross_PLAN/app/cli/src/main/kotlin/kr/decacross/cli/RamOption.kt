package kr.decacross.cli

/** `--ram` 해석 결과. 예외 대신 sealed 결과 (CLAUDE.md 코딩 규칙). */
sealed interface RamParse {
    /** MB 단위 값. 하한([kr.decacross.daemon.install.MIN_RAM_MB]) 검사는 PLAN 이 한다. */
    data class Ok(val megabytes: Int) : RamParse

    /** 형식 오류. [reasonKo] 는 그대로 사용자에게 보여 준다. */
    data class Invalid(val reasonKo: String) : RamParse
}

/**
 * `4G` / `4g` / `4096M` / `4096`(단위 없으면 MB) → MB (DESIGN2 D-I28).
 *
 * # 불변식
 * - 정수만 받는다 (`1.5G` 거부 — JVM `-Xmx` 도 소수를 받지 않는다).
 * - 0 이하 거부. 하한(1 GB, SCP-I13)은 여기서 보지 않는다: `512M` 은 파싱에 성공하고 PLAN 이 거부한다.
 * - 상한은 `Int` MB 로 표현 가능한 범위 (`-Xmx` 인자를 MB 로 렌더링하므로).
 */
fun parseRamMb(text: String): RamParse {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return RamParse.Invalid("메모리 값이 비어 있습니다 (예: 4G, 4096M)")
    val unit = trimmed.last()
    val (digits, multiplier) =
        when {
            unit == 'g' || unit == 'G' -> trimmed.dropLast(1) to 1024L
            unit == 'm' || unit == 'M' -> trimmed.dropLast(1) to 1L
            unit in '0'..'9' -> trimmed to 1L
            else -> return RamParse.Invalid("메모리 단위는 G 또는 M 만 됩니다: '$trimmed' (예: 4G, 4096M)")
        }
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) {
        return RamParse.Invalid("메모리 값은 정수여야 합니다: '$trimmed' (예: 4G, 4096M)")
    }
    val amount = digits.toLongOrNull() ?: return RamParse.Invalid("메모리 값이 너무 큽니다: '$trimmed'")
    // ★ 곱하기 전에 상한을 본다: `18014398509481985G` 처럼 Long 을 넘겨 감기는 값이 있으면
    //    `amount * 1024` 가 작은 양수(1024)로 돌아와 말도 안 되는 요청이 통과한다
    if (amount > Int.MAX_VALUE) return RamParse.Invalid("메모리 값이 너무 큽니다: '$trimmed'")
    val megabytes = amount * multiplier
    if (megabytes <= 0) return RamParse.Invalid("메모리 값은 0보다 커야 합니다: '$trimmed'")
    if (megabytes > Int.MAX_VALUE) return RamParse.Invalid("메모리 값이 너무 큽니다: '$trimmed'")
    return RamParse.Ok(megabytes.toInt())
}
