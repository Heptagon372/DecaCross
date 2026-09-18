package kr.decacross.daemon.install

import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.runtime.JavaCandidate
import java.nio.file.Path

/**
 * 설치 실패 사유. 사용자 문구와 해결책은 [describeKo] (불변식 7 정신: 해결책 없는 에러는 벽이다).
 * 코드 흐름은 예외가 아니라 이 값으로 표현한다.
 */
sealed interface InstallFailure {
    // ── RESOLVE ──
    /** DB 에 없는 MC 라벨. [sameFamily] 는 같은 계열의 수집된 라벨 (안내용). */
    data class UnknownMc(val label: String, val sameFamily: List<String>) : InstallFailure

    /** 03 이 설치할 수 없는 코어 (PAPER·PURPUR·FOLIA 외, SCP-I14). */
    data class UnsupportedCore(val core: CoreKey) : InstallFailure

    /** STABLE 빌드가 없고 실험 빌드만 있다. */
    data class NoStableBuild(val core: CoreKey, val mcLabel: String, val experimentalBuild: String) : InstallFailure

    /** 그 MC 버전의 코어 빌드가 수집돼 있지 않다. */
    data class NoBuildCollected(val core: CoreKey, val mcLabel: String) : InstallFailure

    /** DB 값이 계약(sha256 64 hex, size > 0, https URL)을 어김. */
    data class InvalidCatalogData(val reason: String) : InstallFailure

    // ── PLAN ──
    /** 서버 이름 규칙 위반 (D-I30). */
    data class InvalidServerName(val name: String, val reasonKo: String) : InstallFailure

    /** 같은 이름(대소문자 무시)의 서버·파일이 이미 있다. */
    data class ServerExists(val existing: Path) : InstallFailure

    /** 같은 이름으로 다른 설치가 진행 중. */
    data class NameBusy(val name: String) : InstallFailure

    /** 서버 루트를 쓸 수 없다 (`!`/`+` 포함, 경로 길이 등). */
    data class ServersRootUnusable(val path: Path, val reasonKo: String) : InstallFailure

    /** RAM 값이 하한 미만이거나 물리 메모리 초과. */
    data class InvalidRam(val ramMb: Int, val reasonKo: String) : InstallFailure

    /** 요구를 만족하는 Java 없음. [candidates] 는 조사한 모든 후보와 문제. */
    data class JavaNotFound(val requiredMin: Int, val recommended: Int, val candidates: List<JavaCandidate>) : InstallFailure

    /** 힙·Java 조합에 맞는 플래그 프로파일 없음. */
    data class NoFlagProfile(val reasonKo: String) : InstallFailure

    /** 캐시 또는 서버 루트 볼륨 공간 부족. */
    data class InsufficientDisk(val path: Path, val neededBytes: Long, val usableBytes: Long) : InstallFailure

    /** 사용자가 계획을 거절했다 (`confirmPlan` = false). */
    data object PlanRejected : InstallFailure

    // ── FETCH / VERIFY ──
    /** 다운로드 실패 (전송·로컬·크기 불일치). */
    data class DownloadFailed(val itemId: String, val error: FetchError) : InstallFailure

    /** ★ 해시·크기·zip 불일치. 재시도하지 않는다. */
    data class IntegrityFailed(val itemId: String, val outcome: VerifyOutcome) : InstallFailure

    // ── LAYOUT / CONFIG / READY ──
    /** 로컬 파일 작업 실패. */
    data class LocalIo(val path: Path?, val detail: String) : InstallFailure

    // ── EULA ──
    /** EULA 에 명시적으로 동의하지 않았다 → 롤백. */
    data class EulaDeclined(val reasonKo: String) : InstallFailure

    // ── READY ──
    /** 스테이징 → 최종 폴더 커밋 실패. */
    data class CommitFailed(val detail: String) : InstallFailure

    /**
     * 호출자가 흐름 수집을 취소 (Ctrl+C 등). ★ 파이프라인은 이 값을 내보내지 않는다 (취소 = 사건 없음).
     * CLI 가 취소를 사용자에게 표시하고 종료 코드 130 으로 바꿀 때 스스로 만든다.
     */
    data object Cancelled : InstallFailure
}

/** 사용자에게 보여줄 설명. [fixesKo] 는 항상 1개 이상. */
data class FailureDescription(val messageKo: String, val fixesKo: List<String>)
