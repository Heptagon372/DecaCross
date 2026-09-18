package kr.decacross.collector.sources.mojang

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** §9.2 RemoteZip: 메모리 zip 을 FakeHttp Range 로 읽는다. */
class RemoteZipTest {
    private val dir = newTempDir()
    private val url = "https://data.test/jar/server.jar"

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun httpFor(bytes: ByteArray) = FakeHttp(dir).on(url, FakeResponse.Body(bytes))

    private suspend fun eocdOf(http: FakeHttp, bytes: ByteArray, tail: Int = 64 * 1024 + 22): Eocd {
        val r = readEocd(http, url, bytes.size.toLong(), tail)
        assertIs<EocdResult.Ok>(r, "EOCD: $r")
        return r.eocd
    }

    private suspend fun dirOf(http: FakeHttp, eocd: Eocd, max: Int = 16 * 1024 * 1024): CentralDirectory {
        val r = readCentralDirectory(http, eocd, max)
        assertIs<ZipDirResult.Ok>(r, "central directory: $r")
        return r.dir
    }

    @Test
    fun storedAndDeflated_fromTail() = runTest {
        val versionJson = """{"id":"26.3","pack_version":{"resource_major":97,"resource_minor":1,"data_major":121,"data_minor":0}}""".encodeToByteArray()
        val packMcmeta = """{"pack":{"pack_format":4,"description":"x"}}""".repeat(50).encodeToByteArray()
        // 로컬 헤더 + 1 KiB 보다 큰 저장 엔트리 → 나머지를 한 번 더 받는 경로
        val big = Random(7).nextBytes(5_000)
        val zip = buildZip(
            listOf(
                ZipItem("version.json", versionJson, stored = true),
                ZipItem("pack.mcmeta", packMcmeta),
                ZipItem("data/minecraft/tags/x.json", "{}".encodeToByteArray()),
                ZipItem("big.bin", big, stored = true),
            ),
        )
        val http = httpFor(zip)
        val eocd = eocdOf(http, zip)
        assertTrue(eocd.fitsInTail)
        assertEquals(4, eocd.entryCount)
        val before = http.requests.size
        val cd = dirOf(http, eocd)
        assertEquals(before, http.requests.size, "꼬리 안의 디렉터리는 추가 요청이 없다")
        assertTrue(cd.hasDataDir)
        assertEquals(0, cd.entries.getValue("version.json").method)
        assertEquals(8, cd.entries.getValue("pack.mcmeta").method)

        assertContentEquals(versionJson, readEntry(http, url, cd.entries.getValue("version.json")))
        assertContentEquals(packMcmeta, readEntry(http, url, cd.entries.getValue("pack.mcmeta")))
        val n = http.requests.size
        assertContentEquals(big, readEntry(http, url, cd.entries.getValue("big.bin")))
        assertEquals(n + 2, http.requests.size, "헤더+1KiB 뒤 나머지 1회")
        // maxBytes 초과 → null
        assertNull(readEntry(http, url, cd.entries.getValue("big.bin"), maxBytes = 1_000))
    }

    @Test
    fun eocdSignatureInsideComment_skipped() = runTest {
        // 주석 안에 22바이트 가짜 EOCD: 서명 PK\5\6, 엔트리 7개, 디렉터리 16바이트 @0, 주석 길이 99 (실제로 남은 4바이트와 맞지 않음).
        // 제어 문자는 화면에 보이지 않으므로 반드시 유니코드 이스케이프로 쓴다
        val fakeEocd = "PK\u0005\u0006" + "\u0000\u0000\u0000\u0000" + "\u0007\u0000\u0007\u0000" +
            "\u0010\u0000\u0000\u0000" + "\u0000\u0000\u0000\u0000" + "\u0063\u0000"
        val zip = buildZip(listOf(ZipItem("version.json", "{}".encodeToByteArray())), comment = "comment " + fakeEocd + "tail")

        // 벡터가 실제로 물리는지: 주석 길이 검사 없이 뒤에서 처음 만나는 서명은 가짜 EOCD(엔트리 7)다
        val naive = (zip.size - 22 downTo 0).first { i ->
            zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte() && zip[i + 2] == 0x05.toByte() && zip[i + 3] == 0x06.toByte()
        }
        assertEquals(zip.size - "tail".length - 22, naive, "가짜 EOCD 가 주석 안에 있어야 한다")
        assertEquals(7, u16(zip, naive + 10))

        val http = httpFor(zip)
        val eocd = eocdOf(http, zip)
        assertEquals(1, eocd.entryCount)
        assertTrue(eocd.cdOffset > 0 && eocd.cdSize > 16, "진짜 EOCD: offset=${eocd.cdOffset} size=${eocd.cdSize}")
        val cd = dirOf(http, eocd)
        assertEquals(setOf("version.json"), cd.entries.keys)
        assertContentEquals("{}".encodeToByteArray(), readEntry(http, url, cd.entries.getValue("version.json")))
    }

    @Test
    fun exactly65535Entries_noZip64Locator_uses32bit() = runTest {
        // ZipOutputStream 은 65535 개부터 ZIP64 를 쓰므로 직접 만든다 (ZIP64 레코드·로케이터 없음)
        val names = (0 until 65_535).map { "e$it" }
        val zip = rawStoredZip(names, payloadFor = { if (it == "e123") "hello".encodeToByteArray() else ByteArray(0) })
        val http = httpFor(zip)
        val eocd = eocdOf(http, zip)
        assertEquals(65_535L, eocd.entryCount)
        val cd = dirOf(http, eocd)
        assertEquals(65_535, cd.entries.size)
        assertContentEquals("hello".encodeToByteArray(), readEntry(http, url, cd.entries.getValue("e123")))
    }

    @Test
    fun cdBeyondTail_fetchesPrefix() = runTest {
        val zip = buildZip((0 until 3_000).map { ZipItem("dir/entry-$it.txt", "v$it".encodeToByteArray()) })
        val http = httpFor(zip)
        val eocd = eocdOf(http, zip, tail = 1_024)
        assertFalse(eocd.fitsInTail)
        assertEquals(1, http.requests.size)
        val cd = dirOf(http, eocd)
        assertEquals(3_000, cd.entries.size)
        assertEquals(2, http.requests.size)
        val prefix = http.requests.last()
        assertEquals(eocd.cdOffset, prefix.range?.first)
        assertEquals(eocd.tailOffset - 1, prefix.range?.last)
        assertContentEquals("v2999".encodeToByteArray(), readEntry(http, url, cd.entries.getValue("dir/entry-2999.txt")))
    }

    @Test
    fun crcMismatch_null() = runTest {
        val payload = "0123456789abcdef".encodeToByteArray()
        val zip = buildZip(listOf(ZipItem("version.json", payload, stored = true)))
        val http0 = httpFor(zip)
        val ref = dirOf(http0, eocdOf(http0, zip)).entries.getValue("version.json")
        val corrupt = zip.copyOf()
        val lho = ref.localHeaderOffset.toInt()
        val dataStart = lho + 30 + u16(corrupt, lho + 26) + u16(corrupt, lho + 28)
        corrupt[dataStart + 3] = (corrupt[dataStart + 3].toInt() xor 0x01).toByte()
        val http = httpFor(corrupt)
        val entry = dirOf(http, eocdOf(http, corrupt)).entries.getValue("version.json")
        assertNull(readEntry(http, url, entry))
        assertNotNull(readEntry(http0, url, ref))
    }

    @Test
    fun totalLengthMismatch_failed() = runTest {
        val zip = buildZip(listOf(ZipItem("version.json", "{}".encodeToByteArray())))
        val http = httpFor(zip)
        val r = readEocd(http, url, zip.size.toLong() - 10, 64 * 1024)
        assertIs<EocdResult.Failed>(r)
        assertTrue(r.reason.contains("size mismatch"), r.reason)
        // 서버가 Range 를 실패시키면 Failed (던지지 않는다)
        val failing = FakeHttp(dir).on(url, FakeResponse.Status(503))
        assertIs<EocdResult.Failed>(readEocd(failing, url, zip.size.toLong(), 64 * 1024))
    }

    @Test
    fun cdTooLarge() = runTest {
        val zip = buildZip((0 until 50).map { ZipItem("f$it", ByteArray(0)) })
        val http = httpFor(zip)
        val eocd = eocdOf(http, zip)
        val n = http.requests.size
        val r = readCentralDirectory(http, eocd, maxCdBytes = 100)
        assertIs<ZipDirResult.TooLarge>(r)
        assertEquals(eocd.cdSize, r.cdSize)
        assertEquals(n, http.requests.size)
    }

    @Test
    fun zip64_manyEntries() = runTest {
        val mark = TimeSource.Monotonic.markNow()
        val zip = buildZip((0 until 70_000).map { ZipItem("z$it", ByteArray(0), stored = true) } + ZipItem("version.json", "{\"a\":1}".encodeToByteArray()))
        val http = httpFor(zip)
        val eocd = eocdOf(http, zip)
        assertEquals(70_001L, eocd.entryCount)
        val cd = dirOf(http, eocd)
        assertEquals(70_001, cd.entries.size)
        assertContentEquals("{\"a\":1}".encodeToByteArray(), readEntry(http, url, cd.entries.getValue("version.json")))
        assertTrue(mark.elapsedNow().inWholeMilliseconds <= 5_000, "ZIP64 70,000 엔트리: ${mark.elapsedNow()}")
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** STORED 엔트리만 있는 zip 을 직접 쓴다. EOCD 는 32비트 값 그대로 (ZIP64 없음). */
    private fun rawStoredZip(names: List<String>, payloadFor: (String) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        for (name in names) {
            val data = payloadFor(name)
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(data) }.value
            val lho = out.size()
            out.le32(0x04034b50)
            out.le16(10)
            out.le16(0)
            out.le16(0)
            out.le16(0)
            out.le16(0)
            out.le32(crc)
            out.le32(data.size.toLong())
            out.le32(data.size.toLong())
            out.le16(nameBytes.size)
            out.le16(0)
            out.write(nameBytes)
            out.write(data)

            cd.le32(0x02014b50)
            cd.le16(20)
            cd.le16(10)
            cd.le16(0)
            cd.le16(0)
            cd.le16(0)
            cd.le16(0)
            cd.le32(crc)
            cd.le32(data.size.toLong())
            cd.le32(data.size.toLong())
            cd.le16(nameBytes.size)
            cd.le16(0)
            cd.le16(0)
            cd.le16(0)
            cd.le16(0)
            cd.le32(0)
            cd.le32(lho.toLong())
            cd.write(nameBytes)
        }
        val cdOffset = out.size()
        val cdBytes = cd.toByteArray()
        out.write(cdBytes)
        out.le32(0x06054b50)
        out.le16(0)
        out.le16(0)
        out.le16(names.size)
        out.le16(names.size)
        out.le32(cdBytes.size.toLong())
        out.le32(cdOffset.toLong())
        out.le16(0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Long) {
        le16((v and 0xFFFF).toInt())
        le16(((v ushr 16) and 0xFFFF).toInt())
    }
}
