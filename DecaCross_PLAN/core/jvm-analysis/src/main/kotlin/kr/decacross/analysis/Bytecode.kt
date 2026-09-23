package kr.decacross.analysis

import java.io.DataInputStream
import java.nio.file.Path
import java.util.jar.JarFile

// ── 클래스 파일 버전 실측 ───────────────────────────────────────────────────
// 메타데이터(plugin.yml 의 api-version 등)는 거짓말을 하지만 class major 는 못 한다.

/** class major → Java feature. 45=1.1 … 52=8, 60=16, 61=17, 65=21, 69=25. */
public fun javaFeatureOfClassMajor(major: Int): Int = major - 44

/**
 * 기본 항목(`META-INF/versions/` 밖)의 최고 class major 와 버전 디렉터리별 최고 major.
 *
 * # 불변식
 * - [baseMax] 는 top-level `.class` 만 본다. `module-info.class` 와 `META-INF/` 아래는 제외.
 * - [versionedMax] 의 키는 `META-INF/versions/N/` 의 N. Multi-Release 매니페스트 유무와 무관하게 채운다
 *   (실제 런타임은 매니페스트가 없으면 무시하지만, "이 jar 가 무엇을 담고 있나"를 보는 게 목적이다).
 */
public data class ClassVersionProfile(
    val baseMax: Int?,
    val versionedMax: Map<Int, Int>,
    val classCount: Int,
) {
    /** 기본 항목 기준 요구 Java feature. 클래스가 없으면 null. */
    val requiredJavaFeature: Int? get() = baseMax?.let(::javaFeatureOfClassMajor)
}

/**
 * 모든 top-level .class 의 major version 최댓값 → javaMajor = max - 44.
 * (52→8, 60→16, 61→17, 65→21, 69→25). 메타데이터보다 이 실측값을 우선 신뢰한다.
 *
 * 멀티 릴리스 디렉터리는 제외한다 — 거기 있는 클래스는 그 버전 이상에서만 로드되므로 최소 요구 버전이 아니다.
 * .class 가 하나도 없거나 jar 를 열 수 없으면 null.
 */
public fun requiredJavaFeature(jar: Path): Int? = classVersionProfile(jar)?.requiredJavaFeature

/** [requiredJavaFeature] 의 상세판. jar 를 열 수 없으면 null. */
public fun classVersionProfile(jar: Path): ClassVersionProfile? {
    val opened = runCatching { JarFile(jar.toFile()) }.getOrNull() ?: return null
    return opened.use { jf ->
        var baseMax: Int? = null
        val versioned = HashMap<Int, Int>()
        var count = 0
        val entries = jf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory || !e.name.endsWith(".class")) continue
            if (e.name.substringAfterLast('/') == "module-info.class") continue
            val versionDir = when {
                e.name.startsWith("META-INF/versions/") ->
                    e.name.removePrefix("META-INF/versions/").substringBefore('/').toIntOrNull() ?: continue

                e.name.startsWith("META-INF/") -> continue

                else -> null
            }
            val major = readClassMajor(jf, e) ?: continue
            count++
            if (versionDir == null) {
                baseMax = maxOf(baseMax ?: 0, major)
            } else {
                versioned[versionDir] = maxOf(versioned[versionDir] ?: 0, major)
            }
        }
        ClassVersionProfile(baseMax, versioned, count)
    }
}

/** 헤더 8바이트만 읽는다: magic(4) minor(2) major(2). magic 이 틀리면 null. */
private fun readClassMajor(jf: JarFile, e: java.util.jar.JarEntry): Int? = runCatching {
    DataInputStream(jf.getInputStream(e)).use { input ->
        if (input.readInt() != 0xCAFEBABE.toInt()) return@use null
        input.readUnsignedShort() // minor
        input.readUnsignedShort()
    }
}.getOrNull()
