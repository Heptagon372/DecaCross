package kr.decacross.collector.sources.mojang

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.compat.model.McOrdinal
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

/** 클래스패스 테스트 리소스 텍스트. */
internal fun resourceText(path: String): String {
    val stream = checkNotNull(MojangTestSupport::class.java.getResourceAsStream(path)) { "테스트 리소스 없음: $path" }
    return stream.use { it.readBytes().decodeToString() }
}

internal object MojangTestSupport {
    /**
     * `c2/manifest-trimmed-2026-09-17.json` 의 release/snapshot 항목 (url·sha1 없음 → JsonElement 로 읽는다).
     * old_alpha/old_beta 는 발급 대상이 아니므로 뺀다.
     */
    val manifestEntries: List<IssuerEntry> by lazy {
        val root = Json.parseToJsonElement(resourceText("/c2/manifest-trimmed-2026-09-17.json")).jsonObject
        root.getValue("versions").jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val type = o.getValue("type").jsonPrimitive.content
            if (type != "release" && type != "snapshot") return@mapNotNull null
            IssuerEntry(
                label = o.getValue("id").jsonPrimitive.content,
                isSnapshot = type == "snapshot",
                releasedAt = Instant.parse(o.getValue("releaseTime").jsonPrimitive.content),
            )
        }
    }

    /** `c2/mojang-jarmeta-releases-2026-09-17.json` 의 `releases` 객체 (label → 항목). */
    val jarMetaReleases: JsonObject by lazy {
        Json.parseToJsonElement(resourceText("/c2/mojang-jarmeta-releases-2026-09-17.json")).jsonObject.getValue("releases").jsonObject
    }
}

/** 계획 결과를 강제로 꺼낸다 (Aborted 면 테스트 실패). */
internal fun planned(existing: List<ExistingOrdinal>, entries: List<IssuerEntry>): OrdinalPlan =
    when (val r = OrdinalIssuer.plan(existing, entries)) {
        is PlanResult.Planned -> r.plan
        is PlanResult.Aborted -> throw AssertionError("예상치 못한 Aborted: ${r.reason}")
    }

internal fun Pair<IssuerEntry, McOrdinal>.toExisting(): ExistingOrdinal = ExistingOrdinal(first.label, second, first.isSnapshot, first.releasedAt)

/** 발급된 행을 기존 행으로 누적. */
internal fun List<ExistingOrdinal>.withIssued(issued: List<Pair<IssuerEntry, McOrdinal>>): List<ExistingOrdinal> = this + issued.map { it.toExisting() }

internal fun List<ExistingOrdinal>.labelToOrdinal(): Map<String, Int> = associate { it.label to it.ordinal.value }

private const val FIXED_ZIP_TIME_MS = 1_600_000_000_000L

/** 메모리 zip 한 개의 엔트리 정의. [stored] 면 STORED(0), 아니면 DEFLATED(8). */
internal data class ZipItem(val name: String, val bytes: ByteArray, val stored: Boolean = false)

/** [ZipOutputStream] 으로 메모리에서 zip 을 만든다. 디스크에 쓰지 않는다. */
internal fun buildZip(items: List<ZipItem>, comment: String? = null): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        if (comment != null) zip.setComment(comment)
        for (item in items) {
            val entry = ZipEntry(item.name)
            // 시각을 고정해 같은 입력이면 같은 바이트(같은 sha1)가 나오게 한다
            entry.time = FIXED_ZIP_TIME_MS
            if (item.stored) {
                entry.method = ZipEntry.STORED
                entry.size = item.bytes.size.toLong()
                entry.compressedSize = item.bytes.size.toLong()
                entry.crc = CRC32().apply { update(item.bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(item.bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
