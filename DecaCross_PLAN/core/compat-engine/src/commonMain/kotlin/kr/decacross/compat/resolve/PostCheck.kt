package kr.decacross.compat.resolve

import kr.decacross.compat.Warning
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McVersion

/**
 * ④ POST-CHECK — PubGrub 으로 표현 안 되는 규칙 (설계서 §3.5).
 * - R8 pack_format 구간 교집합 (리소스팩은 rpFormat, 데이터팩은 dpFormat — 절대 섞지 않는다)
 * - Folia + 미대응 플러그인 성능/호환 경고
 * - 미수집 데이터 경고 (Java 실측값 없음 등)
 *
 * 결과는 "차단"이 아니라 경고다. 차단이 필요한 규칙은 의존성으로 표현해 솔버에 넣는다.
 */
public object PostCheck {
    public fun run(mc: McVersion, core: CoreKey, items: List<ContentVersion>, kinds: Map<String, ContentKind>): List<Warning> {
        val out = ArrayList<Warning>()
        for (cv in items) {
            val kind = kinds[cv.slug]
            val decl = cv.pack
            if (decl != null) {
                val target = when (kind) {
                    ContentKind.RESOURCE_PACK, ContentKind.MODEL_PACK -> mc.rpFormat
                    ContentKind.DATA_PACK -> mc.dpFormat
                    else -> null
                }
                when {
                    kind == null || (kind != ContentKind.RESOURCE_PACK && kind != ContentKind.DATA_PACK && kind != ContentKind.MODEL_PACK) -> Unit
                    target == null -> out += Warning("${mc.label} 의 ${if (kind == ContentKind.DATA_PACK) "데이터팩" else "리소스팩"} 포맷이 아직 수집되지 않아 ${cv.slug} 의 pack_format 을 검증하지 못했습니다", cv.slug)
                    !decl.isCompatibleWith(target) -> out += Warning("${cv.slug} 의 pack_format($decl)이 ${mc.label} 의 포맷($target)과 맞지 않습니다 — 클라이언트에서 경고/미적용될 수 있습니다", cv.slug)
                }
            }
            if (cv.javaMajor == null && kind == ContentKind.PLUGIN) {
                out += Warning("${cv.slug} ${cv.version} 은 바이트코드 실측(Java 요구 버전)이 없어 메타데이터만으로 판정했습니다", cv.slug)
            }
            if (core == CoreKey.FOLIA && kind == ContentKind.PLUGIN) {
                out += Warning("${cv.slug} 는 Folia 지원 여부가 확인되지 않았습니다 — Folia 는 플러그인이 명시적으로 대응해야 합니다", cv.slug)
            }
        }
        return out
    }
}
