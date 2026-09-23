package kr.decacross.collector

/**
 * 수집기 진입점. `--once` / `--loop`. 소스(Mojang/Paper/Purpur/Adoptium/Modrinth/Hangar)는 02 단계.
 *
 * # 불변식 (CLAUDE.md 17)
 * 모든 외부 호출은 식별 가능한 User-Agent + 연락처. generic UA 금지 (PaperMC 정책).
 */
fun main(args: Array<String>) {
    TODO("02: collector ${args.joinToString(" ")}")
}
