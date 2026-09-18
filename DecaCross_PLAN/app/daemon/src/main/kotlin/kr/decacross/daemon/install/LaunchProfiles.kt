package kr.decacross.daemon.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `launch-profiles.json` (데몬 리소스) 모델. JVM 플래그 프로파일과 서버 콘솔 패턴은 코드 상수가 아니라 출처가 붙은 리소스다
 * (AE-13/AE-14: 외부 문서의 권장값·로그 문구를 코드에 박지 않는다).
 */
@Serializable
data class LaunchProfiles(val schema: Int, val jvmFlagProfiles: List<JvmFlagProfile>, val console: ConsolePatterns)

/**
 * 플래그 프로파일 하나. 힙 [minHeapMb] 이상 [maxHeapMbExclusive] 미만, Java feature [minJavaFeature] 이상
 * [maxJavaFeatureExclusive] 미만일 때 적용한다.
 *
 * # 불변식 (리소스 테스트로 강제)
 * - 같은 (힙, feature) 에 맞는 프로파일은 최대 하나.
 * - `-XX:+UnlockExperimentalVMOptions` 는 실험 플래그보다 앞에 온다.
 */
@Serializable
data class JvmFlagProfile(
    val id: String,
    val descriptionKo: String,
    val minHeapMb: Int,
    val maxHeapMbExclusive: Int? = null,
    val minJavaFeature: Int,
    val maxJavaFeatureExclusive: Int? = null,
    val flags: List<String>,
    val sources: List<String>,
)

/**
 * 콘솔 줄 판정 정규식 (ANSI 이스케이프를 지운 뒤 `containsMatchIn`). 종료 프로토콜·준비 판정용만 둔다 —
 * 에러 진단 시그니처(예: `eula_not_agreed`)는 명세 §10 의 DB 데이터이지 여기 두지 않는다 (critique m4).
 */
@Serializable
data class ConsolePatterns(
    /** 기동 완료. `Done preparing level …` 은 맞지 않아야 한다. */
    val ready: String,
    /** `save-all` 완료. */
    val saved: String,
    val sources: List<String>,
)

/** 리소스 로드 결과. */
sealed interface LaunchProfilesLoad {
    /** 읽음. */
    data class Loaded(val profiles: LaunchProfiles) : LaunchProfilesLoad

    /** 없음·파싱 실패. */
    data class Invalid(val reason: String) : LaunchProfilesLoad
}

/** 리소스 경로. */
const val LAUNCH_PROFILES_RESOURCE: String = "/launch-profiles.json"

/** `-XX:+AlwaysPreTouch`. `preTouch = false` 면 선택된 플래그에서 뺀다. */
const val PRETOUCH_FLAG: String = "-XX:+AlwaysPreTouch"

private val profilesJson = Json { ignoreUnknownKeys = false }

/** 데몬 리소스에서 읽는다. 던지지 않는다. */
fun loadLaunchProfiles(): LaunchProfilesLoad {
    val text = LaunchProfiles::class.java.getResourceAsStream(LAUNCH_PROFILES_RESOURCE)
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        ?: return LaunchProfilesLoad.Invalid("리소스 $LAUNCH_PROFILES_RESOURCE 없음")
    return try {
        LaunchProfilesLoad.Loaded(profilesJson.decodeFromString(LaunchProfiles.serializer(), text))
    } catch (e: SerializationException) {
        LaunchProfilesLoad.Invalid("리소스 $LAUNCH_PROFILES_RESOURCE 파싱 실패: ${e.message}")
    } catch (e: IllegalArgumentException) {
        LaunchProfilesLoad.Invalid("리소스 $LAUNCH_PROFILES_RESOURCE 파싱 실패: ${e.message}")
    }
}

/** [selectFlagProfile] 결과. */
sealed interface FlagSelection {
    /** [flags] = 프로파일 플래그 (preTouch=false 면 [PRETOUCH_FLAG] 제외), 순서 유지. */
    data class Selected(val profile: JvmFlagProfile, val flags: List<String>) : FlagSelection

    data class NoProfile(val reasonKo: String) : FlagSelection
}

/** 힙·Java feature 로 프로파일 하나를 고른다 (12G 미만/이상 분기는 리소스의 범위로 표현). */
fun selectFlagProfile(profiles: LaunchProfiles, heapMb: Int, javaFeature: Int, preTouch: Boolean): FlagSelection {
    val matches = profiles.jvmFlagProfiles.filter { profile ->
        heapMb >= profile.minHeapMb &&
            (profile.maxHeapMbExclusive == null || heapMb < profile.maxHeapMbExclusive) &&
            javaFeature >= profile.minJavaFeature &&
            (profile.maxJavaFeatureExclusive == null || javaFeature < profile.maxJavaFeatureExclusive)
    }
    return when (matches.size) {
        0 -> FlagSelection.NoProfile("힙 ${heapMb}MB / Java $javaFeature 에 맞는 프로파일 없음")

        1 -> {
            val profile = matches[0]
            FlagSelection.Selected(profile, if (preTouch) profile.flags else profile.flags - PRETOUCH_FLAG)
        }

        else -> FlagSelection.NoProfile("리소스 오류: 겹치는 프로파일 ${matches.joinToString(", ") { it.id }}")
    }
}
