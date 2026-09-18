package kr.decacross.collector.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.decacross.analysis.BytecodeProfile
import kr.decacross.analysis.CapabilityEvidence
import kr.decacross.analysis.EvidenceConfidence
import kr.decacross.analysis.InvalidDescriptor
import kr.decacross.analysis.JarAnalysis
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.analysis.JarMeta
import kr.decacross.analysis.MetaDep
import kr.decacross.analysis.PackMajorRange
import kr.decacross.analysis.PackMcmeta
import kr.decacross.analysis.PackMcmetaResult
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.store.AnalysisRecord
import kr.decacross.collector.store.dbKey
import kr.decacross.compat.model.PackFormat
import kotlin.time.Instant

// ── content_versions.analysis (jsonb) 문서 ─────────────────────────────────
// JarAnalysis 의 @Serializable 거울. 의도적으로 빠진 것 두 가지:
//  1) CANDIDATE 근거는 절대 넣지 않는다 (D33 — 애매한 Capability 는 로그에만).
//  2) pack format 은 PackFormat 문자열로만 쓴다 (불변식 3 — JSON 숫자 금지). modernDecl()/legacyDecl() 은 부르지 않는다.
// 인코딩 설정은 CollectorJson 을 이어받는다 (explicitNulls = false → null 필드는 생략된다).

@Serializable
internal data class AnalysisDoc(
    val analyzerVersion: String,
    val unreadable: String? = null,
    val descriptors: List<DescriptorDoc> = emptyList(),
    val invalidDescriptors: List<InvalidDescriptorDoc> = emptyList(),
    val bytecode: BytecodeDoc? = null,
    val capabilities: List<CapabilityDoc> = emptyList(),
    val packMeta: PackMetaDoc? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
internal data class DescriptorDoc(
    val descriptor: String,
    val name: String,
    val rawName: String,
    val version: String? = null,
    val rawVersion: String? = null,
    val apiVersion: String? = null,
    val main: String? = null,
    val depend: List<String> = emptyList(),
    val softDepend: List<String> = emptyList(),
    val loadBefore: List<String> = emptyList(),
    val loadAfter: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val load: String? = null,
    val foliaSupported: Boolean = false,
    val skipLibrariesOnPaper: Boolean = false,
    val nestedJars: List<String> = emptyList(),
    val deps: List<MetaDepDoc> = emptyList(),
    val loadErrors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class MetaDepDoc(
    val target: String,
    val kind: String,
    val versionPredicates: List<String>? = null,
    val order: String,
    val phase: String,
    val side: String,
)

@Serializable
internal data class InvalidDescriptorDoc(val descriptor: String, val reason: String)

@Serializable
internal data class BytecodeDoc(
    val requiredJavaFeature: Int? = null,
    val outerMaxMajor: Int? = null,
    val nestedMaxMajor: Int? = null,
    /** 키는 major 의 문자열 (JSON 객체 키). */
    val majorHistogram: Map<String, Int> = emptyMap(),
    val multiRelease: Boolean = false,
    val versionedFeatures: List<Int> = emptyList(),
    val previewMajor: Int? = null,
    val invalidClassEntries: Int = 0,
    val nms: NmsDoc,
    val notes: List<String> = emptyList(),
)

@Serializable
internal data class NmsDoc(
    val binaryRefs: Boolean,
    val versionedTokens: List<String> = emptyList(),
    val reflectiveStrings: Boolean,
    val mappingsNamespace: String? = null,
)

/** STORE 근거만. */
@Serializable
internal data class CapabilityDoc(val capability: String, val confidence: String, val rule: String, val detail: String)

/**
 * pack.mcmeta 원시 필드. `ok = false` 면 [reason] 만 있다.
 * 모든 pack format 은 [PackFormat.toString] 텍스트다 (예: `"34"`, `"34.2147483647"`).
 */
@Serializable
internal data class PackMetaDoc(
    val ok: Boolean,
    val packFormat: String? = null,
    val supportedFormats: PackRangeDoc? = null,
    val minFormat: String? = null,
    val maxFormat: String? = null,
    val overlays: List<OverlayDoc>? = null,
    val notes: List<String>? = null,
    val reason: String? = null,
)

@Serializable
internal data class PackRangeDoc(val min: String, val max: String)

@Serializable
internal data class OverlayDoc(
    val directory: String,
    val formats: PackRangeDoc? = null,
    val minFormat: String? = null,
    val maxFormat: String? = null,
)

/** 분석 문서 인코더. CollectorJson 설정 + 기본값도 기록. */
private val AnalysisJsonFormat: Json = Json(CollectorJson) { encodeDefaults = true }

/** 성공한 분석 → jsonb 텍스트. CANDIDATE 근거는 들어가지 않는다. */
internal fun analysisJson(a: JarAnalysis, analyzerVersion: String): String =
    AnalysisJsonFormat.encodeToString(AnalysisDoc.serializer(), analysisDocOf(a, analyzerVersion))

/** 읽을 수 없는 jar → 같은 모양, [AnalysisDoc.unreadable] 만 채우고 나머지는 비움. */
internal fun unreadableJson(reason: String, analyzerVersion: String): String =
    AnalysisJsonFormat.encodeToString(AnalysisDoc.serializer(), AnalysisDoc(analyzerVersion = analyzerVersion, unreadable = reason))

/** jsonb 텍스트 → 문서 (테스트·재해석용). */
internal fun parseAnalysisJson(text: String): AnalysisDoc = AnalysisJsonFormat.decodeFromString(AnalysisDoc.serializer(), text)

internal fun analysisDocOf(a: JarAnalysis, analyzerVersion: String): AnalysisDoc = AnalysisDoc(
    analyzerVersion = analyzerVersion,
    unreadable = null,
    descriptors = a.descriptors.all.map { it.toDoc() },
    invalidDescriptors = a.descriptors.invalid.map { it.toDoc() },
    bytecode = a.bytecode.toDoc(),
    capabilities = a.capabilities.filter { it.confidence == EvidenceConfidence.STORE }.map { it.toDoc() },
    packMeta = a.packMeta?.toDoc(),
    notes = a.notes,
)

/**
 * jar 1개의 분석 결과 → [AnalysisRecord] (§10.3 6단계).
 * - Ok: javaMajor = requiredJavaFeature, apiVersion = primary descriptor 의 apiVersion, provides = STORE 만(중복 제거).
 * - Unreadable: javaMajor·apiVersion = null, provides 없음.
 * - packDecl 은 기록하지 않는다 (선언 도출은 엔진 몫, 07).
 */
internal fun analysisRecordOf(
    result: JarAnalysisResult,
    sha256: String,
    size: Long,
    analyzedAt: Instant,
    analyzerVersion: String,
): AnalysisRecord = when (result) {
    is JarAnalysisResult.Ok -> AnalysisRecord(
        sha256 = sha256,
        size = size,
        javaMajor = result.analysis.bytecode.requiredJavaFeature,
        apiVersion = result.analysis.descriptors.primary?.apiVersion,
        packDecl = null,
        analyzedAt = analyzedAt,
        analyzerVersion = analyzerVersion,
        analysisJson = analysisJson(result.analysis, analyzerVersion),
        provides = result.analysis.capabilities
            .filter { it.confidence == EvidenceConfidence.STORE }
            .map { it.capability }
            .distinct(),
    )

    is JarAnalysisResult.Unreadable -> AnalysisRecord(
        sha256 = sha256,
        size = size,
        javaMajor = null,
        apiVersion = null,
        packDecl = null,
        analyzedAt = analyzedAt,
        analyzerVersion = analyzerVersion,
        analysisJson = unreadableJson(result.reason, analyzerVersion),
        provides = emptyList(),
    )
}

private fun JarMeta.toDoc(): DescriptorDoc = DescriptorDoc(
    descriptor = descriptor.name,
    name = name,
    rawName = rawName,
    version = version,
    rawVersion = rawVersion,
    apiVersion = apiVersion,
    main = main,
    depend = depend,
    softDepend = softDepend,
    loadBefore = loadBefore,
    loadAfter = loadAfter,
    provides = provides,
    libraries = libraries,
    load = load,
    foliaSupported = foliaSupported,
    skipLibrariesOnPaper = skipLibrariesOnPaper,
    nestedJars = nestedJars,
    deps = deps.map { it.toDoc() },
    loadErrors = loadErrors,
    warnings = warnings,
)

private fun MetaDep.toDoc(): MetaDepDoc = MetaDepDoc(
    target = target,
    kind = kind.name,
    versionPredicates = versionPredicates,
    order = order.name,
    phase = phase.name,
    side = side.name,
)

private fun InvalidDescriptor.toDoc(): InvalidDescriptorDoc = InvalidDescriptorDoc(descriptor.name, reason)

private fun BytecodeProfile.toDoc(): BytecodeDoc = BytecodeDoc(
    requiredJavaFeature = requiredJavaFeature,
    outerMaxMajor = outerMaxMajor,
    nestedMaxMajor = nestedMaxMajor,
    majorHistogram = majorHistogram.entries.sortedBy { it.key }.associate { it.key.toString() to it.value },
    multiRelease = multiRelease,
    versionedFeatures = versionedFeatures.sorted(),
    previewMajor = previewMajor,
    invalidClassEntries = invalidClassEntries,
    nms = NmsDoc(
        binaryRefs = nms.binaryRefs,
        versionedTokens = nms.versionedTokens.sorted(),
        reflectiveStrings = nms.reflectiveStrings,
        mappingsNamespace = nms.mappingsNamespace,
    ),
    notes = notes,
)

private fun CapabilityEvidence.toDoc(): CapabilityDoc = CapabilityDoc(capability.dbKey(), confidence.name, rule, detail)

private fun PackMcmetaResult.toDoc(): PackMetaDoc = when (this) {
    is PackMcmetaResult.Ok -> meta.toDoc()
    is PackMcmetaResult.Invalid -> PackMetaDoc(ok = false, reason = reason)
}

/** 원시 필드만 옮긴다 — modernDecl()/legacyDecl() 을 부르지 않는다. */
private fun PackMcmeta.toDoc(): PackMetaDoc = PackMetaDoc(
    ok = true,
    packFormat = packFormat?.text(),
    supportedFormats = supportedFormats?.toDoc(),
    minFormat = minFormat?.text(),
    maxFormat = maxFormat?.text(),
    overlays = overlays.map { o ->
        OverlayDoc(directory = o.directory, formats = o.formats?.toDoc(), minFormat = o.minFormat?.text(), maxFormat = o.maxFormat?.text())
    },
    notes = notes,
)

private fun PackMajorRange.toDoc(): PackRangeDoc = PackRangeDoc(min.text(), max.text())

private fun PackFormat.text(): String = toString()
