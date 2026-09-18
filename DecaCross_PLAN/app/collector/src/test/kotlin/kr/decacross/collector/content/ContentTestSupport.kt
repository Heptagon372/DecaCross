package kr.decacross.collector.content

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kr.decacross.analysis.BytecodeProfile
import kr.decacross.analysis.CapabilityEvidence
import kr.decacross.analysis.EvidenceConfidence
import kr.decacross.analysis.JarAnalysis
import kr.decacross.analysis.JarDescriptors
import kr.decacross.analysis.JarMeta
import kr.decacross.analysis.MetaDep
import kr.decacross.analysis.MetaDepKind
import kr.decacross.analysis.NmsSignal
import kr.decacross.analysis.PackMcmetaResult
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.ContentSettings
import kr.decacross.collector.store.McRow
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.McOrdinal
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Instant

// ── WP-C3 테스트 공용 도우미 (content·modrinth·hangar 테스트가 같이 쓴다) ─────────────

private object ResourceAnchor

/** 테스트 리소스 텍스트 (`/c3/...`). */
internal fun resourceText(path: String): String =
    checkNotNull(ResourceAnchor::class.java.getResourceAsStream(path)) { "테스트 리소스 없음: $path" }.use { it.readBytes().decodeToString() }

/** `mc_versions` 행 (서수·라벨만 의미 있음). */
internal fun mcRow(label: String, ordinal: Int, snapshot: Boolean = false): McRow = McRow(
    id = ordinal.toLong(),
    label = label,
    ordinal = McOrdinal(ordinal),
    releasedAt = Instant.parse("2020-01-01T00:00:00Z"),
    isSnapshot = snapshot,
    javaMin = 21,
    javaRecommended = 21,
    rpFormat = null,
    dpFormat = null,
    protocol = null,
    clientJarUrl = null,
    clientJarSha1 = null,
)

/** 설계서 §10.6 예시 프로파일. */
internal fun sampleBytecode(feature: Int? = 17): BytecodeProfile =
    BytecodeProfile(feature, 61, null, mapOf(61 to 3), false, emptySet(), null, 0, NmsSignal(false, emptySet(), false, null), emptyList())

internal fun storeEvidence(capability: Capability, detail: String = "store-detail"): CapabilityEvidence =
    CapabilityEvidence(capability, EvidenceConfidence.STORE, "SERVICE_PROVIDER", detail)

internal fun candidateEvidence(capability: Capability, detail: String = "candidate-detail"): CapabilityEvidence =
    CapabilityEvidence(capability, EvidenceConfidence.CANDIDATE, "SERVICE_PROVIDER", detail)

/** 손으로 만든 분석 결과 (가짜 분석기가 돌려준다). */
internal fun fakeAnalysis(
    capabilities: List<CapabilityEvidence> = emptyList(),
    packMeta: PackMcmetaResult? = null,
    apiVersion: String? = "1.20",
    javaFeature: Int? = 17,
): JarAnalysis = JarAnalysis(
    descriptors = JarDescriptors(
        listOf(
            JarMeta(
                name = "Essentials",
                version = "2.22.0",
                apiVersion = apiVersion,
                main = "com.earth2me.essentials.Essentials",
                softDepend = listOf("Vault"),
                descriptor = JarMeta.Descriptor.PLUGIN_YML,
                deps = listOf(MetaDep("Vault", MetaDepKind.OPTIONAL)),
            ),
        ),
    ),
    bytecode = sampleBytecode(javaFeature),
    capabilities = capabilities,
    packMeta = packMeta,
    notes = emptyList(),
)

/** 기본 테스트 설정에서 콘텐츠 설정만 바꾼다. */
internal fun contentSettings(dir: Path, change: (ContentSettings) -> ContentSettings = { it }): CollectorSettings {
    val base = testSettings(dir)
    return base.copy(content = change(base.content))
}

/** 로거 하나에 붙이는 메모리 appender (로그로만 남기는 규칙 검증용). */
internal class LogCapture(type: Class<*>) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(type) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    val messages: List<String> get() = synchronized(appender) { appender.list.map { it.formattedMessage } }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
