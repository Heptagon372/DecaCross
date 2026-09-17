package kr.decacross.analysis

import kotlin.test.Test
import kotlin.test.assertTrue

/** WP0 스모크: JVM 모듈에서 JUnit Platform 이 뜨는지 확인한다. 분석 로직 테스트는 WP-JA 소유 파일에 둔다. */
class AnalyzerVersionSmokeTest {
    @Test
    fun analyzerVersion_isSemverLike() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(ANALYZER_VERSION))
    }
}
