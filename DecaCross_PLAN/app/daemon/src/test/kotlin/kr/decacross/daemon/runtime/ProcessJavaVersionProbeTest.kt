package kr.decacross.daemon.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.testkit.FakeServerJar
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/** 실제 `java -version` 실행 (테스트 JVM 의 java). */
class ProcessJavaVersionProbeTest {
    @Test
    fun probesTestJvmAndParsesItsFeature() = runBlocking {
        val output = ProcessJavaVersionProbe().versionOutput(FakeServerJar.testJava())
            ?: fail("테스트 JVM 의 java -version 이 실패했다")
        val parsed = parseJavaVersionOutput(output) ?: fail("해석 실패: $output")
        assertEquals(Runtime.version().feature(), parsed.feature)
        Unit
    }

    @Test
    fun missingExecutableIsNull() = runBlocking {
        val missing = Files.createTempDirectory("dcx-probe").resolve("java-없음.exe")
        assertNull(ProcessJavaVersionProbe().versionOutput(missing))
        Unit
    }

    @Test
    fun aCandidateThatKeepsStdoutOpenIsCutOffByTheTimeout() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"), "배치 파일 후보는 Windows 기준")
        // 이름만 java 인 상주 프로그램: stdout 을 닫지 않는다. 전부 읽고 나서 기다리면 제한 시간이 무의미해진다.
        val dir = Files.createTempDirectory("dcx-probe")
        val stalling = dir.resolve("java.bat")
        Files.writeString(
            stalling,
            "@echo off\r\necho started > \"%~dp0started.txt\"\r\nping -n 4 127.0.0.1 >nul\r\necho openjdk version \"21.0.4\"\r\n",
        )
        val started = System.nanoTime()
        assertNull(ProcessJavaVersionProbe(300.milliseconds).versionOutput(stalling), "제한 시간을 넘기면 null")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "읽기가 끝나기를 기다리지 않는다 (${elapsedMs}ms)")
        // 후보가 정말로 실행됐는지 확인한다 (실행 자체가 실패해 null 이 나온 것이면 이 테스트는 아무것도 증명하지 못한다)
        val marker = dir.resolve("started.txt")
        val deadline = System.nanoTime() + 3_000_000_000
        while (!Files.exists(marker) && System.nanoTime() < deadline) delay(20)
        assertTrue(Files.exists(marker), "가짜 후보가 실제로 실행됐다")
        Unit
    }
}
