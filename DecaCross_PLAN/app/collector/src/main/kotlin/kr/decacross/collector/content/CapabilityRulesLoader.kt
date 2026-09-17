package kr.decacross.collector.content

import kr.decacross.analysis.CapabilityRules

/**
 * 클래스패스 리소스 [RESOURCE] → [CapabilityRules].
 * 정체성 규칙(플러그인 이름·패키지)은 수집 데이터라 코드에 하드코딩하지 않는다 (CLAUDE.md 코딩 규칙).
 */
object CapabilityRulesLoader {
    const val RESOURCE: String = "/capability-identities.json"

    /** 리소스가 없거나 깨졌으면 예외 — 수집기 기동 시 즉시 드러나야 한다. */
    fun load(): CapabilityRules = TODO("WP-C3")
}
