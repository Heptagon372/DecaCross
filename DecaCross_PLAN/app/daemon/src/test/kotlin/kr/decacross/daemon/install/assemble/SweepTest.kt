package kr.decacross.daemon.install.assemble

import kr.decacross.daemon.install.INCOMPLETE_MARKER_NAME
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.StagingOpenResult
import kr.decacross.daemon.install.findLeftovers
import kr.decacross.daemon.install.openStaging
import kr.decacross.daemon.install.sweepStaging
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.testkit.TreeSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 남은 조각 점검(읽기 전용)과 스윕 (DESIGN2 §2.13, D-I41/critique M5). */
class SweepTest {
    private val root: Path = Files.createTempDirectory("dcx-sweep")

    private fun serversRoot(name: String): Path = Files.createDirectories(root.resolve(name))

    private fun stagingRootOf(serversRoot: Path): Path = serversRoot.resolve(DecaPaths.STAGING_DIR_NAME)

    private fun seedStaleStaging(serversRoot: Path, installId: String) {
        val staging = Files.createDirectories(stagingRootOf(serversRoot).resolve(installId))
        Files.writeString(staging.resolve("leftover.txt"), "조각")
        Files.writeString(stagingRootOf(serversRoot).resolve("$installId.lock"), "")
    }

    private fun seedServer(serversRoot: Path, name: String, incompleteId: String? = null): Path {
        val dir = Files.createDirectories(serversRoot.resolve(name))
        Files.createDirectories(dir.resolve(META_DIR_NAME))
        Files.writeString(dir.resolve(META_DIR_NAME).resolve("launch.json"), "{}")
        if (incompleteId != null) Files.writeString(dir.resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME), incompleteId)
        return dir
    }

    @Test
    fun findLeftovers_selectsFreeStagingAndIncompleteServers_withoutTouchingAnything() {
        val servers = serversRoot("read-only")
        seedStaleStaging(servers, "old-1")
        val half = seedServer(servers, "half", incompleteId = "gone-1")
        val normal = seedServer(servers, "demo")

        val before = TreeSnapshot.of(servers)
        val leftovers = findLeftovers(servers)
        assertEquals(before, TreeSnapshot.of(servers), "점검은 파일을 만들거나 지우지 않는다 (잠금 파일도 새로 만들지 않는다)")
        assertTrue(stagingRootOf(servers).resolve("old-1") in leftovers, "$leftovers")
        assertTrue(half in leftovers, "$leftovers")
        assertFalse(normal in leftovers, "$leftovers")
    }

    @Test
    fun findLeftovers_missingRoot_isEmpty() {
        assertEquals(emptyList(), findLeftovers(root.resolve("does-not-exist")))
    }

    @Test
    fun findLeftovers_skipsLockedStaging() {
        val servers = serversRoot("read-only-locked")
        val opened = openStaging(stagingRootOf(servers), "busy-1", "demo") as StagingOpenResult.Opened
        try {
            assertFalse(stagingRootOf(servers).resolve("busy-1") in findLeftovers(servers))
        } finally {
            opened.area.close()
        }
    }

    @Test
    fun openStaging_nameLockThatCannotBeOpened_isFailed_notNameBusy() {
        val servers = serversRoot("name-lock-unopenable")
        val stagingRoot = stagingRootOf(servers)
        // 잠금 파일 자리에 디렉터리를 두면 FileChannel.open 이 IOException 을 던진다 (Windows·Linux 공통).
        // 이건 "다른 설치가 진행 중" 이 아니라 영구적인 I/O 문제이므로 NameBusy 로 보고하면 안 된다.
        Files.createDirectories(stagingRoot.resolve(nameLockFileName("demo")))

        val opened = openStaging(stagingRoot, "t-1", "demo")
        val failed = assertIs<StagingOpenResult.Failed>(opened, "열 수 없는 잠금은 Failed 여야 한다: $opened")
        assertEquals(stagingRoot.resolve(nameLockFileName("demo")), failed.path)
        assertFalse(Files.exists(stagingRoot.resolve("t-1")), "설치 디렉터리는 만들지 않는다")
    }

    @Test
    fun openStaging_nameLockHeldElsewhere_isNameBusy() {
        val servers = serversRoot("name-lock-busy")
        val stagingRoot = stagingRootOf(servers)
        val held = assertIs<LockAcquire.Held>(acquireLockFile(stagingRoot.resolve(nameLockFileName("demo"))))
        try {
            val opened = openStaging(stagingRoot, "t-1", "demo")
            assertIs<StagingOpenResult.NameBusy>(opened, "잠금 경합은 NameBusy 다: $opened")
        } finally {
            held.handle.close()
        }
    }

    @Test
    fun sweep_removesFreeStagingAndIncompleteServers_keepsLockedAndNormal() {
        val servers = serversRoot("sweep")
        seedStaleStaging(servers, "old-1")
        val half = seedServer(servers, "half", incompleteId = "gone-1")
        val normal = seedServer(servers, "demo")
        val normalBefore = TreeSnapshot.of(normal)
        val opened = openStaging(stagingRootOf(servers), "busy-1", "다른 서버") as StagingOpenResult.Opened
        Files.writeString(opened.area.dir.resolve("work.txt"), "진행 중")
        // 잠금이 풀린 이름 잠금 파일도 스윕 대상이다
        Files.writeString(stagingRootOf(servers).resolve("name-0123456789abcdef.lock"), "")

        val report = try {
            sweepStaging(servers)
        } finally {
            opened.area.close()
        }

        assertEquals(emptyList(), report.failed, "$report")
        assertTrue(stagingRootOf(servers).resolve("old-1") in report.removedStaging, "$report")
        assertFalse(Files.exists(stagingRootOf(servers).resolve("old-1")))
        assertFalse(Files.exists(stagingRootOf(servers).resolve("old-1.lock")))
        assertFalse(Files.exists(stagingRootOf(servers).resolve("name-0123456789abcdef.lock")))

        assertTrue(half in report.removedIncomplete, "$report")
        assertFalse(Files.exists(half))

        assertTrue(Files.isDirectory(opened.area.dir), "잠긴 스테이징은 건드리지 않는다")
        assertEquals(normalBefore, TreeSnapshot.of(normal), "정상 서버는 그대로")
    }

    @Test
    fun sweep_removesEmptyStagingRoot() {
        val servers = serversRoot("sweep-empty")
        seedStaleStaging(servers, "old-9")
        sweepStaging(servers)
        assertFalse(Files.exists(stagingRootOf(servers)), "비면 .staging 자체를 지운다")
    }

    @Test
    fun sweep_missingRoot_isNoOp() {
        val report = sweepStaging(root.resolve("nothing-here"))
        assertEquals(emptyList(), report.removedStaging)
        assertEquals(emptyList(), report.removedIncomplete)
        assertEquals(emptyList(), report.failed)
    }
}
