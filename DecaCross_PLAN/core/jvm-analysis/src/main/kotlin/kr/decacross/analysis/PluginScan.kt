package kr.decacross.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.nio.file.Path
import java.util.jar.JarFile

// ── 플러그인 jar 참조 스캔 ─────────────────────────────────────────────────
// 정적 검증의 입력. jar 한 번 읽어 "어떤 클래스의 어떤 메서드가 무엇을 참조하는가"를 전부 모아 두고
// (JarIndexCache 가 보관) 코어 API 조합마다 해석만 다시 한다.

/**
 * 참조가 발견된 문맥. 검증기가 경고/에러를 가르는 1차 근거다 (CLAUDE.md 불변식 16).
 *
 * - [HIERARCHY] / [INSTRUCTION] / [LDC_TYPE] / [CATCH_TYPE]: 클래스가 로드·실행되면 반드시 해석되는 참조.
 * - [SIGNATURE]: 플러그인 자신이 선언한 필드/메서드 디스크립터 안의 타입. JVM 이 지연 로드하므로 없어도 안 터질 수 있다.
 * - [ANNOTATION]: 어노테이션 타입/값. JVM 은 없는 어노테이션 클래스를 조용히 무시한다.
 * - [REFLECTION]: `Class.forName` 계열 호출이 있는 메서드의 클래스명 문자열 상수. 바이트코드 참조가 아니다.
 */
public enum class RefContext { HIERARCHY, INSTRUCTION, LDC_TYPE, CATCH_TYPE, SIGNATURE, ANNOTATION, REFLECTION }

/**
 * 플러그인 클래스 하나가 밖을 향해 낸 참조 하나.
 *
 * # 불변식
 * - [owner] 는 내부 이름(`org/bukkit/Bukkit`)이며 배열 타입(`[L...;`)이나 기본형은 절대 오지 않는다.
 * - [from] 은 `클래스` 또는 `클래스.메서드(디스크립터)` 형태의 사람이 읽는 위치.
 * - [guarded] 는 NoClassDefFoundError 계열을 잡는 try 블록 안의 명령이었는지. 명령 밖(HIERARCHY 등)은 항상 false.
 */
public sealed interface ClassRef {
    public val owner: String
    public val from: String
    public val context: RefContext
    public val guarded: Boolean
}

public data class TypeRef(
    override val owner: String,
    override val from: String,
    override val context: RefContext,
    override val guarded: Boolean = false,
) : ClassRef

public data class FieldRef(
    override val owner: String,
    val name: String,
    val desc: String,
    override val from: String,
    override val context: RefContext = RefContext.INSTRUCTION,
    override val guarded: Boolean = false,
) : ClassRef

public data class MethodRef(
    override val owner: String,
    val name: String,
    val desc: String,
    val isInterface: Boolean,
    override val from: String,
    override val context: RefContext = RefContext.INSTRUCTION,
    override val guarded: Boolean = false,
) : ClassRef

/** 플러그인 클래스 하나의 스캔 결과. [info] 는 멤버 요약, [refs] 는 밖을 향한 참조 전부. */
public data class ScannedClass(
    val info: ClassInfo,
    val refs: List<ClassRef>,
)

/**
 * 플러그인 jar 하나의 스캔 결과. 코드까지 읽으므로 [JarMemberIndex] 보다 무겁다 — 검증 대상 jar 에만 쓴다.
 *
 * # 불변식
 * - [index] 는 [classes] 와 [versioned] 를 합쳐 만든 멤버 인덱스라 항상 같은 클래스 집합을 본다.
 * - 파일 핸들을 쥐지 않는다. 스캔이 끝나면 jar 는 닫혀 있다.
 */
public class PluginScan internal constructor(
    public val path: Path,
    /** 기본 항목(`META-INF/versions/` 밖) 내부 이름 → 스캔 결과 */
    public val classes: Map<String, ScannedClass>,
    /** Multi-Release jar 의 versions/N → (내부 이름 → 스캔 결과) */
    public val versioned: Map<Int, Map<String, ScannedClass>>,
    public val meta: JarMeta?,
    public val notes: List<String>,
) {
    public val index: JarMemberIndex = JarMemberIndex(
        path = path,
        base = classes.mapValues { it.value.info },
        versioned = versioned.mapValues { (_, m) -> m.mapValues { it.value.info } },
        notes = notes,
    )

    /** `javaFeature` 런타임이 실제로 로드할 클래스 집합 (버전 디렉터리가 기본 항목을 덮어쓴다). */
    public fun effectiveClasses(javaFeature: Int): Collection<ScannedClass> {
        if (versioned.isEmpty()) return classes.values
        val merged = HashMap(classes)
        for (v in versioned.keys.sorted()) {
            if (v <= javaFeature) merged.putAll(versioned.getValue(v))
        }
        return merged.values
    }
}

/** jar 의 모든 `.class` 를 코드 포함으로 읽어 [PluginScan] 을 만든다. 읽기 실패는 notes 로 남기고 계속 간다. */
public fun scanPluginJar(jar: Path): PluginScan {
    val base = HashMap<String, ScannedClass>()
    val versioned = HashMap<Int, HashMap<String, ScannedClass>>()
    val notes = ArrayList<String>()
    val opened = runCatching { JarFile(jar.toFile()) }.getOrElse { e ->
        return PluginScan(jar, emptyMap(), emptyMap(), null, listOf("jar 를 열지 못함: ${e.message}"))
    }
    opened.use { jf ->
        val multiRelease = jf.manifest?.mainAttributes?.getValue("Multi-Release")?.trim().equals("true", ignoreCase = true)
        val entries = jf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val cls = classifyEntry(e) ?: continue
            if (cls.versionDir != null && !multiRelease) continue
            val bytes = runCatching { jf.getInputStream(e).use { it.readBytes() } }.getOrNull()
            val scanned = bytes?.let { scanClass(it) }
            if (scanned == null) {
                notes += "클래스를 읽지 못함: ${e.name}"
                continue
            }
            val target = if (cls.versionDir == null) base else versioned.getOrPut(cls.versionDir) { HashMap() }
            target[scanned.info.name] = scanned
        }
    }
    val meta = readJarMeta(jar)
    return PluginScan(jar, base, versioned, meta, notes)
}

/** .class 바이트 하나를 스캔한다. 깨진 클래스면 null. */
public fun scanClass(bytes: ByteArray): ScannedClass? = runCatching {
    val node = ClassNode()
    ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
    if (node.name.isNullOrEmpty()) null else RefCollector(node).collect()
}.getOrNull()

/**
 * try 블록의 핸들러가 이 타입들 중 하나를 잡으면 그 안의 미해결 참조는 "선택적 의존" 패턴으로 본다.
 * (`catch (NoClassDefFoundError)` 가 정석이지만 `Throwable`/`Exception` 으로 뭉뚱그리는 플러그인도 흔하다.)
 */
internal val GUARD_EXCEPTION_TYPES: Set<String> = setOf(
    "java/lang/NoClassDefFoundError",
    "java/lang/ClassNotFoundException",
    "java/lang/NoSuchMethodError",
    "java/lang/NoSuchFieldError",
    "java/lang/NoSuchMethodException",
    "java/lang/NoSuchFieldException",
    "java/lang/ReflectiveOperationException",
    "java/lang/IncompatibleClassChangeError",
    "java/lang/LinkageError",
    "java/lang/Throwable",
    "java/lang/Exception",
    "java/lang/Error",
)

/** `Class.forName` 류로 취급하는 호출. 이 호출이 있는 메서드의 클래스명 문자열은 REFLECTION 참조가 된다. */
private val REFLECTIVE_LOADERS: Set<String> = setOf(
    "java/lang/Class.forName",
    "java/lang/ClassLoader.loadClass",
    "java/lang/ClassLoader.findClass",
)

private val CLASS_NAME_STRING = Regex("""^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Z][A-Za-z0-9_$]*$""")

private class RefCollector(private val node: ClassNode) {
    private val refs = ArrayList<ClassRef>()
    private val className = node.name
    private val methods = HashMap<String, Int>()
    private val fields = HashMap<String, Int>()

    fun collect(): ScannedClass {
        node.superName?.let { addType(it, className, RefContext.HIERARCHY) }
        node.interfaces?.forEach { addType(it, className, RefContext.HIERARCHY) }
        annotations(node.visibleAnnotations, className)
        annotations(node.invisibleAnnotations, className)
        annotations(node.visibleTypeAnnotations, className)
        annotations(node.invisibleTypeAnnotations, className)
        node.nestHostClass?.let { addType(it, className, RefContext.SIGNATURE) }
        node.permittedSubclasses?.forEach { addType(it, className, RefContext.SIGNATURE) }

        for (f in node.fields) {
            fields[ClassInfo.fieldKey(f.name, f.desc)] = f.access
            val from = "$className.${f.name}"
            descTypes(f.desc).forEach { addType(it, from, RefContext.SIGNATURE) }
            annotations(f.visibleAnnotations, from)
            annotations(f.invisibleAnnotations, from)
        }
        for (m in node.methods) {
            methods[ClassInfo.methodKey(m.name, m.desc)] = m.access
            if (m.access and Opcodes.ACC_BRIDGE != 0) continue // 브리지는 실제 메서드로 위임만 한다
            val from = "$className.${m.name}${m.desc}"
            methodDescTypes(m.desc).forEach { addType(it, from, RefContext.SIGNATURE) }
            m.exceptions?.forEach { addType(it, from, RefContext.SIGNATURE) }
            annotations(m.visibleAnnotations, from)
            annotations(m.invisibleAnnotations, from)
            m.visibleParameterAnnotations?.forEach { annotations(it, from) }
            m.invisibleParameterAnnotations?.forEach { annotations(it, from) }
            m.annotationDefault?.let { annotationValue(it, from) }
            code(m, from)
        }
        val info = ClassInfo(
            name = className,
            superName = node.superName,
            interfaces = node.interfaces ?: emptyList(),
            access = node.access,
            methods = methods,
            fields = fields,
            majorVersion = node.version and 0xFFFF,
        )
        return ScannedClass(info, refs)
    }

    private fun code(m: MethodNode, from: String) {
        val insns = m.instructions
        if (insns == null || insns.size() == 0) return
        // 가드 구간: [start, end) 인덱스, 핸들러 타입이 GUARD_EXCEPTION_TYPES 인 것만
        val guards = m.tryCatchBlocks.orEmpty()
            .filter { it.type != null && it.type in GUARD_EXCEPTION_TYPES }
            .map { insns.indexOf(it.start) until insns.indexOf(it.end) }
        m.tryCatchBlocks.orEmpty().forEach { tcb ->
            tcb.type?.let { addType(it, from, RefContext.CATCH_TYPE) }
        }
        var reflective = false
        val stringConstants = ArrayList<String>()
        var insn: AbstractInsnNode? = insns.first
        var i = 0
        while (insn != null) {
            val guarded = guards.any { i in it }
            when (insn) {
                is TypeInsnNode -> addType(objectTypeName(insn.desc), from, RefContext.INSTRUCTION, guarded)

                is FieldInsnNode -> {
                    addFieldRef(insn.owner, insn.name, insn.desc, from, guarded)
                    descTypes(insn.desc).forEach { addType(it, from, RefContext.SIGNATURE, guarded) }
                }

                is MethodInsnNode -> {
                    addMethodRef(insn.owner, insn.name, insn.desc, insn.itf, from, guarded)
                    methodDescTypes(insn.desc).forEach { addType(it, from, RefContext.SIGNATURE, guarded) }
                    if ("${insn.owner}.${insn.name}" in REFLECTIVE_LOADERS) reflective = true
                }

                is LdcInsnNode -> when (val c = insn.cst) {
                    is Type -> elementName(c)?.let { addType(it, from, RefContext.LDC_TYPE, guarded) }
                    is Handle -> handle(c, from, guarded)
                    is String -> if (CLASS_NAME_STRING.matches(c)) stringConstants += c
                }

                is InvokeDynamicInsnNode -> {
                    handle(insn.bsm, from, guarded)
                    // 람다의 함수형 인터페이스 = indy 반환 타입. LambdaMetafactory 가 링크 시 로드한다.
                    elementName(Type.getReturnType(insn.desc))?.let { addType(it, from, RefContext.INSTRUCTION, guarded) }
                    insn.bsmArgs?.forEach { arg ->
                        when (arg) {
                            is Handle -> handle(arg, from, guarded)

                            is Type -> if (arg.sort == Type.METHOD) {
                                methodDescTypes(arg.descriptor).forEach { addType(it, from, RefContext.SIGNATURE, guarded) }
                            } else {
                                elementName(arg)?.let { addType(it, from, RefContext.SIGNATURE, guarded) }
                            }
                        }
                    }
                }

                is MultiANewArrayInsnNode -> addType(objectTypeName(insn.desc), from, RefContext.INSTRUCTION, guarded)
            }
            insn = insn.next
            i++
        }
        if (reflective) {
            for (s in stringConstants) refs += TypeRef(s.replace('.', '/'), from, RefContext.REFLECTION, guarded = true)
        }
    }

    private fun handle(h: Handle, from: String, guarded: Boolean) {
        when (h.tag) {
            Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
                addFieldRef(h.owner, h.name, h.desc, from, guarded)

            else -> addMethodRef(h.owner, h.name, h.desc, h.isInterface, from, guarded)
        }
    }

    private fun addFieldRef(owner: String, name: String, desc: String, from: String, guarded: Boolean) {
        if (owner.startsWith("[")) return // 배열의 필드(length 는 명령이 따로 있다) — 무시
        refs += FieldRef(owner, name, desc, from, RefContext.INSTRUCTION, guarded)
    }

    private fun addMethodRef(owner: String, name: String, desc: String, itf: Boolean, from: String, guarded: Boolean) {
        if (owner.startsWith("[")) return // 배열의 clone() 등은 Object 로 해석된다
        refs += MethodRef(owner, name, desc, itf, from, RefContext.INSTRUCTION, guarded)
    }

    private fun addType(name: String?, from: String, ctx: RefContext, guarded: Boolean = false) {
        if (name.isNullOrEmpty()) return
        refs += TypeRef(name, from, ctx, guarded)
    }

    private fun annotations(list: List<AnnotationNode>?, from: String) {
        list?.forEach { a ->
            elementName(Type.getType(a.desc))?.let { addType(it, from, RefContext.ANNOTATION) }
            a.values?.let { vs -> vs.forEach { annotationValue(it, from) } }
        }
    }

    private fun annotationValue(v: Any?, from: String) {
        when (v) {
            is Type -> elementName(v)?.let { addType(it, from, RefContext.ANNOTATION) }

            is Array<*> -> (v.firstOrNull() as? String)?.let { d ->
                // enum 값: [desc, name]
                if (d.startsWith("L")) elementName(Type.getType(d))?.let { addType(it, from, RefContext.ANNOTATION) }
            }

            is AnnotationNode -> annotations(listOf(v), from)

            is List<*> -> v.forEach { annotationValue(it, from) }
        }
    }
}

/** `NEW`/`CHECKCAST` 의 desc: 내부 이름이거나 배열 디스크립터. 배열이면 원소 클래스 이름, 기본형이면 null. */
internal fun objectTypeName(desc: String): String? =
    if (desc.startsWith("[")) elementName(Type.getType(desc)) else desc

/** 배열은 벗기고, 객체 타입이면 내부 이름. 기본형·void·메서드 타입은 null. */
internal fun elementName(t: Type): String? = when (t.sort) {
    Type.OBJECT -> t.internalName
    Type.ARRAY -> elementName(t.elementType)
    else -> null
}

internal fun descTypes(desc: String): List<String> = listOfNotNull(elementName(Type.getType(desc)))

internal fun methodDescTypes(desc: String): List<String> {
    val out = ArrayList<String>(4)
    Type.getArgumentTypes(desc).forEach { elementName(it)?.let(out::add) }
    elementName(Type.getReturnType(desc))?.let(out::add)
    return out
}
