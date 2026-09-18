package kr.decacross.analysis

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// ── zip 공통 규칙 (설계 §7.2) ─────────────────────────────────────────────
// 항목별·호출별 크기 상한과 1단계 중첩 아카이브(.jar / .jarinjar) 순회를 한곳에 둔다.
// jar 를 zip 으로 열 수 없으면 IOException(ZipException) 이 그대로 전파된다 — 변환은 analyzeJar 몫 (D55).

/** descriptor(plugin.yml 등)·pack.mcmeta·MANIFEST.MF 한 항목의 비압축 상한 (1 MiB). */
internal const val DESCRIPTOR_MAX_BYTES: Int = 1 shl 20

/** `.class` 한 항목의 비압축 상한 (16 MiB). */
internal const val CLASS_MAX_BYTES: Int = 16 shl 20

/** 중첩 아카이브 한 항목의 비압축 상한 (64 MiB). */
internal const val NESTED_ARCHIVE_MAX_BYTES: Int = 64 shl 20

/** 호출 한 번에 읽는 비압축 총량 상한 (512 MiB). */
internal const val TOTAL_MAX_BYTES: Long = 512L shl 20

/** 호출 한 번에 순회하는 중첩 아카이브 항목 수 상한. */
internal const val NESTED_ENTRIES_MAX: Int = 100_000

/** 상한 초과로 건너뛴 항목 note 의 최대 개수 (악성 jar 가 note 를 폭증시키지 않게). */
private const val SKIP_NOTES_MAX: Int = 10

/** UTF-8 BOM 문자. */
private const val BOM: Char = '\uFEFF'

/**
 * jar 를 [ZipFile] 로 열어 [block] 을 실행하고 반드시 닫는다 (반환 시점에 파일 핸들을 쥐지 않는다).
 *
 * @throws IOException zip 으로 열 수 없을 때.
 */
internal inline fun <T> withZipFile(jar: Path, block: (ZipFile) -> T): T = ZipFile(jar.toFile()).use(block)

/** descriptor 류 텍스트 디코딩: UTF-8, 선행 BOM(U+FEFF) 제거. */
internal fun decodeDescriptorText(bytes: ByteArray): String {
    val text = String(bytes, Charsets.UTF_8)
    return if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text
}

/**
 * [cap] 바이트까지만 읽는다. 스트림이 [cap] 을 넘으면 null (넘친 내용은 읽지 않는다).
 * zip 중앙 디렉터리의 크기 값은 거짓일 수 있으므로 항상 실제로 센다.
 */
internal fun readAtMost(input: InputStream, cap: Int): ByteArray? {
    val bytes = input.readNBytes(cap)
    if (bytes.size < cap) return bytes
    return if (input.read() == -1) bytes else null
}

/** 로더·분석기가 삼키면 안 되는 취소 예외는 다시 던진다 (CLAUDE.md). */
internal fun rethrowIfCancellation(e: Throwable) {
    if (e is CancellationException) throw e
}

/** 루트 항목 하나를 상한 안에서 읽은 결과. */
internal sealed interface RootEntryRead {
    /** 항목이 없다 (디렉터리 항목도 없는 것으로 본다). */
    data object Absent : RootEntryRead

    /** 항목은 있지만 상한을 넘었다. 내용은 읽지 않았다. */
    data object TooLarge : RootEntryRead

    /** 상한 안에서 읽은 비압축 바이트. */
    class Bytes(val bytes: ByteArray) : RootEntryRead
}

/**
 * 외부 아카이브의 [name] 항목을 [cap] 안에서 읽는다.
 *
 * @throws IOException 항목 데이터가 손상됐을 때.
 */
internal fun ZipFile.readRootEntry(name: String, cap: Int): RootEntryRead {
    val entry = getEntry(name) ?: return RootEntryRead.Absent
    // getEntry 는 "name/" 디렉터리 항목도 돌려줄 수 있다
    if (entry.isDirectory || entry.name != name) return RootEntryRead.Absent
    if (entry.size > cap) return RootEntryRead.TooLarge
    val bytes = getInputStream(entry).use { readAtMost(it, cap) } ?: return RootEntryRead.TooLarge
    return RootEntryRead.Bytes(bytes)
}

/**
 * zip 하나를 한 번 순회하는 동안의 상한 회계.
 *
 * # 불변식
 * - 항목별 상한을 넘은 항목은 건너뛰고 note 를 남긴다 (최대 [SKIP_NOTES_MAX] 개).
 * - 총량 상한·중첩 항목 수 상한에 걸리면 [stopped] 가 true 가 되고 이후 읽기는 전부 null 이다.
 * - 중첩 아카이브는 1단계만 연다. 더 깊은 `.jar` 는 호출자가 고르지 않는다.
 */
internal class ZipWalker(private val zip: ZipFile, private val notes: MutableList<String>) {
    private var totalRead: Long = 0
    private var nestedEntries: Int = 0
    private var skipNotes: Int = 0

    /** 호출 전체 상한에 걸려 순회를 멈췄는가. */
    var stopped: Boolean = false
        private set

    /** 외부 아카이브의 항목 목록 (중앙 디렉터리 순서). */
    fun entries(): List<ZipEntry> = zip.entries().toList()

    /**
     * 외부 항목 하나를 [cap] 안에서 읽는다. 상한 초과면 note 후 null, 멈춘 상태면 null.
     *
     * @throws IOException 항목 데이터가 손상됐을 때.
     */
    fun read(entry: ZipEntry, cap: Int): ByteArray? {
        if (stopped) return null
        if (entry.size > cap) {
            noteSkipped(entry.name, cap)
            return null
        }
        val bytes = zip.getInputStream(entry).use { readAtMost(it, cap) }
        if (bytes == null) {
            noteSkipped(entry.name, cap)
            return null
        }
        return if (charge(bytes.size)) bytes else null
    }

    /**
     * 1단계 중첩 아카이브의 항목을 순회한다. [archive] 가 `PK` 로 시작하지 않으면 조용히 건너뛴다.
     * [select] 가 항목 이름에 대해 상한(Int)을 주면 읽어서 [consume] 에 넘기고, null 이면 건너뛴다.
     * 중첩 아카이브 자체가 깨졌으면 note 를 남기고 거기서 멈춘다 (외부 jar 분석은 계속).
     */
    fun forEachNestedEntry(
        archiveName: String,
        archive: ByteArray,
        select: (String) -> Int?,
        consume: (String, ByteArray) -> Unit,
    ) {
        if (stopped || !looksLikeZip(archive)) return
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
                while (!stopped) {
                    val entry = zis.nextEntry ?: break
                    nestedEntries++
                    if (nestedEntries > NESTED_ENTRIES_MAX) {
                        stop("중첩 아카이브 항목 수 상한($NESTED_ENTRIES_MAX) 초과 — 순회 중단")
                        break
                    }
                    if (entry.isDirectory) continue
                    val cap = select(entry.name) ?: continue
                    val bytes = readAtMost(zis, cap)
                    if (bytes == null) {
                        noteSkipped("$archiveName!/${entry.name}", cap)
                        continue
                    }
                    if (!charge(bytes.size)) break
                    consume(entry.name, bytes)
                }
            }
        } catch (e: IOException) {
            notes += "중첩 아카이브를 끝까지 읽지 못함: $archiveName (${e.message})"
        } catch (e: IllegalArgumentException) {
            // ZipInputStream 은 UTF-8 이 아닌 항목 이름에 IllegalArgumentException 을 던진다
            notes += "중첩 아카이브를 끝까지 읽지 못함: $archiveName (${e.message})"
        }
    }

    private fun charge(size: Int): Boolean {
        totalRead += size
        if (totalRead > TOTAL_MAX_BYTES) {
            stop("비압축 총량 상한(${TOTAL_MAX_BYTES / (1 shl 20)} MiB) 초과 — 순회 중단")
            return false
        }
        return true
    }

    private fun stop(note: String) {
        if (!stopped) notes += note
        stopped = true
    }

    private fun noteSkipped(name: String, cap: Int) {
        skipNotes++
        when {
            skipNotes <= SKIP_NOTES_MAX -> notes += "항목 크기 상한(${cap / 1024} KiB) 초과로 건너뜀: $name"
            skipNotes == SKIP_NOTES_MAX + 1 -> notes += "상한 초과 항목이 더 있음 — 이후 note 생략"
        }
    }

    companion object {
        private fun looksLikeZip(bytes: ByteArray): Boolean =
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }
}

/** `.jar` / `.jarinjar` 이름이고 `META-INF/versions/` 아래가 아니면 1단계 중첩 아카이브 후보다. */
internal fun isNestedArchiveName(name: String): Boolean =
    (name.endsWith(".jar") || name.endsWith(".jarinjar")) && !name.startsWith("META-INF/versions/")

/**
 * 외부 jar 와 1단계 중첩 아카이브의 `.class` 항목을 순회한다. 더 깊은 중첩 아카이브는 무시한다.
 *
 * @param selectOuter 외부 `.class` 항목 이름 → 바이트를 읽을지. 읽지 않을 항목의 부수 기록도 여기서 한다.
 * @param selectNested 중첩 아카이브 안 `.class` 항목 이름 → 바이트를 읽을지.
 * @param onClass (중첩 아카이브 이름 — 외부면 null, 항목 이름, 바이트)
 * @throws IOException 외부 항목 데이터가 손상됐을 때.
 */
internal fun ZipWalker.forEachClass(
    selectOuter: (String) -> Boolean,
    selectNested: (String) -> Boolean,
    onClass: (archive: String?, entryName: String, bytes: ByteArray) -> Unit,
) {
    for (entry in entries()) {
        if (stopped) return
        if (entry.isDirectory) continue
        val name = entry.name
        if (name.endsWith(".class")) {
            if (!selectOuter(name)) continue
            val bytes = read(entry, CLASS_MAX_BYTES) ?: continue
            onClass(null, name, bytes)
        } else if (isNestedArchiveName(name)) {
            val archive = read(entry, NESTED_ARCHIVE_MAX_BYTES) ?: continue
            forEachNestedEntry(
                archiveName = name,
                archive = archive,
                select = { inner -> if (inner.endsWith(".class") && selectNested(inner)) CLASS_MAX_BYTES else null },
                consume = { inner, bytes -> onClass(name, inner, bytes) },
            )
        }
    }
}
