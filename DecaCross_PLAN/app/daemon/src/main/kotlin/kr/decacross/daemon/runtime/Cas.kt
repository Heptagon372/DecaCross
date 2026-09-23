package kr.decacross.daemon.runtime

import org.slf4j.LoggerFactory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 내용 주소 저장소 (설계서 §4.3). `cache/blobs/{sha[0..1]}/{sha256}`.
 *
 * 서버 5개를 만들어도 같은 jar 는 디스크에 1벌 — 서버 폴더의 파일은 blob 으로의 하드링크다.
 * 하드링크가 안 되는 파일시스템(다른 볼륨 등)에서는 복사로 폴백하고 경고를 남긴다.
 *
 * # 불변식
 * - blob 은 불변이다. 한 번 들어간 파일은 수정하지 않는다.
 * - blob 경로 이름 = 내용의 sha256. 넣기 전에 반드시 해시를 검증한다 ([put] 이 한다).
 */
class Cas(private val blobsDir: Path) {
    private val log = LoggerFactory.getLogger(Cas::class.java)

    /** 하드링크가 실패해 복사로 폴백한 횟수 (진단용). */
    @Volatile
    var copyFallbacks: Long = 0
        private set

    fun blobPath(sha256: String): Path {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 형식이 아니다: $sha256" }
        return blobsDir.resolve(sha256.substring(0, 2)).resolve(sha256)
    }

    fun has(sha256: String): Boolean = Files.isRegularFile(blobPath(sha256))

    /**
     * 임시 파일을 blob 으로 들인다. 해시가 [expectedSha256] 과 다르면 임시 파일을 지우고 null 을 돌려준다.
     * 이미 같은 blob 이 있으면 임시 파일만 지운다.
     */
    fun put(tempFile: Path, expectedSha256: String, actualSha256: String): Path? {
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            Files.deleteIfExists(tempFile)
            return null
        }
        val target = blobPath(expectedSha256.lowercase())
        Files.createDirectories(target.parent)
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(tempFile)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (Files.exists(target)) Files.deleteIfExists(tempFile) else Files.move(tempFile, target)
        }
        return target
    }

    /** blob → [dest] 하드링크. 실패 시 복사 폴백 + 경고. [dest] 가 이미 있으면 덮어쓴다. */
    fun linkInto(sha256: String, dest: Path) {
        val blob = blobPath(sha256.lowercase())
        check(Files.isRegularFile(blob)) { "blob 없음: $sha256" }
        Files.createDirectories(dest.parent)
        Files.deleteIfExists(dest)
        try {
            Files.createLink(dest, blob)
        } catch (e: Exception) {
            // 다른 볼륨 / 하드링크 미지원 FS / 권한 — 복사로 폴백
            copyFallbacks++
            log.warn("하드링크 실패, 복사로 폴백: {} → {} ({})", blob, dest, e.toString())
            Files.copy(blob, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 어떤 서버도 참조하지 않는 blob 을 지운다. [referenced] 는 각 서버의 `.decacross/manifest.json` 해시 집합의 합.
     * [olderThan] 보다 최근에 만들어진 blob 은 참조가 없어도 남긴다 (설치 진행 중일 수 있다). 지운 바이트 수를 돌려준다.
     */
    fun gc(referenced: Set<String>, olderThan: java.time.Duration): Long {
        if (!Files.isDirectory(blobsDir)) return 0
        val cutoff = java.time.Instant.now().minus(olderThan)
        var freed = 0L
        Files.walk(blobsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList().forEach { blob ->
                val sha = blob.fileName.toString()
                if (sha in referenced) return@forEach
                if (Files.getLastModifiedTime(blob).toInstant().isAfter(cutoff)) return@forEach
                val size = Files.size(blob)
                if (runCatching { Files.deleteIfExists(blob) }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    data class CasStats(val blobCount: Long, val totalBytes: Long)

    fun stats(): CasStats {
        if (!Files.isDirectory(blobsDir)) return CasStats(0, 0)
        var count = 0L
        var bytes = 0L
        Files.walk(blobsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach {
                count++
                bytes += Files.size(it)
            }
        }
        return CasStats(count, bytes)
    }
}
