package kr.decacross.daemon.testkit

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** 메모리 안에서 만드는 테스트 jar/zip. 레포에 바이너리를 두지 않는다. */
object TestJars {
    /**
     * Paperclip 모양의 서버 jar: 매니페스트 `Main-Class` + `META-INF/main-class` + 압축 텍스트 엔트리 + 무작위(비압축성) 엔트리.
     * [seed] 가 같으면 바이트가 같다.
     */
    fun serverJar(seed: Int = 1, randomEntryBytes: Int = 256 * 1024, withMainClass: Boolean = true): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (withMainClass) mainAttributes[Attributes.Name.MAIN_CLASS] = "io.papermc.paperclip.Main"
        }
        val bos = ByteArrayOutputStream()
        JarOutputStream(bos, manifest).use { jar ->
            if (withMainClass) put(jar, "META-INF/main-class", "org.bukkit.craftbukkit.Main".toByteArray())
            put(jar, "META-INF/download-context", "0".repeat(64).plus("\thttps://example.invalid/x.jar\tmojang_test.jar").toByteArray())
            put(jar, "version.json", """{"id":"test","java_version":21}""".repeat(50).toByteArray())
            put(jar, "META-INF/libraries/blob.bin", Random(seed).nextBytes(randomEntryBytes))
        }
        return bos.toByteArray()
    }

    /** 매니페스트 없는 평범한 zip. */
    fun plainZip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip -> entries.forEach { (name, bytes) -> put(zip, name, bytes) } }
        return bos.toByteArray()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(if (zip is JarOutputStream) JarEntry(name) else ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
