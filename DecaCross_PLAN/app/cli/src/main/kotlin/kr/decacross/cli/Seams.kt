package kr.decacross.cli

import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.ConsolePatterns
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.PathsResolution
import kr.decacross.daemon.process.CompiledConsolePatterns
import java.nio.file.Path
import java.util.regex.PatternSyntaxException

/**
 * 경로 결정 경계. 실제 구현은 [DecaPaths.resolve] — 테스트는 임시 디렉터리를 돌려주는 람다를 넣는다
 * (§4.2 규칙 3: 테스트는 실제 사용자 폴더를 보지 않는다).
 */
fun interface PathsResolver {
    fun resolve(env: Map<String, String>, os: Os, serversDirOption: Path?): PathsResolution

    companion object {
        /** 운영 구현: 사용자 홈 + 환경변수 + 문서 폴더 정책. */
        val SYSTEM: PathsResolver = PathsResolver { env, os, serversDirOption ->
            DecaPaths.resolve(env, os, Path.of(System.getProperty("user.home") ?: "."), serversDirOption)
        }
    }
}

/**
 * JVM 셧다운 훅 등록 경계 (테스트가 훅 스레드를 직접 돌려 보려고 잡는다).
 *
 * # 불변식
 * - [remove] 는 JVM 이 이미 종료 중이면 조용히 넘어간다 (`IllegalStateException`).
 */
interface ShutdownHookRegistrar {
    fun add(hook: Thread)

    fun remove(hook: Thread)

    companion object {
        val SYSTEM: ShutdownHookRegistrar = object : ShutdownHookRegistrar {
            override fun add(hook: Thread) {
                Runtime.getRuntime().addShutdownHook(hook)
            }

            override fun remove(hook: Thread) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (e: IllegalStateException) {
                    // 이미 종료가 시작됐다 — 훅을 뗄 수 없고, 뗄 필요도 없다
                }
            }
        }
    }
}

/** `decacross.launcher` 시스템 속성 값: Gradle `run` 으로 띄운 CLI (SCP-I17 안내를 띄운다). */
const val GRADLE_RUN_LAUNCHER: String = "gradle-run"

/**
 * 표준입력이 **사람이 앉아 있는 터미널**인가 (EULA 프롬프트 경계, [CliInteraction]).
 *
 * # 불변식
 * - ★ JDK 22+ 의 `System.console()` 은 파이프·리다이렉트에도 `Console` 을 돌려준다 → 반드시
 *   [java.io.Console.isTerminal] 까지 봐야 한다. 이걸 빼면 `(sleep 60; echo y) | decacross create …` 가
 *   "사람이 y 를 쳤다"(`INTERACTIVE_PROMPT`)로 기록된다.
 */
internal val SYSTEM_INTERACTIVE_STDIN: () -> Boolean = { System.console()?.isTerminal() == true }

/**
 * 콘솔 인식 정규식 컴파일. 리소스가 망가져 정규식이 깨졌으면 null — 사용자에게 스택 트레이스 대신
 * `[실패]` 한 줄을 보여 주고 [ExitCodes.UNEXPECTED] 로 끝내려는 것이다 (CLI 는 예외를 밖으로 내보내지 않는다).
 */
internal fun compileConsolePatterns(patterns: ConsolePatterns): CompiledConsolePatterns? =
    try {
        CompiledConsolePatterns.from(patterns)
    } catch (e: PatternSyntaxException) {
        null
    }
