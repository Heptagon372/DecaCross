package kr.decacross.compat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kr.decacross.compat.serial.InstantIsoSerializer
import kr.decacross.compat.serial.PackFormatSerializer
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * MC 버전 비교의 유일한 기준.
 *
 * # 불변식
 * - label 이나 SemVer 로 MC 버전을 비교하는 코드는 전부 버그다.
 *   2026년 Mojang 이 "1." 접두사를 폐지해 "1.21.8" 과 "26.3" 의 문자열 비교가 뒤집힌다.
 * - 한 번 발급된 값은 영원히 재할당되지 않는다. 신규는 max+10 (append-only).
 */
@JvmInline
@Serializable
public value class McOrdinal(public val value: Int) : Comparable<McOrdinal> {
    override fun compareTo(other: McOrdinal): Int = value.compareTo(other.value)

    override fun toString(): String = "McOrdinal($value)"
}

@Serializable
public data class McVersion(
    val ordinal: McOrdinal,
    /** 표시용 라벨. "1.8.9" | "1.21.8" | "26.3". 비교에 쓰지 마라. */
    val label: String,
    @Serializable(with = InstantIsoSerializer::class)
    val releasedAt: Instant,
    val isSnapshot: Boolean = false,
    /** 8 | 16 | 17 | 21 | 25 */
    val javaMin: Int,
    val javaRecommended: Int,
    /** 리소스팩 포맷 */
    val rpFormat: PackFormat?,
    /** 데이터팩 포맷 ← 별개! 합치지 마라 (1.21: rp=34, dp=48) */
    val dpFormat: PackFormat?,
    /** "v1_21_R1" */
    val nms: String? = null,
    val protocol: Int? = null,
)

/**
 * pack.mcmeta 의 pack_format. 1.21.9 부터 "88.0" 형태. Int 로 다루면 비교가 깨진다.
 *
 * # 불변식
 * - DB 컬럼은 numeric, 코드는 (major, minor). 정수 컬럼·정수 비교 금지.
 * - 리소스팩 포맷과 데이터팩 포맷은 같은 타입이지만 절대 같은 필드에 담지 않는다.
 * - 직렬화 형태는 항상 문자열("34", "88.0", "101.1"). JSON 숫자로 내보내면 "101.10" 같은 값이 깨진다.
 */
@Serializable(with = PackFormatSerializer::class)
public data class PackFormat(val major: Int, val minor: Int = 0) : Comparable<PackFormat> {
    init {
        require(major >= 0 && minor >= 0) { "pack_format 은 음수가 될 수 없다: $major.$minor" }
    }

    override fun compareTo(other: PackFormat): Int = compareValuesBy(this, other, PackFormat::major, PackFormat::minor)

    override fun toString(): String = if (minor == 0) "$major" else "$major.$minor"

    public companion object {
        /**
         * "34" → (34,0), "88.0" → (88,0), "101.1" → (101,1).
         * 빈 문자열·음수·비숫자·3단("1.2.3")·빈 세그먼트("34.")는 null.
         */
        public fun parse(s: String): PackFormat? {
            val parts = s.trim().split('.')
            if (parts.isEmpty() || parts.size > 2) return null
            if (parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
            return PackFormat(major, minor)
        }
    }
}

/**
 * 팩이 선언할 수 있는 지원 포맷. 단일값·구간·목록 셋 다 "선언 구간 ∩ 목표 포맷 ≠ ∅" 로 판정한다.
 *
 * 마이너 규칙 (기획서 §5.5 ③): 마이너 버전은 비파괴적 변경이다. 팩이 `88.0` 을 선언하면
 * 게임 포맷 `88.1` 에서도 동작한다 — 즉 단일 선언은 `[major.minor, major.∞)` 구간이다.
 */
@Serializable
public sealed interface PackDecl {
    /** `pack_format: 34` */
    @Serializable
    @SerialName("single")
    public data class Single(val format: PackFormat) : PackDecl {
        override fun isCompatibleWith(target: PackFormat): Boolean =
            format.major == target.major && target.minor >= format.minor
    }

    /** `min_format` / `max_format` (양 끝 포함) */
    @Serializable
    @SerialName("range")
    public data class Range(val min: PackFormat, val max: PackFormat) : PackDecl {
        override fun isCompatibleWith(target: PackFormat): Boolean = target >= min && target <= max
    }

    /** `supported_formats: [..]` */
    @Serializable
    @SerialName("supported")
    public data class Supported(val formats: List<PackFormat>) : PackDecl {
        override fun isCompatibleWith(target: PackFormat): Boolean = formats.any { Single(it).isCompatibleWith(target) }
    }

    /** 선언 구간 ∩ 목표 포맷 ≠ ∅ 인가. */
    public fun isCompatibleWith(target: PackFormat): Boolean
}
