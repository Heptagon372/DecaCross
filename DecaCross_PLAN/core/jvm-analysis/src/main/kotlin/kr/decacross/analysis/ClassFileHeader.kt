package kr.decacross.analysis

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

// ── class 파일 헤더 직접 읽기 (JVMS §4.1, §4.4 — 설계 AE-7) ──────────────────
// ASM 을 쓰지 않는다: ASM 이 아직 모르는 최신 major(예: 72)도 읽어야 하고, 코드 영역은 필요 없다.

/** class 파일 매직 넘버. */
internal const val CLASS_MAGIC: Int = 0xCAFEBABE.toInt()

/**
 * class 파일의 헤더·상수 풀 요약.
 *
 * # 불변식
 * - 이름은 내부 이름(`a/b/C`)이다.
 * - [utf8] 은 상수 풀의 모든 Utf8 상수를 풀 순서대로 담는다.
 */
internal data class ClassHeader(
    val minor: Int,
    val major: Int,
    val access: Int,
    val thisName: String,
    /** `super_class == 0` 이면 null (`java/lang/Object`, `module-info`). */
    val superName: String?,
    val interfaces: List<String>,
    val utf8: List<String>,
)

/** 상수 풀 태그 (JVMS §4.4). */
private object ConstantTag {
    const val UTF8 = 1
    const val INTEGER = 3
    const val FLOAT = 4
    const val LONG = 5
    const val DOUBLE = 6
    const val CLASS = 7
    const val STRING = 8
    const val FIELD_REF = 9
    const val METHOD_REF = 10
    const val INTERFACE_METHOD_REF = 11
    const val NAME_AND_TYPE = 12
    const val METHOD_HANDLE = 15
    const val METHOD_TYPE = 16
    const val DYNAMIC = 17
    const val INVOKE_DYNAMIC = 18
    const val MODULE = 19
    const val PACKAGE = 20
}

/** 바이트 배열 위의 빅엔디언 커서. 범위를 넘으면 [IndexOutOfBoundsException]. */
private class ByteCursor(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    fun u1(): Int {
        if (position >= bytes.size) throw IndexOutOfBoundsException("class 파일 잘림 (offset $position)")
        return bytes[position++].toInt() and 0xFF
    }

    fun u2(): Int = (u1() shl 8) or u1()

    fun u4(): Int = (u2() shl 16) or u2()

    fun skip(count: Int) {
        if (count < 0 || position + count > bytes.size) throw IndexOutOfBoundsException("class 파일 잘림 (offset $position)")
        position += count
    }

    /** Utf8 상수 (u2 길이 + modified UTF-8). 잘못된 인코딩이면 [java.io.UTFDataFormatException]. */
    fun utf8(): String {
        val start = position
        val length = u2()
        skip(length)
        var ascii = true
        for (i in start + 2 until start + 2 + length) {
            if (bytes[i] < 0) {
                ascii = false
                break
            }
        }
        if (ascii) return String(bytes, start + 2, length, Charsets.ISO_8859_1)
        // modified UTF-8 은 readUTF 가 정확히 디코딩한다 (길이 접두사 포함 슬라이스)
        return DataInputStream(ByteArrayInputStream(bytes, start, length + 2)).readUTF()
    }
}

/**
 * class 파일 바이트에서 헤더·상수 풀·this/super/interfaces 를 읽는다.
 * 매직 불일치, 잘림, 모르는 상수 태그, 잘못된 modified UTF-8, 범위 밖 풀 인덱스면 null. 절대 던지지 않는다.
 */
internal fun readClassHeader(bytes: ByteArray): ClassHeader? = try {
    parseClassHeader(bytes)
} catch (e: IOException) {
    // UTFDataFormatException, EOFException
    null
} catch (e: IndexOutOfBoundsException) {
    null
} catch (e: NegativeArraySizeException) {
    null
}

private fun parseClassHeader(bytes: ByteArray): ClassHeader? {
    val cursor = ByteCursor(bytes)
    if (cursor.u4() != CLASS_MAGIC) return null
    val minor = cursor.u2()
    val major = cursor.u2()
    val poolCount = cursor.u2()
    val utf8ByIndex = arrayOfNulls<String>(poolCount)
    // Class 상수 → 이름 Utf8 인덱스 (0 = Class 상수 아님)
    val classNameIndex = IntArray(poolCount)
    val utf8 = ArrayList<String>()
    var index = 1
    while (index < poolCount) {
        when (cursor.u1()) {
            ConstantTag.UTF8 -> {
                val value = cursor.utf8()
                utf8ByIndex[index] = value
                utf8 += value
            }

            ConstantTag.INTEGER, ConstantTag.FLOAT -> cursor.skip(4)

            ConstantTag.LONG, ConstantTag.DOUBLE -> {
                cursor.skip(8)
                // 8바이트 상수는 풀 슬롯 2개를 차지한다
                index++
            }

            ConstantTag.CLASS -> classNameIndex[index] = cursor.u2()

            ConstantTag.STRING, ConstantTag.METHOD_TYPE, ConstantTag.MODULE, ConstantTag.PACKAGE -> cursor.skip(2)

            ConstantTag.FIELD_REF, ConstantTag.METHOD_REF, ConstantTag.INTERFACE_METHOD_REF,
            ConstantTag.NAME_AND_TYPE, ConstantTag.DYNAMIC, ConstantTag.INVOKE_DYNAMIC,
            -> cursor.skip(4)

            ConstantTag.METHOD_HANDLE -> cursor.skip(3)

            else -> return null
        }
        index++
    }

    fun className(poolIndex: Int): String? {
        val nameIndex = classNameIndex[poolIndex]
        if (nameIndex == 0) return null
        return utf8ByIndex[nameIndex]
    }

    val access = cursor.u2()
    val thisName = className(cursor.u2()) ?: return null
    val superIndex = cursor.u2()
    val superName = if (superIndex == 0) null else className(superIndex) ?: return null
    val interfaceCount = cursor.u2()
    val interfaces = ArrayList<String>(interfaceCount)
    repeat(interfaceCount) {
        interfaces += className(cursor.u2()) ?: return null
    }
    return ClassHeader(minor, major, access, thisName, superName, interfaces, utf8)
}
