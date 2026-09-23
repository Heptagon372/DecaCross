package kr.decacross.analysis

import java.nio.file.Path

// ── 정적 검증 (K-1) ────────────────────────────────────────────────────────
// 서버를 띄우지 않고 NoClassDefFoundError / NoSuchMethodError / NoSuchFieldError 를 예측한다.
// ★ 불변식 16: 미해결 참조를 전부 에러로 처리하지 마라. 오탐이 많으면 기능 자체가 무용지물이다.

/**
 * 검증 결과.
 *
 * # 불변식
 * - [ok] == [analyzed] && [missingClasses] · [missingMethods] · [missingFields] 가 전부 비어 있음. 경고는 [ok] 를 내리지 않는다.
 * - 해석 불가 owner 는 [missingClasses] 에 한 번만 오른다. 그 클래스의 멤버는 따로 세지 않는다.
 * - 같은 (owner, name, desc) 는 한 번만 오른다. 참조 지점 수는 [MissingRef.detail] 에 적는다.
 */
public data class StaticVerifyResult(
    val ok: Boolean,
    val missingClasses: List<MissingRef>,
    val missingMethods: List<MissingRef>,
    /** 사람이 읽는 경고 문장. 구조화된 형태는 [warningRefs]. */
    val warnings: List<String>,
    val missingFields: List<MissingRef> = emptyList(),
    val warningRefs: List<WarnRef> = emptyList(),
    /** jar 를 열지 못했거나 클래스가 하나도 없으면 false. 그때 [ok] 도 false 다. */
    val analyzed: Boolean = true,
    /** 인덱싱 실패 항목, 건너뛴 API jar 등 */
    val notes: List<String> = emptyList(),
    val elapsedMillis: Long = 0L,
)

/** [from] 참조 지점(`pkg/Cls.method(desc)`), [target] 내부 이름 또는 `owner.name desc`, [detail] 사람이 읽는 설명. */
public data class MissingRef(val from: String, val target: String, val detail: String)

/** 미해결이지만 에러가 아닌 참조. [reason] 이 왜 경고로 낮췄는지다. */
public data class WarnRef(val from: String, val target: String, val detail: String, val reason: WarnReason)

/** 경고로 낮춘 근거 (CLAUDE.md 불변식 16). */
public enum class WarnReason {
    /** NoClassDefFoundError 계열을 잡는 try 블록 안 */
    GUARDED_TRY,

    /** `softdepend` 에 선언된 플러그인 소속 */
    SOFTDEPEND,

    /** `depend` 에 선언됐지만 이번 조합의 클래스패스에 아예 없는 플러그인 소속 (조합이 불완전한 것이지 플러그인 결함이 아니다) */
    DEPEND_ABSENT,

    /** `plugin.yml` `libraries:` 로 서버가 런타임에 내려받는 라이브러리 소속 */
    LIBRARY,

    /** `Class.forName` 계열 호출이 있는 메서드의 클래스명 문자열 상수 */
    REFLECTION,

    /** 플러그인 자신의 필드/메서드 디스크립터에만 등장 — JVM 이 지연 로드하므로 실행 경로에 따라 안 터질 수 있다 */
    SIGNATURE_ONLY,

    /** 어노테이션에만 등장 — JVM 은 없는 어노테이션 클래스를 조용히 버린다 */
    ANNOTATION_ONLY,
}

/** 어노테이션에만 등장하면 아예 보고하지 않는 접두사 (컴파일 타임 전용 메타데이터). */
public val ANNOTATION_ONLY_IGNORED_PREFIXES: List<String> = listOf(
    "kotlin/",
    "org/jetbrains/annotations/",
    "lombok/",
    "javax/annotation/",
    "org/checkerframework/",
    "com/google/errorprone/",
    "jakarta/annotation/",
    "org/intellij/lang/annotations/",
)

/**
 * 플러그인 jar 의 모든 외부 참조를 코어 API jar 기준으로 해석한다.
 * 서버를 띄우지 않고 NoClassDefFoundError / NoSuchMethodError 를 예측한다.
 *
 * 조합 1건당 목표: 500ms 이내 (실제 기동은 40초). jar 파싱 결과는 [JarIndexCache] 가 보관하므로
 * 같은 플러그인·같은 API jar 의 두 번째 조합부터는 해석만 한다.
 *
 * 해석 순서: 플러그인 자신 → [coreApiJars] (순서대로) → 실행 중인 JDK 시스템 모듈.
 * JDK 클래스는 **실행 중인 JDK** 기준으로 해석한다 — `javaFeature` 보다 새 JDK 에서 돌리면 그 사이에 추가된
 * API 는 "있음"(미탐), 제거된 API 는 [JdkRemovedApis] 표로 보정한다. `javaFeature` 는 Multi-Release 선택에도 쓴다.
 *
 * # 불변식 (CLAUDE.md 16)
 * 미해결 참조를 전부 에러로 처리하지 마라. try/catch·softdepend·리플렉션 문자열은 경고다.
 * 분류 규칙은 [WarnReason] 과 README 에 있다.
 */
public fun staticVerify(
    pluginJar: Path,
    /** paper-api, bukkit, 그리고 함께 설치될 다른 플러그인들 */
    coreApiJars: List<Path>,
    javaFeature: Int,
): StaticVerifyResult {
    val started = System.nanoTime()
    val notes = ArrayList<String>()
    val scan = JarIndexCache.pluginScan(pluginJar)
    notes += scan.notes
    if (scan.classes.isEmpty() && scan.versioned.isEmpty()) {
        notes += "분석할 클래스가 없음: $pluginJar"
        return StaticVerifyResult(false, emptyList(), emptyList(), emptyList(), analyzed = false, notes = notes, elapsedMillis = elapsed(started))
    }
    val apis = coreApiJars.mapNotNull { jar ->
        runCatching { JarIndexCache.memberIndex(jar) }.getOrElse { e ->
            notes += "API jar 를 읽지 못해 건너뜀: $jar (${e.message})"
            null
        }
    }
    val resolver = Resolver(scan, apis, javaFeature)
    val rules = OwnerRules(scan.meta, apis)

    // 1) 참조 해석 → 미해결 집계 (owner 단위 / 멤버 단위)
    val missingClassAgg = LinkedHashMap<String, MutableList<ClassRef>>()
    val missingMemberAgg = LinkedHashMap<String, MutableList<ClassRef>>()
    for (cls in scan.effectiveClasses(javaFeature)) {
        for (ref in cls.refs) {
            val owner = ref.owner
            when (resolver.lookup(owner)) {
                Lookup.Missing -> missingClassAgg.getOrPut(owner) { ArrayList(2) } += ref

                Lookup.Assumed -> Unit

                is Lookup.Found -> when (ref) {
                    is MethodRef -> if (!resolver.hasMethod(owner, ref.name, ref.desc)) {
                        missingMemberAgg.getOrPut("M:$owner.${ref.name}${ref.desc}") { ArrayList(1) } += ref
                    }

                    is FieldRef -> if (!resolver.hasField(owner, ref.name, ref.desc)) {
                        missingMemberAgg.getOrPut("F:$owner.${ref.name}:${ref.desc}") { ArrayList(1) } += ref
                    }

                    is TypeRef -> Unit
                }
            }
        }
    }

    // 2) 분류
    val missingClasses = ArrayList<MissingRef>()
    val missingMethods = ArrayList<MissingRef>()
    val missingFields = ArrayList<MissingRef>()
    val warningRefs = ArrayList<WarnRef>()

    for ((owner, refs) in missingClassAgg) {
        val first = refs.first()
        val detail = "클래스 없음 — 참조 ${refs.size}곳, 문맥 ${refs.map { it.context }.distinct().joinToString(",")}"
        when (val v = classify(owner, refs, rules)) {
            Verdict.Error -> missingClasses += MissingRef(first.from, owner, detail)
            is Verdict.Warning -> warningRefs += WarnRef(first.from, owner, detail, v.reason)
            Verdict.Ignore -> Unit
        }
    }
    for ((_, refs) in missingMemberAgg) {
        val first = refs.first()
        val (target, detail, isField) = when (first) {
            is MethodRef -> Triple("${first.owner}.${first.name}${first.desc}", "메서드 없음 (계층 전체 탐색) — 참조 ${refs.size}곳", false)
            is FieldRef -> Triple("${first.owner}.${first.name}:${first.desc}", "필드 없음 (계층 전체 탐색) — 참조 ${refs.size}곳", true)
            is TypeRef -> continue
        }
        when (val v = classify(first.owner, refs, rules)) {
            Verdict.Error -> if (isField) missingFields += MissingRef(first.from, target, detail) else missingMethods += MissingRef(first.from, target, detail)
            is Verdict.Warning -> warningRefs += WarnRef(first.from, target, detail, v.reason)
            Verdict.Ignore -> Unit
        }
    }

    val warnings = warningRefs.map { "[${it.reason}] ${it.target} ← ${it.from}: ${it.detail}" }
    val ok = missingClasses.isEmpty() && missingMethods.isEmpty() && missingFields.isEmpty()
    return StaticVerifyResult(
        ok = ok,
        missingClasses = missingClasses,
        missingMethods = missingMethods,
        warnings = warnings,
        missingFields = missingFields,
        warningRefs = warningRefs,
        analyzed = true,
        notes = notes,
        elapsedMillis = elapsed(started),
    )
}

private fun elapsed(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

// ── 분류 ──────────────────────────────────────────────────────────────────

private sealed interface Verdict {
    data object Error : Verdict

    data class Warning(val reason: WarnReason) : Verdict

    data object Ignore : Verdict
}

/** 클래스가 로드/실행되면 반드시 해석되는 문맥. 가드 밖에서 이 문맥으로 미해결이면 에러다. */
private val HARD_CONTEXTS = setOf(RefContext.HIERARCHY, RefContext.INSTRUCTION, RefContext.LDC_TYPE, RefContext.CATCH_TYPE)

private fun classify(owner: String, refs: List<ClassRef>, rules: OwnerRules): Verdict {
    rules.reasonFor(owner)?.let { return Verdict.Warning(it) }
    if (refs.any { !it.guarded && it.context in HARD_CONTEXTS }) return Verdict.Error
    return when {
        refs.any { it.guarded && it.context in HARD_CONTEXTS } -> Verdict.Warning(WarnReason.GUARDED_TRY)
        refs.any { it.context == RefContext.REFLECTION } -> Verdict.Warning(WarnReason.REFLECTION)
        refs.any { it.context == RefContext.SIGNATURE } -> Verdict.Warning(WarnReason.SIGNATURE_ONLY)
        ANNOTATION_ONLY_IGNORED_PREFIXES.any { owner.startsWith(it) } -> Verdict.Ignore
        else -> Verdict.Warning(WarnReason.ANNOTATION_ONLY)
    }
}

/**
 * owner 클래스가 어느 플러그인/라이브러리 소속인지로 정하는 규칙. 문맥과 무관하게 경고다.
 * 이름 → 패키지 대응은 휴리스틱이다: 플러그인 이름을 정규화한 토큰이 패키지 세그먼트와 일치하면 소속으로 본다
 * (`Vault` ↔ `net/milkbowl/vault`, `PlaceholderAPI` ↔ `me/clip/placeholderapi`). 이름과 패키지가 전혀 다른
 * 유명 플러그인은 [KNOWN_PLUGIN_PACKAGES] 로 보정한다.
 */
private class OwnerRules(meta: JarMeta?, private val apis: List<JarMemberIndex>) {
    private val soft = pluginMatchers(meta?.softDepend.orEmpty())
    private val hard = pluginMatchers(meta?.depend.orEmpty())
    private val libs = libraryMatchers(meta?.libraries.orEmpty())
    private val memo = HashMap<String, WarnReason?>()

    fun reasonFor(owner: String): WarnReason? = memo.getOrPut(owner) {
        val segments = owner.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.map { norm(it) }
        when {
            soft.any { it.matches(owner, segments) } -> WarnReason.SOFTDEPEND
            hard.any { it.matches(owner, segments) } && apis.none { it.hasPackage(owner.substringBeforeLast('/', "")) } -> WarnReason.DEPEND_ABSENT
            libs.any { it.matches(owner, segments) } -> WarnReason.LIBRARY
            else -> null
        }
    }

    private class Matcher(val prefixes: List<String>, val tokens: List<String>) {
        fun matches(owner: String, segments: List<String>): Boolean =
            prefixes.any { owner.startsWith(it) } ||
                tokens.any { t -> segments.any { s -> s == t || (t.length >= 5 && s.startsWith(t)) } }
    }

    private fun pluginMatchers(names: List<String>): List<Matcher> = names.map { name ->
        val n = norm(name)
        val stripped = n.removeSuffix("api").removeSuffix("plugin").removeSuffix("core").removeSuffix("x")
        val tokens = listOf(n, stripped).filter { it.length >= 4 }.distinct()
        Matcher(KNOWN_PLUGIN_PACKAGES[n].orEmpty(), tokens)
    }

    /** `group:artifact:version` → group 접두사 + artifact 토큰. 일반어(core/api/common…)는 토큰에서 뺀다. */
    private fun libraryMatchers(coords: List<String>): List<Matcher> = coords.mapNotNull { c ->
        val parts = c.split(':')
        if (parts.size < 2) return@mapNotNull null
        val group = parts[0].replace('.', '/') + "/"
        val tokens = parts[1].split('-', '_', '.').map(::norm).filter { it.length >= 4 && it !in GENERIC_ARTIFACT_WORDS }
        Matcher(listOf(group), tokens)
    }

    companion object {
        fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

        private val GENERIC_ARTIFACT_WORDS = setOf("core", "common", "commons", "java", "kotlin", "plugin", "impl", "client", "server", "spigot", "paper", "bukkit", "jdbc", "driver")

        /** 이름과 패키지가 다른 유명 플러그인 (정규화 이름 → 패키지 접두사). 휴리스틱 보정용 소표. */
        private val KNOWN_PLUGIN_PACKAGES: Map<String, List<String>> = mapOf(
            "protocollib" to listOf("com/comphenix/protocol/"),
            "essentials" to listOf("com/earth2me/essentials/", "net/ess3/"),
            "essentialsx" to listOf("com/earth2me/essentials/", "net/ess3/"),
            "citizens" to listOf("net/citizensnpcs/"),
            "towny" to listOf("com/palmergames/"),
            "multiversecore" to listOf("com/onarandombox/", "org/mvplugins/multiverse/"),
            "mcmmo" to listOf("com/gmail/nossr50/"),
            "griefprevention" to listOf("me/ryanhamshire/"),
            "mythicmobs" to listOf("io/lumine/"),
            "nbtapi" to listOf("de/tr7zw/"),
            "packetevents" to listOf("com/github/retrooper/packetevents/", "io/github/retrooper/packetevents/"),
            "floodgate" to listOf("org/geysermc/floodgate/"),
            "geysermc" to listOf("org/geysermc/"),
            "jobs" to listOf("com/gamingmesh/jobs/"),
            "itemsadder" to listOf("dev/lone/itemsadder/"),
            "oraxen" to listOf("io/th0rgal/oraxen/"),
            "nexo" to listOf("com/nexomc/nexo/"),
            "modelengine" to listOf("com/ticxo/modelengine/"),
            "vault" to listOf("net/milkbowl/vault/"),
            "placeholderapi" to listOf("me/clip/placeholderapi/"),
            "luckperms" to listOf("net/luckperms/"),
            "worldedit" to listOf("com/sk89q/worldedit/"),
            "worldguard" to listOf("com/sk89q/worldguard/"),
            "fastasyncworldedit" to listOf("com/fastasyncworldedit/", "com/sk89q/worldedit/"),
            "viaversion" to listOf("com/viaversion/"),
            "coreprotect" to listOf("net/coreprotect/"),
            "dynmap" to listOf("org/dynmap/"),
            "decentholograms" to listOf("eu/decentsoftware/"),
            "skript" to listOf("ch/njol/skript/"),
        )
    }
}

// ── 해석 ──────────────────────────────────────────────────────────────────

private sealed interface Lookup {
    data class Found(val info: ClassInfo) : Lookup

    /** 존재한다고 가정 (제거된 JDK API 를 옛 javaFeature 로 검증하는 경우 등). 멤버 검사도 생략한다. */
    data object Assumed : Lookup

    data object Missing : Lookup
}

/** 폴리모픽 시그니처 메서드는 디스크립터가 호출마다 다르다 — 이름만 맞으면 존재로 본다. */
private val POLYMORPHIC_OWNERS = setOf("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle")

private class Resolver(private val scan: PluginScan, private val apis: List<JarMemberIndex>, private val javaFeature: Int) {
    private val classMemo = HashMap<String, Lookup>()
    private val memberMemo = HashMap<String, Boolean>()

    fun lookup(name: String): Lookup = classMemo.getOrPut(name) { resolve(name) }

    private fun resolve(name: String): Lookup {
        scan.index.resolve(name, javaFeature)?.let { return Lookup.Found(it) }
        for (api in apis) api.resolve(name, javaFeature)?.let { return Lookup.Found(it) }
        if (JdkIndex.isJdkPackage(name)) {
            JdkIndex.classInfo(name)?.let { return Lookup.Found(it) }
        }
        return if (JdkRemovedApis.existedIn(name, javaFeature)) Lookup.Assumed else Lookup.Missing
    }

    /** true = 존재하거나 판단 불가(계층에 모르는 클래스가 있음). false = 계층 전체를 알고 있는데 없음. */
    fun hasMethod(owner: String, name: String, desc: String): Boolean = memberMemo.getOrPut("M:$owner.$name$desc") {
        if (owner in POLYMORPHIC_OWNERS) return@getOrPut true
        // 생성자는 상속되지 않는다 — owner 자신만 본다
        if (name == "<init>") return@getOrPut ownerOnly(owner) { ClassInfo.methodKey(name, desc) in it.methods }
        walk(owner) { ClassInfo.methodKey(name, desc) in it.methods }
    }

    fun hasField(owner: String, name: String, desc: String): Boolean = memberMemo.getOrPut("F:$owner.$name:$desc") {
        walk(owner) { ClassInfo.fieldKey(name, desc) in it.fields }
    }

    private inline fun ownerOnly(owner: String, has: (ClassInfo) -> Boolean): Boolean = when (val l = lookup(owner)) {
        is Lookup.Found -> has(l.info)
        else -> true
    }

    /** BFS: 자신 → 슈퍼 → 인터페이스. 인터페이스 계층은 `java/lang/Object` 의 메서드도 가진다 (JVMS §5.4.3.4). */
    private inline fun walk(owner: String, has: (ClassInfo) -> Boolean): Boolean {
        val queue = ArrayDeque<String>()
        val seen = HashSet<String>()
        queue += owner
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            when (val l = lookup(cur)) {
                is Lookup.Found -> {
                    if (has(l.info)) return true
                    l.info.superName?.let(queue::add)
                    queue.addAll(l.info.interfaces)
                    if (l.info.isInterface) queue += "java/lang/Object"
                }

                Lookup.Assumed -> return true

                Lookup.Missing -> if (cur != owner) return true // 계층 일부를 모른다 — 단정하지 않는다
            }
        }
        return false
    }
}
