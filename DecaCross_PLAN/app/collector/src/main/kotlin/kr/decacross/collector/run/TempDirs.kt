package kr.decacross.collector.run

import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 수집기 임시 디렉터리 관리 (D48).
 *
 * # 불변식
 * - 실행마다 `<tempDir>/run-<pid>` 를 쓴다. 다른 수집기의 시작 청소가 진행 중인 다운로드를 건드리지 않게 하기 위해서다.
 * - 죽은 프로세스의 `run-<pid>` 만 지운다. 살아 있는 실행 디렉터리는 절대 건드리지 않는다.
 * - 청소는 best-effort: 예외를 던지지 않는다.
 * - OneDrive 경로는 CLI 가 이미 거부했다.
 */
object TempDirs {
    private val log = LoggerFactory.getLogger(TempDirs::class.java)

    const val RUN_DIR_PREFIX: String = "run-"
    private val RUN_DIR = Regex("""^run-(\d{1,19})$""")

    /** [prepare] 결과. */
    data class Prepared(val runDir: Path, val removedDeadRunDirs: Int, val removedFiles: Int)

    /** 이 프로세스가 살아 있는가. */
    fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /**
     * 임시 디렉터리를 만들고, 죽은 실행 디렉터리와 [tempDir] 바로 아래 남은 `dl-*` 파일을 지우고, 자기 실행 디렉터리를 만든다.
     * @throws IOException 디렉터리를 만들 수 없을 때 (설정 치명 오류)
     */
    fun prepare(
        tempDir: Path,
        pid: Long = ProcessHandle.current().pid(),
        isAlive: (Long) -> Boolean = ::isProcessAlive,
    ): Prepared {
        Files.createDirectories(tempDir)
        var removedDirs = 0
        var removedFiles = sweepPartFiles(tempDir)
        for (dir in listChildren(tempDir)) {
            val otherPid = runDirPid(dir) ?: continue
            if (otherPid == pid || isAlive(otherPid)) continue
            removedFiles += sweepPartFiles(dir)
            if (deleteDirIfEmpty(dir)) removedDirs++
        }
        val runDir = tempDir.resolve("$RUN_DIR_PREFIX$pid")
        Files.createDirectories(runDir)
        if (removedDirs > 0 || removedFiles > 0) {
            log.warn("이전 실행이 남긴 임시 파일 정리: 실행 디렉터리 {}개, 파일 {}개 ({})", removedDirs, removedFiles, tempDir)
        }
        return Prepared(runDir, removedDirs, removedFiles)
    }

    /**
     * [dir] 바로 아래 `dl-*` 파일을 지운다 ([exclude] 는 건너뜀). 지운 개수를 돌려준다. 던지지 않는다.
     */
    fun sweepPartFiles(dir: Path, exclude: Set<Path> = emptySet()): Int {
        var removed = 0
        for (f in listChildren(dir)) {
            if (!isPartFile(f) || f in exclude) continue
            try {
                if (Files.deleteIfExists(f)) removed++
            } catch (e: IOException) {
                log.warn("임시 파일 삭제 실패 (다음 청소 때 다시 시도): {} — {}", f, e.toString())
            } catch (e: SecurityException) {
                log.warn("임시 파일 삭제 권한 없음: {} — {}", f, e.toString())
            }
        }
        return removed
    }

    /** 실행 디렉터리 안의 `dl-*` 를 지우고 디렉터리 자체도 지운다 (종료 훅용, 던지지 않는다). */
    fun cleanupRunDir(runDir: Path) {
        sweepPartFiles(runDir)
        deleteDirIfEmpty(runDir)
    }

    /**
     * 남은 임시 jar 목록 (S12): [tempDir] 바로 아래, 자기 실행 디렉터리, 죽은 프로세스의 실행 디렉터리.
     * 살아 있는 **다른** 실행의 디렉터리는 건너뛴다 (`--loop` 중에 `--sanity-only` 를 돌려도 흔들리지 않게).
     */
    fun leftoverFiles(
        tempDir: Path,
        pid: Long = ProcessHandle.current().pid(),
        isAlive: (Long) -> Boolean = ::isProcessAlive,
    ): List<Path> {
        if (!tempDir.isDirectory()) return emptyList()
        val out = ArrayList<Path>()
        out += listChildren(tempDir).filter(::isPartFile)
        for (dir in listChildren(tempDir)) {
            val otherPid = runDirPid(dir) ?: continue
            if (otherPid != pid && isAlive(otherPid)) continue
            out += listChildren(dir).filter(::isPartFile)
        }
        return out
    }

    /** `dl-` 로 시작하는 일반 파일 (다운로드 임시 파일 `dl-*.part`). */
    fun isPartFile(p: Path): Boolean = p.name.startsWith("dl-") && p.isRegularFile()

    private fun runDirPid(dir: Path): Long? {
        if (!dir.isDirectory()) return null
        return RUN_DIR.matchEntire(dir.name)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun listChildren(dir: Path): List<Path> = try {
        if (!dir.isDirectory()) emptyList() else Files.list(dir).use { it.toList() }
    } catch (e: IOException) {
        log.warn("디렉터리를 읽을 수 없다: {} — {}", dir, e.toString())
        emptyList()
    } catch (e: UncheckedIOException) {
        log.warn("디렉터리를 읽는 중 실패: {} — {}", dir, e.toString())
        emptyList()
    }

    private fun deleteDirIfEmpty(dir: Path): Boolean = try {
        Files.deleteIfExists(dir)
    } catch (e: DirectoryNotEmptyException) {
        log.warn("실행 디렉터리에 dl-* 가 아닌 파일이 있어 남겨 둔다: {}", dir)
        false
    } catch (e: IOException) {
        log.warn("실행 디렉터리 삭제 실패: {} — {}", dir, e.toString())
        false
    }
}
