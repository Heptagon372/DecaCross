package kr.decacross.daemon.install

/** Mojang 이 eula.txt 에 적는 공식 링크 (AE-15). */
const val MINECRAFT_EULA_URL: String = "https://aka.ms/MinecraftEULA"

/**
 * EULA 안내 문구 (한국어 + 영어). CLI 프롬프트와 (05) UI 다이얼로그가 같은 문구를 쓴다.
 * 비제휴 고지는 Minecraft 이용 지침(공식처럼 보이면 안 됨)에 따른 것이다 (research paper-runtime §9).
 */
val EULA_NOTICE_LINES: List<String> = listOf(
    "서버를 실행하려면 Minecraft 최종 사용자 사용권 계약(EULA)에 동의해야 합니다.",
    "To run a Minecraft server you must agree to the Minecraft End User License Agreement (EULA).",
    "  $MINECRAFT_EULA_URL",
    "DecaCross 는 Mojang Studios 또는 Microsoft 와 제휴하거나 승인받지 않았습니다.",
    "DecaCross is not affiliated with or endorsed by Mojang Studios or Microsoft.",
)

/** 프롬프트 질문 줄. 기본값은 "아니오" 다 (`[y/N]`). */
const val EULA_QUESTION: String = "EULA 에 동의합니까? / Do you agree to the EULA? [y/N]"

/**
 * 사용자가 입력한 답이 **명시적 동의**인가. `y`, `yes`, `예` (앞뒤 공백·대소문자 무시)만 true.
 * null(EOF)·빈 줄·그 밖의 모든 입력은 false.
 */
fun isExplicitEulaAgreement(input: String?): Boolean {
    val answer = input?.trim()?.lowercase() ?: return false
    return answer == "y" || answer == "yes" || answer == "예"
}
