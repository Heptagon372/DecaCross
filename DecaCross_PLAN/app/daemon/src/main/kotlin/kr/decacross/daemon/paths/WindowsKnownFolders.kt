package kr.decacross.daemon.paths

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Windows 알려진 폴더 조회 (FFM, AE-18). `%USERPROFILE%\Documents` 를 그대로 쓰면 OneDrive 로 옮겨진 PC 에서
 * 비어 있는 폴더를 가리키므로 `SHGetKnownFolderPath` 를 쓴다 (research codebase-windows §2 프로브로 확인).
 *
 * # 불변식
 * - 던지지 않는다. Windows 가 아니거나 호출이 실패하면 null.
 * - 네이티브 메모리는 항상 `CoTaskMemFree` 로 돌려준다.
 * - 읽기 전용 — 폴더를 만들지 않는다.
 */
internal object WindowsKnownFolders {
    /** `FOLDERID_Documents` = `{FDD39AD0-238F-46AF-ADB4-6C85480369C7}` (AE-18). */
    private val DOCUMENTS_GUID_TAIL = byteArrayOf(
        0xAD.toByte(), 0xB4.toByte(), 0x6C, 0x85.toByte(), 0x48, 0x03, 0x69, 0xC7.toByte(),
    )

    /** 반환 문자열을 읽을 때 쓰는 상한 (MAX_PATH 확장 경로 32,767자 × UTF-16). */
    private const val MAX_PATH_BYTES: Long = 65_536

    /** 문서 폴더. 실패하면 null. */
    fun documents(): Path? {
        val text = knownFolderPath() ?: return null
        return try {
            Path.of(text).takeIf { it.isAbsolute }
        } catch (e: InvalidPathException) {
            null
        }
    }

    private fun knownFolderPath(): String? =
        try {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val shell32 = SymbolLookup.libraryLookup("shell32", arena)
                val ole32 = SymbolLookup.libraryLookup("ole32", arena)
                val getPath = linker.downcallHandle(
                    shell32.find("SHGetKnownFolderPath").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val free = linker.downcallHandle(
                    ole32.find("CoTaskMemFree").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                )
                val guid = arena.allocate(16)
                guid.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0xFDD39AD0.toInt())
                guid.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, 0x238F.toShort())
                guid.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, 0x46AF.toShort())
                for (i in DOCUMENTS_GUID_TAIL.indices) guid.set(ValueLayout.JAVA_BYTE, 8L + i, DOCUMENTS_GUID_TAIL[i])
                val out = arena.allocate(ValueLayout.ADDRESS)
                // ★ 다형 시그니처 호출: 뒤의 `as Int` 가 호출 서술자의 반환 타입이 된다 (없으면 Object 로 잡혀 실패)
                val hr = getPath.invoke(guid, 0, MemorySegment.NULL, out) as Int
                val raw = out.get(ValueLayout.ADDRESS, 0)
                try {
                    if (hr != 0 || raw.equals(MemorySegment.NULL)) {
                        null
                    } else {
                        raw.reinterpret(MAX_PATH_BYTES).getString(0, StandardCharsets.UTF_16LE).takeIf { it.isNotBlank() }
                    }
                } finally {
                    free.invoke(raw)
                }
            }
        } catch (e: Exception) {
            // 다른 OS·권한 문제·API 부재 — 문서 폴더를 모르는 것으로 본다 (호출자가 대체 경로를 쓴다)
            null
        } catch (e: LinkageError) {
            // FFM 을 쓸 수 없는 런타임 (--enable-native-access 없이 차단되는 미래 JDK 포함)
            null
        }
}
