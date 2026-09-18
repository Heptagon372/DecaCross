package kr.decacross.daemon.install

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 파일 작업 결과. */
sealed interface LayoutIoResult {
    /** 성공. [path] 는 최종 파일. */
    data class Ok(val path: Path) : LayoutIoResult

    /** 실패. 대상 파일은 이전 내용 그대로다 (원자적 교체라 반쯤 쓴 파일이 남지 않는다). */
    data class Failed(val path: Path, val detail: String) : LayoutIoResult
}

/**
 * 같은 디렉터리의 임시 파일에 쓰고 `force` 한 뒤 `ATOMIC_MOVE`(+REPLACE_EXISTING)로 교체한다. 부모 디렉터리는 만든다.
 * WP0 완성본·동결 — 여러 작업 패키지(INSTALL, PROC, FETCH)가 함께 쓰므로 한 곳에만 둔다 (critique A2).
 *
 * # 불변식
 * - 던지지 않는다 (`IOException`·`SecurityException` → [LayoutIoResult.Failed]). 실패하면 임시 파일을 지운다.
 * - 블로킹 I/O 다. 코루틴에서는 `Dispatchers.IO` 에서 부른다.
 */
fun writeFileAtomically(target: Path, bytes: ByteArray): LayoutIoResult {
    val dir = target.toAbsolutePath().parent ?: return LayoutIoResult.Failed(target, "부모 디렉터리 없음")
    var temp: Path? = null
    return try {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".dcx-", ".tmp")
        temp = tmp
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        temp = null
        LayoutIoResult.Ok(target)
    } catch (e: IOException) {
        LayoutIoResult.Failed(target, e.toString())
    } catch (e: SecurityException) {
        LayoutIoResult.Failed(target, e.toString())
    } finally {
        val leftover = temp
        if (leftover != null) {
            try {
                Files.deleteIfExists(leftover)
            } catch (e: IOException) {
                // 임시 파일 정리 실패는 결과를 바꾸지 않는다 (이름이 `.dcx-*.tmp` 라 사용자 파일과 섞이지 않는다)
            }
        }
    }
}
