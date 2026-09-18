package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.testkit.FakeServerJar
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [launchServer] · [stripAnsi] — 실제 가짜 서버 프로세스 (DESIGN2 §2.9). */
class ServerConsoleTest {
    @Test
    fun stripAnsiRemovesCsiSequences() {
        assertEquals("Saved the game", stripAnsi("\u001B[32mSaved the game\u001B[0m"))
        assertEquals("Done (1.2s)! For help", stripAnsi("\u001B[0;1;32mDone (1.2s)! For help\u001B[m"))
        assertEquals("한글 유지", stripAnsi("\u001B[38;5;208m한글 유지\u001B[39m"))
        assertEquals("그대로", stripAnsi("그대로"))
        // 커서 이동·지우기 같은 다른 CSI 도 지운다
        assertEquals("줄", stripAnsi("\u001B[2K\u001B[1;31m줄\u001B[0m"))
    }

    @Test
    fun launchesAndTalksUtf8BothWays() {
        val dir = ServerFixture.createServerDir()
        val printed = CopyOnWriteArrayList<String>()
        val result = launchServer(
            dir,
            ServerFixture.spec(),
            ServerFixture.patterns,
            // 출력 콜백이 예외를 던져도 펌프는 멈추지 않는다 — 멈추면 살아 있는 서버가 "종료됨" 으로 보이고 강제 종료로 이어진다
            outputLine = {
                printed.add(it)
                if (it.contains("fake.args=")) throw IllegalStateException("출력 콜백 실패")
            },
        )
        val process = (result as? LaunchResult.Started)?.process ?: fail("기동 실패: $result")
        try {
            runBlocking {
                assertEquals(ReadyState.READY, process.awaitReady(30.seconds), "콜백이 던져도 준비 완료 줄까지 읽는다")
                assertTrue(printed.any { it.contains("fake.args=") }, "던진 줄도 콜백에는 들어갔다")
                assertTrue(process.pid > 0)
                assertTrue(printed.any { it.contains("fake.korean=한글 출력 확인") }, "UTF-8 출력이 그대로: $printed")
                assertTrue(printed.any { it.contains("fake.maxMemoryMb=") }, "출력 펌프가 모든 줄을 넘긴다")
                assertEquals(
                    SendResult.MATCHED,
                    process.send("say 한글 테스트", Regex("""\[Server] 한글 테스트"""), 10.seconds),
                    "UTF-8 로 쓰고 UTF-8 로 읽는다",
                )
                assertEquals(SendResult.TIMED_OUT, process.send("say x", Regex("절대 안 나옴"), 300.milliseconds))
                // 제한 시간이 0(인터페이스 기본값)이어도 줄은 반드시 쓴다 — 기다리지 않을 뿐이다
                assertEquals(SendResult.TIMED_OUT, process.send("say 즉시", Regex("절대 안 나옴"), Duration.ZERO))
                assertTrue(
                    ServerFixture.awaitEvent(dir, "stdin:say 즉시").contains("stdin:say 즉시"),
                    "제한 시간 0 에서도 stdin 에 쓴다: ${ServerFixture.awaitEvent(dir, "stdin:say 즉시")}",
                )
                assertEquals(ReadyState.READY, process.awaitReady(null), "이미 본 준비 완료는 바로 돌려준다")
                process.kill()
                assertNotNull(process.awaitExit(20.seconds), "강제 종료 뒤 종료 코드가 잡힌다")
                assertFalse(process.isAlive)
            }
        } finally {
            process.kill()
        }
    }

    @Test
    fun awaitReadyReportsExitWhenTheJvmRefusesToStart() {
        val dir = ServerFixture.createServerDir()
        // 없는 JVM 플래그 → JVM 이 바로 죽는다 (준비 완료 줄은 영영 안 온다)
        val result = launchServer(dir, ServerFixture.spec("-XX:+NoSuchFlagDecaCross"), ServerFixture.patterns, outputLine = { })
        val process = (result as? LaunchResult.Started)?.process ?: fail("기동 실패: $result")
        try {
            runBlocking {
                assertEquals(ReadyState.EXITED, process.awaitReady(30.seconds))
                assertEquals(1, process.awaitExit(10.seconds))
                assertEquals(SendResult.STDIN_CLOSED, process.send("stop"), "죽은 프로세스에는 쓸 수 없다")
            }
        } finally {
            process.kill()
        }
    }

    @Test
    fun missingJavaIsFailed() {
        val dir = ServerFixture.createServerDir()
        val spec = ServerFixture.spec().copy(javaPath = dir.resolve("없는 java.exe").toString())
        val result = launchServer(dir, spec, ServerFixture.patterns, outputLine = { })
        val failed = result as? LaunchResult.Failed ?: fail("Failed 를 기대했다: $result")
        assertTrue(failed.detailKo.contains("Java"), failed.detailKo)
    }

    @Test
    fun missingJarAndMissingDirAreFailed() {
        val dir = ServerFixture.createServerDir()
        val noJar: LaunchSpec = ServerFixture.spec().copy(jarFileName = "없는-서버.jar")
        assertTrue(launchServer(dir, noJar, ServerFixture.patterns, outputLine = { }) is LaunchResult.Failed)
        val missingDir = dir.resolve("어디에도 없음")
        assertTrue(launchServer(missingDir, ServerFixture.spec(), ServerFixture.patterns, outputLine = { }) is LaunchResult.Failed)
    }

    @Test
    fun testJavaIsARegularFile() {
        // 다른 테스트가 모두 이 경로를 쓴다 — 못 찾으면 원인을 여기서 알 수 있게
        assertTrue(Files.isRegularFile(FakeServerJar.testJava()), "테스트 JVM 의 java: ${FakeServerJar.testJava()}")
    }
}
