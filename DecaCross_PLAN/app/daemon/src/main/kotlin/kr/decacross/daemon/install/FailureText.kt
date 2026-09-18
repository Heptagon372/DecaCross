package kr.decacross.daemon.install

/**
 * 실패 → 한국어 설명 + 해결책 (1개 이상).
 *
 * # 불변식
 * - 모든 분기의 `fixesKo` 는 비어 있지 않다 (테스트로 강제).
 * - 해결책은 실제로 실행 가능한 명령·행동만 적는다 (불변식 8 정신).
 */
fun InstallFailure.describeKo(): FailureDescription =
    when (this) {
        is InstallFailure.UnknownMc -> FailureDescription(
            "알 수 없는 마인크래프트 버전: $label",
            if (sameFamily.isEmpty()) {
                listOf("`lookup <버전>` 으로 수집된 버전을 확인하세요 (예: 1.21.8)")
            } else {
                listOf("같은 계열에서 고르세요: ${sameFamily.joinToString(", ")}")
            },
        )

        is InstallFailure.UnsupportedCore -> FailureDescription(
            "03 단계 설치기는 ${core.name.lowercase()} 코어를 지원하지 않습니다 (Paper 계열만)",
            listOf("--core paper 로 다시 실행하세요"),
        )

        is InstallFailure.NoStableBuild -> FailureDescription(
            "${core.name.lowercase()} $mcLabel 에 안정(STABLE) 빌드가 없습니다 (실험 빌드 $experimentalBuild 만 있음)",
            listOf("실험 빌드를 쓰려면 --experimental 을 붙이세요", "안정 빌드가 있는 다른 버전을 고르세요"),
        )

        is InstallFailure.NoBuildCollected -> FailureDescription(
            "${core.name.lowercase()} $mcLabel 빌드 정보가 수집되지 않았습니다",
            listOf("`lookup $mcLabel --core ${core.name.lowercase()}` 로 확인하고, 수집된 다른 버전을 고르세요"),
        )

        is InstallFailure.InvalidCatalogData -> FailureDescription(
            "호환성 데이터가 계약을 어깁니다: $reason",
            listOf("데이터를 갱신한 뒤 다시 시도하세요 (수집기 재실행)"),
        )

        is InstallFailure.InvalidServerName -> FailureDescription(
            "서버 이름을 쓸 수 없습니다: '$name' — $reasonKo",
            listOf("영문·숫자·한글과 - _ . 공백으로 된 다른 이름을 --name 에 주세요"),
        )

        is InstallFailure.ServerExists -> FailureDescription(
            "같은 이름의 서버(또는 폴더)가 이미 있습니다: $existing",
            listOf("다른 --name 을 주세요", "기존 서버를 쓰려면 `start <이름>`"),
        )

        is InstallFailure.NameBusy -> FailureDescription(
            "'$name' 이름으로 다른 설치가 진행 중입니다",
            listOf("그 설치가 끝난 뒤 다시 실행하세요"),
        )

        is InstallFailure.ServersRootUnusable -> FailureDescription(
            "서버 폴더를 쓸 수 없습니다: $path — $reasonKo",
            listOf("--servers-dir <다른 폴더> 로 위치를 지정하세요"),
        )

        is InstallFailure.InvalidRam -> FailureDescription(
            "메모리 설정이 잘못됐습니다 (${ramMb}MB): $reasonKo",
            listOf("--ram 에 ${MIN_RAM_MB / 1024}G 이상을 주세요 (예: --ram 4G)"),
        )

        is InstallFailure.JavaNotFound -> FailureDescription(
            "Java $requiredMin 이상을 찾지 못했습니다 (권장 $recommended). 확인한 후보: " +
                candidates.joinToString("; ") { c -> "${c.path} → ${c.feature ?: "?"}${c.problemKo?.let { p -> " ($p)" } ?: ""}" }
                    .ifEmpty { "없음" },
            listOf(
                "--java <java 실행 파일 절대경로> 로 Java $recommended 을 지정하세요",
                "Java 자동 설치는 다음 단계(04)에서 제공됩니다",
            ),
        )

        is InstallFailure.NoFlagProfile -> FailureDescription(
            "이 Java/메모리 조합에 맞는 JVM 플래그 프로파일이 없습니다: $reasonKo",
            listOf("--ram 을 조정하거나 --java 로 다른 Java 를 지정하세요"),
        )

        is InstallFailure.InsufficientDisk -> FailureDescription(
            "디스크 공간이 부족합니다: $path (필요 ${neededBytes / MIB} MiB, 여유 ${usableBytes / MIB} MiB)",
            listOf("공간을 확보하거나 --servers-dir 로 다른 드라이브를 지정하세요"),
        )

        InstallFailure.PlanRejected -> FailureDescription(
            "설치 계획을 취소했습니다",
            listOf("옵션을 바꿔 다시 실행하세요"),
        )

        is InstallFailure.DownloadFailed -> describeDownload(itemId, error)

        is InstallFailure.IntegrityFailed -> FailureDescription(
            "받은 파일($itemId)이 기록된 값과 다릅니다 — 설치를 중단했고 사용자 폴더에는 아무것도 쓰지 않았습니다: " +
                describeVerify(outcome),
            listOf(
                "변조 또는 오래된 데이터일 수 있습니다. 데이터를 갱신한 뒤 다시 시도하세요",
                "문제가 계속되면 네트워크(프록시·백신) 환경을 확인하세요",
            ),
        )

        is InstallFailure.LocalIo -> FailureDescription(
            "파일 작업 실패${path?.let { " ($it)" } ?: ""}: $detail",
            listOf("폴더 권한·백신 차단·디스크 상태를 확인하고 다시 실행하세요"),
        )

        is InstallFailure.EulaDeclined -> FailureDescription(
            "EULA 에 동의하지 않아 서버를 만들지 않았습니다 ($reasonKo). 받은 파일은 캐시에 남아 다음 실행이 바로 씁니다",
            listOf(
                "EULA($MINECRAFT_EULA_URL)를 읽고 동의한다면 프롬프트에 y 를 입력하거나 --accept-eula 를 직접 붙여 다시 실행하세요",
            ),
        )

        is InstallFailure.CommitFailed -> FailureDescription(
            "완성된 서버를 최종 위치로 옮기지 못했습니다: $detail",
            listOf("탐색기·백신이 폴더를 잡고 있지 않은지 확인하고 다시 실행하세요"),
        )

        InstallFailure.Cancelled -> FailureDescription(
            "설치를 취소했습니다",
            listOf("다시 실행하면 받은 부분부터 이어받습니다"),
        )
    }

private const val MIB: Long = 1024L * 1024L

private fun describeDownload(itemId: String, error: FetchError): FailureDescription =
    when (error) {
        is FetchError.SourcesExhausted -> FailureDescription(
            "다운로드 실패($itemId): 모든 소스에서 재시도를 소진했습니다 — " +
                error.attempts.takeLast(3).joinToString("; ") { "${it.source} #${it.attempt}: ${it.outcome}" },
            listOf("네트워크 연결을 확인하고 다시 실행하세요 (받은 부분부터 이어받습니다)"),
        )

        is FetchError.SizeMismatch -> FailureDescription(
            "다운로드 크기가 기록과 다릅니다($itemId, ${error.where}: ${error.observed ?: "?"} ≠ ${error.expected}) — 변조 의심으로 중단했습니다",
            listOf("데이터를 갱신한 뒤 다시 시도하세요"),
        )

        is FetchError.UnexpectedContent -> FailureDescription(
            "다운로드 대신 다른 내용(${error.detail})을 받았습니다($itemId) — 프록시나 로그인 페이지일 수 있습니다",
            listOf("브라우저로 ${error.source} 가 열리는지 확인하고, 사내망·공용 와이파이라면 다른 네트워크에서 시도하세요"),
        )

        is FetchError.LocalIo -> FailureDescription(
            "다운로드 파일을 쓰지 못했습니다(${error.path}): ${error.message}",
            listOf("디스크 공간과 폴더 권한을 확인하세요"),
        )

        is FetchError.Aborted -> FailureDescription(
            "다른 파일(${error.causeItemId})의 무결성 실패로 $itemId 다운로드를 중단했습니다",
            listOf("데이터를 갱신한 뒤 다시 시도하세요 (받은 부분은 남아 있습니다)"),
        )
    }

private fun describeVerify(outcome: VerifyOutcome): String =
    when (outcome) {
        is VerifyOutcome.Verified -> "정상"
        is VerifyOutcome.SizeMismatch -> "크기 ${outcome.actual} ≠ ${outcome.expected}"
        is VerifyOutcome.HashMismatch -> "SHA-256 ${outcome.actual.take(12)}… ≠ ${outcome.expected.take(12)}…"
        is VerifyOutcome.CorruptArchive -> "jar 손상 (${outcome.reason})"
        is VerifyOutcome.Io -> "읽기 실패 (${outcome.message})"
    }
