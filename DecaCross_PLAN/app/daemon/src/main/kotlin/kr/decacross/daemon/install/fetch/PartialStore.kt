package kr.decacross.daemon.install.fetch

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kr.decacross.daemon.install.FetchItem
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.writeFileAtomically
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 부분 파일 메타데이터 (`{sha256}.part.json`).
 *
 * # 불변식
 * - [sha256]·[size] 가 [FetchItem] 과 다르면 메타와 `.part` 를 버린다 (다른 파일의 찌꺼기).
 * - [durableLength] 는 `force` 로 디스크에 내려간 것이 확실한 길이다. 이어받기 오프셋은 항상
 *   `min(실제 파일 크기, durableLength)` 로 잡는다.
 */
@Serializable
internal data class PartMeta(
    val v: Int = 1,
    val sha256: String,
    val size: Long,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val date: String? = null,
    val durableLength: Long = 0,
)

/**
 * `%LOCALAPPDATA%\DecaCross\cache\partial` 의 부분 파일 저장소 (SCP-F2, D-I8).
 *
 * 파일 세 개를 한 벌로 다룬다: `{sha}.part` (받은 바이트), `{sha}.part.json` (메타), `{sha}.lock` (프로세스 간 잠금).
 * 성공한 `.part` 는 이름을 바꾸지 않는다 — 그 파일 자체가 검증 대상이고, `Ready` 뒤 [kr.decacross.daemon.install.ArtifactFetcher.discard]
 * 가 지운다.
 *
 * # 불변식
 * - 모두 블로킹 I/O 다. 코루틴에서는 `Dispatchers.IO` 에서 부른다.
 * - 삭제·정리 함수는 던지지 않는다.
 * - 잠금을 이미 쥐고 있는 쪽(다운로드 중)은 [deleteFiles] 를 직접 부른다. 잠금부터 잡아야 하는 쪽은
 *   [kr.decacross.daemon.install.Fetcher.discard] 를 쓴다 (같은 JVM 에서 겹쳐 잠그면 `OverlappingFileLockException`).
 */
internal class PartialStore(private val dir: Path) {
    fun partFile(item: FetchItem): Path = dir.resolve("${item.sha256}.part")

    fun metaFile(item: FetchItem): Path = dir.resolve("${item.sha256}.part.json")

    fun lockFile(sha256: String): Path = dir.resolve("$sha256.lock")

    /** 현재 `.part` 크기. 없거나 읽을 수 없으면 0. */
    fun partLength(item: FetchItem): Long = try {
        val p = partFile(item)
        if (Files.isRegularFile(p)) Files.size(p) else 0L
    } catch (e: IOException) {
        0L
    }

    /** 메타 읽기. 파일이 없거나 JSON 이 깨졌거나 sha256/size 가 다르면 null. */
    fun readMeta(item: FetchItem): PartMeta? {
        val p = metaFile(item)
        val text = try {
            if (!Files.isRegularFile(p)) return null
            Files.readString(p)
        } catch (e: IOException) {
            return null
        }
        val meta = try {
            JSON.decodeFromString(PartMeta.serializer(), text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (meta.v != 1 || meta.sha256 != item.sha256 || meta.size != item.size) return null
        return meta
    }

    /** 메타 쓰기 (동결된 [writeFileAtomically] — 임시 파일 + `ATOMIC_MOVE`). 실패하면 false. */
    fun writeMeta(item: FetchItem, meta: PartMeta): Boolean {
        val bytes = try {
            JSON.encodeToString(PartMeta.serializer(), meta).toByteArray(Charsets.UTF_8)
        } catch (e: SerializationException) {
            return false
        }
        return writeFileAtomically(metaFile(item), bytes) is LayoutIoResult.Ok
    }

    /**
     * 이어받기 준비. 메타가 없거나 맞지 않으면 `.part`·`.part.json` 을 버리고 0 을 돌려준다.
     * 그렇지 않으면 `min(파일 크기, durableLength)` 로 `.part` 를 자르고 그 오프셋을 돌려준다.
     *
     * @return 이어받을 오프셋 (0 = 처음부터)
     */
    fun prepare(item: FetchItem): Long {
        val meta = readMeta(item)
        if (meta == null) {
            deleteFiles(item)
            return 0L
        }
        val offset = minOf(partLength(item), meta.durableLength).coerceIn(0L, item.size)
        if (offset <= 0L) return 0L
        return try {
            FileChannel.open(partFile(item), StandardOpenOption.WRITE).use { ch -> ch.truncate(offset) }
            offset
        } catch (e: IOException) {
            0L
        }
    }

    /** `.part` 와 `.part.json` 삭제 (`.lock` 은 남긴다). 던지지 않는다. ★ 호출자가 이미 잠금을 쥐고 있어야 한다. */
    fun deleteFiles(item: FetchItem) {
        deleteQuietly(partFile(item))
        deleteQuietly(metaFile(item))
    }

    /**
     * `{sha256}.lock` 삭제. 던지지 않는다.
     *
     * # 불변식
     * - ★ 잠금을 **놓은 뒤에** 부른다 (Windows 는 열린 파일을 지우지 못한다). 지우지 못해도 다음 스윕이 다시 본다.
     */
    fun deleteLockFile(sha256: String) {
        deleteQuietly(lockFile(sha256))
    }

    /**
     * `{sha}.lock` 을 잡는다. 다른 프로세스(또는 같은 JVM 의 다른 잠금)가 쥐고 있으면 null.
     * 잠금 파일 자체는 지우지 않는다 (0 바이트).
     */
    fun tryLock(sha256: String): PartialLock? {
        val path = lockFile(sha256)
        var channel: FileChannel? = null
        return try {
            Files.createDirectories(dir)
            val ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            channel = ch
            val lock = try {
                ch.tryLock()
            } catch (e: OverlappingFileLockException) {
                // 같은 JVM 의 다른 채널이 쥐고 있다 — 남이 쓰는 것으로 본다
                null
            }
            if (lock == null) {
                ch.close()
                channel = null
                null
            } else {
                channel = null
                PartialLock(ch, lock)
            }
        } catch (e: IOException) {
            closeQuietly(channel)
            null
        }
    }

    /**
     * 오래된 부분 파일 청소 (D-I8: 7일, 잠긴 것은 건너뛴다). `Fetcher` 시작 때 한 번 돈다. 던지지 않는다.
     *
     * @param maxAgeMs 이보다 오래된 파일만 지운다
     */
    fun sweepOldPartials(maxAgeMs: Long, nowEpochMs: Long = System.currentTimeMillis()) {
        if (!Files.isDirectory(dir)) return
        val newest = HashMap<String, Long>()
        val strayTemps = ArrayList<Path>()
        // 짝(`.part`/`.part.json`)이 없는 잠금 파일은 여기서만 찾을 수 있다 — 아래에서 나이를 보고 치운다
        val orphanLockAge = HashMap<String, Long>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    val name = p.fileName?.toString() ?: continue
                    val mtime = try {
                        Files.getLastModifiedTime(p).toMillis()
                    } catch (e: IOException) {
                        continue
                    }
                    val sha = when {
                        name.endsWith(".part.json") -> name.removeSuffix(".part.json")

                        name.endsWith(".part") -> name.removeSuffix(".part")

                        name.endsWith(".lock") -> {
                            orphanLockAge[name.removeSuffix(".lock")] = mtime
                            continue
                        }

                        name.startsWith(".dcx-") && name.endsWith(".tmp") -> {
                            if (nowEpochMs - mtime > maxAgeMs) strayTemps.add(p)
                            continue
                        }

                        else -> continue
                    }
                    newest[sha] = maxOf(newest[sha] ?: Long.MIN_VALUE, mtime)
                }
            }
        } catch (e: IOException) {
            return
        }
        strayTemps.forEach(::deleteQuietly)
        for ((sha, mtime) in newest) {
            if (nowEpochMs - mtime <= maxAgeMs) continue
            val lock = tryLock(sha) ?: continue
            try {
                deleteQuietly(dir.resolve("$sha.part"))
                deleteQuietly(dir.resolve("$sha.part.json"))
            } finally {
                lock.close()
            }
            // 잠금을 놓은 뒤에야 잠금 파일을 지울 수 있다 (Windows 는 열린 파일을 못 지운다)
            deleteQuietly(lockFile(sha))
        }
        // 조각은 없고 잠금 파일만 남은 sha (지난 실행이 남긴 0 바이트 파일): 잡히는 것만 치운다
        for ((sha, mtime) in orphanLockAge) {
            if (newest.containsKey(sha)) continue
            if (nowEpochMs - mtime <= maxAgeMs) continue
            val lock = tryLock(sha) ?: continue
            lock.close()
            deleteQuietly(lockFile(sha))
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            // 지우지 못해도 다음 실행이 다시 시도한다
        } catch (e: SecurityException) {
            // 권한 문제도 마찬가지
        }
    }

    private fun closeQuietly(channel: FileChannel?) {
        try {
            channel?.close()
        } catch (e: IOException) {
            // 닫기 실패는 결과를 바꾸지 않는다
        }
    }

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** 프로세스 간 잠금 손잡이. [close] 는 던지지 않는다. */
internal class PartialLock(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } catch (e: IOException) {
            // 이미 풀렸거나 채널이 닫혔다
        }
        try {
            channel.close()
        } catch (e: IOException) {
            // 무시
        }
    }
}
