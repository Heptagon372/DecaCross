package kr.decacross.daemon.install

import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McVersion
import kr.decacross.daemon.runtime.JavaSelection
import java.nio.file.Path

/**
 * 설치 요청. CLI `create`, (05) UI, (08) 브리지가 만든다.
 *
 * # 불변식
 * - [ramMb] 는 CLI 가 `4G`/`4096M` 을 MB 로 바꾼 값. 하한 검사는 PLAN 이 한다 ([MIN_RAM_MB]).
 * - [serverName] 검사는 PLAN 이 한다 ([validateServerName]). 여기서는 원문 그대로 보관한다.
 *   CLI 는 `--name` 이 없으면 [defaultServerName] 을 넣는다 (SCP-I16).
 */
data class InstallRequest(
    val mcLabel: String,
    val core: CoreKey,
    val serverName: String,
    val ramMb: Int,
    val allowExperimental: Boolean = false,
    val settings: ServerSettings = ServerSettings(),
    /** `--java`. null 이면 [kr.decacross.daemon.runtime.JavaLocator] 가 찾는다. */
    val javaOverride: Path? = null,
    /** false 면 `-XX:+AlwaysPreTouch` 를 뺀다 (`--no-pretouch`, SCP-I13). */
    val preTouch: Boolean = true,
)

/** 서버 최소 메모리 (SCP-I13). Aikar 플래그 자체는 256MB 부터 JVM 이 기동하지만 서버가 실용적이지 않다. */
const val MIN_RAM_MB: Int = 1024

/** server.properties 의 `difficulty` 값. */
enum class Difficulty(val propertyValue: String) {
    PEACEFUL("peaceful"),
    EASY("easy"),
    NORMAL("normal"),
    HARD("hard"),
}

/**
 * 런처가 소유하는 server.properties 키 (프롬프트 03 §5). 나머지 키는 서버가 첫 기동 때 기본값으로 채운다.
 *
 * @property motd null 이면 서버 이름을 쓴다.
 */
data class ServerSettings(
    val motd: String? = null,
    val maxPlayers: Int = 20,
    val difficulty: Difficulty = Difficulty.EASY,
    val onlineMode: Boolean = true,
)

/** RESOLVE 결과 (07 의 `resolve()` 전까지의 임시 판정, SCP-I9). */
data class InstallTarget(val mc: McVersion, val build: CoreBuild)

/** PLAN 결과. 사용자에게 보여줄 요약과 이후 단계가 쓰는 확정값. */
data class InstallPlan(
    val installId: String,
    val target: InstallTarget,
    val serverName: String,
    /** 최종 서버 폴더 (아직 없다). */
    val serverDir: Path,
    val items: List<FetchItem>,
    /** 전체 크기 (캐시 적중 포함). */
    val totalBytes: Long,
    val java: JavaSelection,
    val launch: LaunchSpec,
    val warningsKo: List<String>,
)

/** EULA 동의가 **전달된** 경로. 기록용 (eula.txt 주석). 누가 입력했는지를 주장하지 않는다. */
enum class ConsentChannel {
    /** 대화형 프롬프트에 `y`/`yes`/`예` 입력 */
    INTERACTIVE_PROMPT,

    /**
     * 명령줄 `--accept-eula`. 사용자 본인이 입력했거나, 사용자가 (채팅 등에서) 이 실행에 대해 명시적으로 동의·지시한 실행에만 붙는다.
     * 에이전트·스크립트가 스스로 판단해 붙이는 것은 금지 (DESIGN2 §4.2 규칙 8, §5.3).
     */
    CLI_FLAG,

    /** (05) UI 다이얼로그 버튼 */
    UI_DIALOG,
}

/** EULA 질의 결과. */
sealed interface EulaAnswer {
    /** 명시적 동의. [channel] 은 eula.txt 주석에 남는다. */
    data class Accepted(val channel: ConsentChannel) : EulaAnswer

    /** 동의 아님 (EOF·빈 줄·그 밖의 답 포함). [reasonKo] 는 사용자 문구. */
    data class Declined(val reasonKo: String) : EulaAnswer
}

/** EULA 질의에 필요한 정보. 문구는 [EULA_NOTICE_LINES]. */
data class EulaNotice(val url: String, val serverName: String, val mcLabel: String)

/**
 * 파이프라인이 사용자에게 묻는 두 지점. CLI·UI 가 구현한다.
 *
 * # 불변식
 * - ★ [requestEulaConsent] 구현은 **사용자의 명시적 동의** 없이 [EulaAnswer.Accepted] 를 돌려주면 안 된다.
 *   입력 없음(EOF)·비대화형·알 수 없는 답은 전부 [EulaAnswer.Declined].
 * - ★ 파이프라인은 [confirmPlan] 이 true 를 돌려주기 전에는 파일 시스템을 바꾸지 않는다 (명세 §7.2: 승인 전 파일 쓰기 금지).
 */
interface InstallInteraction {
    /**
     * PLAN 요약 확인. false 면 [InstallFailure.PlanRejected] → ROLLBACK(지울 것이 없음). CLI 는 명령줄 자체가 확인이라 true (SCP-I4).
     * (05) UI·(08) 브리지는 네이티브 다이얼로그로 묻는다.
     */
    suspend fun confirmPlan(plan: InstallPlan): Boolean = true

    /** EULA 단계에서 한 번 부른다. 기본 구현 없음 — 구현마다 동의 규칙을 직접 지킨다. */
    suspend fun requestEulaConsent(notice: EulaNotice): EulaAnswer
}
