package kr.decacross.daemon.install

import java.nio.file.Path

/**
 * 설치 상태머신 단계 (설계서 §4.1).
 *
 * ```
 * IDLE → RESOLVE → PLAN → FETCH → VERIFY → LAYOUT → CONFIG → EULA → READY
 *   어느 단계든 실패·취소 → FAILED → ROLLBACK (스테이징 삭제, 사용자 폴더 무변화)
 * ```
 */
enum class InstallStage { IDLE, RESOLVE, PLAN, FETCH, VERIFY, LAYOUT, CONFIG, EULA, READY, FAILED, ROLLBACK }

/**
 * 파이프라인이 내보내는 사건. (05) 에서 그대로 WS 로 나간다 (50ms 배칭은 전송 쪽 몫).
 *
 * # 불변식 (하나의 `run` 흐름)
 * - [StageEntered] 는 단계 순서대로 나오고 같은 단계가 두 번 나오지 않는다.
 * - 종료는 정확히 둘 중 하나: [Ready], 또는 [Failed] → `StageEntered(ROLLBACK)` → [RolledBack]. 그 뒤 흐름이 끝난다.
 * - 수집이 취소되면 롤백은 하되 사건은 더 내보내지 않는다 (취소는 호출자가 이미 안다).
 *   그래서 [InstallFailure.Cancelled] 는 이 흐름에 나오지 않는다 — CLI 가 취소를 표시할 때 스스로 만든다.
 */
sealed interface InstallEvent {
    /** 단계 진입. */
    data class StageEntered(val stage: InstallStage) : InstallEvent

    /** RESOLVE 결과 (MC 버전·코어 빌드). */
    data class Resolved(val target: InstallTarget) : InstallEvent

    /** PLAN 결과. 이 사건 뒤에 `confirmPlan` 을 부른다. */
    data class Planned(val plan: InstallPlan) : InstallEvent

    /** 다운로드 진행률 (초당 최대 10회). */
    data class Progress(val progress: FetchProgress) : InstallEvent

    /** 재시도·미러 전환·이어받기 거부 같은 개별 사건. */
    data class FetchNotice(val event: FetchItemEvent) : InstallEvent

    /** 항목 하나가 크기·sha256·zip 검사를 통과했다. */
    data class Verified(val itemId: String, val sha256: String, val size: Long) : InstallEvent

    /** 설치는 계속되지만 사용자가 알아야 하는 것 (Java 권장 버전 초과, 메모리 부족 우려 등). */
    data class Warning(val messageKo: String) : InstallEvent

    /** 설치 완료: 서버 폴더가 커밋됐다. */
    data class Ready(val server: InstalledServer) : InstallEvent

    /** [stage] 에서 실패. 바로 뒤에 `StageEntered(ROLLBACK)` 과 [RolledBack] 이 온다. */
    data class Failed(val stage: InstallStage, val failure: InstallFailure) : InstallEvent

    /**
     * 롤백 완료. [cleanedUp] 이 false 면 [leftovers] 를 지우지 못했다 (다음 `create` 의 스윕이 치운다).
     * 어느 경우든 **이 설치**는 최종 서버 폴더를 만들지 않았다.
     *
     * # 불변식
     * - [sweptPaths] 는 LAYOUT 스윕이 치운 **이전** 설치의 잔해다. 비어 있지 않으면 이번 실행이 사용자 폴더를
     *   건드린 것이므로, 표시하는 쪽은 "사용자 폴더는 바뀌지 않았습니다" 라고 말하면 안 된다.
     */
    data class RolledBack(
        val failedStage: InstallStage,
        val cleanedUp: Boolean,
        val leftovers: List<Path>,
        val sweptPaths: List<Path> = emptyList(),
    ) : InstallEvent
}

/** READY 결과: 원자적 이동이 끝난 서버. */
data class InstalledServer(val name: String, val dir: Path, val launch: LaunchSpec, val manifest: InstallManifest)
