package kr.decacross.daemon.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.decacross.daemon.install.assemble.LOCK_SUFFIX
import kr.decacross.daemon.install.assemble.NAME_LOCK_PREFIX
import kr.decacross.daemon.install.assemble.deleteTreeOnce
import kr.decacross.daemon.install.assemble.deleteTreeWithRetries
import kr.decacross.daemon.install.assemble.isLockFree
import kr.decacross.daemon.install.assemble.sha256HexOrNull
import kr.decacross.daemon.install.assemble.withLockFile
import kr.decacross.daemon.paths.DecaPaths
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 이름·경로 검사 결과. */
sealed interface NameCheck {
    /** 통과. */
    data object Valid : NameCheck

    /** 거부. [reasonKo] 는 사용자 문구. */
    data class Invalid(val reasonKo: String) : NameCheck
}

/** 서버 이름 최대 길이 (서버 절대경로를 MAX_PATH 아래로 유지하기 위한 상한). */
const val MAX_SERVER_NAME_LENGTH: Int = 64

/** 서버 절대경로 최대 길이. `cmd cd /d`·ProcessBuilder 작업 디렉터리가 MAX_PATH(260)에 걸리지 않게 여유를 둔다. */
const val MAX_SERVER_DIR_LENGTH: Int = 200

/**
 * 서버 이름 검사 (AE-16/AE-17, DESIGN2 §2.6).
 * - 길이 1..[MAX_SERVER_NAME_LENGTH], 허용 문자: 유니코드 문자·숫자, 공백, `-`, `_`, `.` (그래서 `!`·`+`·경로 구분자·`<>:"|?*`·제어문자 불가)
 * - 점·공백으로 시작하거나 끝나지 않음 (Windows 가 끝의 점을 조용히 지우고, `.staging` 과 충돌)
 * - Windows 예약 이름 CON/PRN/AUX/NUL/COM0-9/LPT0-9 (확장자 유무·대소문자 무관) 불가
 * - OneDrive 금지 이름(`desktop.ini`, `~$` 시작, `_vti_` 포함) 불가
 */
fun validateServerName(name: String): NameCheck {
    if (name.length !in 1..MAX_SERVER_NAME_LENGTH) {
        return NameCheck.Invalid("이름은 1~${MAX_SERVER_NAME_LENGTH}자여야 합니다 (현재 ${name.length}자)")
    }
    val bad = name.codePoints().filter { cp -> !isAllowedNameCodePoint(cp) }.findFirst()
    if (bad.isPresent) {
        val text = String(Character.toChars(bad.asInt))
        return NameCheck.Invalid("쓸 수 없는 문자가 있습니다: '$text' (문자·숫자와 공백, - _ . 만 됩니다)")
    }
    if (name.first() == ' ' || name.first() == '.') return NameCheck.Invalid("이름은 공백이나 점으로 시작할 수 없습니다")
    if (name.last() == ' ' || name.last() == '.') return NameCheck.Invalid("이름은 공백이나 점으로 끝날 수 없습니다")
    val device = name.substringBefore('.').trimEnd().uppercase()
    if (device in WINDOWS_DEVICE_NAMES) return NameCheck.Invalid("'$device' 은 Windows 예약 장치 이름입니다")
    val lower = name.lowercase()
    if (lower == "desktop.ini" || name.startsWith("~$") || lower.contains("_vti_")) {
        return NameCheck.Invalid("동기화 폴더(OneDrive)에서 쓸 수 없는 이름입니다")
    }
    return NameCheck.Valid
}

/** 허용 문자: 유니코드 문자·숫자, 공백, `-`, `_`, `.` (AE-16). */
private fun isAllowedNameCodePoint(cp: Int): Boolean =
    Character.isLetterOrDigit(cp) || cp == ' '.code || cp == '-'.code || cp == '_'.code || cp == '.'.code

/** Windows 예약 장치 이름 (확장자·대소문자 무관, AE-16). `COM¹·²·³` 는 위첨자 변형까지 막는다. */
private val WINDOWS_DEVICE_NAMES: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (i in 0..9) {
        add("COM$i")
        add("LPT$i")
    }
    for (sup in listOf("¹", "²", "³")) {
        add("COM$sup")
        add("LPT$sup")
    }
}

/**
 * 서버 루트 경로 검사: `!` 또는 `+` 포함 불가 (Paper 가 기동을 거부, Paperclip 은 `!` 거부),
 * `root/이름` 절대경로 길이 ≤ [MAX_SERVER_DIR_LENGTH].
 */
fun validateServerDir(serversRoot: Path, name: String): NameCheck {
    val rootText = serversRoot.toString()
    if (rootText.contains('!') || rootText.contains('+')) {
        return NameCheck.Invalid("Paper 는 경로에 ! 또는 + 가 있으면 기동하지 않습니다: $serversRoot")
    }
    val dirText = try {
        serversRoot.resolve(name).toAbsolutePath().toString()
    } catch (e: InvalidPathException) {
        return NameCheck.Invalid("경로로 쓸 수 없는 이름입니다: $name")
    }
    if (dirText.length > MAX_SERVER_DIR_LENGTH) {
        return NameCheck.Invalid("서버 폴더 경로가 너무 깁니다 (${dirText.length}자 > $MAX_SERVER_DIR_LENGTH): $dirText")
    }
    return NameCheck.Valid
}

/** [parent] 에 대소문자만 다른 같은 이름의 항목(파일·폴더 무관)이 있으면 그 경로. 없거나 [parent] 가 없으면 null. */
fun findNameClash(parent: Path, name: String): Path? =
    try {
        if (!Files.isDirectory(parent)) {
            null
        } else {
            Files.list(parent).use { stream ->
                stream.filter { it.fileName.toString().equals(name, ignoreCase = true) }.findFirst().orElse(null)
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

/** 디렉터리 원자적 이동. 테스트가 `AtomicMoveNotSupportedException` 등을 주입한다. */
fun interface DirectoryMover {
    @Throws(IOException::class)
    fun moveAtomically(source: Path, target: Path)

    companion object {
        /** `Files.move(source, target, ATOMIC_MOVE)`. ★ Windows 에서 대상이 **파일**이면 조용히 덮어쓴다 → 호출 전 부재 확인 필수. */
        val NIO: DirectoryMover = DirectoryMover { source, target -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
    }
}

/** 이동 재시도 (백신·인덱서가 잠깐 잡은 핸들 대비). */
data class MoveRetryPolicy(val attempts: Int = 8, val initialDelayMs: Long = 100, val maxDelayMs: Long = 2_000)

/** 커밋 방식. */
enum class CommitMode {
    /** 디렉터리 `ATOMIC_MOVE` 한 번 */
    ATOMIC_MOVE,

    /** `AtomicMoveNotSupportedException` 때만: `INCOMPLETE` 표식을 둔 복사 (SCP-I10) */
    COPY_FALLBACK,
}

/** [StagingCommitter.commit] 결과. */
sealed interface CommitResult {
    /** 최종 폴더가 완성됐다. */
    data class Committed(val target: Path, val mode: CommitMode) : CommitResult

    /** 커밋 직전 대상이 생겼다. 재시도하지 않는다. */
    data class TargetExists(val existing: Path) : CommitResult

    /** 스테이징은 그대로 남아 있다(롤백이 지운다). 복사 폴백이 만든 대상은 이미 지웠다. */
    data class Failed(val detail: String) : CommitResult
}

/**
 * 스테이징 → 최종 폴더 (research codebase-windows §3).
 *
 * 매 시도(재시도 포함) 직전에:
 * 1. `findNameClash` + `Files.notExists(target, NOFOLLOW_LINKS)` → 있으면 [CommitResult.TargetExists]
 *    (백오프 동안 대상 **파일**이 생기면 Windows `ATOMIC_MOVE` 가 조용히 덮어쓰므로 첫 시도 전에만 보면 안 된다 — critique windows #11)
 * 2. [DirectoryMover.moveAtomically]
 *    - `AccessDeniedException`/`FileSystemException` → [MoveRetryPolicy] 로 재시도 (다음 시도 전에 1 을 다시 본다)
 *    - `AtomicMoveNotSupportedException` 이면 복사 폴백: `createDirectory(target)` → `.decacross/INCOMPLETE`(installId) 기록
 *      → 트리 복사 → 크기 대조(+jar sha256) → 표식 삭제(**커밋 지점**) → 스테이징 삭제(실패해도 성공 처리, 스윕이 치움).
 *      도중 실패 시 대상 트리 삭제 후 [CommitResult.Failed]
 * 3. 그 밖의 `IOException` → [CommitResult.Failed]
 */
class StagingCommitter internal constructor(
    private val mover: DirectoryMover,
    private val retry: MoveRetryPolicy,
    private val copier: TreeFileCopier,
) {
    constructor(
        mover: DirectoryMover = DirectoryMover.NIO,
        retry: MoveRetryPolicy = MoveRetryPolicy(),
    ) : this(mover, retry, TreeFileCopier.NIO)

    /** 스테이징 → [target]. 블로킹 I/O 는 `Dispatchers.IO` 에서, 백오프는 `delay`. */
    suspend fun commit(stagingDir: Path, target: Path, installId: String): CommitResult {
        val absoluteTarget = target.toAbsolutePath()
        val parent = absoluteTarget.parent ?: return CommitResult.Failed("대상의 부모 디렉터리가 없습니다: $target")
        val name = absoluteTarget.fileName?.toString() ?: return CommitResult.Failed("대상 이름이 없습니다: $target")
        var delayMs = retry.initialDelayMs
        for (attempt in 0 until retry.attempts) {
            // ★ 매 시도 직전에 본다: 백오프 중에 대상 파일이 생기면 Windows ATOMIC_MOVE 가 조용히 덮어쓴다 (critique W11)
            val existing = withContext(Dispatchers.IO) {
                findNameClash(parent, name) ?: target.takeUnless { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) }
            }
            if (existing != null) return CommitResult.TargetExists(existing)
            // ★ 커밋 지점은 취소되지 않는다: 이동이 **성공한 뒤** 취소되면 withContext 가 결과를 버리고 던져,
            //   파이프라인은 "롤백했다" 고 말하는데 사용자 폴더에는 완성된 서버가 남는다.
            val outcome = withContext(NonCancellable + Dispatchers.IO) {
                try {
                    mover.moveAtomically(stagingDir, target)
                    MoveOutcome.Done
                } catch (e: AtomicMoveNotSupportedException) {
                    MoveOutcome.NotSupported
                } catch (e: FileSystemException) {
                    // AccessDenied·공유 위반: 백신·탐색기가 잠깐 쥔 핸들일 수 있다 → 재시도
                    MoveOutcome.Retryable(e.toString())
                } catch (e: IOException) {
                    MoveOutcome.Fatal(e.toString())
                }
            }
            when (outcome) {
                MoveOutcome.Done -> return CommitResult.Committed(target, CommitMode.ATOMIC_MOVE)

                MoveOutcome.NotSupported -> return copyFallback(stagingDir, target, installId)

                is MoveOutcome.Fatal -> return CommitResult.Failed(outcome.detail)

                is MoveOutcome.Retryable -> {
                    if (attempt == retry.attempts - 1) return CommitResult.Failed(outcome.detail)
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, retry.maxDelayMs)
                }
            }
        }
        return CommitResult.Failed("이동 재시도를 모두 소진했습니다 (${retry.attempts}회)")
    }

    /** `AtomicMoveNotSupportedException` 전용 폴백 (SCP-I10). 표식 삭제가 커밋 지점이다. */
    private suspend fun copyFallback(stagingDir: Path, target: Path, installId: String): CommitResult {
        val creation = withContext(Dispatchers.IO) {
            try {
                Files.createDirectory(target)
                null
            } catch (e: FileAlreadyExistsException) {
                CommitResult.TargetExists(target)
            } catch (e: IOException) {
                CommitResult.Failed(e.toString())
            }
        }
        if (creation != null) return creation
        // ★ 복사 폴백도 커밋 지점(표식 삭제)을 품고 있다 — 같은 이유로 취소되지 않는다
        val problem = withContext(NonCancellable + Dispatchers.IO) { copyTree(stagingDir, target, installId) }
        if (problem != null) {
            // 커밋 지점 전 실패 → 만들던 대상을 지운다 (사용자 폴더에 반쯤 만든 서버를 남기지 않는다)
            withContext(Dispatchers.IO) { deleteTreeWithRetries(target) }
            return CommitResult.Failed(problem)
        }
        // 커밋 지점 이후: 스테이징 삭제 실패는 성공을 막지 않는다 (다음 설치의 스윕이 치운다)
        withContext(Dispatchers.IO) { deleteTreeWithRetries(stagingDir, attempts = 2) }
        return CommitResult.Committed(target, CommitMode.COPY_FALLBACK)
    }

    /** 표식 기록 → 트리 복사 → 크기(+jar sha256) 대조 → 표식 삭제. 실패 이유를 돌려주고, 성공이면 null. */
    private fun copyTree(stagingDir: Path, target: Path, installId: String): String? {
        val marker = target.resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME)
        when (val written = writeFileAtomically(marker, installId.toByteArray(Charsets.UTF_8))) {
            is LayoutIoResult.Failed -> return "INCOMPLETE 표식을 쓰지 못했습니다: ${written.detail}"
            is LayoutIoResult.Ok -> Unit
        }
        val relatives = try {
            Files.walk(stagingDir).use { stream -> stream.toList() }
                .map { stagingDir.relativize(it) }
                .filter { it.toString().isNotEmpty() && it.fileName.toString() != INCOMPLETE_MARKER_NAME }
                .sortedBy { it.toString() }
        } catch (e: IOException) {
            return "스테이징을 읽지 못했습니다: $e"
        }
        for (rel in relatives) {
            val source = stagingDir.resolve(rel)
            val destination = target.resolve(rel)
            try {
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let { Files.createDirectories(it) }
                    copier.copy(source, destination)
                }
            } catch (e: IOException) {
                return "복사 실패 ($rel): $e"
            }
        }
        for (rel in relatives) {
            val source = stagingDir.resolve(rel)
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) continue
            val destination = target.resolve(rel)
            val sourceSize = try {
                Files.size(source)
            } catch (e: IOException) {
                return "원본 크기를 읽지 못했습니다 ($rel): $e"
            }
            val destinationSize = try {
                Files.size(destination)
            } catch (e: IOException) {
                return "복사본 크기를 읽지 못했습니다 ($rel): $e"
            }
            if (sourceSize != destinationSize) return "복사 크기 불일치 ($rel): $destinationSize ≠ $sourceSize"
            if (rel.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                val a = sha256HexOrNull(source)
                val b = sha256HexOrNull(destination)
                if (a == null || b == null || a != b) return "복사본 sha256 불일치 ($rel)"
            }
        }
        return try {
            Files.delete(marker)
            null
        } catch (e: IOException) {
            "INCOMPLETE 표식을 지우지 못했습니다: $e"
        }
    }
}

/** [DirectoryMover] 호출 결과 (내부 전용). */
private sealed interface MoveOutcome {
    data object Done : MoveOutcome

    data object NotSupported : MoveOutcome

    data class Retryable(val detail: String) : MoveOutcome

    data class Fatal(val detail: String) : MoveOutcome
}

/** 복사 폴백의 파일 한 개 복사. 테스트가 실패를 주입하는 틈 (critique A3). */
internal fun interface TreeFileCopier {
    @Throws(IOException::class)
    fun copy(source: Path, target: Path)

    companion object {
        /** `Files.copy(source, target, COPY_ATTRIBUTES)` (대상이 있으면 실패). */
        val NIO: TreeFileCopier = TreeFileCopier { source, target -> Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES) }
    }
}

/**
 * 읽기 전용 점검 (PLAN 이 부른다 — `confirmPlan` 전에는 아무것도 지우거나 만들지 않는다, critique M5):
 * `.staging/{id}/` 중 잠금이 **풀린** 것, `.decacross/INCOMPLETE` 가 있고 그 설치 잠금이 풀린 서버 폴더.
 * 잠금 파일이 없으면 풀린 것으로 본다. 잠금 확인은 이미 있는 파일을 열어 `tryLock` 후 즉시 푸는 것만 한다 (새 파일 생성 금지).
 * 루트가 없으면 빈 목록. 던지지 않는다.
 */
fun findLeftovers(serversRoot: Path): List<Path> {
    val stagingRoot = serversRoot.resolve(DecaPaths.STAGING_DIR_NAME)
    val leftovers = ArrayList<Path>()
    for (dir in listDirectories(stagingRoot)) {
        if (isLockFree(stagingRoot.resolve(dir.fileName.toString() + LOCK_SUFFIX))) leftovers.add(dir)
    }
    for (dir in listDirectories(serversRoot)) {
        if (dir.fileName.toString() == DecaPaths.STAGING_DIR_NAME) continue
        val installId = readIncompleteMarker(dir) ?: continue
        if (isLockFree(stagingRoot.resolve(installId + LOCK_SUFFIX))) leftovers.add(dir)
    }
    return leftovers
}

/** [sweepStaging] 결과. */
data class SweepReport(val removedStaging: List<Path>, val removedIncomplete: List<Path>, val failed: List<Path>)

/**
 * 스윕 (파이프라인이 **LAYOUT 시작**에서 부른다 = `confirmPlan` 이 true 를 돌려준 뒤): 잠금이 풀린 `.staging/{id}/`·`{id}.lock`·`name-*.lock` 삭제,
 * `.decacross/INCOMPLETE` 가 있고 그 installId 의 잠금이 없는 서버 폴더 삭제. 던지지 않는다.
 */
fun sweepStaging(serversRoot: Path): SweepReport {
    val stagingRoot = serversRoot.resolve(DecaPaths.STAGING_DIR_NAME)
    val removedStaging = ArrayList<Path>()
    val removedIncomplete = ArrayList<Path>()
    val failed = ArrayList<Path>()

    for (dir in listDirectories(stagingRoot)) {
        val lock = stagingRoot.resolve(dir.fileName.toString() + LOCK_SUFFIX)
        var problems: List<Path> = emptyList()
        // 잠금을 쥔 채로 지운다 (다른 프로세스가 그 사이 그 스테이징을 쓰기 시작하지 못하게)
        val locked = withLockFile(lock, create = true) { problems = deleteTreeOnce(dir) }
        if (!locked) continue
        if (problems.isEmpty()) {
            removedStaging.add(dir)
            deleteQuietly(lock)?.let { failed.add(it) }
        } else {
            failed.addAll(problems)
        }
    }

    for (file in listRegularFiles(stagingRoot)) {
        val name = file.fileName.toString()
        if (!name.startsWith(NAME_LOCK_PREFIX) || !name.endsWith(LOCK_SUFFIX)) continue
        if (withLockFile(file, create = false) { }) deleteQuietly(file)?.let { failed.add(it) }
    }

    for (dir in listDirectories(serversRoot)) {
        if (dir.fileName.toString() == DecaPaths.STAGING_DIR_NAME) continue
        val installId = readIncompleteMarker(dir) ?: continue
        var problems: List<Path> = emptyList()
        val locked = withLockFile(stagingRoot.resolve(installId + LOCK_SUFFIX), create = false) {
            problems = deleteTreeOnce(dir)
        }
        if (!locked) continue
        if (problems.isEmpty()) removedIncomplete.add(dir) else failed.addAll(problems)
    }

    if (Files.isDirectory(stagingRoot)) deleteIfEmpty(stagingRoot)
    return SweepReport(removedStaging, removedIncomplete, failed)
}

/** [parent] 바로 아래 디렉터리 목록 (없거나 읽기 실패면 빈 목록). 던지지 않는다. */
internal fun listDirectories(parent: Path): List<Path> = listEntries(parent) { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

/** [parent] 바로 아래 일반 파일 목록. 던지지 않는다. */
internal fun listRegularFiles(parent: Path): List<Path> = listEntries(parent) { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

private fun listEntries(parent: Path, keep: (Path) -> Boolean): List<Path> =
    try {
        if (!Files.isDirectory(parent)) {
            emptyList()
        } else {
            Files.list(parent).use { stream -> stream.toList() }.filter(keep).sortedBy { it.fileName.toString() }
        }
    } catch (e: IOException) {
        emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

/**
 * 서버 폴더의 `.decacross/INCOMPLETE` 에 적힌 설치 id. 없거나 형식이 이상하면 null
 * (id 는 잠금 파일 이름으로 쓰이므로 `[A-Za-z0-9._-]` 만 허용한다).
 */
private fun readIncompleteMarker(serverDir: Path): String? {
    val marker = serverDir.resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME)
    val text = try {
        if (Files.isRegularFile(marker)) Files.readString(marker, Charsets.UTF_8).trim() else return null
    } catch (e: IOException) {
        return null
    } catch (e: SecurityException) {
        return null
    }
    if (text.isEmpty() || text.length > 100) return INVALID_INSTALL_ID
    return if (text.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) text else INVALID_INSTALL_ID
}

/** 표식이 깨졌을 때 쓰는 이름 — 이런 잠금 파일은 없으므로 "잠금 없음"(= 치워도 됨)으로 판정된다. */
private const val INVALID_INSTALL_ID: String = "invalid-install-id"

/** 조용히 지운다. 실패하면 그 경로를 돌려준다. */
internal fun deleteQuietly(path: Path): Path? =
    try {
        Files.deleteIfExists(path)
        null
    } catch (e: IOException) {
        path
    } catch (e: SecurityException) {
        path
    }

/** 비어 있으면 지운다 (비어 있지 않거나 실패면 그대로 둔다). */
internal fun deleteIfEmpty(dir: Path) {
    try {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        val empty = Files.list(dir).use { stream -> stream.findAny().isEmpty }
        if (empty) Files.deleteIfExists(dir)
    } catch (e: IOException) {
        // 비우지 못한 디렉터리는 다음 스윕이 본다
    } catch (e: SecurityException) {
        // 권한 없음
    }
}
