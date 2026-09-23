package kr.decacross.logparse

/**
 * 에러 시그니처. 규칙은 코드가 아니라 데이터다 — DB(error_signatures)에서 로드하고 모듈에는 시드 JSON 만 번들.
 *
 * @property key 고유 키 (`unsupported_class_version` 등)
 * @property pattern 매칭 정규식. [LogLine.fullText] (본문 + 연속 줄) 에 `find` 로 적용된다
 * @property category 분류 (`java`, `plugin`, `network`, ...)
 * @property captures 캡처 그룹 이름. 그룹 순서대로 대응한다. 매칭 안 된 그룹은 결과에서 빠진다
 * @property priority 한 엔트리에 여러 시그니처가 맞을 때 큰 값이 이긴다. 같으면 목록 앞이 이긴다
 */
public data class Signature(
    val key: String,
    val pattern: Regex,
    val category: String,
    val captures: List<String> = emptyList(),
    val priority: Int = 0,
)

/**
 * 매칭 결과.
 *
 * @property captured [Signature.captures] 이름 → 캡처 값
 */
public data class SignatureMatch(
    val signature: Signature,
    val captured: Map<String, String>,
    val line: LogLine,
)

/**
 * 한 엔트리에 맞는 시그니처 중 최우선 하나. 없으면 `null`.
 * 매칭 대상은 [matchText] — 스레드 접두를 포함한 본문과 연속 줄 전부. `Caused by:` 가 진짜 원인을 담기 때문이다.
 */
public fun matchSignatures(line: LogLine, signatures: List<Signature>): SignatureMatch? = matchAllSignatures(line, signatures).firstOrNull()

/** 한 엔트리에 맞는 시그니처 전부. 우선순위 내림차순(같으면 목록 순). */
public fun matchAllSignatures(line: LogLine, signatures: List<Signature>): List<SignatureMatch> {
    val text = matchText(line)
    val matches = mutableListOf<SignatureMatch>()
    for (signature in signatures) {
        val m = runCatching { signature.pattern.find(text) }.getOrNull() ?: continue
        val captured = LinkedHashMap<String, String>()
        signature.captures.forEachIndexed { index, name ->
            val value = m.groups[index + 1]?.value ?: return@forEachIndexed
            captured[name] = value
        }
        matches += SignatureMatch(signature, captured, line)
    }
    return matches.sortedByDescending { it.signature.priority }
}

/**
 * 시그니처 매칭에 쓰는 텍스트. `[thread/LEVEL]: message` 헤더 + 연속 줄.
 * 스레드 이름을 포함하는 이유: Paper 의 watchdog 은 `[Paper Watchdog Thread/ERROR]: Server thread dump ...` 처럼
 * "Watchdog" 이 스레드 이름에만 나온다.
 */
public fun matchText(line: LogLine): String {
    val header =
        buildString {
            if (line.thread != null || line.level != null) {
                append('[').append(line.thread ?: "").append('/').append(line.level ?: "").append("]: ")
            }
            append(line.message)
        }
    return if (line.continuation.isEmpty()) header else (listOf(header) + line.continuation).joinToString("\n")
}

/**
 * `Unsupported class file major version 65` 의 65 → 필요한 Java 메이저(21).
 * JVM 클래스 파일 메이저 버전 = Java 버전 + 44.
 */
public fun requiredJavaFromClassMajor(major: Int): Int = major - 44
