package kr.decacross.daemon.install

import kotlinx.serialization.Serializable
import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.install.assemble.batJavaToken
import kr.decacross.daemon.install.assemble.checkScriptTokens
import kr.decacross.daemon.install.assemble.escapePropertiesValue
import kr.decacross.daemon.install.assemble.shellQuotePosix
import kotlin.time.Instant

/**
 * 서버 실행 명세 (`.decacross/launch.json`, 스키마 1). start.bat / start.sh / CLI 프로세스 기동이 전부 여기서 나온다.
 *
 * # 불변식
 * - [javaPath] 는 서버용 Java 의 절대경로 (런처 JVM 의 `java.home` 이 아니다 — 불변식 9).
 * - [jarFileName] 은 서버 폴더 기준 파일 이름. 스크립트·프로세스 모두 작업 디렉터리 = 서버 폴더에서 상대경로로 쓴다
 *   (JVM 인자에 유니코드 절대경로를 넣지 않는다: Windows java 런처가 ANSI 밖 문자를 `?` 로 바꾼다).
 * - [jvmFlags] 는 선택된 프로파일의 플래그를 그대로(순서 포함) 담는다. `-Xms/-Xmx` 와 인코딩 인자는 담지 않는다.
 */
@Serializable
data class LaunchSpec(
    val schema: Int = 1,
    val javaPath: String,
    val javaFeature: Int,
    val xmsMb: Int,
    val xmxMb: Int,
    val flagProfileId: String? = null,
    val jvmFlags: List<String> = emptyList(),
    val jarFileName: String,
    val serverArgs: List<String> = listOf("nogui"),
)

/** 스크립트·프로세스 공통 인코딩 인자 (프롬프트 03 필수). */
const val FILE_ENCODING_ARG: String = "-Dfile.encoding=UTF-8"

/**
 * 런처가 띄운 프로세스에만 붙이는 인자. 출력이 파이프라서 JDK 기본(native.encoding)이 되는 것을 막는다.
 * start.bat 에는 넣지 않는다: 코드페이지 949 콘솔에서 UTF-8 로 강제하면 한글이 깨진다.
 */
val PROCESS_STREAM_ENCODING_ARGS: List<String> = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

/** `-Xms -Xmx 플래그… -Dfile.encoding=UTF-8` (스크립트용 JVM 인자, `-jar` 앞). */
fun LaunchSpec.scriptJvmArgs(): List<String> = listOf("-Xms${xmsMb}M", "-Xmx${xmxMb}M") + jvmFlags + FILE_ENCODING_ARG

/** ProcessBuilder 명령: `java 스크립트인자… -Dstdout.encoding… -jar <jar> nogui`. 작업 디렉터리는 서버 폴더. */
fun LaunchSpec.processCommand(): List<String> =
    listOf(javaPath) + scriptJvmArgs() + PROCESS_STREAM_ENCODING_ARGS + listOf("-jar", jarFileName) + serverArgs

/**
 * 코어 jar 파일 이름: `{core}-{mc 라벨}.jar` (설계서 §14 예: `paper-1.21.8.jar`). `[A-Za-z0-9._-]` 밖 문자는 `_`.
 * WP0 완성본·동결 (CLI 의 기본 서버 이름 [defaultServerName] 이 같은 규칙을 쓴다).
 */
fun coreJarFileName(core: CoreKey, mcLabel: String): String = "${defaultServerName(core, mcLabel)}.jar"

/**
 * `--name` 을 생략했을 때의 서버 이름 `{core}-{mc 라벨}` (예: `paper-1.21.8`, SCP-I16 — 명세 §12 의 `create --mc 1.21.8 --core paper --ram 4G`).
 * `[A-Za-z0-9._-]` 밖 문자는 `_`. WP0 완성본·동결. 결과는 PLAN 의 [validateServerName] 을 그대로 거친다.
 */
fun defaultServerName(core: CoreKey, mcLabel: String): String {
    val label = mcLabel.map { c -> if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "._-") c else '_' }.joinToString("")
    return "${core.name.lowercase()}-$label"
}

/**
 * start.bat 렌더링 (DESIGN2 §2.7, research codebase-windows §6 프로브로 확인한 규칙). CRLF, BOM 없음.
 *
 * - Java 경로가 ASCII 면 그대로(`%` → `%%`), 아니면 [env] 의 `LOCALAPPDATA`·`USERPROFILE`·`ProgramFiles`·`ProgramFiles(x86)`
 *   접두사를 `%VAR%` 로 바꿔 나머지가 ASCII 가 되면 그 형태로 → 파일 전체 ASCII (SCP-I22). [env] 조회는 이름 대소문자 무시.
 * - 그래도 ASCII 가 아니면 UTF-8 + `for /f … in ('chcp ^<nul')` 로 원래 코드페이지 저장 + `chcp 65001 <nul >nul` + 복원
 *   `chcp %DECACROSS_OLDCP% <nul >nul`. ★ `chcp` 에 `<nul` 이 없으면 미리 리다이렉트된 표준입력을 먹어 버린다 (critique windows #3, 개정 프로브 확인).
 * - 항상 `cd /d "%~dp0"`, Java 줄 다음 `set "DECACROSS_RC=%ERRORLEVEL%"` → `pause` → `exit /b %DECACROSS_RC%`
 *   (더블클릭 창은 에러에서 멈추고, 스크립트 실행은 Java 종료 코드를 받는다 — 개정 프로브 확인). 괄호 블록·지연 확장 없음. 주석 줄 없음.
 * - `eula` 관련 인자·문자열을 절대 넣지 않는다 (테스트로 강제, critique B2).
 *
 * @throws IllegalArgumentException Java 경로 **밖**의 토큰(`jvmFlags`·`jarFileName`·`serverArgs`)이 ASCII 가 아니거나
 *   `eula` 를 담을 때. 파이프라인은 이걸 잡아 `InstallFailure.LocalIo` 로 보고한다. 사용자가 손댈 수 있는
 *   `.decacross/launch.json` 을 읽어 다시 부르는 쪽(WP-CLI `start --java X --save`)도 반드시 잡아서 사용자 오류로 바꿔야 한다.
 */
fun renderStartBat(spec: LaunchSpec, env: Map<String, String>): ByteArray {
    val arguments = scriptArguments(spec)
    checkScriptTokens(arguments)
    val java = batJavaToken(spec.javaPath, env)
    val javaLine = (listOf(java.token) + arguments).joinToString(" ")
    val lines = if (java.needsUtf8) {
        // 변형 C: 코드페이지를 바꾸되 ★ 모든 chcp 는 <nul 로 표준입력을 건드리지 않는다 (critique W3)
        listOf(
            "@echo off",
            "setlocal",
            "for /f \"tokens=2 delims=:\" %%a in ('chcp ^<nul') do set \"DECACROSS_OLDCP=%%a\"",
            "chcp 65001 <nul >nul",
            "cd /d \"%~dp0\"",
            javaLine,
            BAT_RC_LINE,
            "chcp %DECACROSS_OLDCP% <nul >nul",
            "pause",
            BAT_EXIT_LINE,
        )
    } else {
        listOf("@echo off", "setlocal", "cd /d \"%~dp0\"", javaLine, BAT_RC_LINE, "pause", BAT_EXIT_LINE)
    }
    val text = lines.joinToString("\r\n") + "\r\n"
    return if (java.needsUtf8) text.toByteArray(Charsets.UTF_8) else text.toByteArray(Charsets.US_ASCII)
}

/** `-Xms… -Xmx… 플래그… -Dfile.encoding=UTF-8 -jar <jar> <서버 인자…>` (스크립트 공통). */
private fun scriptArguments(spec: LaunchSpec): List<String> =
    spec.scriptJvmArgs() + listOf("-jar", spec.jarFileName) + spec.serverArgs

/** java 줄 다음 줄: 종료 코드를 잡아 둔다 (pause 가 ERRORLEVEL 을 지운다). */
private const val BAT_RC_LINE: String = "set \"DECACROSS_RC=%ERRORLEVEL%\""

/** 마지막 줄: 스크립트 호출자에게 java 의 종료 코드를 그대로 준다 (critique A7). */
private const val BAT_EXIT_LINE: String = "exit /b %DECACROSS_RC%"

/**
 * start.sh 렌더링: `#!/bin/sh`, `cd "$(dirname "$0")" || exit 1`, `exec '<java>' … -jar '<jar>' nogui`. LF, 작은따옴표 이스케이프.
 *
 * @throws IllegalArgumentException [renderStartBat] 과 같은 조건 (Java 경로 밖 토큰이 비 ASCII 이거나 `eula` 를 담을 때).
 */
fun renderStartSh(spec: LaunchSpec): ByteArray {
    val arguments = scriptArguments(spec)
    checkScriptTokens(arguments)
    val execLine = (listOf("exec", shellQuotePosix(spec.javaPath)) + arguments.map { shellQuotePosix(it) }).joinToString(" ")
    // exec 로 바꿔치기해야 SIGTERM 이 셸이 아니라 Java 에 간다
    val lines = listOf("#!/bin/sh", "cd \"\$(dirname \"\$0\")\" || exit 1", execLine)
    return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
}

/**
 * server.properties: 런처 소유 키 4개만 (`motd`, `max-players`, `difficulty`, `online-mode`) 이 순서로.
 * ASCII 전용 — `java.util.Properties` 저장 규칙대로 0x20 미만·0x7E 초과 문자는 `\uXXXX`, `\ = : # !` 와 앞 공백은 이스케이프.
 * 머리 주석 `#Minecraft server properties` 한 줄 (타임스탬프 없음 → 결정적). LF.
 */
fun renderServerProperties(settings: ServerSettings, serverName: String): ByteArray {
    val text = buildString {
        append("#Minecraft server properties\n")
        append("motd=").append(escapePropertiesValue(settings.motd ?: serverName)).append('\n')
        append("max-players=").append(settings.maxPlayers).append('\n')
        append("difficulty=").append(settings.difficulty.propertyValue).append('\n')
        append("online-mode=").append(settings.onlineMode).append('\n')
    }
    return text.toByteArray(Charsets.US_ASCII)
}

/**
 * eula.txt (동의한 경우에만 만든다):
 * ```
 * #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).
 * #<acceptedAt ISO-8601>
 * #EULA accepted via DecaCross (<INTERACTIVE_PROMPT|CLI_FLAG|UI_DIALOG>)
 * eula=true
 * ```
 * ASCII, LF. ★ 부르는 곳은 파이프라인 EULA 단계, ServerCatalog.kt 의 [writeEulaAccepted], CLI `start` 의 동의 경로, 테스트뿐이다
 * (merge_wps2.py 가 다른 호출을 거부한다, critique B2).
 */
fun renderEulaTxt(channel: ConsentChannel, acceptedAt: Instant): ByteArray {
    val text = buildString {
        append("#By changing the setting below to TRUE you are indicating your agreement to our EULA (")
        append(MINECRAFT_EULA_URL)
        append(").\n")
        append('#').append(acceptedAt.toString()).append('\n')
        append("#EULA accepted via DecaCross (").append(channel.name).append(")\n")
        append("eula=true\n")
    }
    return text.toByteArray(Charsets.US_ASCII)
}
