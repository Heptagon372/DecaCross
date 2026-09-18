package kr.decacross.collector.store

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.name

/**
 * 테스트용 임베디드 Postgres (JVM 당 하나, 임의 포트, zonky 기본 임시 작업 디렉터리).
 *
 * # 불변식
 * - `runTest` **밖에서** 띄운다 (`@BeforeTest` 등). 첫 기동은 부하가 있으면 20 초를 넘는다.
 * - 테스트마다 [resetAndMigrate] 로 public 스키마를 비우고 레포 마이그레이션을 다시 적용한다.
 * - 테스트는 연 [PgCollectorStore] 를 반드시 닫는다 (열린 트랜잭션이 스키마 삭제를 막는다).
 */
object TestPg {
    private val pg: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder()
            .setCleanDataDirectory(true)
            .setLocaleConfig("locale", "C")
            .setLocaleConfig("lc-messages", "C")
            .start()
    }

    /** 기동 (이미 떠 있으면 아무것도 안 함). */
    fun start() {
        pg.port
    }

    val target: DbTarget.Url get() = DbTarget.Url(pg.getJdbcUrl("postgres", "postgres"), null, null)

    fun connection(): Connection = PgCollectorStore.connect(target)

    /** 빌드 스크립트가 넘긴 레포 마이그레이션 디렉터리. */
    fun migrationsDir(): Path {
        val dir = requireNotNull(System.getProperty(MigrationRunner.SYSTEM_PROPERTY)) { "시스템 속성 ${MigrationRunner.SYSTEM_PROPERTY} 가 없다 (build.gradle.kts)" }
        return Path.of(dir)
    }

    /** public 스키마와 마이그레이션 이력을 모두 지운다. */
    fun resetSchema() {
        start()
        connection().use { c ->
            c.createStatement().use { s ->
                s.execute("drop schema public cascade")
                s.execute("create schema public")
                s.execute("drop table if exists ${MigrationRunner.HISTORY_TABLE}")
            }
        }
    }

    /** 스키마를 비우고 [dir] 의 마이그레이션을 적용한다. */
    fun resetAndMigrate(dir: Path = migrationsDir()) {
        resetSchema()
        val result = connection().use { MigrationRunner(dir).migrate(it, allowOutOfOrder = false) }
        check(result is MigrationResult.Applied) { "테스트 DB 마이그레이션 실패: $result" }
    }

    fun openStore(isDevDatabase: Boolean = true): PgCollectorStore = PgCollectorStore.open(target, isDevDatabase)

    /** 레포 마이그레이션 중 이름이 [keep] 을 만족하는 파일만 [dest] 로 복사한다. */
    fun copyMigrations(dest: Path, keep: (String) -> Boolean = { true }): Path {
        Files.createDirectories(dest)
        Files.list(migrationsDir()).use { s ->
            s.filter { it.name.endsWith(".sql") && keep(it.name) }.forEach { Files.copy(it, dest.resolve(it.name)) }
        }
        return dest
    }
}
