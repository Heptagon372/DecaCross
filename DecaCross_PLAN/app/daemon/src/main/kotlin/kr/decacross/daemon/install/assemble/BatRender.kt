package kr.decacross.daemon.install.assemble

import kr.decacross.compat.model.Os
import kr.decacross.daemon.paths.hostEnvironment

/**
 * start.bat / start.sh 토큰 만들기 (DESIGN2 §2.7, research codebase-windows §6 프로브로 확인).
 *
 * # 불변식
 * - Java 경로 외의 토큰은 전부 ASCII 여야 한다 (플래그는 리소스, jar 이름은 정규화된 이름).
 * - 스크립트 어디에도 `eula` 문자열을 넣지 않는다 (critique B2).
 */

/** start.bat 의 java 토큰. [needsUtf8] 이면 파일을 UTF-8 + `chcp` 변형(C)으로 써야 한다. */
internal data class BatJavaToken(val token: String, val needsUtf8: Boolean)

/** `%LOCALAPPDATA%` 처럼 접두사를 대체해 파일을 ASCII 로 유지할 때 쓰는 환경변수 (SCP-I22, 순서 = 선호도). */
internal val BAT_PATH_VARIABLES: List<String> = listOf("LOCALAPPDATA", "USERPROFILE", "ProgramFiles", "ProgramFiles(x86)")

/** 모두 ASCII 인가 (제어문자 포함 여부는 따지지 않는다 — 경로·플래그에는 나오지 않는다). */
internal fun isAscii(text: String): Boolean = text.all { it.code < 0x80 }

/**
 * start.bat 의 java 토큰을 고른다.
 * 1. ASCII 경로면 그대로, 2. 아니면 [BAT_PATH_VARIABLES] 접두사 대체(가장 긴 값 우선), 3. 둘 다 아니면 UTF-8 변형.
 *
 * ★ 세 갈래 모두 리터럴 `%` 는 `%%` 로 이중화한다 — cmd 는 배치 파일의 짝 없는 `%` 를 지워 버려서
 * `D:\한글 100%\bin\java.exe` 가 `D:\한글 100\bin\java.exe` 로 실행된다 (프로브: rc 3 "지정된 경로를 찾을 수 없습니다").
 */
internal fun batJavaToken(javaPath: String, env: Map<String, String>): BatJavaToken {
    if (isAscii(javaPath)) return BatJavaToken("\"" + javaPath.replace("%", "%%") + "\"", needsUtf8 = false)
    // Windows 는 환경변수 이름 대소문자를 구분하지 않는다 (D-I40: Git Bash 의 PROGRAMFILES, 일반 셸의 ProgramFiles)
    val ci = hostEnvironment(Os.WINDOWS, env)
    var bestVariable: String? = null
    var bestRemainder = ""
    var bestLength = -1
    for (variable in BAT_PATH_VARIABLES) {
        val value = ci[variable]?.takeIf { it.isNotBlank() }?.trimEnd('\\') ?: continue
        if (!javaPath.startsWith(value, ignoreCase = true)) continue
        val remainder = javaPath.substring(value.length)
        if (remainder.isNotEmpty() && !remainder.startsWith("\\")) continue
        if (!isAscii(remainder)) continue
        if (value.length > bestLength) {
            bestLength = value.length
            bestVariable = variable
            bestRemainder = remainder
        }
    }
    val variable = bestVariable
        ?: return BatJavaToken("\"" + javaPath.replace("%", "%%") + "\"", needsUtf8 = true)
    return BatJavaToken("\"%$variable%" + bestRemainder.replace("%", "%%") + "\"", needsUtf8 = false)
}

/** POSIX 셸 인용: 안전한 문자만이면 그대로, 아니면 작은따옴표로 감싸고 내부 `'` 를 `'\''` 로 쪼갠다. */
internal fun shellQuotePosix(text: String): String =
    if (text.isNotEmpty() && SAFE_POSIX_TOKEN.matches(text)) text else "'" + text.replace("'", "'\\''") + "'"

/**
 * java 경로를 뺀 스크립트 토큰 검사. ASCII 가 아니거나 `eula` 를 담으면 스크립트를 만들지 않는다
 * (파이프라인이 [kr.decacross.daemon.install.InstallFailure.LocalIo] 로 보고한다).
 */
internal fun checkScriptTokens(tokens: List<String>) {
    for (token in tokens) {
        require(isAscii(token)) { "스크립트 토큰이 ASCII 가 아닙니다: $token" }
        require(!token.contains("eula", ignoreCase = true)) { "스크립트에 넣을 수 없는 토큰입니다: $token" }
    }
}

private val SAFE_POSIX_TOKEN = Regex("^[A-Za-z0-9_./:=+,@%-]+$")
