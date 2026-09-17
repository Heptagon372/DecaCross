package kr.decacross.analysis

import java.nio.file.Path

public data class StaticVerifyResult(
    val ok: Boolean,
    val missingClasses: List<MissingRef>,
    val missingMethods: List<MissingRef>,
    val warnings: List<String>,
)

public data class MissingRef(val from: String, val target: String, val detail: String)

/**
 * 플러그인 jar 의 모든 외부 참조를 코어 API jar 기준으로 해석한다.
 * 서버를 띄우지 않고 NoClassDefFoundError / NoSuchMethodError 를 예측한다.
 *
 * 조합 1건당 목표: 500ms 이내 (실제 기동은 40초)
 *
 * # 불변식 (CLAUDE.md 16)
 * 미해결 참조를 전부 에러로 처리하지 마라. try/catch·softdepend·리플렉션 문자열은 경고다.
 */
public fun staticVerify(
    pluginJar: Path,
    /** paper-api, bukkit, 그리고 함께 설치될 다른 플러그인들 */
    coreApiJars: List<Path>,
    javaFeature: Int,
): StaticVerifyResult = TODO("P2-a")
