package kr.decacross.daemon.process

import kotlinx.serialization.json.Json
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.LayoutIoResult
import kr.decacross.daemon.install.META_DIR_NAME
import kr.decacross.daemon.install.PID_FILE_NAME
import kr.decacross.daemon.paths.currentOs
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `.decacross/server.pid` 와 월드 `session.lock` (DESIGN2 §2.9). */
class PidFileTest {
    private fun pidFile(dir: Path): Path = dir.resolve(META_DIR_NAME).resolve(PID_FILE_NAME)

    @Test
    fun writesAndRecognisesALiveServer() {
        val dir = ServerFixture.createServerDir()
        val result = launchServer(dir, ServerFixture.spec(), ServerFixture.patterns, outputLine = { })
        val process = (result as? LaunchResult.Started)?.process ?: fail("기동 실패: $result")
        val handle = ProcessHandle.of(process.pid).orElse(null) ?: fail("pid ${process.pid} 핸들 없음")
        try {
            assertTrue(writePidFile(dir, handle) is LayoutIoResult.Ok)
            val record = readPidFile(dir) ?: fail("기록을 못 읽었다")
            assertEquals(process.pid, record.pid)
            assertEquals(ProcessHandle.current().pid(), record.launcherPid, "띄운 쪽(런처) pid 를 같이 적는다")
            assertNotNull(record.startedAtEpochMs)
            assertNotNull(record.command)
            assertEquals(process.pid, liveServerProcess(dir)?.pid())
        } finally {
            process.kill()
        }
        handle.onExit().orTimeout(20, java.util.concurrent.TimeUnit.SECONDS).join()
        assertNull(liveServerProcess(dir), "끝난 프로세스는 실행 중이 아니다")
        assertTrue(Files.exists(pidFile(dir)), "낡은 기록을 스스로 지우지는 않는다")
        deletePidFile(dir)
        assertFalse(Files.exists(pidFile(dir)))
        deletePidFile(dir)
    }

    @Test
    fun staleRecordWithReusedPidIsRejected() {
        val dir = Files.createTempDirectory("dcx-pid")
        // 살아 있는 pid(이 JVM)지만 시작 시각이 다르면 다른 프로세스다
        val stale = """{"pid":${ProcessHandle.current().pid()},"startedAtEpochMs":1,"command":"java","launcherPid":1}"""
        Files.createDirectories(pidFile(dir).parent)
        Files.writeString(pidFile(dir), stale)
        assertNull(liveServerProcess(dir))
    }

    @Test
    fun recordWithADifferentCommandIsRejected() {
        val dir = Files.createTempDirectory("dcx-pid")
        val self = ProcessHandle.current()
        val startedAt = self.info().startInstant().orElse(null)?.toEpochMilli() ?: fail("시작 시각을 못 읽었다")
        val command = self.info().command().orElse(null) ?: fail("명령을 못 읽었다")
        Files.createDirectories(pidFile(dir).parent)

        // 시작 시각까지 같지만(=pid 재사용 허용 오차 안) 명령이 다르면 우리 서버가 아니다
        writeRecord(dir, PidRecord(self.pid(), startedAt, "C:\\다른 폴더\\다른것.exe", 1))
        assertNull(liveServerProcess(dir), "명령이 다르면 같은 프로세스로 보지 않는다")

        // 같은 명령이면 인정한다. Windows 에서는 대소문자가 달라도 같은 경로다
        val onWindows = currentOs() == Os.WINDOWS
        writeRecord(dir, PidRecord(self.pid(), startedAt, if (onWindows) command.uppercase() else command, 1))
        assertEquals(self.pid(), liveServerProcess(dir)?.pid(), if (onWindows) "대소문자 무시" else "같은 명령")
    }

    private fun writeRecord(dir: Path, record: PidRecord) {
        Files.writeString(pidFile(dir), Json.encodeToString(PidRecord.serializer(), record))
    }

    @Test
    fun corruptOrMissingRecordIsNull() {
        val dir = Files.createTempDirectory("dcx-pid")
        assertNull(readPidFile(dir), "파일이 없으면 null")
        assertNull(liveServerProcess(dir))
        Files.createDirectories(pidFile(dir).parent)
        Files.writeString(pidFile(dir), "{이건 JSON 이 아니다")
        assertNull(readPidFile(dir))
        assertNull(liveServerProcess(dir))
    }

    @Test
    fun sessionLockDetectsAServerStartedOutsideTheLauncher() {
        val dir = Files.createTempDirectory("dcx-lock")
        assertFalse(isWorldSessionLocked(dir), "world 폴더가 없으면 판단 불가 → false")
        assertFalse(Files.exists(dir.resolve("world").resolve("session.lock")), "파일을 만들지 않는다")

        val lock = dir.resolve("world").resolve("session.lock")
        Files.createDirectories(lock.parent)
        Files.writeString(lock, "☃☃")
        assertFalse(isWorldSessionLocked(dir), "잠기지 않은 파일 → false")

        FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                // 같은 JVM 의 OverlappingFileLockException 도 "실행 중" 으로 본다
                assertTrue(isWorldSessionLocked(dir))
            }
        }
        assertFalse(isWorldSessionLocked(dir), "풀린 뒤에는 다시 false")
    }

    @Test
    fun levelNameFromServerPropertiesIsRespected() {
        val dir = Files.createTempDirectory("dcx-lock")
        val properties = Properties()
        properties.setProperty("level-name", "월드 2")
        properties.setProperty("motd", "안녕")
        // server.properties 형식 그대로 (ISO-8859-1 + \\uXXXX 이스케이프)
        Files.newOutputStream(dir.resolve("server.properties")).use { properties.store(it, null) }
        val lock = dir.resolve("월드 2").resolve("session.lock")
        Files.createDirectories(lock.parent)
        Files.writeString(lock, "x")
        FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertTrue(isWorldSessionLocked(dir), "level-name 폴더의 잠금을 본다")
            }
        }
        // 기본 이름의 잠금은 보지 않는다
        val defaultLock = dir.resolve("world").resolve("session.lock")
        Files.createDirectories(defaultLock.parent)
        Files.writeString(defaultLock, "x")
        FileChannel.open(defaultLock, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertFalse(isWorldSessionLocked(dir))
            }
        }
    }
}
