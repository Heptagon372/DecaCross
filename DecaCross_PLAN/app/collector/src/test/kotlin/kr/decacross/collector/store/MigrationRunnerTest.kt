package kr.decacross.collector.store

import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationRunnerTest {
    private val tmp = newTempDir("decacross-migrations-")

    @BeforeTest
    fun setUp() {
        TestPg.resetSchema()
    }

    @AfterTest
    fun tearDown() = deleteTree(tmp)

    private fun migrate(dir: Path, allowOutOfOrder: Boolean = false): MigrationResult =
        TestPg.connection().use { MigrationRunner(dir).migrate(it, allowOutOfOrder) }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): List<T> = TestPg.connection().use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).mapRows(map) }
    }

    private fun write(dir: Path, name: String, text: String): Path {
        Files.createDirectories(dir)
        return Files.write(dir.resolve(name), text.encodeToByteArray())
    }

    @Test
    fun appliesAll_idempotent_recordsChecksums() {
        val dir = TestPg.migrationsDir()
        val expected = Files.list(dir).use { s -> s.map { it.name }.filter { it.endsWith(".sql") }.sorted().toList() }
        assertTrue(expected.containsAll(listOf("0001_base_versions.sql", "0002_content.sql", "0003_compat_facts.sql", "0008_collector_runtime_state.sql")))

        val first = assertIs<MigrationResult.Applied>(migrate(dir))
        assertEquals(expected, first.files)
        assertEquals(0, first.skipped)

        val history = query("select filename, sha256 from decacross_schema_migrations order by filename") { it.getString(1) to it.getString(2) }
        assertEquals(expected, history.map { it.first })
        for ((name, sha) in history) {
            assertEquals(MigrationRunner.checksum(MigrationRunner.normalize(Files.readAllBytes(dir.resolve(name)))), sha, name)
        }

        val second = assertIs<MigrationResult.Applied>(migrate(dir))
        assertEquals(emptyList(), second.files)
        assertEquals(expected.size, second.skipped)

        // 0008 이 실제로 적용됐다: 테이블·컬럼·collector_state RLS
        assertEquals(listOf(true), query("select relrowsecurity from pg_class where relname = 'collector_state'") { it.getBoolean(1) })
        assertEquals(8, query("select count(*) from cores") { it.getInt(1) }.single())
        assertEquals(1, query("select count(*) from information_schema.columns where table_name = 'content_versions' and column_name = 'source_version_id'") { it.getInt(1) }.single())
    }

    @Test
    fun changedChecksum_fails() {
        val dir = TestPg.copyMigrations(tmp.resolve("copy"))
        assertIs<MigrationResult.Applied>(migrate(dir))
        val target = dir.resolve("0002_content.sql")
        Files.writeString(target, Files.readString(target) + "\n-- 수정됨\n")
        val failed = assertIs<MigrationResult.Failed>(migrate(dir))
        assertTrue(failed.reason.contains("적용된 마이그레이션이 변경됨: 0002_content.sql"), failed.reason)
    }

    @Test
    fun crlfBom_sameChecksum() {
        val lf = "create table t (x int);\n-- 주석\n"
        val crlfBom = "﻿create table t (x int);\r\n-- 주석\r\n"
        assertEquals(MigrationRunner.normalize(lf.encodeToByteArray()), MigrationRunner.normalize(crlfBom.encodeToByteArray()))
        assertEquals(
            MigrationRunner.checksum(MigrationRunner.normalize(lf.encodeToByteArray())),
            MigrationRunner.checksum(MigrationRunner.normalize(crlfBom.encodeToByteArray())),
        )

        // 적용 후 core.autocrlf 로 CRLF + BOM 이 된 파일은 "변경"이 아니다
        val dir = TestPg.copyMigrations(tmp.resolve("copy"))
        assertIs<MigrationResult.Applied>(migrate(dir))
        Files.list(dir).use { s -> s.toList() }.forEach { f ->
            val text = Files.readString(f).replace("\n", "\r\n")
            Files.write(f, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray())
        }
        val again = assertIs<MigrationResult.Applied>(migrate(dir))
        assertEquals(emptyList(), again.files)
        assertEquals(4, again.skipped)
    }

    @Test
    fun outOfOrder_strictFails_devApplies() {
        val dir = tmp.resolve("ooo")
        write(dir, "0001_a.sql", "create table ooo_a (x int);")
        write(dir, "0003_c.sql", "create table ooo_c (x int);")
        assertEquals(listOf("0001_a.sql", "0003_c.sql"), assertIs<MigrationResult.Applied>(migrate(dir)).files)
        write(dir, "0002_b.sql", "create table ooo_b (x int);")
        write(dir, "0004_d.sql", "create table ooo_d (x int);")

        val strict = assertIs<MigrationResult.Failed>(migrate(dir, allowOutOfOrder = false))
        assertTrue(strict.reason.contains("0002_b.sql"), strict.reason)
        assertEquals(0, query("select count(*) from pg_tables where tablename in ('ooo_b', 'ooo_d')") { it.getInt(1) }.single(), "엄격 모드는 아무것도 적용하지 않는다")

        val dev = assertIs<MigrationResult.Applied>(migrate(dir, allowOutOfOrder = true))
        assertEquals(listOf("0002_b.sql", "0004_d.sql"), dev.files)
        assertEquals(2, dev.skipped)
    }

    @Test
    fun missingAppliedFile_fails() {
        val dir = TestPg.copyMigrations(tmp.resolve("copy"))
        assertIs<MigrationResult.Applied>(migrate(dir))
        Files.delete(dir.resolve("0003_compat_facts.sql"))
        val failed = assertIs<MigrationResult.Failed>(migrate(dir))
        assertTrue(failed.reason.contains("0003_compat_facts.sql"), failed.reason)
    }

    @Test
    fun concurrentlyKeyword_rejected() {
        val dir = tmp.resolve("conc")
        write(dir, "0001_ok.sql", "create table conc_ok (x int);")
        write(dir, "0002_index.sql", "create table conc_t (x int);\nCREATE INDEX CONCURRENTLY conc_idx ON conc_t (x);")
        val failed = assertIs<MigrationResult.Failed>(migrate(dir))
        assertTrue(failed.reason.contains("0002_index.sql"), failed.reason)
        assertEquals(0, query("select count(*) from pg_tables where tablename like 'conc_%'") { it.getInt(1) }.single(), "거부되면 앞 파일도 적용하지 않는다")

        val vacuum = tmp.resolve("vac")
        write(vacuum, "0001_v.sql", "vacuum full;")
        assertIs<MigrationResult.Failed>(migrate(vacuum))
        val alterSystem = tmp.resolve("alt")
        write(alterSystem, "0001_s.sql", "ALTER  SYSTEM SET work_mem = '64MB';")
        assertIs<MigrationResult.Failed>(migrate(alterSystem))
    }

    @Test
    fun badFileName_andBrokenSql_fail() {
        val badName = tmp.resolve("bad")
        write(badName, "0001_Upper.sql", "select 1;")
        assertIs<MigrationResult.Failed>(migrate(badName))

        val broken = tmp.resolve("broken")
        write(broken, "0001_ok.sql", "create table broken_ok (x int);")
        write(broken, "0002_broken.sql", "create table broken_bad (x int); this is not sql;")
        val failed = assertIs<MigrationResult.Failed>(migrate(broken))
        assertTrue(failed.reason.contains("0002_broken.sql"), failed.reason)
        assertEquals(listOf("0001_ok.sql"), query("select filename from decacross_schema_migrations") { it.getString(1) }, "파일마다 한 트랜잭션")
        assertEquals(0, query("select count(*) from pg_tables where tablename = 'broken_bad'") { it.getInt(1) }.single())
    }

    @Test
    fun locate_propertyThenEnvThenWalkUp() {
        val dir = Files.createDirectories(tmp.resolve("repo/infra/supabase/migrations"))
        val nested = Files.createDirectories(tmp.resolve("repo/app/collector"))
        assertEquals(dir, MigrationRunner.locate(systemProperty = null, env = null, userDir = nested.toString()))
        assertEquals(nested, MigrationRunner.locate(systemProperty = nested.toString(), env = dir.toString(), userDir = null))
        assertEquals(dir, MigrationRunner.locate(systemProperty = tmp.resolve("missing").toString(), env = dir.toString(), userDir = null))
        assertNull(MigrationRunner.locate(systemProperty = null, env = null, userDir = tmp.resolve("repo/app").toString().replace("repo", "elsewhere")))
    }
}
