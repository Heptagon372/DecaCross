package kr.decacross.daemon.install.assemble

import kr.decacross.daemon.install.InstallFailure

/**
 * 파이프라인 한 단계의 결과 (WP-INSTALL 내부 전용). 예외 대신 이 값으로 단계 실패를 나른다
 * ([kr.decacross.daemon.install.InstallPipeline] 이 `Failed` → `ROLLBACK` 으로 바꾼다).
 */
internal sealed interface StageResult<out T> {
    /** 성공. */
    data class Ok<T>(val value: T) : StageResult<T>

    /** 실패. 이 단계에서 파이프라인이 멈춘다. */
    data class Fail(val failure: InstallFailure) : StageResult<Nothing>
}
