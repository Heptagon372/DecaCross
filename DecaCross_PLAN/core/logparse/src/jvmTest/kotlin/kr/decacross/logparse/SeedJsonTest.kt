package kr.decacross.logparse

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 번들 시드 JSON 과 Kotlin 객체 [SeedSignatures] 는 항상 같아야 한다. DB 시드(0004_error_signatures.sql)도 이 JSON 에서 나온다. */
class SeedJsonTest {
    private val json = Json { ignoreUnknownKeys = false }

    private fun loadJson(): List<SeedSignature> {
        val stream = checkNotNull(SeedJsonTest::class.java.getResourceAsStream("/error-signatures.seed.json")) { "시드 JSON 없음" }
        return json.decodeFromString(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    @Test
    fun `JSON 과 Kotlin 객체가 같다`() {
        val fromJson = loadJson()
        assertEquals(SeedSignatures.all.size, fromJson.size)
        SeedSignatures.all.zip(fromJson).forEach { (kotlin, js) -> assertEquals(kotlin, js, "시드 불일치: ${kotlin.key}") }
        assertEquals(SeedSignatures.all, fromJson)
    }

    @Test
    fun `JSON 의 모든 pattern 이 컴파일되고 fix 가 1개 이상이며 action type 이 명세 이름이다`() {
        val specActions =
            setOf(
                "ChangeJava", "ShowEulaDialog", "FindPortOwner", "SuggestPort", "RecalcRam", "IncreaseRam", "EnableHeapDump",
                "InstallDependency", "ReResolve", "ReplaceWithMatchingBuild", "DisablePlugin", "BlamePluginFromStack",
                "SuggestJmxProfile", "SuggestRenumber", "RetryWithMirror", "RepairRuntime", "RestoreSnapshot", "BumpMc",
                "ShowShadeConflict",
            )
        loadJson().forEach { seed ->
            seed.toSignature()
            assertTrue(seed.fixes.isNotEmpty(), "${seed.key}: fix 없음")
            seed.fixes.forEach { fix ->
                assertTrue(fix.action.type in specActions, "${seed.key}: 명세에 없는 action ${fix.action.type}")
                fix.action.capture?.let { assertTrue(it in seed.captures, "${seed.key}: 캡처 $it 가 captures 에 없다") }
            }
        }
    }

    @Test
    fun `Kotlin 객체를 직렬화해 다시 읽어도 같다`() {
        val roundTrip: List<SeedSignature> = json.decodeFromString(json.encodeToString(SeedSignatures.all))
        assertEquals(SeedSignatures.all, roundTrip)
    }
}
