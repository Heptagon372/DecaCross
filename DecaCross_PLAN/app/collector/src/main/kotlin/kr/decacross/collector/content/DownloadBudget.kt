package kr.decacross.collector.content

/**
 * 한 소스 실행(사이클)의 jar 다운로드 예산.
 *
 * # 불변식
 * - 크기를 모르는 jar 는 받지 않는다 ([Admit.TOO_LARGE]) — 예산을 지킬 수 없다.
 * - [Admit.OK] 일 때만 [bytesLeft] 가 줄어든다.
 */
internal class DownloadBudget(var bytesLeft: Long, val maxJarBytes: Long) {
    enum class Admit { OK, TOO_LARGE, EXHAUSTED }

    fun admit(size: Long?): Admit = when {
        size == null || size > maxJarBytes -> Admit.TOO_LARGE

        size > bytesLeft -> Admit.EXHAUSTED

        else -> {
            bytesLeft -= size
            Admit.OK
        }
    }
}
