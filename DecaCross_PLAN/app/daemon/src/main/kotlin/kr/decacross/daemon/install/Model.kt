package kr.decacross.daemon.install

import kotlinx.serialization.Serializable
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McVersion
import java.nio.file.Path

/** 설치 상태머신 단계 (설계서 §4.1). */
enum class InstallStage { RESOLVE, PLAN, FETCH, VERIFY, LAYOUT, CONFIG, EULA, READY }

/** 파이프라인 입력. 03 단계에서는 CLI 가 채우고, 07 부터 resolve() 결과(Plan)에서 만들어진다. */
data class InstallSpec(
    /** 서버 이름 = 폴더 이름 */
    val name: String,
    val mc: McVersion,
    val core: CoreBuild,
    /** 서버 실행용 java 절대경로. ★ 번들 JRE 금지 (불변식 9) */
    val javaExe: Path,
    val ramMb: Int,
    /** 사용자가 명시적으로 동의했을 때만 true. 자동 동의 금지 (F-05). */
    val acceptEula: Boolean,
    val port: Int = 25565,
    /** server.properties 추가/덮어쓰기 값 */
    val properties: Map<String, String> = emptyMap(),
    /** 실행 스크립트 title 등에 쓰는 표시 이름 (기본 = name) */
    val displayName: String = name,
    /**
     * 07: resolve() 가 확정한 플러그인 목록. `plugins/<slug>-<version>.jar` 로 배치된다.
     * ★ 항상 원본 URL(fileUrl)에서 받는다 — redistributable=false 콘텐츠를 우리가 미러링하지 않는다.
     */
    val plugins: List<kr.decacross.compat.model.ContentVersion> = emptyList(),
)

/** 진행 이벤트. 나중에 WS 로 그대로 흘려보낸다 (05). */
sealed interface InstallEvent {
    data class StageChanged(val stage: InstallStage) : InstallEvent

    data class Progress(val stage: InstallStage, val done: Long, val total: Long?, val detailKo: String) : InstallEvent

    data class Message(val textKo: String) : InstallEvent

    data class Failed(val stage: InstallStage, val error: InstallError) : InstallEvent

    data class Completed(val server: InstalledServer) : InstallEvent
}

/** 실패 사유. 예외 대신 값으로. */
sealed interface InstallError {
    val messageKo: String

    data class InvalidName(val name: String, val reasonKo: String) : InstallError {
        override val messageKo: String get() = "서버 이름 '$name' 을 쓸 수 없습니다: $reasonKo"
    }

    data class AlreadyExists(val dir: Path) : InstallError {
        override val messageKo: String get() = "이미 같은 이름의 서버가 있습니다: $dir"
    }

    data class Network(val url: String, val detailKo: String) : InstallError {
        override val messageKo: String get() = "다운로드 실패 ($url): $detailKo"
    }

    /** ★ 해시 불일치는 재시도하지 않고 즉시 중단한다 (변조 의심). */
    data class HashMismatch(val file: String, val expected: String, val actual: String) : InstallError {
        override val messageKo: String get() = "파일 무결성 검증 실패 ($file): 예상 ${expected.take(12)}…, 실제 ${actual.take(12)}…"
    }

    data class CorruptArchive(val file: String) : InstallError {
        override val messageKo: String get() = "손상된 압축 파일: $file"
    }

    data object EulaNotAccepted : InstallError {
        override val messageKo: String get() = "Mojang EULA 에 동의하지 않아 설치를 완료할 수 없습니다"
    }

    data class Io(val detailKo: String) : InstallError {
        override val messageKo: String get() = "파일 처리 오류: $detailKo"
    }
}

/**
 * 설치된 서버 기록. `<server>/.decacross/server.json` 에 저장된다.
 * start 스크립트는 이 파일 없이도 돈다 — 이건 런처용 메타데이터일 뿐이다 (불변식 11).
 */
@Serializable
data class InstalledServer(
    val name: String,
    val dir: String,
    val mcLabel: String,
    val mcOrdinal: Int,
    val core: CoreKey,
    val build: String,
    val coreJar: String,
    val javaExe: String,
    val ramMb: Int,
    val port: Int,
    val createdAt: String,
    /** null 이면 미동의 — READY 로 갈 수 없다 */
    val eulaAcceptedAt: String?,
)

/** `<server>/.decacross/manifest.json` — 설치된 파일 목록 + 해시. CAS gc 의 참조 판정 근거(04). */
@Serializable
data class InstallManifest(
    val files: List<ManifestEntry>,
)

@Serializable
data class ManifestEntry(val path: String, val sha256: String, val size: Long)
