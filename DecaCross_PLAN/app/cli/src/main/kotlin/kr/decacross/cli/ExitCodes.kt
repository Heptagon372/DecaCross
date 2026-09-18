package kr.decacross.cli

/**
 * CLI 종료 코드 (DESIGN2 D-I36). 데모 스크립트와 (08) 브리지가 이 값으로 성공·실패를 가른다.
 *
 * # 불변식
 * - 값은 계약이다. 새 실패 종류가 생기면 여기 표를 먼저 늘린다.
 */
object ExitCodes {
    /** 정상 종료. */
    const val OK: Int = 0

    /** 예상 못 한 오류 (리소스 손상 등 — 사용자가 고칠 수 없는 것). */
    const val UNEXPECTED: Int = 1

    /** 입력 오류·RESOLVE·PLAN 실패 (Java 못 찾음 포함). */
    const val INPUT: Int = 2

    /** EULA 에 동의하지 않음. */
    const val EULA_DECLINED: Int = 3

    /** 다운로드·검증·로컬 파일 작업·커밋 실패. */
    const val TRANSFER: Int = 4

    /** 서버 없음·이미 실행 중·기동 실패·`stop` 대상이 실행 중이 아님. */
    const val SERVER_STATE: Int = 5

    /** 서버가 0 아닌 코드로 끝났거나 강제 종료됨, `stop` 이 제한 시간 안에 끝나지 않음. */
    const val SERVER_FAILED: Int = 6

    /** 사용자가 취소 (Ctrl+C). */
    const val CANCELLED: Int = 130
}
