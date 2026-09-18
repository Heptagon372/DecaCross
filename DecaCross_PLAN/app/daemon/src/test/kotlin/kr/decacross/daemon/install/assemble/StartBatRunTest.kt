package kr.decacross.daemon.install.assemble

import kr.decacross.daemon.install.FlagSelection
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.install.renderStartBat
import kr.decacross.daemon.install.selectFlagProfile
import kr.decacross.daemon.testkit.FakeServerJar
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 생성한 start.bat 이 **런처 없이** 실제로 서버를 띄우는가 (불변식 11, critique A1/A7, 개정 프로브 `R2\revision\batrc`).
 * 진짜 마인크래프트 서버는 절대 쓰지 않는다 — WP0 테스트 킷의 가짜 서버 jar 만 쓴다 (§4.2 규칙 8).
 */
class StartBatRunTest {
    private val root: Path = Files.createTempDirectory("dcx-bat")
    private val profiles = (loadLaunchProfiles() as? LaunchProfilesLoad.Loaded)?.profiles ?: fail("리소스 로드 실패")
    private val nativeCharset: Charset = runCatching { Charset.forName(System.getProperty("native.encoding")) }
        .getOrElse { Charsets.UTF_8 }

    /** 가짜 jar 는 class 파일 69(Java 25)라 테스트 JVM 으로만 돈다. */
    private val testJavaHome: Path = Path.of(System.getProperty("java.home"))

    private data class RunOutcome(val exitCode: Int, val text: String, val events: List<String>, val serverDir: Path)

    @Test
    fun variantA_asciiJavaPathWithSpacesAndParens_runsAndStops() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        withJunction(root.resolve("Java Home (x86)").resolve("jdk")) { java ->
            val outcome = runStartBat("variant-a", java, emptyMap(), emptyList(), 64)
            assertRanAndStopped(outcome)
            assertTrue(outcome.text.contains("fake.maxMemoryMb="), outcome.text)
            val reported = Regex("""fake\.maxMemoryMb=(\d+)""").find(outcome.text)?.groupValues?.get(1)?.toInt() ?: fail("힙 표시 없음")
            assertTrue(reported in 50..80, "-Xmx64M 이 전달돼야 한다: $reported")
        }
    }

    @Test
    fun variantB_koreanLocalAppDataPrefix_staysAsciiAndRuns() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        val prefix = Files.createDirectories(root.resolve("한글 앱데이터"))
        withJunction(prefix.resolve("jdk")) { java ->
            val env = mapOf("LOCALAPPDATA" to prefix.toString())
            val bat = renderStartBat(specFor(java, emptyList(), 64), env)
            assertTrue(bat.all { it.toInt() in 0..127 }, "변형 B 는 ASCII 파일이어야 한다")
            val outcome = runStartBat("variant-b", java, env, emptyList(), 64)
            assertRanAndStopped(outcome)
        }
    }

    @Test
    fun variantC_koreanJavaPath_runsThroughUtf8Script() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        withJunction(root.resolve("한글 자바").resolve("jdk")) { java ->
            val bat = String(renderStartBat(specFor(java, emptyList(), 64), emptyMap()), Charsets.UTF_8)
            assertTrue(bat.contains("chcp 65001 <nul >nul"), bat)
            val outcome = runStartBat("variant-c", java, emptyMap(), emptyList(), 64)
            assertRanAndStopped(outcome)
        }
    }

    @Test
    fun badJvmFlag_exitCodeIsPreserved() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        withJunction(root.resolve("Java Home (x86)").resolve("jdk-bad")) { java ->
            val outcome = runStartBat("bad-flag", java, emptyMap(), listOf("-XX:+NoSuchFlagDecaCross"), 64)
            // set RC / pause / exit /b 꼬리가 java 의 종료 코드를 그대로 넘긴다 (critique A7)
            assertEquals(1, outcome.exitCode, outcome.text)
        }
    }

    @Test
    fun aikarProfileWithoutPreTouch_startsOnThisJdk() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "start.bat 은 Windows 전용")
        val flags = (selectFlagProfile(profiles, 256, 25, preTouch = false) as FlagSelection.Selected).flags
        assertTrue(flags.none { it == "-XX:+AlwaysPreTouch" }, "$flags")
        withJunction(root.resolve("Java Home (x86)").resolve("jdk-aikar")) { java ->
            val outcome = runStartBat("aikar", java, emptyMap(), flags, 256)
            assertRanAndStopped(outcome)
        }
    }

    private fun assertRanAndStopped(outcome: RunOutcome) {
        assertTrue(outcome.text.contains("Done ("), "기동 완료 줄이 없다: ${outcome.text}")
        assertTrue(
            outcome.text.lines().any { it.contains("fake.cwd=") && it.trimEnd().endsWith(outcome.serverDir.fileName.toString()) },
            "작업 디렉터리가 서버 폴더가 아니다: ${outcome.text}",
        )
        assertTrue("stdin:stop" in outcome.events, "리다이렉트된 stop 이 서버에 닿지 않았다: ${outcome.events}")
        assertTrue("stop-exit" in outcome.events, "${outcome.events}")
        assertEquals(0, outcome.exitCode, outcome.text)
    }

    private fun specFor(java: Path, flags: List<String>, heapMb: Int): LaunchSpec = LaunchSpec(
        javaPath = java.toString(),
        javaFeature = 25,
        xmsMb = heapMb,
        xmxMb = heapMb,
        flagProfileId = null,
        jvmFlags = flags,
        jarFileName = "paper-1.21.8.jar",
    )

    /**
     * `<tmp>\루트 & 100% (x)\서버 폴더 (테스트) <case>\` 에 가짜 서버와 start.bat 을 두고, **다른 작업 디렉터리**에서
     * 절대경로로 부른다 (`cmd.exe /d /s /c ""<abs>""` — `&` 가 든 경로에서 `/d /c abs` 는 실패한다, 프로브 확인).
     */
    private fun runStartBat(
        caseName: String,
        java: Path,
        env: Map<String, String>,
        flags: List<String>,
        heapMb: Int,
    ): RunOutcome {
        val serverDir = Files.createDirectories(
            root.resolve("루트 & 100% (x)").resolve("서버 폴더 (테스트) $caseName"),
        )
        val elsewhere = Files.createDirectories(root.resolve("elsewhere"))
        FakeServerJar.write(serverDir.resolve("paper-1.21.8.jar"))
        val bat = serverDir.resolve("start.bat")
        Files.write(bat, renderStartBat(specFor(java, flags, heapMb), env))
        val stdin = serverDir.resolve("stdin.txt")
        Files.write(stdin, "stop\r\n".toByteArray(Charsets.US_ASCII))

        val builder = ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"\"" + bat.toAbsolutePath() + "\"\"")
            .directory(elsewhere.toFile())
            .redirectInput(stdin.toFile())
            .redirectErrorStream(true)
        env.forEach { (key, value) -> builder.environment()[key] = value }
        val process = builder.start()
        try {
            val raw = process.inputStream.readBytes()
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "start.bat 이 끝나지 않았다")
            // 코드페이지가 다를 수 있어 두 해석을 모두 본다 (개정 프로브와 같은 방식)
            val text = String(raw, nativeCharset) + "\n" + String(raw, Charsets.UTF_8)
            return RunOutcome(process.exitValue(), text, FakeServerJar.events(serverDir), serverDir)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(20, TimeUnit.SECONDS)
        }
    }

    /** `<link>` → 테스트 JVM 의 `java.home` 접합(junction). 경로에 공백·괄호·한글이 있어도 되는지 확인하기 위한 장치다. */
    private fun withJunction(link: Path, body: (Path) -> Unit) {
        Files.createDirectories(link.parent)
        val create = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), testJavaHome.toString())
            .redirectErrorStream(true)
            .start()
        val output = String(create.inputStream.readBytes(), nativeCharset)
        assertTrue(create.waitFor(30, TimeUnit.SECONDS), "mklink 가 끝나지 않았다")
        assumeTrue(create.exitValue() == 0, "접합을 만들 수 없는 환경: $output")
        try {
            body(link.resolve("bin").resolve("java.exe"))
        } finally {
            ProcessBuilder("cmd.exe", "/d", "/c", "rmdir", link.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor(30, TimeUnit.SECONDS)
        }
    }
}
