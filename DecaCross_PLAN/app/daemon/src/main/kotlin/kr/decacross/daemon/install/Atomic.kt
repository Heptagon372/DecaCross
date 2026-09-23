package kr.decacross.daemon.install

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.deleteRecursively

/**
 * 스테이징 → 원자적 이동 (설계서 §4.2, 불변식 12).
 * 사용자 폴더에는 "완성된 서버" 아니면 "아무것도 없음" 두 상태만 존재한다.
 */
object Atomic {
    private val log = LoggerFactory.getLogger(Atomic::class.java)

    /** `servers/.staging/{uuid}/` 생성. */
    fun newStaging(stagingRoot: Path): Path {
        val dir = stagingRoot.resolve(UUID.randomUUID().toString())
        Files.createDirectories(dir)
        return dir
    }

    /**
     * [staging] 을 [target] 으로 옮긴다. 1) ATOMIC_MOVE → 2) 일반 move(rename) → 3) 복사 후 삭제 순으로 폴백.
     * [target] 이 이미 있으면 실패한다 (덮어쓰지 않는다).
     */
    fun moveInto(staging: Path, target: Path, mover: (Path, Path) -> Unit = ::defaultMove) {
        if (Files.exists(target)) throw IOException("대상이 이미 존재: $target")
        Files.createDirectories(target.parent)
        mover(staging, target)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteStaging(staging: Path) {
        runCatching { staging.deleteRecursively() }
            .onFailure { log.warn("스테이징 정리 실패: {} ({})", staging, it.toString()) }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    internal fun defaultMove(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            log.warn("ATOMIC_MOVE 미지원 파일시스템 — 일반 이동으로 폴백: {} → {}", from, to)
            try {
                Files.move(from, to)
            } catch (_: IOException) {
                copyTree(from, to)
                from.deleteRecursively()
            }
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { p ->
                val rel = from.relativize(p)
                val dest = to.resolve(rel.toString())
                if (Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
