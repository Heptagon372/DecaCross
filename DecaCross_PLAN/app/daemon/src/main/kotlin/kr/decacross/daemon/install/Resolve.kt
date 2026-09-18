package kr.decacross.daemon.install

import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey

/** 03 설치기가 다루는 코어: Paperclip 방식 단일 jar(`-jar <core>.jar nogui`)인 Paper 계열. */
val INSTALLABLE_CORES: Set<CoreKey> = setOf(CoreKey.PAPER, CoreKey.PURPUR, CoreKey.FOLIA)

/** RESOLVE 결과. */
sealed interface TargetResolution {
    data class Resolved(val target: InstallTarget) : TargetResolution

    data class Failed(val failure: InstallFailure) : TargetResolution
}

/**
 * 07 의 `compat.resolve()` 전까지의 RESOLVE (SCP-I9). 명세 §3 의 `Plan`/`ResolveOutcome` 이름을 재사용하지 않는다.
 *
 * 1. `db.mcByLabel(mcLabel)` 없으면 [InstallFailure.UnknownMc] (`mcInFamily` 로 같은 계열 라벨 제시)
 * 2. [core] 가 [INSTALLABLE_CORES] 밖이면 [InstallFailure.UnsupportedCore]
 * 3. `db.coreBuilds(core, ordinal, stableOnly = !allowExperimental).firstOrNull()` (최신 빌드 먼저 정렬된 계약)
 *    없으면: 실험 빌드가 있으면 [InstallFailure.NoStableBuild], 아니면 [InstallFailure.NoBuildCollected]
 *
 * 07 이 오면: `resolve(ResolveRequest(...), db)` 의 `Plan(mc, core, java, ...)` 를 [InstallTarget] 으로 바꾸기만 한다.
 * PLAN 이후는 그대로다.
 */
fun resolveInstallTarget(db: CompatDb, mcLabel: String, core: CoreKey, allowExperimental: Boolean): TargetResolution {
    val mc = db.mcByLabel(mcLabel)
        ?: return TargetResolution.Failed(InstallFailure.UnknownMc(mcLabel, sameFamilyLabels(db, mcLabel)))
    if (core !in INSTALLABLE_CORES) return TargetResolution.Failed(InstallFailure.UnsupportedCore(core))
    val build = db.coreBuilds(core, mc.ordinal, stableOnly = !allowExperimental).firstOrNull()
    if (build == null) {
        val any = db.coreBuilds(core, mc.ordinal, stableOnly = false).firstOrNull()
        return if (any != null && any.channel == Channel.EXPERIMENTAL) {
            TargetResolution.Failed(InstallFailure.NoStableBuild(core, mcLabel, any.build))
        } else {
            TargetResolution.Failed(InstallFailure.NoBuildCollected(core, mcLabel))
        }
    }
    return TargetResolution.Resolved(InstallTarget(mc, build))
}

/**
 * 같은 계열의 수집된 라벨 (안내용). 계열이 비고 라벨에 점이 둘 이상이면 마지막 구간을 뗀 접두사로 한 번 더 본다
 * (`1.21.99` → `1.21`).
 */
private fun sameFamilyLabels(db: CompatDb, mcLabel: String): List<String> {
    val direct = db.mcInFamily(mcLabel)
    if (direct.isNotEmpty()) return direct.map { it.label }
    if (!mcLabel.contains('.')) return emptyList()
    val prefix = mcLabel.substringBeforeLast('.')
    if (prefix.isBlank() || prefix == mcLabel) return emptyList()
    return db.mcInFamily(prefix).map { it.label }
}
