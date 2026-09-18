package kr.decacross.collector.store

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 마이그레이션 결과. 예외 대신 sealed. */
sealed interface MigrationResult {
    /** [files] 이번에 적용한 파일, [skipped] 이미 적용돼 있던 파일 수 */
    data class Applied(val files: List<String>, val skipped: Int) : MigrationResult

    data class Failed(val reason: String) : MigrationResult
}

/**
 * `infra/supabase/migrations/NNNN_*.sql` 적용기 (개발 DB 또는 `--migrate` 일 때만 쓴다. Supabase 운영은 Supabase CLI).
 *
 * # 불변식
 * - 이력은 `decacross_schema_migrations(filename, sha256, applied_at)`.
 * - 체크섬은 **정규화한 텍스트**(UTF-8, BOM 제거, CRLF → LF)의 SHA-256 이다 — `core.autocrlf` 로 바이트가 달라져도 같다.
 * - 이미 적용한 파일이 바뀌었거나 사라졌으면 실패한다 (append-only).
 * - 마지막 적용분보다 앞 번호인 미적용 파일: `allowOutOfOrder` 면 WARN 후 적용, 아니면 실패.
 * - 트랜잭션 안에서 돌 수 없는 문(`concurrently`, `vacuum`, `alter system`)이 든 파일은 거부한다.
 * - 파일마다 한 트랜잭션: 본문 실행 → 이력 기록 → commit.
 */
class MigrationRunner(private val dir: Path) {
    fun migrate(conn: Connection, allowOutOfOrder: Boolean): MigrationResult {
        val files = try {
            listMigrationFiles()
        } catch (e: IOException) {
            return MigrationResult.Failed("마이그레이션 디렉터리를 읽을 수 없다: $dir ($e)")
        }
        val invalid = files.filterNot { FILE_NAME.matches(it.name) }
        if (invalid.isNotEmpty()) {
            return MigrationResult.Failed("마이그레이션 파일 이름 규칙(NNNN_소문자_숫자.sql) 위반: ${invalid.joinToString { it.name }}")
        }
        val texts = LinkedHashMap<String, String>()
        try {
            for (f in files.sortedBy { it.name }) texts[f.name] = normalize(Files.readAllBytes(f))
        } catch (e: IOException) {
            return MigrationResult.Failed("마이그레이션 파일을 읽을 수 없다: $e")
        }

        return try {
            conn.createStatement().use { it.execute(CREATE_HISTORY) }
            lockAndApply(conn, texts, allowOutOfOrder)
        } catch (e: SQLException) {
            MigrationResult.Failed("마이그레이션 실행 오류: ${e.message}")
        }
    }

    private fun lockAndApply(conn: Connection, texts: Map<String, String>, allowOutOfOrder: Boolean): MigrationResult {
        conn.createStatement().use { it.executeQuery("select pg_advisory_lock(hashtext('$MIGRATION_LOCK_KEY'))").close() }
        try {
            return apply(conn, texts, allowOutOfOrder)
        } finally {
            try {
                conn.createStatement().use { it.executeQuery("select pg_advisory_unlock(hashtext('$MIGRATION_LOCK_KEY'))").close() }
            } catch (e: SQLException) {
                log.warn("마이그레이션 락 해제 실패 (연결을 닫으면 풀린다): {}", e.toString())
            }
        }
    }

    private fun apply(conn: Connection, texts: Map<String, String>, allowOutOfOrder: Boolean): MigrationResult {
        val applied = conn.prepared("select filename, sha256 from decacross_schema_migrations order by filename") { ps ->
            ps.executeQuery().mapRows { rs -> rs.getString(1) to rs.getString(2) }.toMap()
        }
        for ((name, sha) in applied) {
            val text = texts[name] ?: return MigrationResult.Failed("적용된 마이그레이션 파일이 없다: $name")
            if (checksum(text) != sha) return MigrationResult.Failed("적용된 마이그레이션이 변경됨: $name")
        }
        val pending = texts.keys.filter { it !in applied }
        val maxApplied = applied.keys.maxOrNull()
        if (maxApplied != null) {
            val outOfOrder = pending.filter { it < maxApplied }
            if (outOfOrder.isNotEmpty()) {
                if (!allowOutOfOrder) {
                    return MigrationResult.Failed("마지막 적용분($maxApplied)보다 앞 번호인 미적용 마이그레이션: ${outOfOrder.joinToString()}")
                }
                log.warn("순서가 어긋난 마이그레이션을 개발 DB 에 적용한다 (마지막 적용분 {}): {}", maxApplied, outOfOrder.joinToString())
            }
        }
        for (name in pending) {
            val text = texts.getValue(name)
            if (FORBIDDEN.containsMatchIn(text)) {
                return MigrationResult.Failed("트랜잭션 안에서 실행할 수 없는 문(concurrently / vacuum / alter system)이 있다: $name")
            }
        }
        val done = ArrayList<String>()
        for (name in pending) {
            val text = texts.getValue(name)
            try {
                conn.inTransaction { tx ->
                    tx.createStatement().use { it.execute(text) }
                    tx.prepared("insert into decacross_schema_migrations (filename, sha256) values (?, ?)") { ps ->
                        ps.setString(1, name)
                        ps.setString(2, checksum(text))
                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                return MigrationResult.Failed("마이그레이션 적용 실패: $name — ${e.message} (앞서 적용: ${done.joinToString().ifEmpty { "없음" }})")
            }
            log.info("마이그레이션 적용: {}", name)
            done += name
        }
        return MigrationResult.Applied(done, skipped = applied.size)
    }

    private fun listMigrationFiles(): List<Path> {
        if (!dir.isDirectory()) throw IOException("디렉터리가 아니다: $dir")
        return Files.list(dir).use { s -> s.filter { it.isRegularFile() && it.name.endsWith(".sql", ignoreCase = true) }.toList() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MigrationRunner::class.java)

        const val HISTORY_TABLE: String = "decacross_schema_migrations"
        const val SYSTEM_PROPERTY: String = "decacross.migrations.dir"
        const val ENV_VAR: String = "DECACROSS_MIGRATIONS_DIR"
        private const val MIGRATION_LOCK_KEY = "decacross.migrations"

        private const val CREATE_HISTORY =
            "create table if not exists decacross_schema_migrations (filename text primary key, sha256 text not null, applied_at timestamptz not null default now())"

        private val FILE_NAME = Regex("""^\d{4}_[a-z0-9_]+\.sql$""")
        private val FORBIDDEN = Regex("""\bconcurrently\b|\bvacuum\b|\balter\s+system\b""", RegexOption.IGNORE_CASE)

        /** UTF-8 디코드 → BOM 제거 → CRLF 를 LF 로. */
        fun normalize(bytes: ByteArray): String = bytes.decodeToString().removePrefix("﻿").replace("\r\n", "\n")

        /** 정규화 텍스트의 SHA-256 (소문자 hex). */
        fun checksum(normalizedText: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalizedText.encodeToByteArray()))

        /**
         * 마이그레이션 디렉터리 찾기: 시스템 속성 `decacross.migrations.dir` → 환경변수 `DECACROSS_MIGRATIONS_DIR`
         * → `user.dir` 에서 위로 올라가며 `infra/supabase/migrations`. 못 찾으면 null.
         */
        fun locate(
            systemProperty: String? = System.getProperty(SYSTEM_PROPERTY),
            env: String? = System.getenv(ENV_VAR),
            userDir: String? = System.getProperty("user.dir"),
        ): Path? {
            for (candidate in listOf(systemProperty, env)) {
                val p = candidate?.takeIf { it.isNotBlank() }?.let(::pathOrNull) ?: continue
                if (p.isDirectory()) return p
            }
            var cur = userDir?.let(::pathOrNull)?.toAbsolutePath()
            while (cur != null) {
                val p = cur.resolve("infra").resolve("supabase").resolve("migrations")
                if (p.isDirectory()) return p
                cur = cur.parent
            }
            return null
        }

        private fun pathOrNull(s: String): Path? = try {
            Path.of(s.trim())
        } catch (e: InvalidPathException) {
            null
        }
    }
}
