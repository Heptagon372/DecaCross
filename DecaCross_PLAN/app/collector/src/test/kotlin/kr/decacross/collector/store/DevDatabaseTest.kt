package kr.decacross.collector.store

import kr.decacross.collector.testkit.newTempDir
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 느린 테스트 (~25 초 이상: 바이너리 압축 해제 + initdb). 이름에 Slow 를 붙였지만 기본 테스트에서 돈다.
 * 포트 54339 고정 (개발 기본 54329·스모크 54349 와 겹치지 않게).
 */
class DevDatabaseSlowTest {
    private val log = LoggerFactory.getLogger(DevDatabaseSlowTest::class.java)

    @Test
    fun startsInRoot_persistsAcrossRestart_guardsDataDirectory() {
        // zonky 는 JVM 안에서 바이너리 위치를 캐시한다. TestPg 를 먼저 띄워 바이너리를 기본 작업 디렉터리에 두면
        // 이 테스트가 끝에 root 를 지워도 같은 JVM 의 다른 DB 테스트가 지워진 바이너리를 가리키지 않는다.
        TestPg.start()
        val root = newTempDir("decacross-devdb-")
        val otherRoot = newTempDir("decacross-devdb-other-")
        try {
            DevDatabase.start(root, PORT).use { db ->
                DriverManager.getConnection(db.jdbcUrl).use { c ->
                    c.createStatement().use { s ->
                        s.execute("create table persisted (x int)")
                        s.execute("insert into persisted values (42)")
                    }
                }
            }
            assertTrue(Files.isRegularFile(root.resolve(DevDatabase.DATA_DIR_NAME).resolve("postgresql.conf")), "데이터는 root/pg17-data")
            // 바이너리 작업 디렉터리(root/epg)는 검사하지 않는다: zonky 는 JVM 안에서 한 번 푼 바이너리 위치를 캐시하므로
            // 같은 JVM 에서 TestPg 가 먼저 떴으면 기본 작업 디렉터리를 재사용한다 (운영 실행은 첫 기동이라 root/epg 를 쓴다).

            DevDatabase.start(root, PORT).use { db ->
                val value = DriverManager.getConnection(db.jdbcUrl).use { c ->
                    c.createStatement().use { s -> s.executeQuery("select x from persisted").use { rs -> if (rs.next()) rs.getInt(1) else -1 } }
                }
                assertEquals(42, value, "재시작해도 행이 남는다 (clean data directory 아님)")

                // 같은 포트에 이미 다른 데이터 디렉터리의 Postgres 가 떠 있으면 거부
                // zonky 자체의 기동 실패(IllegalStateException/IOException)가 아니라 data_directory 가드가 거부했는지 메시지로 고정한다
                val e = assertFailsWith<IllegalStateException> { DevDatabase.start(otherRoot, PORT).close() }
                log.info("가드 동작: {}", e.toString())
                assertTrue(e.message.orEmpty().contains("다른 Postgres"), "data_directory 가드가 아닌 실패: $e")

                // 가드가 원래 서버를 멈추지 않았다
                DriverManager.getConnection(db.jdbcUrl).use { c -> assertTrue(c.isValid(5)) }
            }
        } finally {
            bestEffortDelete(root)
            bestEffortDelete(otherRoot)
        }
    }

    private fun bestEffortDelete(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach {
                try {
                    Files.deleteIfExists(it)
                } catch (e: IOException) {
                    log.warn("테스트 임시 파일 삭제 실패: {}", it)
                }
            }
        }
    }

    private companion object {
        const val PORT = 54339
    }
}
