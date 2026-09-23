package kr.decacross.daemon.install

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** SHA-256 (소문자 hex). 스트리밍이라 큰 jar 도 메모리를 안 먹는다. */
fun sha256(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** zip 무결성: 모든 엔트리를 끝까지 읽어 CRC 를 검사한다. 실패하면 false. */
fun isValidZip(path: Path): Boolean =
    runCatching {
        ZipFile(path.toFile()).use { zip ->
            val buf = ByteArray(64 * 1024)
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                zip.getInputStream(entry).use { s ->
                    while (s.read(buf) >= 0) { /* CRC 는 스트림 끝에서 검증된다 */ }
                }
            }
            zip.size() > 0
        }
    }.getOrDefault(false)
