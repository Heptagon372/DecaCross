package kr.decacross.analysis

import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

// ── shaded 라이브러리 충돌 (K-2) ───────────────────────────────────────────
// 두 플러그인이 같은 라이브러리를 relocate 없이 shade 하면 먼저 로드된 쪽의 버전이 이긴다.
// 버전이 다르면 늦게 로드된 플러그인이 NoSuchMethodError 로 터진다. 클래스 경로 집합 비교만으로 미리 찾는다.

public enum class Severity { LOW, MEDIUM, HIGH }

/**
 * jar 여러 개에 같은 내부 이름의 클래스가 있는 경우 하나.
 *
 * # 불변식
 * - [providers] 는 2개 이상이며 입력 jar 순서를 따른다.
 * - [severity] 가 LOW 면 모든 제공자의 바이트가 동일하다 (어느 쪽이 이겨도 같은 코드).
 */
public data class ShadeConflict(
    /** "com/google/gson/Gson" */
    val classPath: String,
    /** ["EssentialsX-2.21.0.jar", "SomePlugin-1.0.jar"] */
    val providers: List<String>,
    /** relocate 안 된 공용 라이브러리면 HIGH */
    val severity: Severity,
    /** 모든 제공자에서 바이트가 같은가 (sha-256) */
    val identicalBytes: Boolean = false,
    /** 잘 알려진 라이브러리 접두사에 해당하면 그 접두사 */
    val knownLibrary: String? = null,
)

/**
 * relocate 없이 shade 될 때 충돌이 잦은 공용 라이브러리 접두사. 데이터가 아니라 "어떤 라이브러리가 널리 쓰이나"라는
 * 생태계 상식이라 코드에 둔다. 새 항목은 README 의 표와 함께 늘린다.
 */
public val WELL_KNOWN_LIBRARY_PREFIXES: List<String> = listOf(
    "com/google/gson/",
    "com/google/common/",
    "io/netty/",
    "kotlin/",
    "org/slf4j/",
    "com/fasterxml/jackson/",
    "org/yaml/snakeyaml/",
    "org/apache/commons/",
    "okhttp3/",
    "okio/",
    "com/zaxxer/hikari/",
    "com/mysql/",
    "org/mariadb/",
    "net/kyori/adventure/",
    "org/bstats/",
    "redis/clients/",
    "org/apache/http/",
    "com/squareup/",
)

/** 이 경로 조각이 들어 있으면 relocate 된 것으로 본다 (shadow 플러그인 관례). */
private val RELOCATION_MARKERS = listOf("/libs/", "/lib/", "/shaded/", "/relocated/", "/shadow/")

/**
 * jar 들의 클래스 경로 집합을 비교해 중복을 찾는다. relocate 된 것(플러그인 자체 패키지 하위)은 제외.
 *
 * 제외 규칙:
 * - `META-INF/` 아래, `module-info` / `package-info`
 * - 그 jar 의 메인 클래스 패키지(`plugin.yml` 의 `main`) 아래 — 플러그인 자기 코드 또는 그 아래로 relocate 한 것
 * - 경로에 `/libs/`, `/lib/`, `/shaded/`, `/relocated/` 가 있는 것
 *
 * 심각도: [WELL_KNOWN_LIBRARY_PREFIXES] 에 해당하면 HIGH, 아니면 MEDIUM, 모든 제공자에서 바이트가 같으면 LOW.
 * 결과는 심각도 내림차순 → 클래스 경로 순. 열 수 없는 jar 는 조용히 건너뛴다 (충돌은 "있다"만 말할 수 있다).
 */
public fun detectShadeConflicts(jars: List<Path>): List<ShadeConflict> {
    if (jars.size < 2) return emptyList()
    // 1차: 각 jar 의 후보 클래스 경로 집합
    val perJar = jars.map { jar -> jar to candidateClasses(jar) }
    val owners = HashMap<String, MutableList<Int>>()
    perJar.forEachIndexed { i, (_, classes) -> classes.forEach { owners.getOrPut(it) { ArrayList(2) } += i } }
    val duplicated = owners.filterValues { it.size >= 2 }
    if (duplicated.isEmpty()) return emptyList()

    // 2차: 중복된 항목만 해시 (jar 당 한 번 더 열되, 필요한 항목만 읽는다)
    val hashes = HashMap<String, Array<String?>>()
    perJar.forEachIndexed { i, (jar, _) ->
        val wanted = duplicated.filterValues { i in it }.keys
        if (wanted.isEmpty()) return@forEachIndexed
        runCatching {
            JarFile(jar.toFile()).use { jf ->
                for (cls in wanted) {
                    val e = jf.getJarEntry("$cls.class") ?: continue
                    val digest = jf.getInputStream(e).use { sha256(it.readBytes()) }
                    hashes.getOrPut(cls) { arrayOfNulls(jars.size) }[i] = digest
                }
            }
        }
    }

    return duplicated.map { (cls, idx) ->
        val providers = idx.map { jars[it].fileName?.toString() ?: jars[it].toString() }
        val digests = hashes[cls]?.let { arr -> idx.map { arr[it] } } ?: emptyList()
        val identical = digests.isNotEmpty() && digests.all { it != null } && digests.distinct().size == 1
        val known = WELL_KNOWN_LIBRARY_PREFIXES.firstOrNull { cls.startsWith(it) }
        val severity = when {
            identical -> Severity.LOW
            known != null -> Severity.HIGH
            else -> Severity.MEDIUM
        }
        ShadeConflict(cls, providers, severity, identical, known?.trimEnd('/'))
    }.sortedWith(compareByDescending<ShadeConflict> { it.severity }.thenBy { it.classPath })
}

/** jar 의 클래스 경로 중 충돌 후보만 (relocate·자기 패키지 제외). 열 수 없으면 빈 집합. */
private fun candidateClasses(jar: Path): Set<String> {
    val ownPrefix = readJarMeta(jar)?.main?.replace('.', '/')?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }?.plus("/")
    val out = HashSet<String>()
    runCatching {
        JarFile(jar.toFile()).use { jf ->
            val entries = jf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val name = e.name
                if (e.isDirectory || !name.endsWith(".class") || name.startsWith("META-INF/")) continue
                val simple = name.substringAfterLast('/')
                if (simple == "module-info.class" || simple == "package-info.class") continue
                if (ownPrefix != null && name.startsWith(ownPrefix)) continue
                if (RELOCATION_MARKERS.any { it in name }) continue
                out += name.removeSuffix(".class")
            }
        }
    }
    return out
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
