package kr.decacross.daemon.paths

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * OneDrive 동기화 폴더 판정: 경로 문자열에 `OneDrive` 가 있거나(대소문자 무시),
 * 환경변수 `OneDrive` / `OneDriveConsumer` / `OneDriveCommercial` 디렉터리 아래다.
 *
 * 수집기 `kr.decacross.collector.config.isUnderOneDrive` 와 같은 규칙이다. 데몬은 수집기에 의존할 수 없어서 복사했다
 * (공유 모듈 추출은 SCP-F4 후보).
 */
fun isUnderOneDrive(path: Path, env: Map<String, String>): Boolean {
    val p = normalizedText(path)
    if (p.contains("onedrive")) return true
    return listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial").any { name ->
        val root = env[name]?.takeIf { it.isNotBlank() } ?: return@any false
        val r = try {
            normalizedText(Path.of(root)).trimEnd('/')
        } catch (e: InvalidPathException) {
            return@any false
        }
        r.isNotEmpty() && (p == r || p.startsWith("$r/"))
    }
}

/** Windows UNC 경로(`\\server\share\...`)인가. 네트워크 드라이브의 서버 폴더는 잠금·원자적 이동이 보장되지 않는다. */
fun isUncPath(path: Path): Boolean {
    val s = path.toString()
    return s.startsWith("\\\\") || s.startsWith("//")
}

private fun normalizedText(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/').lowercase()
