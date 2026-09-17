package kr.decacross.analysis

import java.nio.file.Path

public enum class Severity { LOW, MEDIUM, HIGH }

public data class ShadeConflict(
    /** "com/google/gson/Gson" */
    val classPath: String,
    /** ["EssentialsX-2.21.0.jar", "SomePlugin-1.0.jar"] */
    val providers: List<String>,
    /** relocate 안 된 공용 라이브러리면 HIGH */
    val severity: Severity,
)

/** jar 들의 클래스 경로 집합을 비교해 중복을 찾는다. relocate 된 것(플러그인 자체 패키지 하위)은 제외. */
public fun detectShadeConflicts(jars: List<Path>): List<ShadeConflict> = TODO("P2-a")
