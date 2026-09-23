package kr.decacross.daemon.diagnosis

import kr.decacross.analysis.Severity
import kr.decacross.analysis.detectShadeConflicts
import kr.decacross.logparse.LogLine
import kr.decacross.logparse.LogParser
import kr.decacross.logparse.SeedSignature
import kr.decacross.logparse.SeedSignatures
import kr.decacross.logparse.Signature
import kr.decacross.logparse.SignatureMatch
import kr.decacross.logparse.matchSignatures
import kr.decacross.logparse.requiredJavaFromClassMajor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 서버 하나의 로그를 스트리밍으로 받아 [Diagnosis] 를 만든다. 서버당 인스턴스 하나.
 *
 * 시그니처는 데이터(시드 JSON / DB)다. 여기 있는 코드는 "캡처값을 실행 가능한 fix 로 바꾸는" 특수 처리뿐이다:
 * - unsupported_class_version: major − 44 = 필요 Java → ChangeJava(feature) 실행 가능
 * - unknown_dependency: 캡처 slug → InstallDependency (07 에서 콘텐츠 검색과 연결)
 * - watchdog: 스택 덤프에서 플러그인 패키지 추출 → 범인 지목
 * - port_in_use: 포트를 쓰는 프로세스 이름 + 대체 포트 제안
 * - linkage_error: plugins/ 의 jar 들로 detectShadeConflicts (Kotlin 이라서 가능한 진단, K-2)
 */
class DiagnosisEngine(
    private val serverDir: Path,
    private val serverPort: Int,
    signatures: List<SeedSignature> = SeedSignatures.all,
    private val portOwnerLookup: (Int) -> String? = ::defaultPortOwner,
) {
    private val seeds: Map<String, SeedSignature> = signatures.associateBy { it.key }
    private val compiled: List<Signature> = signatures.map { it.toSignature() }
    private val parser = LogParser()
    private val byKey = LinkedHashMap<String, Diagnosis>()
    private var seq = 0

    /** 진단 목록 (최신 갱신 순). */
    @Synchronized
    fun all(): List<Diagnosis> = byKey.values.sortedByDescending { it.at }

    /** 라인 하나를 넣는다. 새로 생기거나 갱신된 진단이 있으면 돌려준다. */
    @Synchronized
    fun feed(rawLine: String): Diagnosis? = parser.feed(rawLine).firstNotNullOfOrNull(::diagnose)

    @Synchronized
    fun flush(): Diagnosis? = parser.flush().firstNotNullOfOrNull(::diagnose)

    /** 이미 쌓인 로그(최근 N줄)를 한 번에 분석한다. */
    @Synchronized
    fun analyze(lines: List<String>): List<Diagnosis> {
        val out = LinkedHashMap<String, Diagnosis>()
        lines.forEach { l -> feed(l)?.let { out[it.signatureKey] = it } }
        flush()?.let { out[it.signatureKey] = it }
        // 같은 키가 재구성됐으면(스택 덤프가 나중에 붙는 경우) 최신 카드만
        return out.keys.map { k -> byKey.getValue(k) }
    }

    private fun diagnose(line: LogLine): Diagnosis? {
        val m = matchSignatures(line, compiled) ?: return null
        val seed = seeds[m.signature.key] ?: return null
        val existing = byKey[seed.key]
        // 반복이면 카드만 갱신. 단, 처음엔 스택이 없었는데 이번엔 스택 덤프가 붙어 왔으면(watchdog 등) 근거를 더해 다시 만든다.
        if (existing != null && !(line.continuation.isNotEmpty() && existing.evidence.size <= 1)) {
            byKey[seed.key] = existing.copy(occurrences = existing.occurrences + 1, at = Instant.now().toString())
            return null
        }
        val fixes = buildFixes(seed, m)
        val d = Diagnosis(
            id = existing?.id ?: "d${++seq}",
            occurrences = (existing?.occurrences ?: 0) + 1,
            signatureKey = seed.key,
            category = seed.category,
            titleKo = seed.titleKo,
            causeKo = enrichCause(seed, m),
            fixes = fixes.ifEmpty { listOf(DiagFix("로그 원문 보기", FixActionDto("ShowLog"), recommended = true)) },
            evidence = (listOf(line.raw) + line.continuation.take(6)).map { it.take(300) },
            at = Instant.now().toString(),
        )
        check(d.fixes.isNotEmpty()) { "fix 없는 Diagnosis: ${seed.key}" }
        byKey[seed.key] = d
        return d
    }

    private fun enrichCause(seed: SeedSignature, m: SignatureMatch): String =
        when (seed.key) {
            "unsupported_class_version", "unsupported_class_error" ->
                m.captured["major"]?.toIntOrNull()?.let { "${seed.causeKo} 이 플러그인은 Java ${requiredJavaFromClassMajor(it)} 이상이 필요합니다." } ?: seed.causeKo

            "unknown_dependency" -> m.captured.values.firstOrNull()?.let { "${seed.causeKo} 누락: $it" } ?: seed.causeKo

            "port_in_use" -> portOwnerLookup(serverPort)?.let { "${seed.causeKo} 포트 $serverPort 사용 중: $it" } ?: seed.causeKo

            "watchdog" -> blamePlugin(m.line)?.let { "${seed.causeKo} 스택 덤프에 가장 많이 등장한 플러그인 패키지: $it" } ?: seed.causeKo

            "linkage_error" -> {
                val conflicts = shadeConflicts()
                if (conflicts.isEmpty()) seed.causeKo else "${seed.causeKo} shaded 라이브러리 충돌 감지: " + conflicts.take(3).joinToString("; ") { "${it.first} (${it.second.joinToString(", ")})" }
            }

            else -> seed.causeKo
        }

    private fun buildFixes(seed: SeedSignature, m: SignatureMatch): List<DiagFix> =
        seed.fixes.map { f ->
            val type = f.action.type
            val captured = f.action.capture?.let { m.captured[it] }
            when (type) {
                "ChangeJava" -> {
                    val feature = captured?.toIntOrNull()?.let(::requiredJavaFromClassMajor)
                    DiagFix(feature?.let { "Java $it 런타임으로 전환" } ?: f.labelKo, FixActionDto(type, feature?.toString(), executable = feature != null), f.recommended)
                }

                "SuggestPort" -> {
                    val next = serverPort + 1
                    DiagFix("포트를 $next 로 변경", FixActionDto(type, next.toString(), executable = true), f.recommended)
                }

                "FindPortOwner" -> DiagFix(f.labelKo, FixActionDto(type, portOwnerLookup(serverPort), executable = false), f.recommended)

                "InstallDependency" -> DiagFix(captured?.let { "$it 설치" } ?: f.labelKo, FixActionDto(type, captured, executable = false), f.recommended, noteKo = "07 단계에서 콘텐츠 검색과 연결")

                "DisablePlugin" -> DiagFix(captured?.let { "$it 비활성화 후 재기동" } ?: f.labelKo, FixActionDto(type, captured, executable = captured != null), f.recommended)

                "BlamePluginFromStack" -> {
                    val blamed = blamePlugin(m.line)
                    DiagFix(blamed?.let { "$it 비활성화" } ?: f.labelKo, FixActionDto("DisablePlugin", blamed, executable = blamed != null), f.recommended)
                }

                "ShowShadeConflict" -> {
                    val c = shadeConflicts()
                    DiagFix(if (c.isEmpty()) f.labelKo else "충돌 ${c.size}건 보기", FixActionDto(type, c.joinToString("\n") { "${it.first}: ${it.second.joinToString(", ")}" }, executable = false), f.recommended)
                }

                "ShowEulaDialog" -> DiagFix(f.labelKo, FixActionDto(type, null, executable = true), f.recommended)

                else -> DiagFix(f.labelKo, FixActionDto(type, captured, executable = false), f.recommended)
            }
        }

    /** 스택 덤프에서 서버/JDK 소속이 아닌 패키지를 세어 최다 출현 플러그인 후보를 고른다. */
    private fun blamePlugin(line: LogLine): String? {
        val counts = HashMap<String, Int>()
        for (l in line.continuation) {
            val m = STACK_AT.find(l) ?: continue
            val cls = m.groupValues[1]
            if (SERVER_PREFIXES.any { cls.startsWith(it) }) continue
            val pkg = cls.split('.').take(3).joinToString(".")
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun shadeConflicts(): List<Pair<String, List<String>>> {
        val plugins = serverDir.resolve("plugins")
        if (!Files.isDirectory(plugins)) return emptyList()
        val jars = Files.list(plugins).use { s -> s.filter { it.toString().endsWith(".jar") }.toList() }
        return runCatching { detectShadeConflicts(jars) }.getOrDefault(emptyList())
            .filter { it.severity != Severity.LOW }
            .map { it.classPath to it.providers }
    }

    companion object {
        private val STACK_AT = Regex("""^\s*at ([\w.$]+)\(""")
        private val SERVER_PREFIXES = listOf("net.minecraft", "org.bukkit", "io.papermc", "com.destroystokyo", "org.spigotmc", "java.", "javax.", "jdk.", "sun.", "kotlin.", "com.mojang", "io.netty", "com.google")

        /** Windows: `netstat -ano` 로 포트 점유 PID → `tasklist` 로 이름. 다른 OS: `lsof -i`. 실패하면 null. */
        fun defaultPortOwner(port: Int): String? = runCatching {
            val win = System.getProperty("os.name").lowercase().contains("win")
            if (win) {
                val p = ProcessBuilder("cmd", "/c", "netstat -ano -p tcp").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(5, TimeUnit.SECONDS)
                val pid = out.lineSequence().firstOrNull { it.contains(":$port ") && it.contains("LISTENING") }?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toLongOrNull()
                    ?: return null
                ProcessHandle.of(pid).map { h -> "${h.info().command().map { it.substringAfterLast('\\') }.orElse("?")} (pid $pid)" }.orElse("pid $pid")
            } else {
                val p = ProcessBuilder("sh", "-c", "lsof -nP -iTCP:$port -sTCP:LISTEN | tail -n +2 | head -1").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(5, TimeUnit.SECONDS)
                out.ifEmpty { null }?.split(Regex("\\s+"))?.let { "${it[0]} (pid ${it[1]})" }
            }
        }.getOrNull()
    }
}
