package kr.decacross.pubgrub.golden

import kr.decacross.pubgrub.MapDependencyProvider
import kr.decacross.pubgrub.SolverResult
import kr.decacross.pubgrub.VersionSet
import kr.decacross.pubgrub.packages
import kr.decacross.pubgrub.render
import kr.decacross.pubgrub.resolve
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `src/jvmTest/resources/golden` 폴더의 `.json` 골든 케이스.
 *
 * 형식:
 * ```
 * {
 *   "name": "...", "origin": "출처",
 *   "packages": { "foo": { "1.0.0": { "bar": "^1.0.0" } } },
 *   "unavailable": { "foo": { "2.0.0": "사유" } },          // 선택
 *   "root": { "foo": "^1.0.0" },                            // 루트(root@1.0.0)의 의존성
 *   "expect": { "solution": { "foo": "1.0.0" } }            // root 제외
 *          | { "noSolution": true, "mentions": ["root", "foo"] }  // 트리에 등장하는 패키지 집합 (정확히 일치)
 * }
 * ```
 */
class GoldenCaseTest {
    private val rootName = "root"
    private val rootVersion = SemVer(1, 0, 0)

    data class GoldenCase(val name: String, val origin: String, val file: File, val json: Map<String, Any?>)

    private fun loadCases(): List<GoldenCase> {
        val url = checkNotNull(GoldenCaseTest::class.java.getResource("/golden")) { "golden 리소스 폴더가 없습니다" }
        val dir = File(url.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "골든 케이스가 하나도 없습니다: $dir" }
        return files.map { f ->
            @Suppress("UNCHECKED_CAST")
            val json = MiniJson.parse(f.readText()) as Map<String, Any?>
            GoldenCase(json["name"] as? String ?: f.nameWithoutExtension, json["origin"] as? String ?: "", f, json)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildProvider(json: Map<String, Any?>): MapDependencyProvider<String, SemVer> {
        val packages = json["packages"] as? Map<String, Any?> ?: emptyMap()
        val unavailable = json["unavailable"] as? Map<String, Any?> ?: emptyMap()
        val root = json["root"] as? Map<String, Any?> ?: emptyMap()
        require(rootName !in packages) { "'root' 는 packages 에 넣지 않는다. root 필드를 쓴다" }
        return MapDependencyProvider.build {
            add(rootName, rootVersion, parseDeps(root))
            for ((pkg, versions) in packages) {
                for ((v, deps) in versions as Map<String, Any?>) {
                    add(pkg, SemVer.parse(v), parseDeps(deps as? Map<String, Any?> ?: emptyMap()))
                }
            }
            for ((pkg, versions) in unavailable) {
                for ((v, reason) in versions as Map<String, Any?>) {
                    addUnavailable(pkg, SemVer.parse(v), reason as String)
                }
            }
        }
    }

    private fun parseDeps(deps: Map<String, Any?>): Map<String, VersionSet<SemVer>> =
        deps.entries.associate { (k, v) -> k to RangeParser.parse(v as String) }

    @Suppress("UNCHECKED_CAST")
    private fun runCase(case: GoldenCase) {
        val provider = buildProvider(case.json)
        val expect = case.json["expect"] as Map<String, Any?>
        val result = resolve(provider, rootName, rootVersion)
        val expectedSolution = expect["solution"] as? Map<String, Any?>
        if (expectedSolution != null) {
            assertIs<SolverResult.Solution<String, SemVer>>(result, "[${case.name}] 해가 있어야 한다: $result")
            val actual = result.selected.filterKeys { it != rootName }.mapValues { it.value.toString() }
            val expected = expectedSolution.filterKeys { it != rootName }.mapValues { it.value as String }
            assertEquals(expected, actual, "[${case.name}] 해가 다르다")
            assertTrue(rootName in result.selected, "[${case.name}] 해에 루트가 없다")
        } else {
            assertEquals(true, expect["noSolution"], "[${case.name}] expect 에 solution 또는 noSolution 이 필요하다")
            assertIs<SolverResult.NoSolution<String, SemVer>>(result, "[${case.name}] 해가 없어야 한다: $result")
            val mentions = (expect["mentions"] as List<Any?>).map { it as String }.toSet()
            val actual = result.tree.packages()
            assertEquals(mentions, actual, "[${case.name}] 트리에 등장하는 패키지 집합이 다르다\n${result.tree.render()}")
        }
    }

    @Test
    fun allGoldenCasesPass() {
        val cases = loadCases()
        val failures = ArrayList<String>()
        for (case in cases) {
            try {
                runCase(case)
                println("[golden] OK  ${case.name}  (${case.origin})")
            } catch (e: Throwable) {
                failures += "${case.file.name}: ${e.message}"
                println("[golden] FAIL ${case.name}: ${e.message}")
            }
        }
        println("[golden] ${cases.size - failures.size}/${cases.size} 통과")
        if (failures.isNotEmpty()) fail("골든 케이스 실패 ${failures.size}/${cases.size}:\n" + failures.joinToString("\n"))
    }

    @Test
    fun goldenCaseCount() {
        assertTrue(loadCases().size >= 15, "골든 케이스는 15개 이상이어야 한다")
    }

    @Test
    fun rangeParser_parsesAllForms() {
        val v = SemVer.parse("1.2.3")
        assertTrue(RangeParser.parse("*").contains(v))
        assertTrue(RangeParser.parse("1.2.3").contains(v))
        assertTrue(!RangeParser.parse("1.2.4").contains(v))
        assertTrue(RangeParser.parse(">=1.0.0 <2.0.0").contains(v))
        assertTrue(!RangeParser.parse(">=1.0.0 <1.2.3").contains(v))
        assertTrue(RangeParser.parse("^1.2.0").contains(v))
        assertTrue(!RangeParser.parse("^1.2.0").contains(SemVer(2, 0, 0)))
        assertTrue(RangeParser.parse("^0.2.0").contains(SemVer(0, 2, 9)))
        assertTrue(!RangeParser.parse("^0.2.0").contains(SemVer(0, 3, 0)))
        assertTrue(RangeParser.parse(">=1.0.0").contains(SemVer(9, 0, 0)))
        assertTrue(RangeParser.parse("<2.0.0").contains(SemVer(0, 0, 1)))
        assertTrue(RangeParser.parse("<=1.2.3").contains(v))
        assertTrue(RangeParser.parse("~1.2.0").contains(v))
        assertTrue(!RangeParser.parse("~1.2.0").contains(SemVer(1, 3, 0)))
        assertTrue(RangeParser.parse("<1.0.0 || >=3.0.0").contains(SemVer(3, 0, 0)))
        assertTrue(!RangeParser.parse("<1.0.0 || >=3.0.0").contains(v))
        assertEquals(">=1.0.0 <2.0.0", RangeParser.parse("^1.0.0").toString())
    }
}
