package kr.decacross.daemon.paths

import kr.decacross.compat.model.Os
import java.util.TreeMap

/**
 * 이 JVM 이 도는 OS (명세 §2 [Os]). `os.name` 문자열을 코드 곳곳에서 해석하지 않도록 여기서 한 번만 바꾼다.
 * 알 수 없는 이름은 [Os.LINUX] 로 본다 (POSIX 규칙이 가장 무난하다).
 */
fun currentOs(osName: String = System.getProperty("os.name").orEmpty()): Os =
    when {
        osName.startsWith("Windows", ignoreCase = true) -> Os.WINDOWS
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> Os.MAC
        else -> Os.LINUX
    }

/**
 * OS 에 맞는 환경변수 맵. Windows 는 이름의 대소문자를 구분하지 않는다 — 일반 셸에서 `PATH` 는 실제로 `Path` 이고
 * Git Bash 에서 `ProgramFiles` 는 `PROGRAMFILES` 다 (critique windows #1 실측). `System.getenv()` 맵은 대소문자를 구분하므로
 * Windows 에서는 대소문자 무시 맵으로 감싼다. 그 밖의 OS 는 그대로.
 *
 * # 불변식
 * - 환경을 읽기만 한다. 바꾸지 않는다 (불변식 10).
 * - 같은 이름이 대소문자만 달리 두 번 있으면(드묾) 원본 맵 순회 순서의 첫 값이 남는다.
 */
fun hostEnvironment(os: Os = currentOs(), raw: Map<String, String> = System.getenv()): Map<String, String> =
    if (os != Os.WINDOWS) {
        raw
    } else {
        val ci = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((k, v) in raw) ci.putIfAbsent(k, v)
        ci
    }
