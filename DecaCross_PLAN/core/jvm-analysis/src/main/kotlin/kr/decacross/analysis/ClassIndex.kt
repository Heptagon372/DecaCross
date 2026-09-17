package kr.decacross.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.module.ModuleFinder
import java.lang.module.ModuleReader
import java.lang.module.ModuleReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry

// ── 클래스 멤버 인덱스 ─────────────────────────────────────────────────────
// 정적 검증(StaticVerify)이 참조를 해석할 때 쓰는 "이 클래스에 무엇이 있나" 테이블.
// jar 하나를 한 번만 파싱해 두고(JarIndexCache) 여러 조합에서 재사용한다.

/**
 * .class 하나의 멤버 요약. 코드는 읽지 않는다(ClassReader.SKIP_CODE).
 *
 * # 불변식
 * - [name] 은 내부 이름(`org/bukkit/Bukkit`). 점(.) 표기는 쓰지 않는다.
 * - [methods] 키는 `name + descriptor`(`onEnable()V`), [fields] 키는 `name:descriptor`(`INSTANCE:Lfoo/Bar;`).
 */
public data class ClassInfo(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    /** `name+desc` → access flags */
    val methods: Map<String, Int>,
    /** `name:desc` → access flags */
    val fields: Map<String, Int>,
    val majorVersion: Int,
) {
    val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0

    public companion object {
        internal fun fieldKey(name: String, desc: String): String = "$name:$desc"

        internal fun methodKey(name: String, desc: String): String = "$name$desc"
    }
}

/** ASM 으로 .class 바이트를 읽어 [ClassInfo] 로 요약한다. 깨진 클래스면 null. */
public fun readClassInfo(bytes: ByteArray): ClassInfo? {
    if (bytes.size < 8) return null
    val collector = MemberCollector()
    return runCatching {
        ClassReader(bytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        collector.build()
    }.getOrNull()
}

internal class MemberCollector : ClassVisitor(Opcodes.ASM9) {
    private var name: String = ""
    private var superName: String? = null
    private var interfaces: List<String> = emptyList()
    private var access: Int = 0
    private var major: Int = 0
    private val methods = HashMap<String, Int>()
    private val fields = HashMap<String, Int>()

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        this.name = name
        this.superName = superName
        this.interfaces = interfaces?.toList() ?: emptyList()
        this.access = access
        this.major = version and 0xFFFF
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
        methods[ClassInfo.methodKey(name, descriptor)] = access
        return null
    }

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
        fields[ClassInfo.fieldKey(name, descriptor)] = access
        return null
    }

    fun build(): ClassInfo? = if (name.isEmpty()) null else ClassInfo(name, superName, interfaces, access, methods, fields, major)
}

// ── jar 하나의 인덱스 ──────────────────────────────────────────────────────

/**
 * jar 하나의 클래스 멤버 인덱스. 멀티 릴리스 jar(`META-INF/versions/N/`)는 [resolve] 호출 시
 * `javaFeature` 이하의 가장 높은 N 이 기본 클래스를 덮어쓴다 — 실제 JarFile 런타임 해석과 같은 규칙.
 *
 * # 불변식
 * - [base] 에는 `META-INF/` 아래 항목이 들어가지 않는다.
 * - `module-info` / `package-info` 는 인덱싱하지 않는다.
 */
public class JarMemberIndex internal constructor(
    public val path: Path,
    internal val base: Map<String, ClassInfo>,
    /** versions/N → (내부 이름 → ClassInfo). Multi-Release: true 인 jar 만 채워진다. */
    internal val versioned: Map<Int, Map<String, ClassInfo>>,
    /** 읽지 못한 항목 등 */
    public val notes: List<String>,
) {
    /** 기본 항목의 패키지 집합(`org/bukkit`). softdepend 판정에서 "라이브러리가 아예 없는가"를 볼 때 쓴다. */
    internal val packages: Set<String> by lazy { base.keys.mapTo(HashSet()) { it.substringBeforeLast('/', "") } }

    public val classNames: Set<String> get() = base.keys

    public val size: Int get() = base.size

    /** `javaFeature` 에서 런타임이 실제로 로드할 클래스 정의. */
    public fun resolve(internalName: String, javaFeature: Int): ClassInfo? {
        if (versioned.isNotEmpty()) {
            for (v in versioned.keys.sortedDescending()) {
                if (v <= javaFeature) versioned.getValue(v)[internalName]?.let { return it }
            }
        }
        return base[internalName]
    }

    public fun hasPackage(pkg: String): Boolean = pkg in packages
}

/** jar 안의 `.class` 항목을 돌며 [ClassInfo] 를 모은다. 읽기 실패는 예외 대신 notes 로 남긴다. */
internal fun indexJarMembers(jar: Path): JarMemberIndex {
    val base = HashMap<String, ClassInfo>()
    val versioned = HashMap<Int, HashMap<String, ClassInfo>>()
    val notes = ArrayList<String>()
    forEachClassEntry(jar) { e, cls, bytes ->
        val info = readClassInfo(bytes)
        if (info == null) {
            notes += "클래스를 읽지 못함: ${e.name}"
        } else if (cls.versionDir == null) {
            base[info.name] = info
        } else {
            versioned.getOrPut(cls.versionDir) { HashMap() }[info.name] = info
        }
    }
    return JarMemberIndex(jar, base, versioned, notes)
}

/** jar 항목이 인덱싱 대상 .class 인지 판정. `versionDir` 는 `META-INF/versions/N/` 의 N. */
internal data class ClassEntry(val internalName: String, val versionDir: Int?)

internal fun classifyEntry(e: ZipEntry): ClassEntry? {
    if (e.isDirectory || !e.name.endsWith(".class")) return null
    val name = e.name
    val simple = name.substringAfterLast('/')
    if (simple == "module-info.class" || simple == "package-info.class") return null
    if (name.startsWith("META-INF/")) {
        val rest = name.removePrefix("META-INF/versions/")
        if (rest == name) return null // META-INF 아래 다른 클래스(서명 등)는 무시
        val n = rest.substringBefore('/').toIntOrNull() ?: return null
        val inner = rest.substringAfter('/', "")
        if (inner.isEmpty()) return null
        return ClassEntry(inner.removeSuffix(".class"), n)
    }
    return ClassEntry(name.removeSuffix(".class"), null)
}

// ── JDK 인덱스 ────────────────────────────────────────────────────────────

/**
 * 실행 중인 JDK 의 시스템 모듈(jrt 이미지)에서 클래스를 읽는다. `ModuleFinder.ofSystem()` 기준이라
 * `java.*` 뿐 아니라 `jdk.*` / `sun.*` / `javax.*` 전부를 클래스로더 없이 해석한다.
 *
 * # 한계 (KDoc 에 명시하라는 요구사항)
 * - 해석 기준은 **실행 중인 JDK** 이지 `javaFeature` 가 아니다. `javaFeature` 가 실행 JDK 보다 낮으면
 *   그 사이에 **제거된** API(`javax.xml.bind` 등)는 "없음"으로 나오고(오탐), 그 사이에 **추가된** API 는
 *   "있음"으로 나온다(미탐). 제거 API 는 [JdkRemovedApis] 표로 일부 보정한다.
 */
internal object JdkIndex {
    private val packageToModule: Map<String, ModuleReference> by lazy {
        val map = HashMap<String, ModuleReference>()
        for (ref in ModuleFinder.ofSystem().findAll()) {
            for (pkg in ref.descriptor().packages()) map.putIfAbsent(pkg.replace('.', '/'), ref)
        }
        map
    }
    private val readers = ConcurrentHashMap<String, ModuleReader>()
    private val cache = ConcurrentHashMap<String, Optional<ClassInfo>>()

    /** 실행 중인 JDK 의 feature 버전(25 등). */
    val runtimeFeature: Int = Runtime.version().feature()

    /** 이 패키지가 시스템 모듈 소속인가 (= JDK 클래스인가). */
    fun isJdkPackage(internalName: String): Boolean = internalName.substringBeforeLast('/', "") in packageToModule

    fun classInfo(internalName: String): ClassInfo? {
        cache[internalName]?.let { return it.orElse(null) }
        val ref = packageToModule[internalName.substringBeforeLast('/', "")]
        val info = ref?.let { read(it, internalName) }
        if (cache.size > 40_000) cache.clear() // 상한: JDK 전체 클래스 수 근처에서 비운다
        cache[internalName] = Optional.ofNullable(info)
        return info
    }

    private fun read(ref: ModuleReference, internalName: String): ClassInfo? = runCatching {
        val reader = readers.computeIfAbsent(ref.descriptor().name()) { ref.open() }
        val buf = reader.read("$internalName.class").orElse(null) ?: return null
        try {
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            readClassInfo(bytes)
        } finally {
            reader.release(buf)
        }
    }.getOrNull()
}

/**
 * 실행 JDK 에서 제거됐지만 예전 JDK 에는 있던 패키지 → 제거된 feature. `javaFeature` 가 그보다 낮으면
 * "존재한다"로 본다. 수집 데이터가 아니라 JEP 이력이므로 코드에 둔다.
 */
internal object JdkRemovedApis {
    private val removed: List<Pair<String, Int>> = listOf(
        "javax/xml/bind" to 11,
        "javax/xml/ws" to 11,
        "javax/xml/soap" to 11,
        "javax/jws" to 11,
        "javax/activation" to 11,
        "javax/transaction" to 11,
        "javax/annotation" to 11,
        "org/omg" to 11,
        "javax/activity" to 11,
        "javax/rmi/CORBA" to 11,
        "sun/misc/BASE64Encoder" to 9,
        "sun/misc/BASE64Decoder" to 9,
        "java/security/acl" to 14,
        "jdk/nashorn" to 15,
        "javax/script/Nashorn" to 15,
        "java/applet" to 17,
        "javax/security/cert" to 25,
    )

    /** `javaFeature` 에서는 아직 존재했던 JDK 클래스인가. */
    fun existedIn(internalName: String, javaFeature: Int): Boolean =
        removed.any { (prefix, since) -> javaFeature < since && (internalName == prefix || internalName.startsWith("$prefix/")) }
}

// ── 캐시 ──────────────────────────────────────────────────────────────────

/**
 * jar 파싱 결과 캐시. 키는 (절대경로, 크기, 수정시각) 이라 같은 경로에 파일이 바뀌면 자동으로 무효화된다.
 * 코어 API jar 하나를 플러그인 수백 개가 공유하고, 플러그인 jar 하나를 MC 버전 수십 개가 공유하므로
 * 이 캐시가 "조합 1건 < 500ms" 를 만든다. LRU, [maxEntries] 로 상한.
 *
 * # 불변식
 * - 파일 핸들을 쥐고 있지 않는다. 파싱이 끝나면 jar 는 닫힌다 (분석 후 jar 삭제 가능해야 한다).
 * - 스레드 안전. verifier 가 병렬로 호출해도 된다.
 */
public object JarIndexCache {
    private data class Key(val path: String, val size: Long, val mtime: Long, val mode: Mode)

    private enum class Mode { MEMBERS, PLUGIN_SCAN }

    @Volatile
    public var maxEntries: Int = 64
        set(value) {
            require(value >= 1) { "maxEntries 는 1 이상" }
            field = value
            synchronized(lock) { evict() }
        }

    private val lock = Any()
    private val lru = object : LinkedHashMap<Key, Any>(32, 0.75f, true) {}

    @Volatile
    public var hits: Long = 0L
        private set

    @Volatile
    public var misses: Long = 0L
        private set

    public val size: Int get() = synchronized(lock) { lru.size }

    /** 멤버 인덱스(코드 미포함). 코어 API jar 와 함께 설치될 플러그인에 쓴다. */
    public fun memberIndex(jar: Path): JarMemberIndex {
        val key = keyOf(jar, Mode.MEMBERS)
        // 같은 파일의 PLUGIN_SCAN 이 이미 있으면 그 안의 인덱스를 재사용한다
        synchronized(lock) {
            lru[key]?.let {
                hits++
                return it as JarMemberIndex
            }
            lru[key.copy(mode = Mode.PLUGIN_SCAN)]?.let {
                hits++
                return (it as PluginScan).index
            }
        }
        val built = indexJarMembers(jar)
        synchronized(lock) {
            misses++
            lru[key] = built
            evict()
        }
        return built
    }

    /** 참조 스캔(코드 포함). 검증 대상 플러그인 jar 에 쓴다. */
    public fun pluginScan(jar: Path): PluginScan {
        val key = keyOf(jar, Mode.PLUGIN_SCAN)
        synchronized(lock) {
            lru[key]?.let {
                hits++
                return it as PluginScan
            }
        }
        val built = scanPluginJar(jar)
        synchronized(lock) {
            misses++
            lru[key] = built
            evict()
        }
        return built
    }

    public fun invalidate(jar: Path) {
        synchronized(lock) { lru.keys.removeIf { it.path == normalize(jar) } }
    }

    public fun clear() {
        synchronized(lock) {
            lru.clear()
            hits = 0
            misses = 0
        }
    }

    private fun evict() {
        val it = lru.entries.iterator()
        while (lru.size > maxEntries && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun normalize(jar: Path): String = jar.toAbsolutePath().normalize().toString()

    private fun keyOf(jar: Path, mode: Mode): Key {
        val attrs = Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes::class.java)
        return Key(normalize(jar), attrs.size(), attrs.lastModifiedTime().toMillis(), mode)
    }
}
