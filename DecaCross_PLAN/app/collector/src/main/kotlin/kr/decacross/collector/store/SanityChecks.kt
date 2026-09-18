package kr.decacross.collector.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.config.CompletionThresholds
import kr.decacross.collector.run.TempDirs
import kr.decacross.collector.stripUtf8Bom
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.version.ORDINAL_STEP
import kr.decacross.compat.version.seedOrdinals
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.time.Instant

/** 검사 수준. */
enum class SanityLevel { PASS, WARN, FAIL }

/** 검사 하나의 결과. */
data class SanityResult(val name: String, val level: SanityLevel, val detail: String)

/**
 * `--sanity-expect` 파일 (S14). 수집 사실의 **리터럴 기대값은 이 외부 파일에만** 둔다 (CLAUDE.md: 수집 데이터 하드코딩 금지).
 * `{"ordinals": {"1.21": 1860}, "facts": {"1.21": {"rp": "34", "dp": "48"}}, "javaMin": {"26.3": 25}}`
 */
@Serializable
data class SanityExpect(
    val ordinals: Map<String, Int> = emptyMap(),
    val facts: Map<String, ExpectedFacts> = emptyMap(),
    val javaMin: Map<String, Int> = emptyMap(),
) {
    @Serializable
    data class ExpectedFacts(val rp: String? = null, val dp: String? = null)

    companion object {
        /** JSON 텍스트 → [SanityExpect]. 형식이 틀리면 [IllegalArgumentException]. */
        fun parse(json: String): SanityExpect = try {
            // Windows 편집기로 저장한 파일의 BOM 을 벗긴다 (V-1)
            CollectorJson.decodeFromString(serializer(), stripUtf8Bom(json))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("sanity-expect 형식 오류: ${e.message}", e)
        }
    }
}

/**
 * `license-policy.json` 의 재배포 스위치(SCP-20)와 허용 목록 — 정합성 검사 S9 의 입력.
 *
 * @property enabled `redistributableEnabled`. false 면 `redistributable = true` 인 행이 하나도 없어야 한다 (Phase 1).
 * @property allowlist `redistributableSpdx` (정확·대소문자 구분 일치)
 */
data class RedistributionPolicy(val enabled: Boolean, val allowlist: Set<String>)

/**
 * 인수 검사(읽기 전용 SQL). S1~S13 은 DB·`collector_state`·compat-engine 상수로만 계산하는 **자기 일관성** 검사다.
 * 리터럴 기대값은 [expect] (S14) 로만 받는다.
 *
 * @param policy `license-policy.json` 의 재배포 스위치·허용 목록. null 이면 S9 는 WARN.
 * @param tempDir 수집기 임시 디렉터리 루트 (`run-<pid>` 들의 부모)
 */
suspend fun runSanity(
    store: PgCollectorStore,
    policy: RedistributionPolicy?,
    tempDir: Path,
    expect: SanityExpect?,
    thresholds: CompletionThresholds = CompletionThresholds(),
): List<SanityResult> {
    val results = ArrayList<SanityResult>()
    val counts = store.counts()
    val unmet = buildList {
        if (counts.mcVersions < thresholds.mcVersions) add("mc_versions ${counts.mcVersions} < ${thresholds.mcVersions}")
        if (counts.coreBuilds < thresholds.coreBuilds) add("core_builds ${counts.coreBuilds} < ${thresholds.coreBuilds}")
        if (counts.content < thresholds.content) add("content ${counts.content} < ${thresholds.content}")
    }
    results += if (unmet.isEmpty()) {
        pass("S1 완료 기준", "mc_versions ${counts.mcVersions}, core_builds ${counts.coreBuilds}, content ${counts.content}")
    } else {
        fail("S1 완료 기준", unmet.joinToString("; "))
    }
    results += store.read { c -> sqlChecks(c, policy, expect) }
    results += tempDirCheck(tempDir)
    return results.sortedBy { it.name.substringBefore(' ').removePrefix("S").toIntOrNull() ?: Int.MAX_VALUE }
}

private fun pass(name: String, detail: String = "") = SanityResult(name, SanityLevel.PASS, detail)

private fun warn(name: String, detail: String) = SanityResult(name, SanityLevel.WARN, detail)

private fun fail(name: String, detail: String) = SanityResult(name, SanityLevel.FAIL, detail)

private fun zeroCheck(name: String, n: Long, what: String): SanityResult = if (n == 0L) pass(name) else fail(name, "$what: $n")

private data class MinimalMc(
    val label: String,
    val ordinal: Int,
    val releasedAt: Instant,
    val isSnapshot: Boolean,
    val javaMin: Int,
    val javaRecommended: Int,
    val rp: String?,
    val dp: String?,
    val protocol: Int?,
)

private fun sqlChecks(c: Connection, policy: RedistributionPolicy?, expect: SanityExpect?): List<SanityResult> {
    val out = ArrayList<SanityResult>()
    fun count(sql: String, bind: (PreparedStatement) -> Unit = {}): Long = c.queryScalarLong(sql, bind) ?: 0L

    out += zeroCheck("S2 서수 유일", count("select count(*) - count(distinct ordinal) from mc_versions"), "중복 서수")
    out += zeroCheck(
        "S3 릴리스 서수 증가",
        count(
            "select count(*) from (select ordinal, lag(ordinal) over (order by released_at, label collate \"C\") p " +
                "from mc_versions where not is_snapshot) t where p is not null and ordinal <= p",
        ),
        "시간순으로 서수가 증가하지 않는 릴리스",
    )
    out += zeroCheck(
        "S4 릴리스 서수 간격",
        count("select count(*) from mc_versions where not is_snapshot and ordinal % ? <> 0") { it.setInt(1, ORDINAL_STEP) },
        "ORDINAL_STEP($ORDINAL_STEP) 배수가 아닌 릴리스 서수",
    )
    out += zeroCheck(
        "S5 스냅샷 슬롯",
        count(
            "select count(*) from mc_versions s where s.is_snapshot and not exists (select 1 from mc_versions r " +
                "where not r.is_snapshot and s.ordinal > r.ordinal and s.ordinal <= r.ordinal + (? - 1))",
        ) { it.setInt(1, ORDINAL_STEP) },
        "릴리스 사이 슬롯 밖의 스냅샷",
    )
    // S16 (INV-2): 서수 순서와 시간 순서가 어긋난 스냅샷 — 늦게 도착한 릴리스를 발급할 때 생기는 알려진 영구 효과라 WARN
    val inverted = count(
        "select count(*) from mc_versions s where s.is_snapshot and exists (select 1 from mc_versions r where not r.is_snapshot and " +
            "((r.ordinal < s.ordinal and r.released_at > s.released_at) or (r.ordinal > s.ordinal and r.released_at < s.released_at)))",
    )
    out += if (inverted == 0L) {
        pass("S16 스냅샷 시간 순서")
    } else {
        warn("S16 스냅샷 시간 순서", "서수로는 릴리스 앞(뒤)인데 그 릴리스보다 늦게(먼저) 나온 스냅샷 $inverted (mojang.inversion 경고 참고, 서수는 바꾸지 않는다)")
    }

    val rows = c.prepared(
        "select label, ordinal, released_at, is_snapshot, java_min, java_recommended, rp_format, dp_format, protocol " +
            "from mc_versions order by released_at, label collate \"C\"",
    ) { ps ->
        ps.executeQuery().mapRows { rs ->
            MinimalMc(
                label = rs.getString(1),
                ordinal = rs.getInt(2),
                releasedAt = requireNotNull(rs.getInstant(3)),
                isSnapshot = rs.getBoolean(4),
                javaMin = rs.getInt(5),
                javaRecommended = rs.getInt(6),
                rp = rs.getString(7),
                dp = rs.getString(8),
                protocol = rs.getIntOrNull(9),
            )
        }
    }
    val byLabel = rows.associateBy { it.label }
    out += s6SeedSequence(rows)
    out += s7Facts(c, rows, byLabel)

    out += zeroCheck(
        "S8 core_builds 해시",
        count("select count(*) from core_builds where sha256 !~ '^[0-9a-f]{64}$' or size <= 0"),
        "형식이 틀린 sha256 또는 size <= 0",
    )

    out += when {
        policy == null -> warn("S9 재배포 허용", "license-policy.json 을 읽지 못해 허용 목록 없이 건너뜀")

        // 스위치가 꺼져 있으면(Phase 1) 라이선스와 무관하게 redistributable 행이 없어야 한다 (INV-4, SCP-20)
        !policy.enabled -> zeroCheck(
            "S9 재배포 허용",
            count("select count(*) from content where redistributable"),
            "재배포 스위치(redistributableEnabled=false)가 꺼졌는데 redistributable",
        )

        else -> zeroCheck(
            "S9 재배포 허용",
            count("select count(*) from content where redistributable and (license is null or not (license = any(?)))") {
                it.setArray(1, c.textArray(policy.allowlist))
            },
            "허용 목록 밖 라이선스인데 redistributable",
        )
    }

    val noAnalyzer = count("select count(*) from content_versions where analysis is not null and analyzer_version is null")
    val badMajor = count("select count(*) from content_versions where java_major is not null and (java_major < 1 or java_major > 211)")
    val oldMajor = count("select count(*) from content_versions where java_major is not null and java_major between 1 and 7")
    out += when {
        noAnalyzer > 0 || badMajor > 0 -> fail("S10 분석 컬럼", "analyzer_version 없는 분석 $noAnalyzer, java_major 범위(1..211) 밖 $badMajor")
        oldMajor > 0 -> warn("S10 분석 컬럼", "java_major < 8 인 버전 $oldMajor (Java 6/7 플러그인은 정상일 수 있다)")
        else -> pass("S10 분석 컬럼")
    }

    out += zeroCheck(
        "S11 PROVIDES 대상",
        count("select count(*) from content_deps where (kind = 'PROVIDES') <> (target_capability is not null)"),
        "PROVIDES ⇔ capability 대상 위반",
    )

    val mojangState = count("select count(*) from collector_state where key like 'mojang.%'")
    out += if (mojangState > 0) pass("S13 Mojang 상태", "$mojangState 키") else warn("S13 Mojang 상태", "collector_state 에 mojang.* 키가 없다")

    if (expect != null) out += s14Expect(expect, byLabel)

    val nonHttps = count("select count(*) from content_versions where file_url is not null and file_url !~ '^https://'")
    val candidates = count("select count(*) from content_versions where analysis::text like '%\"CANDIDATE\"%'")
    out += if (nonHttps == 0L && candidates == 0L) {
        pass("S15 저장 금지 항목")
    } else {
        fail("S15 저장 금지 항목", "https 가 아닌 file_url $nonHttps, analysis 안의 CANDIDATE $candidates")
    }
    return out
}

/** S6: DB 릴리스를 (released_at, label collate "C") 로 정렬했을 때 서수가 정확히 `seedOrdinals` 와 같다. */
private fun s6SeedSequence(rows: List<MinimalMc>): SanityResult {
    val releases = rows.filter { !it.isSnapshot }
    val seeded = seedOrdinals(releases.map { it.label to it.releasedAt })
    val actual = releases.associate { it.label to it.ordinal }
    val mismatches = seeded.filter { (label, ord) -> actual[label] != ord.value }
    return if (mismatches.isEmpty()) {
        pass("S6 시드 서수 일치", "릴리스 ${releases.size}")
    } else {
        val sample = mismatches.take(5).joinToString { (label, ord) -> "$label db=${actual[label]} seed=${ord.value}" }
        fail("S6 시드 서수 일치", "시드 순서와 다른 릴리스 서수 ${mismatches.size}: $sample")
    }
}

/** S7: `mojang.jarmeta.<label>` 이 FOUND/NONE 이면 mc_versions 사실과 같아야 한다 + 포맷·Java 기본 형태. */
private fun s7Facts(c: Connection, rows: List<MinimalMc>, byLabel: Map<String, MinimalMc>): SanityResult {
    val problems = ArrayList<String>()
    for (r in rows) {
        if (r.rp != null && PackFormat.parse(r.rp) == null) problems += "${r.label} rp_format '${r.rp}'"
        if (r.dp != null && PackFormat.parse(r.dp) == null) problems += "${r.label} dp_format '${r.dp}'"
        if (r.javaMin < 1 || r.javaRecommended < r.javaMin) problems += "${r.label} java ${r.javaMin}/${r.javaRecommended}"
    }
    val states = c.prepared("select key, value::text from collector_state where key like 'mojang.jarmeta.%'") { ps ->
        ps.executeQuery().mapRows { rs -> rs.getString(1) to rs.getString(2) }
    }
    var compared = 0
    for ((key, text) in states) {
        val label = key.removePrefix(JARMETA_PREFIX)
        val obj = try {
            CollectorJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        if (obj == null) {
            problems += "$key JSON 형식 오류"
            continue
        }
        val status = obj["status"].contentOrNull()
        if (status != "FOUND" && status != "NONE") continue
        val row = byLabel[label] ?: continue
        compared++
        val wantRp = if (status == "NONE") null else obj["rp"].contentOrNull()?.let(PackFormat::parse)
        val wantDp = if (status == "NONE") null else obj["dp"].contentOrNull()?.let(PackFormat::parse)
        val wantProtocol = if (status == "NONE") null else obj["protocol"].contentOrNull()?.toIntOrNull()
        val gotRp = row.rp?.let(PackFormat::parse)
        val gotDp = row.dp?.let(PackFormat::parse)
        if (wantRp != gotRp || wantDp != gotDp || wantProtocol != row.protocol) {
            problems += "$label 상태($status rp=$wantRp dp=$wantDp protocol=$wantProtocol) ≠ DB(rp=$gotRp dp=$gotDp protocol=${row.protocol})"
        }
    }
    return if (problems.isEmpty()) {
        pass("S7 사실 일관성", "jarmeta 상태 비교 $compared")
    } else {
        fail("S7 사실 일관성", "${problems.size}건: ${problems.take(5).joinToString("; ")}")
    }
}

/** S14: 외부 기대값 파일. 행·값이 null 이면 WARN (jar 메타 꺼짐 등), 다르면 FAIL. */
private fun s14Expect(expect: SanityExpect, byLabel: Map<String, MinimalMc>): SanityResult {
    val fails = ArrayList<String>()
    val warns = ArrayList<String>()
    for ((label, ordinal) in expect.ordinals) {
        val row = byLabel[label]
        when {
            row == null -> warns += "$label 행 없음"
            row.ordinal != ordinal -> fails += "$label ordinal ${row.ordinal} ≠ $ordinal"
        }
    }
    for ((label, facts) in expect.facts) {
        val row = byLabel[label]
        if (row == null) {
            warns += "$label 행 없음"
            continue
        }
        compareFormat(label, "rp", facts.rp, row.rp, fails, warns)
        compareFormat(label, "dp", facts.dp, row.dp, fails, warns)
    }
    for ((label, javaMin) in expect.javaMin) {
        val row = byLabel[label]
        when {
            row == null -> warns += "$label 행 없음"
            row.javaMin != javaMin -> fails += "$label java_min ${row.javaMin} ≠ $javaMin"
        }
    }
    return when {
        fails.isNotEmpty() -> fail("S14 기대값", fails.joinToString("; ") + if (warns.isEmpty()) "" else " (WARN: ${warns.joinToString("; ")})")
        warns.isNotEmpty() -> warn("S14 기대값", warns.joinToString("; "))
        else -> pass("S14 기대값", "ordinals ${expect.ordinals.size}, facts ${expect.facts.size}, javaMin ${expect.javaMin.size}")
    }
}

private fun compareFormat(label: String, what: String, expected: String?, actual: String?, fails: MutableList<String>, warns: MutableList<String>) {
    if (expected == null) return
    val want = PackFormat.parse(expected)
    if (want == null) {
        fails += "$label $what 기대값 '$expected' 형식 오류"
        return
    }
    if (actual == null) {
        warns += "$label $what null"
        return
    }
    if (PackFormat.parse(actual) != want) fails += "$label $what $actual ≠ $expected"
}

/** S12: 자기 실행 디렉터리·tempDir 바로 아래·죽은 실행 디렉터리에 `dl-*` 파일이 남지 않았다. 살아 있는 다른 실행은 건너뛴다. */
private fun tempDirCheck(tempDir: Path): SanityResult {
    val leftovers = TempDirs.leftoverFiles(tempDir)
    return if (leftovers.isEmpty()) {
        pass("S12 임시 jar 없음", tempDir.toString())
    } else {
        fail("S12 임시 jar 없음", "${leftovers.size}개: ${leftovers.take(5).joinToString()}")
    }
}

private fun JsonElement?.contentOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private const val JARMETA_PREFIX = "mojang.jarmeta."
