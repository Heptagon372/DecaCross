package kr.decacross.daemon.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * UI 토큰 (명세 §7.1). 데몬이 기동 시 `%LOCALAPPDATA%/Decacross/ui.token` 에 랜덤 토큰을 쓴다.
 * UI/CLI 가 읽어 `Authorization: Bearer` 로 보낸다. 브리지 라우트(08)는 이 토큰을 쓰지 않는다 — 별도 라우팅 트리.
 */
object UiToken {
    const val FILE_NAME = "ui.token"

    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 새 토큰을 만들어 파일에 쓴다 (권한 600 — POSIX 에서만 가능, Windows 는 사용자 프로필 ACL 에 의존). */
    fun writeNew(appData: Path): String {
        Files.createDirectories(appData)
        val token = generate()
        val f = appData.resolve(FILE_NAME)
        Files.writeString(f, token)
        runCatching { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")) }
        return token
    }

    fun read(appData: Path): String? =
        appData.resolve(FILE_NAME).takeIf(Files::isRegularFile)?.let { Files.readString(it).trim() }?.takeIf { it.isNotEmpty() }
}

/** `Authorization: Bearer <token>` 헤더 검사. 상수 시간 비교. */
fun bearerMatches(header: String?, token: String): Boolean {
    if (header == null || !header.startsWith("Bearer ")) return false
    val presented = header.removePrefix("Bearer ").trim()
    if (presented.length != token.length) return false
    var diff = 0
    for (i in token.indices) diff = diff or (presented[i].code xor token[i].code)
    return diff == 0
}
