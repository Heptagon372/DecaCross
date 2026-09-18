package kr.decacross.daemon.install.assemble

import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * 설치 파이프라인이 쓰는 파일 보조 함수 (WP-INSTALL 내부 전용). 전부 블로킹이므로 코루틴에서는 `Dispatchers.IO` 에서 부른다.
 *
 * # 불변식
 * - 어떤 함수도 던지지 않는다. 실패는 반환값(실패 경로 목록·null·false)으로 알린다.
 * - 잠금은 항상 열었던 채널까지 닫는다 (Windows 에서 열린 핸들은 삭제·이동을 막는다).
 */

/** 쥐고 있는 파일 잠금. [close] 는 해제 + 채널 닫기. 두 번 닫아도 안전하다. */
internal class FileLockHandle(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } catch (e: IOException) {
            // 이미 풀렸거나 채널이 닫힘 — 해제는 최선 노력이다
        }
        try {
            channel.close()
        } catch (e: IOException) {
            // 닫기 실패는 뒤따르는 삭제 실패로 드러난다
        }
    }
}

/**
 * [acquireLockFile] 결과. ★ "남이 쥐고 있음"([Busy]) 과 "열지 못함"([Failed]) 을 구분한다 —
 * 전자는 기다렸다 다시 하면 되지만 후자(권한·읽기 전용 볼륨·잘못된 경로)는 다시 해도 같은 결과라
 * 사용자에게 다른 안내를 해야 한다.
 */
internal sealed interface LockAcquire {
    /** 잠금을 잡았다. 쓰는 쪽이 [FileLockHandle.close] 로 돌려준다. */
    data class Held(val handle: FileLockHandle) : LockAcquire

    /** 다른 프로세스나 같은 JVM 이 이미 쥐고 있다. */
    data object Busy : LockAcquire

    /** 잠금 파일 자체를 열지 못했다. */
    data class Failed(val detail: String) : LockAcquire
}

/** 잠금 파일을 만들어(없으면) 잡는다. 던지지 않는다. */
internal fun acquireLockFile(path: Path): LockAcquire {
    val channel = try {
        path.parent?.let { Files.createDirectories(it) }
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
    } catch (e: IOException) {
        return LockAcquire.Failed("잠금 파일을 열지 못했습니다: $e")
    } catch (e: SecurityException) {
        return LockAcquire.Failed("잠금 파일을 열지 못했습니다: $e")
    }
    val lock = try {
        channel.tryLock()
    } catch (e: OverlappingFileLockException) {
        null
    } catch (e: IOException) {
        null
    }
    if (lock == null) {
        try {
            channel.close()
        } catch (e: IOException) {
            // 무시: 잠금을 못 잡은 것이 결과다
        }
        return LockAcquire.Busy
    }
    return LockAcquire.Held(FileLockHandle(channel, lock))
}

/**
 * 잠금 파일을 잡은 채로 [body] 를 돌린다. 이미 누군가 쥐고 있으면 [body] 를 돌리지 않고 false.
 * [create] = false 이고 파일이 없으면 "아무도 쥐지 않음" 으로 보고 잠금 없이 돌린다 (읽기 전용 점검용).
 */
internal fun withLockFile(path: Path, create: Boolean, body: () -> Unit): Boolean {
    if (!create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        body()
        return true
    }
    val options = if (create) {
        arrayOf(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
    } else {
        arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
    }
    val channel = try {
        FileChannel.open(path, *options)
    } catch (e: NoSuchFileException) {
        body()
        return true
    } catch (e: IOException) {
        return false
    } catch (e: SecurityException) {
        return false
    }
    return channel.use { ch ->
        val lock = try {
            ch.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            null
        }
        if (lock == null) {
            false
        } else {
            try {
                body()
            } finally {
                try {
                    if (lock.isValid) lock.release()
                } catch (e: IOException) {
                    // 해제 실패는 채널 닫기가 마무리한다
                }
            }
            true
        }
    }
}

/** [path] 잠금이 풀려 있는가 (읽기 전용 점검: 없는 파일은 풀린 것으로 본다, 새로 만들지 않는다). */
internal fun isLockFree(path: Path): Boolean = withLockFile(path, create = false) { }

/** 트리를 아래에서 위로 한 번 지운다. 지우지 못한 경로 목록을 돌려준다. */
internal fun deleteTreeOnce(root: Path): List<Path> {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val failed = ArrayList<Path>()
    try {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                try {
                    Files.deleteIfExists(p)
                } catch (e: IOException) {
                    failed.add(p)
                } catch (e: SecurityException) {
                    failed.add(p)
                }
            }
        }
    } catch (e: IOException) {
        failed.add(root)
    } catch (e: SecurityException) {
        failed.add(root)
    }
    return failed
}

/** 백신·인덱서가 잠깐 잡은 핸들 대비 재시도 삭제 (기본 8회, 100 ms → 2 s). 남은 경로 목록을 돌려준다. */
internal suspend fun deleteTreeWithRetries(
    root: Path,
    attempts: Int = DELETE_ATTEMPTS,
    initialDelayMs: Long = DELETE_INITIAL_DELAY_MS,
    maxDelayMs: Long = DELETE_MAX_DELAY_MS,
): List<Path> {
    var failed = deleteTreeOnce(root)
    var delayMs = initialDelayMs
    var left = attempts - 1
    while (failed.isNotEmpty() && left > 0) {
        delay(delayMs)
        delayMs = minOf(delayMs * 2, maxDelayMs)
        left--
        failed = deleteTreeOnce(root)
    }
    return failed
}

/** 파일 SHA-256 (소문자 hex). 읽기 실패는 null. */
internal fun sha256HexOrNull(path: Path): String? =
    try {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

/** 바이트 SHA-256 (소문자 hex). */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** 이름 잠금 파일 이름 `name-{sha256(소문자 이름) 앞 16자}.lock` (DESIGN2 §2.5). */
internal fun nameLockFileName(serverName: String): String =
    "$NAME_LOCK_PREFIX${sha256Hex(serverName.lowercase().toByteArray(Charsets.UTF_8)).take(16)}$LOCK_SUFFIX"

/** Windows 에서 `.staging` 을 숨김 속성으로 (실패 무시 — 다른 OS 에는 속성이 없다). */
internal fun setHiddenBestEffort(dir: Path) {
    try {
        Files.setAttribute(dir, "dos:hidden", true)
    } catch (e: IOException) {
        // 다른 파일 시스템·권한 — 숨김은 편의 기능이다
    } catch (e: UnsupportedOperationException) {
        // dos 속성이 없는 OS
    } catch (e: IllegalArgumentException) {
        // 속성 이름을 모르는 파일 시스템
    } catch (e: SecurityException) {
        // 권한 없음
    }
}

/** 이름 잠금 파일 접두사. */
internal const val NAME_LOCK_PREFIX: String = "name-"

/** 잠금 파일 확장자. */
internal const val LOCK_SUFFIX: String = ".lock"

private const val DELETE_ATTEMPTS: Int = 8
private const val DELETE_INITIAL_DELAY_MS: Long = 100
private const val DELETE_MAX_DELAY_MS: Long = 2_000
