package kr.decacross.daemon.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

/**
 * 디렉터리 트리 스냅샷. "사용자 폴더 무오염" 판정에 쓴다: 설치 전·실패 롤백 후 스냅샷이 같아야 한다.
 * 값: 디렉터리 `d`, 파일 `f:<크기>:<sha256>`. 키: 루트 기준 상대경로(`/` 구분). 루트가 없으면 빈 맵.
 */
object TreeSnapshot {
    fun of(root: Path): Map<String, String> {
        if (!Files.exists(root)) return emptyMap()
        val out = sortedMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.forEach { p ->
                if (p == root) return@forEach
                val key = p.relativeTo(root).toString().replace('\\', '/')
                out[key] = if (p.isDirectory()) "d" else "f:${Files.size(p)}:${sha256(p)}"
            }
        }
        return out
    }

    private fun sha256(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
