package kr.decacross.daemon.install

import kotlinx.serialization.Serializable
import kr.decacross.daemon.install.assemble.LOCK_SUFFIX
import kr.decacross.daemon.install.assemble.LockAcquire
import kr.decacross.daemon.install.assemble.NAME_LOCK_PREFIX
import kr.decacross.daemon.install.assemble.acquireLockFile
import kr.decacross.daemon.install.assemble.deleteTreeWithRetries
import kr.decacross.daemon.install.assemble.nameLockFileName
import kr.decacross.daemon.install.assemble.setHiddenBestEffort
import kr.decacross.daemon.install.assemble.withLockFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 서버 폴더 안 런처 메타 디렉터리 (설계서 §14). */
const val META_DIR_NAME: String = ".decacross"

/** `.decacross/manifest.json`: 설치한 파일 목록 + 해시. */
const val MANIFEST_FILE_NAME: String = "manifest.json"

/** `.decacross/launch.json`: [LaunchSpec]. CLI `start` 의 단일 진실 소스 (start.bat 은 이것의 렌더링, SCP-I5). */
const val LAUNCH_FILE_NAME: String = "launch.json"

/** `.decacross/server.pid`: 실행 중 표시 (프로세스 모듈이 쓴다). */
const val PID_FILE_NAME: String = "server.pid"

/** `.decacross/INCOMPLETE`: 복사 폴백 도중 표시. 이 파일이 있는 폴더는 서버로 취급하지 않고 스윕이 지운다. */
const val INCOMPLETE_MARKER_NAME: String = "INCOMPLETE"

/**
 * 설치 매니페스트 (스키마 1). JSON 은 kotlinx.serialization 기본 형식, 들여쓰기 2칸.
 * 설치 시점의 기록이다: EULA 단계 끝(eula.txt 를 쓴 뒤)에 마지막으로 쓰고, 이후 `start` 의 동의·`--save` 는 이 파일을 고치지 않는다.
 */
@Serializable
data class InstallManifest(
    val schema: Int = 1,
    val installId: String,
    val serverName: String,
    /** ISO-8601 UTC (`kotlin.time.Instant.toString()`). */
    val createdAt: String,
    val mcLabel: String,
    /** 비교는 이 값으로만 (불변식 1). */
    val mcOrdinal: Int,
    /** `CoreKey.name` */
    val core: String,
    val coreBuild: String,
    /** `Channel.name` */
    val coreChannel: String,
    val files: List<ManifestFile>,
)

/** 매니페스트의 파일 한 줄 (설계서 §14 "설치된 파일 목록 + 해시"). */
@Serializable
data class ManifestFile(
    /** 서버 폴더 기준 상대경로, 구분자 `/`. */
    val path: String,
    /** 소문자 hex 64자. 받은 파일·생성 파일·eula.txt 모두 기록한다 (업데이트 흐름이 사용자 수정 여부를 가린다). */
    val sha256: String,
    val size: Long,
    val origin: FileOrigin,
)

/** 파일 출처. */
@Serializable
enum class FileOrigin {
    /** 카탈로그에서 받아 sha256 을 검증한 파일 (코어 jar) */
    DOWNLOADED,

    /** 런처가 만든 파일 (server.properties, start.bat, start.sh, launch.json, 동의한 경우 eula.txt) */
    GENERATED,
}

/**
 * 스테이징 한 벌: `servers/.staging/{installId}/` + 잠금 파일 `{installId}.lock`, `name-{sha256(소문자 이름) 앞 16자}.lock`.
 * 잠금은 스테이징 디렉터리 **밖**에 둔다 (디렉터리 안의 열린 핸들은 Windows 에서 이동을 막는다).
 *
 * [close] 는 잠금만 푼다. 디렉터리 삭제는 [rollbackStaging], 이동은 [StagingCommitter].
 */
class StagingArea(
    val installId: String,
    val stagingRoot: Path,
    val dir: Path,
    private val releaseLocks: () -> Unit,
) : AutoCloseable {
    override fun close() {
        releaseLocks()
    }
}

/** [openStaging] 결과. */
sealed interface StagingOpenResult {
    /** 잠금 두 개를 쥐고 `{installId}/` 를 만들었다. */
    data class Opened(val area: StagingArea) : StagingOpenResult

    /** 같은 이름 잠금을 다른 프로세스가 쥐고 있음. */
    data class NameBusy(val name: String) : StagingOpenResult

    /** 디렉터리·잠금 파일 생성 실패. 이미 만든 것은 지웠다. */
    data class Failed(val path: Path, val detail: String) : StagingOpenResult
}

/** 롤백 결과. [cleanedUp] 이 false 면 [leftovers] 가 남았다 (다음 `create` 의 LAYOUT 스윕이 치운다). */
data class RollbackReport(val cleanedUp: Boolean, val leftovers: List<Path>)

/**
 * `.staging` 을 만들고(Windows 면 숨김 속성, 실패 무시) 이름 잠금 → 설치 잠금 → `{installId}/` 생성 순으로 연다.
 * 이름 잠금을 다른 설치가 **쥐고 있으면** [StagingOpenResult.NameBusy], 잠금 파일 자체를 **열지 못하면**
 * [StagingOpenResult.Failed] (권한·읽기 전용 볼륨 — 기다려도 풀리지 않으므로 다른 안내를 해야 한다).
 * 중간 실패 시 이미 잡은 잠금을 풀고 만든 것을 지운다.
 */
fun openStaging(stagingRoot: Path, installId: String, serverName: String): StagingOpenResult {
    try {
        Files.createDirectories(stagingRoot)
    } catch (e: IOException) {
        return StagingOpenResult.Failed(stagingRoot, e.toString())
    } catch (e: SecurityException) {
        return StagingOpenResult.Failed(stagingRoot, e.toString())
    }
    setHiddenBestEffort(stagingRoot)

    val nameLock = stagingRoot.resolve(nameLockFileName(serverName))
    // ★ 잠금 파일을 "열지 못한" 경우는 NameBusy 가 아니다 — 기다려도 풀리지 않으므로 LocalIo 안내를 내보낸다
    val nameHandle = when (val acquired = acquireLockFile(nameLock)) {
        is LockAcquire.Held -> acquired.handle
        LockAcquire.Busy -> return StagingOpenResult.NameBusy(serverName)
        is LockAcquire.Failed -> return StagingOpenResult.Failed(nameLock, acquired.detail)
    }

    val installLock = stagingRoot.resolve(installId + LOCK_SUFFIX)
    val installHandle = when (val acquired = acquireLockFile(installLock)) {
        is LockAcquire.Held -> acquired.handle

        LockAcquire.Busy, is LockAcquire.Failed -> {
            nameHandle.close()
            deleteQuietly(nameLock)
            val detail = (acquired as? LockAcquire.Failed)?.detail ?: "설치 잠금 파일을 잡지 못했습니다"
            return StagingOpenResult.Failed(installLock, detail)
        }
    }

    val dir = stagingRoot.resolve(installId)
    try {
        Files.createDirectory(dir)
    } catch (e: IOException) {
        installHandle.close()
        nameHandle.close()
        deleteQuietly(installLock)
        deleteQuietly(nameLock)
        return StagingOpenResult.Failed(dir, e.toString())
    } catch (e: SecurityException) {
        installHandle.close()
        nameHandle.close()
        deleteQuietly(installLock)
        deleteQuietly(nameLock)
        return StagingOpenResult.Failed(dir, e.toString())
    }

    var released = false
    return StagingOpenResult.Opened(
        StagingArea(installId, stagingRoot, dir) {
            // 잠금 해제는 한 번만 (커밋 경로와 롤백 경로가 모두 닫아도 안전해야 한다)
            if (!released) {
                released = true
                installHandle.close()
                nameHandle.close()
            }
        },
    )
}

/** [source] 를 `area.dir/[fileName]` 로 복사한다 (롤백 뒤 재실행을 위해 원본 캐시는 남긴다). 복사 후 크기 대조. */
fun placeArtifact(area: StagingArea, source: Path, fileName: String): LayoutIoResult {
    val target = area.dir.resolve(fileName)
    return try {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val sourceSize = Files.size(source)
        val targetSize = Files.size(target)
        if (sourceSize != targetSize) {
            LayoutIoResult.Failed(target, "복사 크기 불일치: $targetSize ≠ $sourceSize")
        } else {
            LayoutIoResult.Ok(target)
        }
    } catch (e: IOException) {
        LayoutIoResult.Failed(target, e.toString())
    } catch (e: SecurityException) {
        LayoutIoResult.Failed(target, e.toString())
    }
}

/**
 * 롤백: 잠금 해제 → 스테이징 트리 삭제(백신·인덱서 핸들 대비 최대 8회, 100 ms→2 s) → 잠금 파일 삭제 →
 * 빈 `.staging` 삭제 → [removeIfEmpty] 의 빈 디렉터리 삭제(이 설치가 새로 만든 서버 루트). 던지지 않는다.
 * 파이프라인은 한 설치에서 이 함수를 **한 번만** 부른다 (critique A6: 롤백 사건 전송 중 취소돼도 다시 부르지 않는다).
 */
suspend fun rollbackStaging(area: StagingArea?, stagingRoot: Path, removeIfEmpty: List<Path>): RollbackReport {
    val leftovers = ArrayList<Path>()
    if (area != null) {
        // (1) 잠금 해제 — Windows 에서 열린 핸들은 삭제를 막는다
        area.close()
        // (2) 스테이징 트리 삭제 (백신·인덱서 핸들 대비 재시도)
        leftovers.addAll(deleteTreeWithRetries(area.dir))
        // (3) 잠금 파일 삭제: 설치 잠금은 이름으로, 이름 잠금은 "다시 잡히는 것" 만 (다른 설치 것은 건드리지 않는다)
        deleteQuietly(stagingRoot.resolve(area.installId + LOCK_SUFFIX))?.let { leftovers.add(it) }
        for (file in listRegularFiles(stagingRoot)) {
            val name = file.fileName.toString()
            if (!name.startsWith(NAME_LOCK_PREFIX) || !name.endsWith(LOCK_SUFFIX)) continue
            if (withLockFile(file, create = false) { }) deleteQuietly(file)?.let { leftovers.add(it) }
        }
    }
    // (4) 비어 있으면 .staging 삭제
    deleteIfEmpty(stagingRoot)
    // (5) 이 설치가 새로 만든 디렉터리를 깊은 것부터 (비어 있을 때만)
    for (dir in removeIfEmpty) deleteIfEmpty(dir)
    return RollbackReport(cleanedUp = leftovers.isEmpty(), leftovers = leftovers)
}
