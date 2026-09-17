package kr.decacross.analysis

import java.nio.file.Path

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
internal fun bytecodeProfile(jar: Path): BytecodeProfile = TODO("WP-JA")

/**
 * 명세 §6.3 시그니처. javaMajor = max(class major) − 44 (52→8, 60→16, 61→17, 65→21, 69→25).
 * 제외 규칙은 [BytecodeProfile] 불변식을 따른다.
 *
 * @throws java.io.IOException jar 를 zip 으로 열 수 없을 때. (예외 없는 경계는 [analyzeJar])
 */
public fun requiredJavaFeature(jar: Path): Int? = bytecodeProfile(jar).requiredJavaFeature
