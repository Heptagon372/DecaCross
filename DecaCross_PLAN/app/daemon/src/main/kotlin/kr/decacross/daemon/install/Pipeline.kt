package kr.decacross.daemon.install

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kr.decacross.compat.db.CompatDb
import kr.decacross.compat.model.CoreBuild
import kr.decacross.daemon.install.assemble.LOCK_SUFFIX
import kr.decacross.daemon.install.assemble.NAME_LOCK_PREFIX
import kr.decacross.daemon.install.assemble.StageResult
import kr.decacross.daemon.install.assemble.sha256HexOrNull
import kr.decacross.daemon.install.assemble.withLockFile
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.paths.hostEnvironment
import kr.decacross.daemon.runtime.JavaLocateResult
import kr.decacross.daemon.runtime.JavaLocator
import kr.decacross.daemon.runtime.JavaRequirement
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.time.Clock

/**
 * 설치 상태머신 (설계서 §4.1, DESIGN2 §2.4). 라이브러리다: CLI 가 부르고, (05) 데몬 API 가 WS 로 잇는다.
 *
 * ```
 * RESOLVE  resolveInstallTarget                                  → Resolved
 * PLAN     (읽기 전용) 이름·서버 루트·RAM·Java·플래그·디스크 검사, 남은 조각은 경고만, FetchItem 확정 → Planned, confirmPlan
 * FETCH    fetcher.fetchAll (cache/partial, 스테이징 밖)          → Progress / FetchNotice
 * VERIFY   verifier.verify (불일치 → discard + 즉시 중단)          → Verified
 * LAYOUT   sweepStaging → servers/.staging/{installId}/ 생성·잠금, jar 복사
 * CONFIG   server.properties, start.bat, start.sh, .decacross/launch.json
 * EULA     interaction.requestEulaConsent → 동의 시에만 eula.txt(eula=true) → .decacross/manifest.json (전 파일 sha256)
 * READY    StagingCommitter.commit (ATOMIC_MOVE, 실패 시 복사 폴백) → 캐시 조각 discard(최선) → Ready
 * 실패     Failed → ROLLBACK(스테이징·잠금 삭제, 새로 만든 빈 루트 삭제) → RolledBack
 * ```
 *
 * # 불변식
 * - ★ `confirmPlan` 이 true 를 돌려주기 전에는 파일 시스템을 바꾸지 않는다 (RESOLVE·PLAN 은 읽기와 `java -version` 실행만).
 * - 최종 서버 폴더는 READY 의 커밋 한 번으로만 나타난다. 그 전의 어떤 실패도 사용자 폴더를 바꾸지 않는다
 *   (`.staging` 과 이 설치가 만든 빈 서버 루트는 롤백이 지운다).
 * - EULA 동의 없이 `eula=true` 를 쓰지 않는다. 동의 거부는 롤백이다 (설계서 §4.1 EULA → FAILED).
 * - 다운로드는 스테이징 밖에 남는다 → 실패·거부·취소 뒤 재실행은 받지 않고 재검증만 한다.
 *   성공(READY) 뒤에는 캐시 조각을 지운다 (CAS 는 04 — critique m12, SCP-F2).
 * - 취소(CancellationException)도 롤백한다 (`NonCancellable`), 사건은 더 내보내지 않는다. 롤백은 설치당 한 번만 돈다.
 * - [env] 는 Windows 에서 이름 대소문자를 무시하는 맵이어야 한다 ([hostEnvironment]).
 */
class InstallPipeline(
    private val db: CompatDb,
    private val paths: DecaPaths,
    private val fetcher: ArtifactFetcher,
    private val verifier: ArtifactVerifier,
    private val javaLocator: JavaLocator,
    private val interaction: InstallInteraction,
    private val profiles: LaunchProfiles,
    private val env: Map<String, String> = hostEnvironment(),
    private val memory: MemoryProbe = MemoryProbe.SYSTEM,
    private val disk: DiskSpaceProbe = DiskSpaceProbe.SYSTEM,
    private val committer: StagingCommitter = StagingCommitter(),
    private val clock: Clock = Clock.System,
    private val newInstallId: () -> String = { UUID.randomUUID().toString() },
) {
    /** 테스트 전용 틈: 단계에 들어간 직후 실패를 주입한다 (null 이면 아무 일도 없다). */
    internal var faultBeforeStage: ((InstallStage) -> InstallFailure?)? = null

    /** 테스트 전용 틈: 롤백이 실제로 몇 번 도는지 센다 (critique A6). */
    internal var onRollback: (() -> Unit)? = null

    /**
     * 테스트 전용 틈: 스테이징을 여는 함수. 스테이징을 **연 직후** 취소가 들어오는 순간을 재현하려면
     * 여는 시점을 가로채야 한다 (그 창은 밖에서는 ms 단위라 맞출 수 없다).
     */
    internal var openStagingFn: (Path, String, String) -> StagingOpenResult =
        { root, id, name -> openStaging(root, id, name) }

    /** 차가운(cold) 흐름. 수집할 때마다 새 설치를 한 번 돈다. */
    fun run(request: InstallRequest): Flow<InstallEvent> = channelFlow {
        val installId = newInstallId()
        var stage = InstallStage.IDLE
        var area: StagingArea? = null
        var rolledBack = false // 롤백은 설치당 한 번 (critique A6)
        val createdDirs = ArrayList<Path>() // 이 설치가 만든 디렉터리 (롤백 때 비어 있으면 삭제)
        val swept = ArrayList<Path>() // LAYOUT 스윕이 치운 이전 설치의 잔해 (롤백 안내가 이것을 밝힌다)

        suspend fun rollbackOnce(): RollbackReport? {
            if (rolledBack) return null
            rolledBack = true
            val current = area
            area = null
            onRollback?.invoke()
            // NonCancellable 은 Job 만 바꾼다 — 디스패처를 함께 주지 않으면 롤백의 블로킹 삭제·잠금이 수집자 스레드에서 돈다
            return withContext(NonCancellable + Dispatchers.IO) {
                rollbackStaging(current, paths.stagingRoot, createdDirs.reversed())
            }
        }

        suspend fun enter(next: InstallStage): InstallFailure? {
            stage = next
            send(InstallEvent.StageEntered(next))
            return faultBeforeStage?.invoke(next)
        }

        suspend fun failAndRollback(failure: InstallFailure) {
            val failedStage = stage
            send(InstallEvent.Failed(failedStage, failure))
            send(InstallEvent.StageEntered(InstallStage.ROLLBACK))
            // 여기서 취소돼도 아래 catch 는 다시 롤백하지 않는다 (rolledBack 이 이미 true)
            val report = rollbackOnce() ?: return
            send(InstallEvent.RolledBack(failedStage, report.cleanedUp, report.leftovers, swept.toList()))
        }

        try {
            // ── RESOLVE ──────────────────────────────────────────────
            enter(InstallStage.RESOLVE)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val resolution = resolveInstallTarget(db, request.mcLabel, request.core, request.allowExperimental)
            val target = when (resolution) {
                is TargetResolution.Resolved -> resolution.target

                is TargetResolution.Failed -> {
                    failAndRollback(resolution.failure)
                    return@channelFlow
                }
            }
            send(InstallEvent.Resolved(target))

            // ── PLAN (읽기 전용, D-I41) ───────────────────────────────
            enter(InstallStage.PLAN)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val warnings = ArrayList<String>()
            val leftovers = withContext(Dispatchers.IO) { findLeftovers(paths.serversRoot.path) }
            for (leftover in leftovers) {
                val message = "이전 설치가 남긴 조각이 있습니다: $leftover (다음 단계에서 정리합니다)"
                warnings.add(message)
                send(InstallEvent.Warning(message))
            }
            val plan = when (val planned = buildPlan(request, target, installId, leftovers, warnings)) {
                is StageResult.Fail -> {
                    failAndRollback(planned.failure)
                    return@channelFlow
                }

                is StageResult.Ok -> planned.value
            }
            for (index in leftovers.size until warnings.size) send(InstallEvent.Warning(warnings[index]))
            send(InstallEvent.Planned(plan))
            if (!interaction.confirmPlan(plan)) {
                failAndRollback(InstallFailure.PlanRejected)
                return@channelFlow
            }

            // ── FETCH ────────────────────────────────────────────────
            enter(InstallStage.FETCH)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val listener = object : FetchListener {
                override suspend fun onProgress(progress: FetchProgress) {
                    send(InstallEvent.Progress(progress))
                }

                override suspend fun onEvent(event: FetchItemEvent) {
                    send(InstallEvent.FetchNotice(event))
                }
            }
            val results = fetcher.fetchAll(plan.items, listener)
            val failedFetch = results.filterIsInstance<FetchResult.Failed>().firstOrNull()
            if (failedFetch != null) {
                failAndRollback(InstallFailure.DownloadFailed(failedFetch.item.id, failedFetch.error))
                return@channelFlow
            }
            val fetched = results.filterIsInstance<FetchResult.Fetched>()
            if (fetched.size != plan.items.size) {
                failAndRollback(InstallFailure.LocalIo(null, "다운로드 결과 수가 요청(${plan.items.size})과 다릅니다: ${results.size}"))
                return@channelFlow
            }

            // ── VERIFY ───────────────────────────────────────────────
            enter(InstallStage.VERIFY)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            for (result in fetched) {
                val outcome = verifier.verify(result.file, result.item)
                if (outcome !is VerifyOutcome.Verified) {
                    // ★ 오염된 조각을 다음 실행이 이어받지 않게 지운다. 재시도는 없다 (변조 의심)
                    if (outcome is VerifyOutcome.SizeMismatch ||
                        outcome is VerifyOutcome.HashMismatch ||
                        outcome is VerifyOutcome.CorruptArchive
                    ) {
                        fetcher.discard(result.item)
                    }
                    failAndRollback(InstallFailure.IntegrityFailed(result.item.id, outcome))
                    return@channelFlow
                }
                send(InstallEvent.Verified(result.item.id, outcome.sha256, outcome.size))
            }

            // ── LAYOUT (여기서부터 파일 시스템을 바꾼다) ────────────────
            enter(InstallStage.LAYOUT)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val serversRoot = paths.serversRoot.path
            // 스윕이 무엇을 치웠는지는 롤백 안내에 필요하다 — "사용자 폴더는 바뀌지 않았습니다" 를 함부로 말하지 않기 위해
            val sweep = withContext(Dispatchers.IO) {
                if (Files.isDirectory(serversRoot)) sweepStaging(serversRoot) else null
            }
            if (sweep != null) swept.addAll(sweep.removedStaging + sweep.removedIncomplete)
            val rootProblem = withContext(Dispatchers.IO) { createMissingDirectories(serversRoot, createdDirs) }
            if (rootProblem != null) {
                failAndRollback(rootProblem)
                return@channelFlow
            }
            // ★ 연 스테이징은 **취소되지 않는 블록 안에서** area 에 적는다:
            //   `withContext(Dispatchers.IO)` 가 취소되면 블록의 결과가 버려져, 이미 만든 `.staging/{installId}/` 와
            //   잡은 잠금 두 개를 롤백이 보지 못하고 그대로 남긴다 (게다가 cleanedUp = true 로 보고한다).
            val opened = withContext(NonCancellable + Dispatchers.IO) {
                openStagingFn(paths.stagingRoot, installId, request.serverName).also {
                    if (it is StagingOpenResult.Opened) area = it.area
                }
            }
            // 여는 동안 들어온 취소는 여기서 본다 — area 가 이미 채워져 있으므로 catch 의 rollbackOnce() 가 치운다
            currentCoroutineContext().ensureActive()
            val staging = when (opened) {
                is StagingOpenResult.Opened -> opened.area

                is StagingOpenResult.NameBusy -> {
                    failAndRollback(InstallFailure.NameBusy(opened.name))
                    return@channelFlow
                }

                is StagingOpenResult.Failed -> {
                    failAndRollback(InstallFailure.LocalIo(opened.path, opened.detail))
                    return@channelFlow
                }
            }
            val placed = withContext(Dispatchers.IO) { placeArtifact(staging, fetched[0].file, plan.launch.jarFileName) }
            if (placed is LayoutIoResult.Failed) {
                failAndRollback(InstallFailure.LocalIo(placed.path, placed.detail))
                return@channelFlow
            }

            // ── CONFIG ───────────────────────────────────────────────
            enter(InstallStage.CONFIG)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val configProblem = withContext(Dispatchers.IO) { writeConfigFiles(staging, request, plan) }
            if (configProblem != null) {
                failAndRollback(configProblem)
                return@channelFlow
            }

            // ── EULA (명시적 동의 없이는 eula.txt 를 쓰지 않는다) ────────
            enter(InstallStage.EULA)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            val notice = EulaNotice(MINECRAFT_EULA_URL, request.serverName, target.mc.label)
            when (val answer = interaction.requestEulaConsent(notice)) {
                is EulaAnswer.Declined -> {
                    failAndRollback(InstallFailure.EulaDeclined(answer.reasonKo))
                    return@channelFlow
                }

                is EulaAnswer.Accepted -> {
                    val written = withContext(Dispatchers.IO) {
                        writeFileAtomically(staging.dir.resolve(EULA_FILE_NAME), renderEulaTxt(answer.channel, clock.now()))
                    }
                    if (written is LayoutIoResult.Failed) {
                        failAndRollback(InstallFailure.LocalIo(written.path, written.detail))
                        return@channelFlow
                    }
                }
            }
            val built = withContext(Dispatchers.IO) { buildManifest(staging, request, plan, target, installId) }
            val manifest = when (built) {
                is StageResult.Fail -> {
                    failAndRollback(built.failure)
                    return@channelFlow
                }

                is StageResult.Ok -> built.value
            }
            val manifestWritten = withContext(Dispatchers.IO) {
                writeFileAtomically(
                    staging.dir.resolve(META_DIR_NAME).resolve(MANIFEST_FILE_NAME),
                    encodeJsonLine(InstallManifest.serializer(), manifest),
                )
            }
            if (manifestWritten is LayoutIoResult.Failed) {
                failAndRollback(InstallFailure.LocalIo(manifestWritten.path, manifestWritten.detail))
                return@channelFlow
            }

            // ── READY (원자적 이동 한 번) ──────────────────────────────
            enter(InstallStage.READY)?.let {
                failAndRollback(it)
                return@channelFlow
            }
            when (val commit = committer.commit(staging.dir, plan.serverDir, installId)) {
                is CommitResult.Committed -> {
                    // 커밋 뒤의 취소가 커밋된 서버를 건드리지 않게 한다
                    rolledBack = true
                    area = null
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { cleanupAfterCommit(staging) }
                        for (item in plan.items) fetcher.discard(item)
                    }
                    send(InstallEvent.Ready(InstalledServer(request.serverName, commit.target, plan.launch, manifest)))
                }

                is CommitResult.TargetExists -> {
                    failAndRollback(InstallFailure.ServerExists(commit.existing))
                    return@channelFlow
                }

                is CommitResult.Failed -> {
                    failAndRollback(InstallFailure.CommitFailed(commit.detail))
                    return@channelFlow
                }
            }
        } catch (e: CancellationException) {
            rollbackOnce()
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            failAndRollback(InstallFailure.LocalIo(null, e.toString()))
        }
    }

    /** PLAN: 읽기 전용 검사 → [InstallPlan]. 경고는 [warnings] 에 쌓고 호출자가 사건으로 내보낸다. */
    private suspend fun buildPlan(
        request: InstallRequest,
        target: InstallTarget,
        installId: String,
        leftovers: List<Path>,
        warnings: MutableList<String>,
    ): StageResult<InstallPlan> {
        val serversRoot = paths.serversRoot.path

        when (val check = validateServerName(request.serverName)) {
            is NameCheck.Invalid -> return StageResult.Fail(InstallFailure.InvalidServerName(request.serverName, check.reasonKo))
            NameCheck.Valid -> Unit
        }
        when (val check = validateServerDir(serversRoot, request.serverName)) {
            is NameCheck.Invalid -> return StageResult.Fail(InstallFailure.ServersRootUnusable(serversRoot, check.reasonKo))
            NameCheck.Valid -> Unit
        }
        // 남은 조각과 같은 경로면 충돌이 아니다 (LAYOUT 스윕이 치운다, D-I41)
        val clash = withContext(Dispatchers.IO) { findNameClash(serversRoot, request.serverName) }
        if (clash != null && leftovers.none { it == clash }) return StageResult.Fail(InstallFailure.ServerExists(clash))

        if (request.ramMb < MIN_RAM_MB) {
            return StageResult.Fail(InstallFailure.InvalidRam(request.ramMb, "최소 ${MIN_RAM_MB}MB 가 필요합니다"))
        }
        val memoryInfo = memory.read()
        if (memoryInfo != null) {
            val totalMb = memoryInfo.totalBytes / MIB
            if (request.ramMb > totalMb) {
                return StageResult.Fail(InstallFailure.InvalidRam(request.ramMb, "이 PC 의 물리 메모리(${totalMb}MB)보다 큽니다"))
            }
        }

        checkCatalogContract(target.build)?.let { return StageResult.Fail(InstallFailure.InvalidCatalogData(it)) }

        val requirement = JavaRequirement(target.mc.javaMin, target.mc.javaRecommended)
        val java = when (val located = javaLocator.locate(requirement, request.javaOverride)) {
            is JavaLocateResult.Found -> located.selection

            is JavaLocateResult.NotFound -> return StageResult.Fail(
                InstallFailure.JavaNotFound(requirement.minFeature, requirement.recommendedFeature, located.candidates),
            )
        }

        val flags = when (val selection = selectFlagProfile(profiles, request.ramMb, java.feature, request.preTouch)) {
            is FlagSelection.NoProfile -> return StageResult.Fail(InstallFailure.NoFlagProfile(selection.reasonKo))
            is FlagSelection.Selected -> selection
        }

        checkDisk(target.build.size, warnings)?.let { return StageResult.Fail(it) }

        if (request.preTouch && memoryInfo != null) {
            val needed = request.ramMb * MIB + 512 * MIB
            if (memoryInfo.availableBytes < needed) {
                warnings.add(
                    "지금 쓸 수 있는 메모리(${memoryInfo.availableBytes / MIB}MB)가 요청(${request.ramMb}MB + 여유 512MB)보다 적습니다. " +
                        "기동이 느리거나 실패하면 --ram 을 줄이거나 --no-pretouch 를 쓰세요",
                )
            }
        }

        warnings.addAll(java.warningsKo)

        val item = FetchItem(
            id = "core:" + fileNameFromUrl(target.build.downloadUrl),
            sources = listOf(target.build.downloadUrl),
            sha256 = target.build.sha256,
            size = target.build.size,
            kind = ArtifactKind.SERVER_JAR,
        )
        val launch = LaunchSpec(
            javaPath = java.javaPath.toString(),
            javaFeature = java.feature,
            xmsMb = request.ramMb,
            xmxMb = request.ramMb,
            flagProfileId = flags.profile.id,
            jvmFlags = flags.flags,
            jarFileName = coreJarFileName(request.core, target.mc.label),
        )
        return StageResult.Ok(
            InstallPlan(
                installId = installId,
                target = target,
                serverName = request.serverName,
                serverDir = serversRoot.resolve(request.serverName),
                items = listOf(item),
                totalBytes = item.size,
                java = java,
                launch = launch,
                warningsKo = warnings.toList(),
            ),
        )
    }

    /** 캐시 볼륨과 서버 루트 볼륨의 여유 공간. 같은 볼륨이면 두 벌이 동시에 필요하다. */
    private suspend fun checkDisk(size: Long, warnings: MutableList<String>): InstallFailure? = withContext(Dispatchers.IO) {
        val margin = 64 * MIB
        val root = paths.serversRoot.path
        val partial = paths.partialDir
        val rootUsable = disk.usableBytes(root)
        val partialUsable = disk.usableBytes(partial)
        if (sameFileStore(root, partial)) {
            val needed = 2 * size + margin
            if (rootUsable != null && rootUsable < needed) {
                return@withContext InstallFailure.InsufficientDisk(root, needed, rootUsable)
            }
        } else {
            if (partialUsable != null && partialUsable < size + margin) {
                return@withContext InstallFailure.InsufficientDisk(partial, size + margin, partialUsable)
            }
            if (rootUsable != null && rootUsable < size + margin) {
                return@withContext InstallFailure.InsufficientDisk(root, size + margin, rootUsable)
            }
        }
        if (rootUsable != null && rootUsable - size < 1024 * MIB) {
            warnings.add("설치 뒤 $root 의 여유 공간이 1GiB 미만이 됩니다. 첫 기동 때 Mojang jar·라이브러리·월드가 더 필요합니다")
        }
        null
    }

    /** 서버 루트까지 없는 단계를 얕은 것부터 만든다. 만든 디렉터리는 롤백이 비어 있을 때만 지운다. */
    private fun createMissingDirectories(root: Path, created: MutableList<Path>): InstallFailure? {
        if (Files.isDirectory(root)) return null
        val missing = ArrayList<Path>()
        var current: Path? = root.toAbsolutePath()
        while (current != null && !Files.exists(current)) {
            missing.add(current)
            current = current.parent
        }
        for (dir in missing.reversed()) {
            try {
                Files.createDirectory(dir)
            } catch (e: FileAlreadyExistsException) {
                continue
            } catch (e: IOException) {
                return InstallFailure.LocalIo(dir, e.toString())
            } catch (e: SecurityException) {
                return InstallFailure.LocalIo(dir, e.toString())
            }
            created.add(dir)
        }
        return if (Files.isDirectory(root)) null else InstallFailure.LocalIo(root, "서버 폴더를 만들지 못했습니다")
    }

    /** CONFIG 단계의 네 파일. 렌더러가 거부한 경우(비 ASCII 토큰 등)도 `LocalIo` 로 보고한다. */
    private fun writeConfigFiles(area: StagingArea, request: InstallRequest, plan: InstallPlan): InstallFailure? {
        val files: List<Pair<Path, ByteArray>> = try {
            listOf(
                area.dir.resolve(SERVER_PROPERTIES_FILE_NAME) to renderServerProperties(request.settings, request.serverName),
                area.dir.resolve(START_BAT_FILE_NAME) to renderStartBat(plan.launch, env),
                area.dir.resolve(START_SH_FILE_NAME) to renderStartSh(plan.launch),
                area.dir.resolve(META_DIR_NAME).resolve(LAUNCH_FILE_NAME) to encodeLaunchJson(plan.launch),
            )
        } catch (e: IllegalArgumentException) {
            return InstallFailure.LocalIo(area.dir, e.message ?: e.toString())
        }
        for ((path, bytes) in files) {
            when (val written = writeFileAtomically(path, bytes)) {
                is LayoutIoResult.Failed -> return InstallFailure.LocalIo(written.path, written.detail)
                is LayoutIoResult.Ok -> Unit
            }
        }
        makeExecutableBestEffort(area.dir.resolve(START_SH_FILE_NAME))
        return null
    }

    /** 스테이징의 모든 파일 + sha256 (D-I45). manifest.json 자신은 아직 없다. */
    private fun buildManifest(
        area: StagingArea,
        request: InstallRequest,
        plan: InstallPlan,
        target: InstallTarget,
        installId: String,
    ): StageResult<InstallManifest> {
        val relatives = try {
            Files.walk(area.dir).use { stream -> stream.toList() }
                .filter { Files.isRegularFile(it) }
                .map { area.dir.relativize(it) }
                .sortedBy { it.toString() }
        } catch (e: IOException) {
            return StageResult.Fail(InstallFailure.LocalIo(area.dir, e.toString()))
        }
        val entries = ArrayList<ManifestFile>(relatives.size)
        for (relative in relatives) {
            val file = area.dir.resolve(relative)
            val sha256 = sha256HexOrNull(file)
                ?: return StageResult.Fail(InstallFailure.LocalIo(file, "sha256 을 계산하지 못했습니다"))
            val size = try {
                Files.size(file)
            } catch (e: IOException) {
                return StageResult.Fail(InstallFailure.LocalIo(file, e.toString()))
            }
            val path = relative.toString().replace('\\', '/')
            val origin = if (path == plan.launch.jarFileName) FileOrigin.DOWNLOADED else FileOrigin.GENERATED
            entries.add(ManifestFile(path, sha256, size, origin))
        }
        return StageResult.Ok(
            InstallManifest(
                installId = installId,
                serverName = request.serverName,
                createdAt = clock.now().toString(),
                mcLabel = target.mc.label,
                mcOrdinal = target.mc.ordinal.value,
                core = request.core.name,
                coreBuild = target.build.build,
                coreChannel = target.build.channel.name,
                files = entries,
            ),
        )
    }

    /** 커밋 뒤 정리: 잠금 해제 → 잠금 파일 삭제 → 빈 `.staging` 삭제 (전부 최선 노력). */
    private fun cleanupAfterCommit(area: StagingArea) {
        area.close()
        deleteQuietly(area.stagingRoot.resolve(area.installId + LOCK_SUFFIX))
        for (file in listRegularFiles(area.stagingRoot)) {
            val name = file.fileName.toString()
            if (!name.startsWith(NAME_LOCK_PREFIX) || !name.endsWith(LOCK_SUFFIX)) continue
            if (withLockFile(file, create = false) { }) deleteQuietly(file)
        }
        deleteIfEmpty(area.stagingRoot)
    }
}

/** 생성 파일 이름 (설계서 §14). */
internal const val SERVER_PROPERTIES_FILE_NAME: String = "server.properties"

/** Windows 실행 스크립트. */
internal const val START_BAT_FILE_NAME: String = "start.bat"

/** POSIX 실행 스크립트. */
internal const val START_SH_FILE_NAME: String = "start.sh"

private const val MIB: Long = 1024L * 1024L

/** 런처가 쓰는 JSON: 들여쓰기 2칸, 기본값도 기록 (사용자가 파일만 보고 알 수 있게). */
@OptIn(ExperimentalSerializationApi::class)
private val installJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}

private fun <T> encodeJsonLine(serializer: SerializationStrategy<T>, value: T): ByteArray =
    (installJson.encodeToString(serializer, value) + "\n").toByteArray(Charsets.UTF_8)

/**
 * `.decacross/launch.json` 바이트 (DESIGN2 §2.8: 들여쓰기 2칸 + 기본값 포함 + 끝 줄바꿈).
 *
 * # 불변식
 * - ★ 설치와 CLI `start --java … --save` 는 **이 함수 하나만** 쓴다. 인코더가 갈라지면 같은 내용인데 파일 바이트가
 *   달라져(들여쓰기 4칸·줄바꿈 없음), 설치가 쓴 파일과 다시 쓴 파일이 서로 다른 파일이 된다.
 */
fun encodeLaunchJson(spec: LaunchSpec): ByteArray = encodeJsonLine(LaunchSpec.serializer(), spec)

/** 카탈로그 계약: sha256 64 hex, 크기 > 0, http(s) URL (무결성은 sha256 이 지킨다 — 로컬 테스트를 위해 http 도 허용). */
private fun checkCatalogContract(build: CoreBuild): String? {
    if (!SHA256_HEX.matches(build.sha256)) return "sha256 형식이 아닙니다: ${build.sha256}"
    if (build.size <= 0) return "파일 크기가 0 이하입니다: ${build.size}"
    val uri = try {
        URI(build.downloadUrl)
    } catch (e: URISyntaxException) {
        return "다운로드 URL 을 해석할 수 없습니다: ${build.downloadUrl}"
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return "다운로드 URL 이 http(s) 가 아닙니다: ${build.downloadUrl}"
    if (uri.host.isNullOrBlank()) return "다운로드 URL 에 호스트가 없습니다: ${build.downloadUrl}"
    return null
}

private fun fileNameFromUrl(url: String): String {
    val path = try {
        URI(url).path.orEmpty()
    } catch (e: URISyntaxException) {
        ""
    }
    return path.substringAfterLast('/').ifBlank { "artifact" }
}

/** 두 경로가 같은 파일 저장소인가 (없는 경로는 가장 가까운 조상으로 본다). 알 수 없으면 false. */
private fun sameFileStore(a: Path, b: Path): Boolean =
    try {
        val storeA = nearestExisting(a)?.let { Files.getFileStore(it) }
        val storeB = nearestExisting(b)?.let { Files.getFileStore(it) }
        storeA != null && storeA == storeB
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

private fun nearestExisting(path: Path): Path? {
    var current: Path? = path.toAbsolutePath()
    while (current != null && !Files.exists(current)) current = current.parent
    return current
}

/** POSIX 파일 시스템이면 `rwxr-xr-x`. Windows 에는 뷰가 없어 아무 일도 하지 않는다. */
private fun makeExecutableBestEffort(path: Path) {
    try {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java) ?: return
        view.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    } catch (e: IOException) {
        // 권한 설정 실패는 설치를 막지 않는다
    } catch (e: UnsupportedOperationException) {
        // POSIX 가 아닌 파일 시스템
    }
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/**
 * 기본 구성 묶음. HttpClient 수명을 여기서 관리해서 CLI 가 Ktor 타입을 몰라도 되게 한다.
 * `use {}` 로 닫아라.
 */
class DefaultInstallEnvironment(private val paths: DecaPaths, userAgent: String) : AutoCloseable {
    private val client: HttpClient = installHttpClient(userAgent)

    /** 기본 다운로더 (동시 4, 재시도 정책 기본값). */
    val fetcher: ArtifactFetcher = Fetcher(client, paths.partialDir)

    /** CLI·데몬이 쓰는 기본 파이프라인 ([fetcher] + [ArtifactVerifier.DEFAULT]). */
    fun pipeline(
        db: CompatDb,
        javaLocator: JavaLocator,
        interaction: InstallInteraction,
        profiles: LaunchProfiles,
    ): InstallPipeline = InstallPipeline(db, paths, fetcher, ArtifactVerifier.DEFAULT, javaLocator, interaction, profiles)

    override fun close() {
        client.close()
    }
}
