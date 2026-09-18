package kr.decacross.pubgrub

// 출처: pubgrub-rs (MPL-2.0) src/internal/arena.rs 의 알고리즘 번역 (commit 0399630, 2026-09-17 확인). 라이선스 정책은 Q3 (사용자 결정 대기).

import kotlin.jvm.JvmInline

/** 패키지 인턴 번호. 발견 순서(0 = 루트)이며 우선순위 동점 처리의 기준이다. */
@JvmInline
internal value class PackageId(val raw: Int)

/** 비호환 아레나 번호. 단조 증가 → 번호 순서가 위상 순서다 (P1-d 트리 구성이 의존). */
@JvmInline
internal value class IncompId(val raw: Int)

/** 포팅 버그 신호. 데이터로는 도달할 수 없는 분기에서만 쓴다 (throw 지점은 여기 하나). */
internal fun invariantViolated(message: String): Nothing =
    throw IllegalStateException("pubgrub-kt 내부 불변식 위반 (포팅 버그): $message")

/** P ↔ [PackageId] 인턴 테이블 (rs `HashArena<P>`). */
internal class PackageStore<P : Any> {
    private val ids = HashMap<P, PackageId>()
    private val values = ArrayList<P>()

    val size: Int get() = values.size

    fun intern(p: P): PackageId {
        val known = ids[p]
        if (known != null) return known
        val id = PackageId(values.size)
        values.add(p)
        ids[p] = id
        return id
    }

    operator fun get(id: PackageId): P = values[id.raw]
}
