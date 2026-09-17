package kr.decacross.collector.core

import kr.decacross.analysis.CapabilityRules
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.analysis.analyzeJar
import java.nio.file.Path

/** jar 분석기 추상. 콘텐츠 파이프라인 테스트가 가짜 분석기를 넣을 수 있게 한다. */
fun interface JarAnalyzer {
    fun analyze(jar: Path): JarAnalysisResult
}

/** 운영 구현: core/jvm-analysis 의 [analyzeJar]. */
class JvmJarAnalyzer(private val rules: CapabilityRules) : JarAnalyzer {
    override fun analyze(jar: Path): JarAnalysisResult = analyzeJar(jar, rules)
}
