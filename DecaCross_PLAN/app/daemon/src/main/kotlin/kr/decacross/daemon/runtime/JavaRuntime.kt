package kr.decacross.daemon.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** `java -version` 을 실제로 실행해 얻은 정보. 메타데이터를 믿지 않는다. */
data class JavaInfo(val exe: Path, val feature: Int, val versionString: String)

/**
 * 03 단계: 시스템에 있는 Java 를 찾아 쓴다. 04 단계에서 Adoptium 자동 설치(`ensureRuntime`)가 붙는다.
 *
 * # 불변식
 * - JAVA_HOME / PATH / 레지스트리를 수정하지 않는다. 절대경로만 돌려준다 (불변식 10).
 * - 런처 자신의 JVM 은 "후보" 일 뿐이다. 번들(jlink) 런타임은 절대 후보가 아니다 (불변식 9).
 */
object JavaRuntime {
    /** `java -version` 출력에서 feature 버전 추출. "25.0.2" → 25, "1.8.0_402" → 8. */
    fun parseFeature(versionOutput: String): Int? {
        val m = Regex("version \"([^\"]+)\"").find(versionOutput) ?: return null
        val v = m.groupValues[1]
        val parts = v.split('.', '_', '-', '+')
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    /** 실제로 실행해서 검증한다. 실행이 안 되면 null. */
    fun probe(exe: Path): JavaInfo? =
        runCatching {
            val p = ProcessBuilder(exe.toAbsolutePath().toString(), "-version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            val feature = parseFeature(out) ?: return null
            JavaInfo(exe.toAbsolutePath(), feature, out.lineSequence().firstOrNull().orEmpty())
        }.getOrNull()

    /** 후보: DECACROSS_JAVA → 현재 JVM(java.home) → JAVA_HOME → PATH 의 java. 실행돼야만 채택. */
    fun findSystemJava(env: Map<String, String> = System.getenv(), bundledJre: Path? = null): JavaInfo? {
        val exeName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val candidates = listOfNotNull(
            env["DECACROSS_JAVA"]?.let(Paths::get),
            System.getProperty("java.home")?.let { Paths.get(it, "bin", exeName) },
            env["JAVA_HOME"]?.let { Paths.get(it, "bin", exeName) },
            env["PATH"]?.split(java.io.File.pathSeparator)?.map { Paths.get(it, exeName) }?.firstOrNull { Files.isRegularFile(it) },
        )
        return candidates
            .filter { Files.isRegularFile(it) }
            .filterNot { bundledJre != null && it.toAbsolutePath().startsWith(bundledJre.toAbsolutePath()) }
            .firstNotNullOfOrNull(::probe)
    }
}
