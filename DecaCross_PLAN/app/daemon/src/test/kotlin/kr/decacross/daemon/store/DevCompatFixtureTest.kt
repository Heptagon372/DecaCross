package kr.decacross.daemon.store

import kr.decacross.compat.model.CoreKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 03~04 의 유일한 호환성 데이터 소스인 `dev-compat.json` 스냅샷 (docs/04_설계결정_03설치.md D-03-1).
 *
 * # 왜 이 시험이 있나
 * 임시 데이터가 **자기 정체를 숨기면** 조용히 낡는다. 그래서 스냅샷 날짜와 출처를 리소스에 박아 두고,
 * 코드가 그것을 읽어 매 `create` 마다 한 줄 알린다. 날짜 필드를 지우면 여기서 깨진다.
 */
class DevCompatFixtureTest {
    @Test
    fun `스냅샷은 날짜와 출처를 스스로 밝힌다`() {
        val date = assertNotNull(DevCompatFixture.snapshotDate(), "dev-compat.json 에 snapshotDate 가 없다")
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(date), "ISO 날짜여야 한다: $date")
        val sources = DevCompatFixture.snapshotSources()
        assertTrue(sources.isNotEmpty(), "출처 URL 이 있어야 한다")
        assertTrue(sources.all { it.startsWith("https://") }, sources.toString())

        val notice = DevCompatFixture.snapshotNoticeKo()
        assertTrue(notice.contains(date), notice)
        assertTrue(notice.contains("04"), "언제 사라지는 임시물인지 말해야 한다: $notice")
    }

    @Test
    fun `스냅샷에는 Paper 빌드만 있다`() {
        // ★ CLI 의 `--core` 도움말이 이 사실을 말한다. 다른 코어가 들어오면 도움말부터 고쳐라.
        val fixture = DevCompatFixture.load()
        assertTrue(fixture.mcVersions.isNotEmpty())
        assertTrue(fixture.coreBuilds.isNotEmpty())
        assertEquals(setOf(CoreKey.PAPER), fixture.coreBuilds.map { it.core }.toSet())
    }
}
