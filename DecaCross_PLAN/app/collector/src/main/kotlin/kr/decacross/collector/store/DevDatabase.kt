package kr.decacross.collector.store

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 개발용 임베디드 Postgres (zonky embedded-postgres, PG 17 바이너리).
 *
 * 배치: `<root>/pg17-data` (데이터), `<root>/epg` (바이너리 작업 디렉터리).
 * zonky 는 JVM 안에서 한 번 푼 바이너리 위치를 캐시한다 — 같은 JVM 에서 다른 임베디드 PG 가 먼저 떴으면 그 위치를 재사용한다.
 *
 * # 불변식
 * - 데이터 디렉터리를 지우지 않는다 (`setCleanDataDirectory(false)`). 재시작해도 행이 남는다.
 * - 같은 포트에 다른 Postgres 가 떠 있으면 거부한다 (`show data_directory` 가드).
 * - `root` 가 OneDrive 아래면 CLI 가 이미 거부했다 (`--allow-onedrive-temp` 가 없으면).
 * - zonky 의 오래된 데이터 디렉터리 정리는 `Builder.parentDirectory`(`ot.epg.working-dir` 또는 `%TEMP%\embedded-pg`)만 훑는다.
 *   `setOverrideWorkingDirectory` 는 그 값을 바꾸지 않으므로 이 배치는 안전하다. ★ `-Dot.epg.working-dir` 를 root 로 두지 마라.
 */
class DevDatabase private constructor(
    val jdbcUrl: String,
    private val pg: EmbeddedPostgres,
) : AutoCloseable {
    /** `pg_ctl stop -m fast`. 두 번 불러도 된다. */
    override fun close() {
        try {
            pg.close()
        } catch (e: IOException) {
            log.warn("개발 DB 종료 실패: {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DevDatabase::class.java)

        const val DATA_DIR_NAME: String = "pg17-data"
        const val WORKING_DIR_NAME: String = "epg"

        /**
         * 개발 DB 를 띄운다. 첫 기동은 바이너리 압축 해제 + initdb 로 18~22 초, 이후 약 1 초.
         * @throws IOException 기동 실패
         * @throws IllegalStateException 포트에 다른 Postgres 가 떠 있을 때
         */
        fun start(root: Path, port: Int = DbTarget.DEFAULT_DEV_DB_PORT): DevDatabase {
            Files.createDirectories(root)
            val data = root.resolve(DATA_DIR_NAME)
            val pg = EmbeddedPostgres.builder()
                .setOverrideWorkingDirectory(root.resolve(WORKING_DIR_NAME).toFile())
                .setDataDirectory(data)
                .setCleanDataDirectory(false)
                .setPort(port)
                .setLocaleConfig("locale", "C")
                .setLocaleConfig("lc-messages", "C")
                .setServerConfig("listen_addresses", "localhost")
                .setPGStartupWait(Duration.ofSeconds(60))
                .start()
            try {
                val actual = pg.postgresDatabase.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("show data_directory").use { rs ->
                            check(rs.next()) { "show data_directory 결과 없음" }
                            rs.getString(1)
                        }
                    }
                }
                val same = try {
                    Path.of(actual).toRealPath() == data.toRealPath()
                } catch (e: IOException) {
                    false
                }
                check(same) { "포트 $port 에 다른 Postgres 가 떠 있다 (data_directory=$actual, 기대=$data)" }
            } catch (e: Exception) {
                try {
                    pg.close()
                } catch (closeError: IOException) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
            log.info("개발 DB 기동: {} (port {})", data, port)
            return DevDatabase(pg.getJdbcUrl("postgres", "postgres"), pg)
        }
    }
}
