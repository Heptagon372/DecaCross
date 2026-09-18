package kr.decacross.daemon.install.assemble

import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.CommitMode
import kr.decacross.daemon.install.CommitResult
import kr.decacross.daemon.install.DirectoryMover
import kr.decacross.daemon.install.INCOMPLETE_MARKER_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.MoveRetryPolicy
import kr.decacross.daemon.install.StagingCommitter
import kr.decacross.daemon.install.StagingOpenResult
import kr.decacross.daemon.install.TreeFileCopier
import kr.decacross.daemon.install.openStaging
import kr.decacross.daemon.install.rollbackStaging
import kr.decacross.daemon.testkit.TreeSnapshot
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** 스테이징 → 최종 폴더 커밋 (DESIGN2 §2.13, research codebase-windows §3 프로브 표, critique W11/A3). */
class CommitTest {
    private val root: Path = Files.createTempDirectory("dcx-commit")
    private val fastRetry = MoveRetryPolicy(attempts = 8, initialDelayMs = 1, maxDelayMs = 2)

    private fun newCase(name: String): Pair<Path, Path> {
        val base = Files.createDirectories(root.resolve(name))
        val staging = Files.createDirectories(base.resolve("staging"))
        Files.writeString(staging.resolve("a.txt"), "첫 번째")
        Files.write(staging.resolve("z.jar"), ByteArray(2048) { (it % 251).toByte() })
        Files.createDirectories(staging.resolve(META_DIR_NAME))
        Files.writeString(staging.resolve(META_DIR_NAME).resolve("launch.json"), "{}")
        return base to staging
    }

    @Test
    fun normalCommit_movesTree() = runBlocking {
        val (base, staging) = newCase("normal")
        val target = base.resolve("demo")
        val result = StagingCommitter(DirectoryMover.NIO, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.Committed(target, CommitMode.ATOMIC_MOVE), result)
        assertTrue(Files.isRegularFile(target.resolve("a.txt")))
        assertTrue(Files.isRegularFile(target.resolve(META_DIR_NAME).resolve("launch.json")))
        assertFalse(Files.exists(staging))
    }

    @Test
    fun existingTargetDirectory_isTargetExists_andStagingSurvives() = runBlocking {
        val (base, staging) = newCase("exists-dir")
        val target = Files.createDirectories(base.resolve("demo"))
        val before = TreeSnapshot.of(staging)
        val result = StagingCommitter(DirectoryMover.NIO, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.TargetExists(target), result)
        assertEquals(before, TreeSnapshot.of(staging))
    }

    @Test
    fun existingTargetRegularFile_isTargetExists_andFileUnchanged() = runBlocking {
        // 프로브 case 4: Windows ATOMIC_MOVE 는 대상이 **파일**이면 조용히 덮어쓴다 → 사전 확인이 막아야 한다
        val (base, staging) = newCase("exists-file")
        val target = base.resolve("demo")
        Files.writeString(target, "사용자 파일")
        val result = StagingCommitter(DirectoryMover.NIO, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.TargetExists(target), result)
        assertEquals("사용자 파일", Files.readString(target))
    }

    @Test
    fun accessDeniedTwice_thenSucceeds() = runBlocking {
        val (base, staging) = newCase("retry")
        val target = base.resolve("demo")
        var calls = 0
        val mover = DirectoryMover { source, destination ->
            calls++
            if (calls <= 2) throw AccessDeniedException(destination.toString())
            DirectoryMover.NIO.moveAtomically(source, destination)
        }
        val result = StagingCommitter(mover, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.Committed(target, CommitMode.ATOMIC_MOVE), result)
        assertEquals(3, calls)
    }

    @Test
    fun fileAppearingDuringBackoff_isSeenBeforeNextAttempt() = runBlocking {
        // critique W11: 백오프 중에 대상 파일이 생기면 다음 시도의 ATOMIC_MOVE 가 그 파일을 덮어쓴다
        val (base, staging) = newCase("race")
        val target = base.resolve("demo")
        var calls = 0
        val mover = DirectoryMover { _, destination ->
            calls++
            if (calls == 1) {
                Files.writeString(destination, "사용자 파일")
                throw AccessDeniedException(destination.toString())
            }
            fail("두 번째 시도는 이동을 시도하면 안 된다 (대상이 이미 있다)")
        }
        val result = StagingCommitter(mover, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.TargetExists(target), result)
        assertEquals(1, calls)
        assertEquals("사용자 파일", Files.readString(target))
    }

    @Test
    fun alwaysAccessDenied_failsAfterAllAttempts() = runBlocking {
        val (base, staging) = newCase("always-denied")
        val target = base.resolve("demo")
        var calls = 0
        val mover = DirectoryMover { _, destination ->
            calls++
            throw AccessDeniedException(destination.toString())
        }
        val result = StagingCommitter(mover, fastRetry).commit(staging, target, "t-1")
        assertTrue(result is CommitResult.Failed, "$result")
        assertEquals(fastRetry.attempts, calls)
        assertTrue(Files.isDirectory(staging), "스테이징은 남아 있어야 한다 (롤백이 지운다)")
    }

    @Test
    fun atomicMoveNotSupported_usesCopyFallback() = runBlocking {
        val (base, staging) = newCase("copy-fallback")
        val target = base.resolve("demo")
        val mover = DirectoryMover { _, destination -> throw AtomicMoveNotSupportedException(null, destination.toString(), "주입") }
        val result = StagingCommitter(mover, fastRetry).commit(staging, target, "t-1")
        assertEquals(CommitResult.Committed(target, CommitMode.COPY_FALLBACK), result)
        assertEquals("첫 번째", Files.readString(target.resolve("a.txt")))
        assertTrue(Files.isRegularFile(target.resolve("z.jar")))
        assertEquals(2048, Files.size(target.resolve("z.jar")).toInt())
        assertFalse(Files.exists(target.resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME)), "표식은 커밋 지점에서 지운다")
        assertFalse(Files.exists(staging), "복사 성공 뒤 스테이징은 사라진다")
    }

    /**
     * ★ 예외 없이 "조용히 잘린" 복사는 크기 대조가 잡아야 한다 (§2.13 커밋 지점 직전 검증).
     * 이게 없으면 반쪽짜리 jar 가 그대로 커밋되고 사용자는 기동 실패로만 알게 된다.
     */
    @Test
    fun copyFallback_silentlyTruncatedCopy_isRejectedBySizeCheck() = runBlocking {
        val (base, staging) = newCase("copy-truncated")
        val target = base.resolve("demo")
        val truncating = TreeFileCopier { source, destination ->
            if (source.fileName.toString() == "z.jar") Files.write(destination, ByteArray(1)) else TreeFileCopier.NIO.copy(source, destination)
        }
        val mover = DirectoryMover { _, destination -> throw AtomicMoveNotSupportedException(null, destination.toString(), "주입") }
        val result = StagingCommitter(mover, fastRetry, truncating).commit(staging, target, "t-1")
        val failed = assertIs<CommitResult.Failed>(result, "$result")
        assertTrue(failed.detail.contains("크기"), failed.detail)
        assertFalse(Files.exists(target), "검증 실패 → 대상은 흔적 없이 지워진다")
        assertTrue(Files.isDirectory(staging), "스테이징은 남아 있다 (롤백이 지운다)")
    }

    /** 크기는 같고 내용만 다른 jar → sha256 대조가 잡는다 (크기 대조만으로는 통과한다). */
    @Test
    fun copyFallback_corruptedJarOfSameSize_isRejectedByHashCheck() = runBlocking {
        val (base, staging) = newCase("copy-corrupt")
        val target = base.resolve("demo")
        val corrupting = TreeFileCopier { source, destination ->
            if (source.fileName.toString() == "z.jar") {
                Files.write(destination, Files.readAllBytes(source).also { it[0] = (it[0] + 1).toByte() })
            } else {
                TreeFileCopier.NIO.copy(source, destination)
            }
        }
        val mover = DirectoryMover { _, destination -> throw AtomicMoveNotSupportedException(null, destination.toString(), "주입") }
        val result = StagingCommitter(mover, fastRetry, corrupting).commit(staging, target, "t-1")
        val failed = assertIs<CommitResult.Failed>(result, "$result")
        assertTrue(failed.detail.contains("sha256"), failed.detail)
        assertFalse(Files.exists(target), "검증 실패 → 대상은 흔적 없이 지워진다")
    }

    @Test
    fun copyFallback_injectedCopyFailure_leavesNoTarget_andRollbackCleans() = runBlocking {
        val (base, stagingRoot) = injectedCase("copy-failure")
        val area = (openStaging(stagingRoot, "t-1", "demo") as StagingOpenResult.Opened).area
        Files.writeString(area.dir.resolve("a.txt"), "첫 번째")
        Files.write(area.dir.resolve("z.jar"), ByteArray(512))
        val target = base.resolve("demo")
        val failingCopier = TreeFileCopier { source, destination ->
            if (source.endsWith("z.jar")) throw IOException("주입된 복사 실패") else TreeFileCopier.NIO.copy(source, destination)
        }
        val mover = DirectoryMover { _, destination -> throw AtomicMoveNotSupportedException(null, destination.toString(), "주입") }
        val committer = StagingCommitter(mover, fastRetry, failingCopier)

        val result = committer.commit(area.dir, target, "t-1")
        assertTrue(result is CommitResult.Failed, "$result")
        assertFalse(Files.exists(target), "커밋 지점 전 실패 → 대상은 흔적 없이 지워진다")
        assertTrue(Files.isDirectory(area.dir), "스테이징은 남아 있다")

        val report = rollbackStaging(area, stagingRoot, emptyList())
        assertTrue(report.cleanedUp, "남은 것: ${report.leftovers}")
        assertFalse(Files.exists(area.dir))
        assertFalse(Files.exists(stagingRoot), "빈 .staging 은 지운다")
    }

    @Test
    fun copyFallback_windowsFileLock_leavesNoTarget_andRollbackCleansAfterRelease() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "실제 파일 잠금은 Windows 에서만 복사를 막는다")
        val (base, stagingRoot) = injectedCase("copy-locked")
        val area = (openStaging(stagingRoot, "t-2", "demo") as StagingOpenResult.Opened).area
        Files.writeString(area.dir.resolve("a.txt"), "첫 번째")
        val jar = area.dir.resolve("z.jar")
        Files.write(jar, ByteArray(512))
        val target = base.resolve("demo")
        val mover = DirectoryMover { _, destination -> throw AtomicMoveNotSupportedException(null, destination.toString(), "주입") }
        val committer = StagingCommitter(mover, fastRetry)

        val channel = FileChannel.open(jar, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock = channel.lock()
        try {
            val result = committer.commit(area.dir, target, "t-2")
            assertTrue(result is CommitResult.Failed, "$result")
            assertFalse(Files.exists(target), "이미 복사한 a.txt 까지 정리한다")
        } finally {
            lock.release()
            channel.close()
        }
        val report = rollbackStaging(area, stagingRoot, emptyList())
        assertTrue(report.cleanedUp, "잠금을 푼 뒤 롤백은 깨끗하다: ${report.leftovers}")
        assertFalse(Files.exists(area.dir))
    }

    /** 잠금까지 갖춘 실제 스테이징이 필요한 경우 (`servers/.staging` 모양). */
    private fun injectedCase(name: String): Pair<Path, Path> {
        val base = Files.createDirectories(root.resolve(name))
        return base to base.resolve(".staging")
    }
}
