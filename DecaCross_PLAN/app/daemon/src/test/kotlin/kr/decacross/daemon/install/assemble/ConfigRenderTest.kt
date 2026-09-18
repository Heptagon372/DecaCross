package kr.decacross.daemon.install.assemble

import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.install.ConsentChannel
import kr.decacross.daemon.install.Difficulty
import kr.decacross.daemon.install.FlagSelection
import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.ServerSettings
import kr.decacross.daemon.install.coreJarFileName
import kr.decacross.daemon.install.defaultServerName
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.install.processCommand
import kr.decacross.daemon.install.renderEulaTxt
import kr.decacross.daemon.install.renderServerProperties
import kr.decacross.daemon.install.renderStartBat
import kr.decacross.daemon.install.renderStartSh
import kr.decacross.daemon.install.scriptJvmArgs
import kr.decacross.daemon.install.selectFlagProfile
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/** 생성 파일 골든 (DESIGN2 §2.7·§2.8, research codebase-windows §6 + 개정 프로브 batrc). */
class ConfigRenderTest {
    private val profiles = (loadLaunchProfiles() as? LaunchProfilesLoad.Loaded)?.profiles ?: fail("리소스 로드 실패")
    private val aikarBase = (selectFlagProfile(profiles, 4096, 21, preTouch = true) as FlagSelection.Selected).flags

    private fun spec(javaPath: String): LaunchSpec = LaunchSpec(
        javaPath = javaPath,
        javaFeature = 21,
        xmsMb = 4096,
        xmxMb = 4096,
        flagProfileId = "aikar-base",
        jvmFlags = aikarBase,
        jarFileName = "paper-1.21.8.jar",
    )

    private fun expectedArgs(spec: LaunchSpec): String =
        (spec.scriptJvmArgs() + listOf("-jar", spec.jarFileName) + spec.serverArgs).joinToString(" ")

    @Test
    fun startBat_variantA_isAsciiCrlf_withRcTail() {
        val launch = spec("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe")
        val bytes = renderStartBat(launch, emptyMap())
        val expected = listOf(
            "@echo off",
            "setlocal",
            "cd /d \"%~dp0\"",
            "\"C:\\Program Files\\Java\\jdk-21\\bin\\java.exe\" " + expectedArgs(launch),
            "set \"DECACROSS_RC=%ERRORLEVEL%\"",
            "pause",
            "exit /b %DECACROSS_RC%",
        ).joinToString("\r\n") + "\r\n"
        assertEquals(expected, String(bytes, Charsets.US_ASCII))
        assertTrue(bytes.all { it.toInt() in 0..127 }, "모든 바이트가 ASCII 여야 한다")
        assertFalse(bytes.size >= 3 && bytes[0] == 0xEF.toByte(), "BOM 금지")
        assertTrue(expectedArgs(launch).startsWith("-Xms4096M -Xmx4096M "), expectedArgs(launch))
        assertTrue(expectedArgs(launch).contains(" -Dfile.encoding=UTF-8 -jar paper-1.21.8.jar nogui"), expectedArgs(launch))
    }

    @Test
    fun startBat_percentInJavaPath_isDoubled() {
        val bytes = renderStartBat(spec("C:\\java 100%\\bin\\java.exe"), emptyMap())
        val text = String(bytes, Charsets.US_ASCII)
        assertTrue(text.contains("\"C:\\java 100%%\\bin\\java.exe\""), text.lines()[3])
    }

    /**
     * ★ 변형 B·C 도 리터럴 `%` 를 이중화해야 한다. cmd 는 배치 파일의 짝 없는 `%` 를 지우므로
     * `D:\한글 100%\bin\java.exe` 가 `D:\한글 100\bin\java.exe` 로 불려 기동이 조용히 실패한다 (프로브 rc 3).
     */
    @Test
    fun batJavaToken_doublesPercent_inEveryVariant() {
        // 변형 C: 어느 환경변수 접두사에도 걸리지 않는 비 ASCII 경로
        val variantC = batJavaToken("D:\\한글 100%\\bin\\java.exe", emptyMap())
        assertTrue(variantC.needsUtf8, "$variantC")
        assertEquals("\"D:\\한글 100%%\\bin\\java.exe\"", variantC.token)

        // 변형 B: 접두사는 대체되고 나머지의 `%` 도 이중화된다
        val localAppData = "C:\\사용자\\데카\\AppData\\Local"
        val variantB = batJavaToken("$localAppData\\jdk 100%\\bin\\java.exe", mapOf("localappdata" to localAppData))
        assertFalse(variantB.needsUtf8, "$variantB")
        assertEquals("\"%LOCALAPPDATA%\\jdk 100%%\\bin\\java.exe\"", variantB.token)

        // 렌더링된 파일에도 그대로 나온다 (한 줄도 홀수 개의 `%` 를 남기지 않는다)
        val text = String(renderStartBat(spec("D:\\한글 100%\\bin\\java.exe"), emptyMap()), Charsets.UTF_8)
        assertTrue(text.contains("\"D:\\한글 100%%\\bin\\java.exe\""), text)
        assertFalse(text.contains("100%\\"), text)
    }

    @Test
    fun startBat_variantB_usesEnvPrefix_withCaseInsensitiveKey() {
        // env 키 철자는 `localappdata`, 값에는 한글 — 그래도 파일 전체가 ASCII 로 유지돼야 한다 (SCP-I22, D-I40)
        val localAppData = "C:\\사용자\\데카\\AppData\\Local"
        val launch = spec("$localAppData\\DecaCross\\runtimes\\temurin-21-jre\\bin\\java.exe")
        val bytes = renderStartBat(launch, mapOf("localappdata" to localAppData))
        val text = String(bytes, Charsets.US_ASCII)
        assertTrue(bytes.all { it.toInt() in 0..127 }, "변형 B 는 ASCII 파일이다")
        assertTrue(
            text.contains("\"%LOCALAPPDATA%\\DecaCross\\runtimes\\temurin-21-jre\\bin\\java.exe\""),
            text.lines()[3],
        )
        assertFalse(text.contains("chcp"), "ASCII 로 유지되면 코드페이지를 건드리지 않는다")
    }

    @Test
    fun startBat_variantC_isUtf8_andEveryChcpReadsNul() {
        val launch = spec("D:\\한글 자바\\bin\\java.exe")
        val bytes = renderStartBat(launch, emptyMap())
        val text = String(bytes, Charsets.UTF_8)
        assertFalse(bytes.size >= 3 && bytes[0] == 0xEF.toByte(), "BOM 금지")
        val lines = text.split("\r\n").dropLast(1)
        assertEquals(
            listOf(
                "@echo off",
                "setlocal",
                "for /f \"tokens=2 delims=:\" %%a in ('chcp ^<nul') do set \"DECACROSS_OLDCP=%%a\"",
                "chcp 65001 <nul >nul",
                "cd /d \"%~dp0\"",
                "\"D:\\한글 자바\\bin\\java.exe\" " + expectedArgs(launch),
                "set \"DECACROSS_RC=%ERRORLEVEL%\"",
                "chcp %DECACROSS_OLDCP% <nul >nul",
                "pause",
                "exit /b %DECACROSS_RC%",
            ),
            lines,
        )
        // ★ critique W3: chcp 가 <nul 없이 돌면 미리 리다이렉트된 표준입력을 먹는다
        for (line in lines.filter { it.contains("chcp") }) assertTrue(line.contains("<nul"), line)
    }

    @Test
    fun startSh_isLfGolden_andQuotesCorrectly() {
        val launch = spec("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe")
        val text = String(renderStartSh(launch), Charsets.UTF_8)
        val expected = listOf(
            "#!/bin/sh",
            "cd \"\$(dirname \"\$0\")\" || exit 1",
            "exec 'C:\\Program Files\\Java\\jdk-21\\bin\\java.exe' " + expectedArgs(launch),
        ).joinToString("\n") + "\n"
        assertEquals(expected, text)
        assertFalse(text.contains("\r"), "start.sh 는 LF")
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'it'\\''s'", shellQuotePosix("it's"))
        assertEquals("-XX:+UseG1GC", shellQuotePosix("-XX:+UseG1GC"))
        assertEquals("-Dusing.aikars.flags=https://mcflags.emc.gs", shellQuotePosix("-Dusing.aikars.flags=https://mcflags.emc.gs"))
        assertEquals("'a b'", shellQuotePosix("a b"))
    }

    @Test
    fun renderedScriptsAndProcessCommand_neverMentionEula() {
        val launch = spec("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe")
        val bat = String(renderStartBat(launch, emptyMap()), Charsets.US_ASCII).lowercase()
        val sh = String(renderStartSh(launch), Charsets.UTF_8).lowercase()
        assertFalse(bat.contains("eula"), "start.bat 에 eula 금지 (critique B2)")
        assertFalse(sh.contains("eula"), "start.sh 에 eula 금지")
        assertTrue(launch.processCommand().none { it.contains("eula", ignoreCase = true) })
    }

    @Test
    fun nonAsciiOrForbiddenTokens_areRejected_soThePipelineReportsLocalIo() {
        // java 경로 외의 토큰은 리소스·정규화된 이름에서만 오므로 ASCII 다. 아니면 스크립트를 만들지 않는다 (DESIGN2 §2.7)
        val korean = spec("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe").copy(serverArgs = listOf("한글인자"))
        assertFailsWith<IllegalArgumentException> { renderStartBat(korean, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { renderStartSh(korean) }

        // 동의 관련 문자열이 든 토큰은 스크립트에 들어가지 못한다 (critique B2)
        val forbidden = spec("C:\\Program Files\\Java\\jdk-21\\bin\\java.exe").copy(jvmFlags = listOf("-Dsome.eula.flag=1"))
        assertFailsWith<IllegalArgumentException> { renderStartBat(forbidden, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { renderStartSh(forbidden) }
    }

    @Test
    fun serverProperties_isGolden_andAsciiOnly() {
        val bytes = renderServerProperties(ServerSettings(motd = null, maxPlayers = 20, difficulty = Difficulty.EASY), "demo")
        assertEquals(
            "#Minecraft server properties\nmotd=demo\nmax-players=20\ndifficulty=easy\nonline-mode=true\n",
            String(bytes, Charsets.US_ASCII),
        )
        val korean = renderServerProperties(
            ServerSettings(motd = "데모 서버 #1 = 테스트!", maxPlayers = 8, difficulty = Difficulty.HARD, onlineMode = false),
            "demo",
        )
        assertTrue(korean.all { it.toInt() in 0..127 }, "ASCII 전용 (Properties 규칙의 \\uXXXX)")
        val text = String(korean, Charsets.US_ASCII)
        assertTrue(text.contains("\\uB370\\uBAA8"), text) // 데모
        assertTrue(text.contains("\\#1 \\= "), text)
        assertTrue(text.endsWith("online-mode=false\n"), text)
    }

    @Test
    fun serverProperties_roundTripsThroughProperties() {
        for (motd in listOf("데모 서버 #1 = 테스트!", " lead", "a:b", "back\\slash", "tab\there")) {
            val bytes = renderServerProperties(ServerSettings(motd = motd), "demo")
            val properties = Properties()
            bytes.inputStream().use { properties.load(it) }
            assertEquals(motd, properties.getProperty("motd"), "motd 왕복: $motd")
            assertEquals("20", properties.getProperty("max-players"))
            assertEquals("easy", properties.getProperty("difficulty"))
            assertEquals("true", properties.getProperty("online-mode"))
        }
    }

    @Test
    fun eulaTxt_isGolden() {
        val bytes = renderEulaTxt(ConsentChannel.INTERACTIVE_PROMPT, Instant.parse("2026-09-17T13:05:00Z"))
        assertEquals(
            "#By changing the setting below to TRUE you are indicating your agreement to our EULA " +
                "(https://aka.ms/MinecraftEULA).\n" +
                "#2026-09-17T13:05:00Z\n" +
                "#EULA accepted via DecaCross (INTERACTIVE_PROMPT)\n" +
                "eula=true\n",
            String(bytes, Charsets.US_ASCII),
        )
        val cli = String(renderEulaTxt(ConsentChannel.CLI_FLAG, Instant.parse("2026-09-17T13:05:00Z")), Charsets.US_ASCII)
        assertTrue(cli.contains("#EULA accepted via DecaCross (CLI_FLAG)"), cli)
    }

    @Test
    fun jarAndServerNames_sanitiseLabels() {
        assertEquals("paper-1.14.2_Pre-Release_4.jar", coreJarFileName(CoreKey.PAPER, "1.14.2 Pre-Release 4"))
        assertEquals("paper-1.21.8", defaultServerName(CoreKey.PAPER, "1.21.8"))
        assertEquals("purpur-1.21.8.jar", coreJarFileName(CoreKey.PURPUR, "1.21.8"))
    }

    @Test
    fun batJavaToken_prefersLongestEnvValue() {
        val env = mapOf(
            "USERPROFILE" to "C:\\사용자\\데카",
            "LOCALAPPDATA" to "C:\\사용자\\데카\\AppData\\Local",
        )
        val token = batJavaToken("C:\\사용자\\데카\\AppData\\Local\\DecaCross\\jre\\bin\\java.exe", env)
        assertEquals("\"%LOCALAPPDATA%\\DecaCross\\jre\\bin\\java.exe\"", token.token)
        assertFalse(token.needsUtf8)

        val userProfile = batJavaToken("C:\\사용자\\데카\\jdk\\bin\\java.exe", env)
        assertEquals("\"%USERPROFILE%\\jdk\\bin\\java.exe\"", userProfile.token)

        val unmatched = batJavaToken("D:\\한글\\bin\\java.exe", env)
        assertTrue(unmatched.needsUtf8)
        assertEquals("\"D:\\한글\\bin\\java.exe\"", unmatched.token)
    }
}
