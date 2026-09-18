package kr.decacross.analysis

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
import java.util.TreeMap
import java.util.TreeSet
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * NMS(마인크래프트 서버 내부) 결합 신호. 02 에서는 저장·로그용이며 MC 서수 범위를 좁히는 데 쓰지 않는다.
 */
public data class NmsSignal(
    /** 상수 풀에 `net/minecraft/` 또는 `org/bukkit/craftbukkit/` 클래스 참조가 있는가. */
    val binaryRefs: Boolean,
    /** `v1_20_R3` 같은 버전 패키지 토큰 (바이너리 참조 또는 문자열 상수에서). */
    val versionedTokens: Set<String>,
    /** `net.minecraft.` / `org.bukkit.craftbukkit.` 형태의 문자열 상수만 있는가 (리플렉션). */
    val reflectiveStrings: Boolean,
    /** MANIFEST `paperweight-mappings-namespace` (mojang | spigot | null). */
    val mappingsNamespace: String?,
)

/**
 * jar 의 클래스 파일 헤더 프로파일.
 *
 * # 불변식
 * - [requiredJavaFeature] 계산에서 `META-INF/` 아래 전부(멀티 릴리스 `versions/N` 포함), `module-info.class`,
 *   `org/bukkit/`·`net/minecraft/`·`io/papermc/paper/`·`com/destroystokoyo/paper/` 아래 클래스
 *   (플러그인 클래스로더가 정의하지 않는 네임스페이스 — Paper `NamespaceChecker`)는 제외한다.
 *   이 제외 규칙이 모듈 안에서 "Java 요구 버전"의 **유일한 정의**다.
 * - ASM 을 쓰지 않고 8바이트 헤더를 직접 읽는다 → ASM 이 아직 모르는 최신 major 도 처리한다.
 */
public data class BytecodeProfile(
    /** max(major) − 44 (외부 + 1단계 중첩 jar). 대상 클래스가 없으면 null. */
    val requiredJavaFeature: Int?,
    val outerMaxMajor: Int?,
    /** `.jar` / `.jarinjar` 중첩 아카이브(1단계)의 최대 major. */
    val nestedMaxMajor: Int?,
    /** major → 클래스 수 (제외 규칙 적용 후). */
    val majorHistogram: Map<Int, Int>,
    val multiRelease: Boolean,
    /** `META-INF/versions/N` 의 N 집합. */
    val versionedFeatures: Set<Int>,
    /** minor 0xFFFF(프리뷰) 이고 major ≥ 56(JVMS §4.1) 인 클래스의 최대 major. `--enable-preview` 없이는 로드 불가 — 경고 대상. */
    val previewMajor: Int?,
    /** 매직 불일치·잘림 등으로 무시한 .class 항목 수. */
    val invalidClassEntries: Int,
    val nms: NmsSignal,
    val notes: List<String>,
)

/**
 * jar 의 바이트코드 프로파일을 계산한다.
 * 모듈 내부 전용 — 예외 없는 공개 경계는 [analyzeJar] 다 (CLAUDE.md: 라이브러리 모듈 throw 최소화).
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때.
 */
internal fun bytecodeProfile(jar: Path): BytecodeProfile = withZipFile(jar) { zip -> bytecodeProfile(zip) }

/**
 * 명세 §6.3 시그니처. javaMajor = max(class major) − 44 (52→8, 60→16, 61→17, 65→21, 69→25).
 * 제외 규칙은 [BytecodeProfile] 불변식을 따른다.
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때. (예외 없는 경계는 [analyzeJar])
 */
public fun requiredJavaFeature(jar: Path): Int? = bytecodeProfile(jar).requiredJavaFeature

// ── 구현 (설계 §7.4) ──────────────────────────────────────────────────────
// 규칙 상수는 JVMS §4.1 과 플러그인 클래스로더 규칙(Paper NamespaceChecker)의 계약이다 (설계 AE-3, AE-7).

/** 플러그인 클래스로더가 정의하지 않는 네임스페이스 (Paper `NamespaceChecker`). */
private val LOADER_RESERVED_PREFIXES: List<String> =
    listOf("org/bukkit/", "net/minecraft/", "io/papermc/paper/", "com/destroystokoyo/paper/")

private const val VERSIONS_PREFIX: String = "META-INF/versions/"

/** 유효한 class major 범위 (45 = JDK 1.1). */
private val VALID_MAJORS: IntRange = 45..255

/** 이 major(Java 12) 부터 minor 는 0 또는 0xFFFF(프리뷰)만 허용된다 (JVMS §4.1). */
private const val STRICT_MINOR_SINCE_MAJOR: Int = 56

private const val PREVIEW_MINOR: Int = 0xFFFF

/** feature = major − 44 (52→8, 61→17, 65→21). */
private const val MAJOR_TO_FEATURE_OFFSET: Int = 44

/** 비정상 헤더 note 의 최대 개수. */
private const val HEADER_NOTES_MAX: Int = 10

private val NMS_VERSION_TOKEN = Regex("(net[/.]minecraft[/.]server|org[/.]bukkit[/.]craftbukkit)[/.](v\\d+_\\d+_R\\d+)")

private const val LOADER_NAMESPACE_NOTE: String = "로더가 정의하지 않는 네임스페이스 클래스 무시"

private fun isLoaderReservedClass(entryName: String): Boolean = LOADER_RESERVED_PREFIXES.any { entryName.startsWith(it) }

/**
 * "Java 요구 버전" 계산 대상 class 항목인가 — 모듈 안의 **유일한 정의** ([BytecodeProfile] 불변식).
 * `META-INF/` 아래 전부, `module-info.class`, 로더가 정의하지 않는 네임스페이스는 제외한다.
 * [PluginScan.maxBaseMajor] 도 이 술어를 쓴다.
 */
internal fun isJavaRequirementClass(entryName: String): Boolean =
    !entryName.startsWith("META-INF/") &&
        entryName.substringAfterLast('/') != "module-info.class" &&
        !isLoaderReservedClass(entryName)

/** class 헤더를 누적해 [BytecodeProfile] 을 만든다. */
private class BytecodeAccumulator(private val notes: MutableList<String>) {
    private var outerMax: Int? = null
    private var nestedMax: Int? = null
    private var previewMajor: Int? = null
    private var invalid: Int = 0
    private val histogram = TreeMap<Int, Int>()
    private var headerNotes: Int = 0
    private var binaryRefs: Boolean = false
    private var reflectiveStrings: Boolean = false
    private val versionedTokens = TreeSet<String>()

    fun accept(nested: Boolean, location: String, bytes: ByteArray) {
        if (bytes.size < 8 || readU4(bytes, 0) != CLASS_MAGIC) {
            invalid++
            return
        }
        val minor = readU2(bytes, 4)
        val major = readU2(bytes, 6)
        if (major !in VALID_MAJORS) {
            invalid++
            headerNote("비정상 major $major ($location)")
            return
        }
        if (major >= STRICT_MINOR_SINCE_MAJOR && minor != 0 && minor != PREVIEW_MINOR) {
            invalid++
            headerNote("JVMS 위반 minor $minor (major $major, $location)")
            return
        }
        histogram.merge(major, 1, Int::plus)
        if (nested) {
            nestedMax = maxOf(nestedMax ?: major, major)
        } else {
            outerMax = maxOf(outerMax ?: major, major)
        }
        // major < 56 에서 0xFFFF 는 평범한 minor 다 (JVMS §4.1)
        if (minor == PREVIEW_MINOR && major >= STRICT_MINOR_SINCE_MAJOR) previewMajor = maxOf(previewMajor ?: major, major)
        // 헤더 파싱 실패는 NMS 스캔만 건너뛴다 (Java 요구 버전은 이미 셌다)
        readClassHeader(bytes)?.let { scanNms(it.utf8) }
    }

    private fun scanNms(utf8: List<String>) {
        for (value in utf8) {
            if (!binaryRefs && isBinaryNmsRef(value)) binaryRefs = true
            if (!reflectiveStrings && (value.startsWith("net.minecraft.") || value.startsWith("org.bukkit.craftbukkit."))) {
                reflectiveStrings = true
            }
            if (value.contains("minecraft") || value.contains("craftbukkit")) {
                for (match in NMS_VERSION_TOKEN.findAll(value)) versionedTokens += match.groupValues[2]
            }
        }
    }

    private fun isBinaryNmsRef(value: String): Boolean =
        value.startsWith("net/minecraft/") ||
            value.startsWith("org/bukkit/craftbukkit/") ||
            value.contains("Lnet/minecraft/") ||
            value.contains("Lorg/bukkit/craftbukkit/")

    private fun headerNote(note: String) {
        if (headerNotes < HEADER_NOTES_MAX) notes += note
        headerNotes++
    }

    fun build(multiRelease: Boolean, versionedFeatures: Set<Int>, mappingsNamespace: String?): BytecodeProfile {
        val outer = outerMax
        val nestedMajor = nestedMax
        val maxMajor = listOfNotNull(outer, nestedMajor).maxOrNull()
        if (nestedMajor != null && (outer == null || nestedMajor > outer)) notes += "중첩 jar 가 더 높은 Java 요구"
        if (previewMajor != null) notes += "프리뷰 클래스 포함 — --enable-preview 없이는 로드 불가"
        return BytecodeProfile(
            requiredJavaFeature = maxMajor?.minus(MAJOR_TO_FEATURE_OFFSET),
            outerMaxMajor = outer,
            nestedMaxMajor = nestedMajor,
            majorHistogram = histogram.toMap(),
            multiRelease = multiRelease,
            versionedFeatures = versionedFeatures,
            previewMajor = previewMajor,
            invalidClassEntries = invalid,
            nms = NmsSignal(
                binaryRefs = binaryRefs,
                versionedTokens = versionedTokens.toSet(),
                reflectiveStrings = reflectiveStrings,
                mappingsNamespace = mappingsNamespace,
            ),
            notes = notes.toList(),
        )
    }
}

private fun readU2(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun readU4(bytes: ByteArray, offset: Int): Int = (readU2(bytes, offset) shl 16) or readU2(bytes, offset + 2)

/** MANIFEST 에서 읽는 두 값. */
private data class ManifestFlags(val multiRelease: Boolean, val mappingsNamespace: String?)

private fun readManifestFlags(zip: ZipFile, notes: MutableList<String>): ManifestFlags {
    val bytes = when (val read = zip.readRootEntry("META-INF/MANIFEST.MF", DESCRIPTOR_MAX_BYTES)) {
        RootEntryRead.Absent -> return ManifestFlags(false, null)

        RootEntryRead.TooLarge -> {
            notes += "MANIFEST.MF 크기 상한 초과 — 무시"
            return ManifestFlags(false, null)
        }

        is RootEntryRead.Bytes -> read.bytes
    }
    return try {
        val attributes = Manifest(ByteArrayInputStream(bytes)).mainAttributes
        ManifestFlags(
            multiRelease = attributes.getValue("Multi-Release")?.trim().equals("true", ignoreCase = true),
            mappingsNamespace = attributes.getValue("paperweight-mappings-namespace")?.trim(),
        )
    } catch (e: IOException) {
        // 손상된 zip 데이터가 아니라 MANIFEST 문법 문제다 — jar 전체를 Unreadable 로 만들지 않는다
        notes += "MANIFEST.MF 해석 실패: ${e.message}"
        ManifestFlags(false, null)
    } catch (e: IllegalArgumentException) {
        notes += "MANIFEST.MF 해석 실패: ${e.message}"
        ManifestFlags(false, null)
    }
}

/**
 * 이미 열린 zip 의 바이트코드 프로파일.
 *
 * @throws IOException 항목 데이터가 손상됐을 때.
 */
internal fun bytecodeProfile(zip: ZipFile): BytecodeProfile {
    val notes = ArrayList<String>()
    val manifest = readManifestFlags(zip, notes)
    val versionedFeatures = TreeSet<Int>()
    var loaderNamespaceNoted = false
    val accumulator = BytecodeAccumulator(notes)

    fun keep(entryName: String): Boolean {
        if (isJavaRequirementClass(entryName)) return true
        if (!loaderNamespaceNoted && isLoaderReservedClass(entryName)) {
            notes += LOADER_NAMESPACE_NOTE
            loaderNamespaceNoted = true
        }
        return false
    }

    ZipWalker(zip, notes).forEachClass(
        selectOuter = { name ->
            if (name.startsWith(VERSIONS_PREFIX)) {
                name.removePrefix(VERSIONS_PREFIX).substringBefore('/').toIntOrNull()?.let { versionedFeatures += it }
                false
            } else {
                keep(name)
            }
        },
        selectNested = { name -> keep(name) },
        onClass = { archive, name, bytes ->
            val location = if (archive == null) name else "$archive!/$name"
            accumulator.accept(nested = archive != null, location = location, bytes = bytes)
        },
    )
    return accumulator.build(manifest.multiRelease, versionedFeatures.toSet(), manifest.mappingsNamespace)
}
