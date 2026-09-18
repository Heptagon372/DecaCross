package kr.decacross.collector.sources.mojang

import kr.decacross.collector.http.Http
import kr.decacross.collector.http.HttpResult
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// ── HTTP Range 로 원격 zip(jar)의 중앙 디렉터리·엔트리 하나만 읽는다 (D11, SCP-13) ──────────────
// 기준 구현: RESEARCH_DIR/mojang/rangezip.py. 모든 정수는 little-endian.

/**
 * 원격 zip 의 End Of Central Directory 와 이미 받아 둔 꼬리.
 *
 * @property tail 파일 끝 [tail].size 바이트 (파일 오프셋 [tailOffset] 부터)
 */
internal data class Eocd(
    val url: String,
    val size: Long,
    val cdOffset: Long,
    val cdSize: Long,
    val entryCount: Long,
    val tail: ByteArray,
    val tailOffset: Long,
) {
    /** 중앙 디렉터리가 이미 받은 꼬리 안에 있다 (추가 요청 없음). */
    val fitsInTail: Boolean get() = cdOffset >= tailOffset
}

internal sealed interface EocdResult {
    data class Ok(val eocd: Eocd) : EocdResult

    data class Failed(val reason: String) : EocdResult
}

internal sealed interface ZipDirResult {
    data class Ok(val dir: CentralDirectory) : ZipDirResult

    data class Failed(val reason: String) : ZipDirResult

    /** 중앙 디렉터리가 상한보다 크다. 읽지 않았다. */
    data class TooLarge(val cdSize: Long) : ZipDirResult
}

internal data class CentralDirectory(
    val url: String,
    val size: Long,
    val cdOffset: Long,
    val cdSize: Long,
    val entries: Map<String, ZipEntryRef>,
    val fitsInTail: Boolean,
) {
    /** `data/` 로 시작하는 엔트리가 있다 (데이터팩 시대, D3). */
    val hasDataDir: Boolean get() = entries.keys.any { it.startsWith("data/") }
}

internal data class ZipEntryRef(
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

private const val EOCD_SIG = 0x06054b50L
private const val EOCD_LEN = 22
private const val ZIP64_LOCATOR_SIG = 0x07064b50L
private const val ZIP64_LOCATOR_LEN = 20
private const val ZIP64_EOCD_SIG = 0x06064b50L
private const val ZIP64_EOCD_LEN = 56
private const val CEN_SIG = 0x02014b50L
private const val CEN_LEN = 46
private const val LOC_SIG = 0x04034b50L
private const val LOC_LEN = 30
private const val LOCAL_HEADER_SLACK = 1024
private const val U16_MAX = 0xFFFFL
private const val U32_MAX = 0xFFFFFFFFL
private const val ZIP64_EXTRA_ID = 0x0001
private const val DIGITAL_SIGNATURE_SIG = 0x05054b50L
private const val COMPRESSED_SLACK = 65_536L

private fun u16(b: ByteArray, at: Int): Long = ((b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)).toLong()

private fun u32(b: ByteArray, at: Int): Long = u16(b, at) or (u16(b, at + 2) shl 16)

/** 부호 없는 64비트. 2^63 이상이면 음수가 되며 호출자가 범위 검사에서 거른다. */
private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)

private fun describe(r: HttpResult<*>): String = when (r) {
    is HttpResult.Ok -> "ok"
    is HttpResult.NotModified -> "HTTP 304"
    is HttpResult.Status -> "HTTP ${r.meta.status}"
    is HttpResult.Failure -> "${r.kind}: ${r.message}"
}

/**
 * 파일 끝 `min(size, tailBytes)` 바이트를 Range 요청 한 번으로 받아 EOCD(필요하면 ZIP64 EOCD)를 해석한다.
 * ZIP64 EOCD 레코드가 꼬리 밖에 있을 때만 요청이 하나 더 생긴다.
 *
 * # 불변식
 * - 응답의 `Content-Range` 총 길이가 [size] 와 다르면 실패 (매니페스트 정보와 다른 파일).
 * - 주석 안에 들어 있는 가짜 EOCD 서명은 주석 길이 검사로 건너뛴다.
 */
internal suspend fun readEocd(http: Http, url: String, size: Long, tailBytes: Int): EocdResult {
    if (size < EOCD_LEN || tailBytes < EOCD_LEN) return EocdResult.Failed("too small: size=$size tailBytes=$tailBytes")
    val tailLen = minOf(size, tailBytes.toLong()).toInt()
    val tailOffset = size - tailLen
    val r = http.getRange(url, tailOffset, tailLen)
    if (r !is HttpResult.Ok) return EocdResult.Failed("tail: ${describe(r)}")
    if (r.meta.totalLength != size) return EocdResult.Failed("size mismatch: total=${r.meta.totalLength} expected=$size")
    val tail = r.value
    if (tail.size != tailLen) return EocdResult.Failed("short tail: ${tail.size} != $tailLen")

    var at = -1
    var i = tail.size - EOCD_LEN
    while (i >= 0) {
        if (u32(tail, i) == EOCD_SIG && i + EOCD_LEN + u16(tail, i + 20) == tail.size.toLong()) {
            at = i
            break
        }
        i--
    }
    if (at < 0) return EocdResult.Failed("no EOCD in tail")

    var entries = u16(tail, at + 10)
    var cdSize = u32(tail, at + 12)
    var cdOffset = u32(tail, at + 16)
    if (entries == U16_MAX || cdSize == U32_MAX || cdOffset == U32_MAX) {
        val loc = at - ZIP64_LOCATOR_LEN
        // 로케이터가 없으면 (예: ZIP64 가 아닌 정확히 65535 엔트리) 32비트 값을 그대로 쓴다
        if (loc >= 0 && u32(tail, loc) == ZIP64_LOCATOR_SIG) {
            val z64Offset = u64(tail, loc + 8)
            if (z64Offset < 0 || z64Offset + ZIP64_EOCD_LEN > size) return EocdResult.Failed("bad zip64 offset $z64Offset")
            val rec: ByteArray = if (z64Offset >= tailOffset && z64Offset + ZIP64_EOCD_LEN <= size) {
                val from = (z64Offset - tailOffset).toInt()
                tail.copyOfRange(from, from + ZIP64_EOCD_LEN)
            } else {
                val rr = http.getRange(url, z64Offset, ZIP64_EOCD_LEN)
                if (rr !is HttpResult.Ok) return EocdResult.Failed("zip64 record: ${describe(rr)}")
                if (rr.value.size != ZIP64_EOCD_LEN) return EocdResult.Failed("short zip64 record")
                rr.value
            }
            if (u32(rec, 0) != ZIP64_EOCD_SIG) return EocdResult.Failed("bad zip64 EOCD signature")
            entries = u64(rec, 32)
            cdSize = u64(rec, 40)
            cdOffset = u64(rec, 48)
        }
    }
    if (cdOffset < 0 || cdSize < 0 || entries < 0 || cdOffset + cdSize > size) {
        return EocdResult.Failed("bad central directory bounds: offset=$cdOffset size=$cdSize file=$size")
    }
    return EocdResult.Ok(Eocd(url, size, cdOffset, cdSize, entries, tail, tailOffset))
}

/**
 * 중앙 디렉터리를 읽어 엔트리 표를 만든다. [Eocd.fitsInTail] 이면 요청 0회, 아니면 모자란 앞부분만 1회.
 */
internal suspend fun readCentralDirectory(http: Http, eocd: Eocd, maxCdBytes: Int): ZipDirResult {
    if (eocd.cdSize > maxCdBytes) return ZipDirResult.TooLarge(eocd.cdSize)
    val cdLen = eocd.cdSize.toInt()
    val cd: ByteArray = if (eocd.fitsInTail) {
        val from = eocd.cdOffset - eocd.tailOffset
        if (from + cdLen > eocd.tail.size) return ZipDirResult.Failed("central directory beyond tail")
        eocd.tail.copyOfRange(from.toInt(), from.toInt() + cdLen)
    } else if (eocd.cdOffset + cdLen <= eocd.tailOffset) {
        // 디렉터리 전체가 꼬리 앞에 있다 (디렉터리 뒤에 다른 데이터가 있는 드문 경우) → 정확히 그만큼만
        val r = http.getRange(eocd.url, eocd.cdOffset, cdLen)
        if (r !is HttpResult.Ok) return ZipDirResult.Failed("central directory: ${describe(r)}")
        if (r.value.size != cdLen) return ZipDirResult.Failed("short central directory")
        r.value
    } else {
        val missing = (eocd.tailOffset - eocd.cdOffset).toInt()
        val r = http.getRange(eocd.url, eocd.cdOffset, missing)
        if (r !is HttpResult.Ok) return ZipDirResult.Failed("central directory prefix: ${describe(r)}")
        if (r.value.size != missing) return ZipDirResult.Failed("short central directory prefix")
        (r.value + eocd.tail).copyOf(cdLen)
    }
    val entries = parseCentralDirectory(cd) ?: return ZipDirResult.Failed("malformed central directory")
    return ZipDirResult.Ok(CentralDirectory(eocd.url, eocd.size, eocd.cdOffset, eocd.cdSize, entries, eocd.fitsInTail))
}

/** 중앙 디렉터리 바이트 → name → 엔트리. 구조가 깨졌으면 null. */
private fun parseCentralDirectory(cd: ByteArray): Map<String, ZipEntryRef>? {
    val out = LinkedHashMap<String, ZipEntryRef>()
    var p = 0
    while (p + CEN_LEN <= cd.size) {
        val sig = u32(cd, p)
        // 중앙 디렉터리 뒤에 붙는 (드문) 디지털 서명 레코드에서 멈춘다
        if (sig == DIGITAL_SIGNATURE_SIG) return out
        if (sig != CEN_SIG) return null
        val method = u16(cd, p + 10).toInt()
        val crc = u32(cd, p + 16)
        var csize = u32(cd, p + 20)
        var usize = u32(cd, p + 24)
        val nameLen = u16(cd, p + 28).toInt()
        val extraLen = u16(cd, p + 30).toInt()
        val commentLen = u16(cd, p + 32).toInt()
        var lho = u32(cd, p + 42)
        val nameStart = p + CEN_LEN
        val extraStart = nameStart + nameLen
        val next = extraStart + extraLen + commentLen
        if (next > cd.size) return null
        val name = String(cd, nameStart, nameLen, Charsets.UTF_8)
        if (usize == U32_MAX || csize == U32_MAX || lho == U32_MAX) {
            var q = extraStart
            val extraEnd = extraStart + extraLen
            while (q + 4 <= extraEnd) {
                val id = u16(cd, q).toInt()
                val len = u16(cd, q + 2).toInt()
                val dataStart = q + 4
                if (dataStart + len > extraEnd) return null
                if (id == ZIP64_EXTRA_ID) {
                    var k = dataStart
                    fun nextU64(): Long? = if (k + 8 <= dataStart + len) u64(cd, k).also { k += 8 } else null
                    if (usize == U32_MAX) usize = nextU64() ?: return null
                    if (csize == U32_MAX) csize = nextU64() ?: return null
                    if (lho == U32_MAX) lho = nextU64() ?: return null
                }
                q = dataStart + len
            }
        }
        out[name] = ZipEntryRef(method, crc, csize, usize, lho)
        p = next
    }
    return if (p == cd.size || u32OrNull(cd, p) == DIGITAL_SIGNATURE_SIG) out else null
}

private fun u32OrNull(b: ByteArray, at: Int): Long? = if (at + 4 <= b.size) u32(b, at) else null

/**
 * 엔트리 하나를 Range 로 받아 풀고 CRC 를 검사한다. 로컬 헤더 + 1 KiB 를 먼저 받고 모자라면 나머지를 한 번 더 받는다.
 *
 * @return 내용. 저장(0)·deflate(8) 외 방식, CRC 불일치, [maxBytes] 초과, HTTP 실패는 전부 null.
 */
internal suspend fun readEntry(http: Http, url: String, e: ZipEntryRef, maxBytes: Int = 1 shl 20): ByteArray? {
    if (e.uncompressedSize < 0 || e.uncompressedSize > maxBytes) return null
    // 압축 크기는 원본보다 약간 클 수 있다 (저장 방식·압축 불가 데이터). Int 범위 안에서만 받는다
    if (e.compressedSize < 0 || e.compressedSize > maxBytes.toLong() + COMPRESSED_SLACK) return null
    if (e.method != 0 && e.method != 8) return null
    val head = (http.getRange(url, e.localHeaderOffset, LOC_LEN + LOCAL_HEADER_SLACK) as? HttpResult.Ok)?.value ?: return null
    if (head.size < LOC_LEN || u32(head, 0) != LOC_SIG) return null
    val dataStart = LOC_LEN + u16(head, 26).toInt() + u16(head, 28).toInt()
    val need = dataStart + e.compressedSize.toInt()
    val raw: ByteArray = if (need <= head.size) {
        head
    } else {
        val rest = (http.getRange(url, e.localHeaderOffset + head.size, need - head.size) as? HttpResult.Ok)?.value ?: return null
        head + rest
    }
    if (raw.size < need) return null
    val data = raw.copyOfRange(dataStart, need)
    val out = when (e.method) {
        0 -> data
        else -> inflate(data, maxBytes) ?: return null
    }
    if (out.size.toLong() != e.uncompressedSize || out.size > maxBytes) return null
    val crc = CRC32().apply { update(out) }.value
    return if (crc == e.crc) out else null
}

/** raw deflate 해제. [maxBytes] 를 넘거나 깨졌으면 null. */
private fun inflate(data: ByteArray, maxBytes: Int): ByteArray? {
    val inflater = Inflater(true)
    try {
        // nowrap 모드는 입력 끝에 더미 바이트 하나가 필요할 수 있다 (Inflater KDoc)
        inflater.setInput(data + 0.toByte())
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray()
    } catch (ex: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}
