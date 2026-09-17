package kr.decacross.analysis

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.jar.JarFile

// ── 플러그인 jar 참조 스캔 (코드 포함) ───────────────────────────────────────
// StaticVerify(P2-a) 의 입력. 여기서는 "무엇을 참조하는가 + 어디서 + 어떤 catch 아래서" 만 수집하고
// 에러/경고 분류는 하지 않는다 (CLAUDE.md 불변식 16 의 판단은 StaticVerify 몫).

/** 참조가 생기는 위치. 링크 시점과 실패 양상이 달라서 분류 기준이 된다. */
public enum class RefKind {
    /** 상위 클래스. 없으면 클래스 로드 자체가 실패한다. */
    SUPER,

    /** 구현 인터페이스. 없으면 클래스 로드 자체가 실패한다. */
    INTERFACE,

    /** 클래스·메서드·필드·파라미터 어노테이션 타입. 없어도 링크 오류가 아니다 (리플렉션이 조용히 무시). */
    ANNOTATION,

    /** 선언된 필드의 타입. `getDeclaredFields()` 때 터진다. */
    FIELD_DECL,

    /** 선언된 메서드의 파라미터·반환·throws 타입. `getDeclaredMethods()` (Bukkit 리스너 등록) 때 터진다. */
    METHOD_DECL,

    /** catch 절 예외 타입. 바이트코드 검증 중 로드될 수 있다. */
    CATCH,

    /** NEW / ANEWARRAY / MULTIANEWARRAY / CHECKCAST / INSTANCEOF / LDC 클래스 상수. */
    TYPE_INSN,

    /** GETFIELD / PUTFIELD / GETSTATIC / PUTSTATIC. */
    FIELD_ACCESS,

    /** INVOKEVIRTUAL / INVOKESPECIAL / INVOKESTATIC / INVOKEINTERFACE. */
    METHOD_INVOKE,

    /** invokedynamic·LDC 의 MethodHandle / MethodType / 호출지점 타입 (람다·메서드 참조). */
    METHOD_HANDLE,
}

/**
 * 참조 대상 하나.
 *
 * # 불변식
 * - [owner] 는 내부 이름(`org/bukkit/Bukkit`). 배열이면 원소 타입으로 풀어서 담고, 원시 타입 배열은 담지 않는다.
 * - 타입 참조([name] == null)와 멤버 참조를 같은 타입으로 표현한다. 멤버 참조면 [name]/[descriptor] 둘 다 non-null.
 */
public data class RefTarget(
    val kind: RefKind,
    val owner: String,
    val name: String? = null,
    val descriptor: String? = null,
    /** 바이트코드 opcode (`Opcodes.INVOKESTATIC` 등) 또는 Handle tag. 코드 밖 참조면 -1. */
    val opcode: Int = -1,
    /** INVOKE*·Handle 의 `itf` 플래그. owner 가 인터페이스↔클래스로 바뀌면 IncompatibleClassChangeError. */
    val ownerIsInterface: Boolean = false,
)

/**
 * 참조가 발생한 지점.
 *
 * [caught] 는 이 명령을 덮는 예외 핸들러들의 catch 타입(내부 이름). `finally`(타입 없음)는 넣지 않는다 —
 * finally 는 예외를 삼키지 않는다. 코드 밖 참조(SUPER 등)는 항상 빈 집합.
 */
public data class ScannedRef(
    val target: RefTarget,
    /** `name+descriptor`. 클래스 수준 참조면 null. */
    val fromMember: String?,
    val caught: Set<String>,
)

/** `Class.forName("a.b.C")` / `ClassLoader.loadClass("a.b.C")` 같은 문자열 기반 클래스 조회. */
public data class ReflectiveLookup(
    /** 내부 이름으로 변환한 대상 (`a/b/C`) */
    val className: String,
    /** 호출된 API (`java/lang/Class.forName`) */
    val api: String,
    val fromMember: String,
    val caught: Set<String>,
)

/** 클래스 하나의 스캔 결과. */
public data class ClassScan(
    val name: String,
    /** `META-INF/versions/N/` 의 N. 기본 항목이면 null. */
    val versionDir: Int?,
    val majorVersion: Int,
    val refs: List<ScannedRef>,
    val reflective: List<ReflectiveLookup>,
)

/**
 * 플러그인 jar 하나의 코드 포함 스캔 결과. [JarIndexCache.pluginScan] 이 캐시한다.
 *
 * # 불변식
 * - [index] 는 같은 jar 에 대한 `indexJarMembers` 결과와 동일한 내용이다 (MEMBERS 캐시가 재사용한다).
 * - jar 파일 핸들을 쥐고 있지 않다.
 */
public class PluginScan internal constructor(
    public val index: JarMemberIndex,
    internal val base: Map<String, ClassScan>,
    internal val versioned: Map<Int, Map<String, ClassScan>>,
    /** jar 전체 LDC 문자열 중 FQCN 모양(`a.b.C`)인 것 → 내부 이름. 리플렉션 경고 판정의 약한 근거. */
    public val classNameStrings: Set<String>,
) {
    /** 기본 항목 클래스들의 major version 최댓값. MR jar 의 versions/N 은 제외 (그 JVM 에서만 로드된다). */
    public val maxBaseMajor: Int? by lazy { base.values.maxOfOrNull { it.majorVersion } }

    /** `javaFeature` 에서 실제로 로드될 클래스 정의들. [JarMemberIndex.resolve] 와 같은 규칙. */
    public fun effectiveClasses(javaFeature: Int): List<ClassScan> {
        if (versioned.isEmpty()) return base.values.toList()
        val out = HashMap(base)
        for (v in versioned.keys.sorted()) {
            if (v <= javaFeature) out.putAll(versioned.getValue(v))
        }
        return out.values.toList()
    }
}

/** jar 안 모든 `.class` 를 코드까지 읽어 [PluginScan] 을 만든다. 읽기 실패는 notes 로 남긴다. */
internal fun scanPluginJar(jar: Path): PluginScan {
    val infos = HashMap<String, ClassInfo>()
    val versionedInfos = HashMap<Int, HashMap<String, ClassInfo>>()
    val base = HashMap<String, ClassScan>()
    val versioned = HashMap<Int, HashMap<String, ClassScan>>()
    val strings = HashSet<String>()
    val notes = ArrayList<String>()
    forEachClassEntry(jar) { entry, cls, bytes ->
        val result = runCatching { scanClass(bytes, cls.versionDir, strings) }.getOrNull()
        if (result == null) {
            notes += "클래스를 읽지 못함: ${entry.name}"
        } else {
            val (info, scan) = result
            if (cls.versionDir == null) {
                infos[info.name] = info
                base[info.name] = scan
            } else {
                versionedInfos.getOrPut(cls.versionDir) { HashMap() }[info.name] = info
                versioned.getOrPut(cls.versionDir) { HashMap() }[info.name] = scan
            }
        }
    }
    val index = JarMemberIndex(jar, infos, versionedInfos, notes)
    return PluginScan(index, base, versioned, strings)
}

/**
 * jar 의 인덱싱 대상 `.class` 항목을 순회한다. `indexJarMembers` 와 `scanPluginJar` 가 공유해서
 * 두 결과의 클래스 집합이 항상 같게 만든다. Multi-Release 가 아니면 versions/N 항목은 건너뛴다.
 */
internal inline fun forEachClassEntry(jar: Path, action: (java.util.zip.ZipEntry, ClassEntry, ByteArray) -> Unit) {
    // verify=false: 서명 검증은 분석과 무관하고 느리다
    JarFile(jar.toFile(), false).use { jf ->
        val multiRelease = jf.manifest?.mainAttributes?.getValue("Multi-Release")?.trim().equals("true", ignoreCase = true)
        val entries = jf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            val cls = classifyEntry(e) ?: continue
            if (cls.versionDir != null && !multiRelease) continue
            val bytes = jf.getInputStream(e).use { it.readBytes() }
            action(e, cls, bytes)
        }
    }
}

private fun scanClass(bytes: ByteArray, versionDir: Int?, strings: MutableSet<String>): Pair<ClassInfo, ClassScan>? {
    if (bytes.size < 8) return null
    val members = MemberCollector()
    val refs = RefCollector(members, strings)
    // SKIP_FRAMES: 참조 수집에 프레임은 불필요. SKIP_DEBUG: 라인 번호 대신 속도.
    ClassReader(bytes).accept(refs, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
    val info = members.build() ?: return null
    val scan = ClassScan(info.name, versionDir, info.majorVersion, refs.refs.toList(), refs.reflective.toList())
    return info to scan
}

private val CLASS_NAME_STRING = Regex("""^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+$""")

/** 내부 이름 또는 배열 기술자 → 원소 클래스 내부 이름. 원시 타입이면 null. */
private fun elementClass(internalOrArray: String): String? {
    if (!internalOrArray.startsWith("[")) return internalOrArray
    val t = Type.getType(internalOrArray).elementType
    return if (t.sort == Type.OBJECT) t.internalName else null
}

/** 필드·메서드 기술자 안의 클래스 타입들. */
private fun classesInDescriptor(desc: String): List<String> {
    val t = Type.getType(desc)
    val types = if (t.sort == Type.METHOD) t.argumentTypes.toList() + t.returnType else listOf(t)
    return types.mapNotNull(::classOf)
}

private fun classOf(t: Type): String? = when (t.sort) {
    Type.OBJECT -> t.internalName
    Type.ARRAY -> t.elementType.takeIf { it.sort == Type.OBJECT }?.internalName
    else -> null
}

private class RefCollector(
    delegate: ClassVisitor,
    val strings: MutableSet<String>,
) : ClassVisitor(Opcodes.ASM9, delegate) {
    val refs = LinkedHashSet<ScannedRef>()
    val reflective = LinkedHashSet<ReflectiveLookup>()

    fun addType(kind: RefKind, internalOrArray: String?, member: String?, caught: Set<String>) {
        val owner = internalOrArray?.let(::elementClass) ?: return
        refs += ScannedRef(RefTarget(kind, owner), member, caught)
    }

    fun addDescriptor(kind: RefKind, desc: String, member: String?, caught: Set<String>) {
        for (c in classesInDescriptor(desc)) refs += ScannedRef(RefTarget(kind, c), member, caught)
    }

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        super.visit(version, access, name, signature, superName, interfaces)
        addType(RefKind.SUPER, superName, null, emptySet())
        interfaces?.forEach { addType(RefKind.INTERFACE, it, null, emptySet()) }
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        addDescriptor(RefKind.ANNOTATION, descriptor, null, emptySet())
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
        val member = ClassInfo.fieldKey(name, descriptor)
        addDescriptor(RefKind.FIELD_DECL, descriptor, member, emptySet())
        val next = super.visitField(access, name, descriptor, signature, value)
        return object : FieldVisitor(Opcodes.ASM9, next) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                addDescriptor(RefKind.ANNOTATION, descriptor, member, emptySet())
                return super.visitAnnotation(descriptor, visible)
            }
        }
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val member = ClassInfo.methodKey(name, descriptor)
        addDescriptor(RefKind.METHOD_DECL, descriptor, member, emptySet())
        exceptions?.forEach { addType(RefKind.METHOD_DECL, it, member, emptySet()) }
        return RefMethodVisitor(this, member, super.visitMethod(access, name, descriptor, signature, exceptions))
    }
}

private class RefMethodVisitor(
    private val owner: RefCollector,
    private val member: String,
    next: MethodVisitor?,
) : MethodVisitor(Opcodes.ASM9, next) {
    private class TryBlock(val start: Label, val end: Label, val type: String)

    private val tries = ArrayList<TryBlock>()
    private val active = ArrayList<TryBlock>()
    private var caught: Set<String> = emptySet()
    private var lastClassString: String? = null

    // ClassReader 는 명령어보다 먼저 예외 테이블을 방문한다 (MethodVisitor 계약: 라벨 방문 전에 호출).
    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        if (type != null) {
            tries += TryBlock(start, end, type)
            owner.addType(RefKind.CATCH, type, member, emptySet())
        }
        super.visitTryCatchBlock(start, end, handler, type)
    }

    override fun visitLabel(label: Label) {
        // end 는 배타적이라 먼저 닫고, 같은 라벨에서 시작하는 블록을 연다
        val closed = active.removeAll { it.end === label }
        var opened = false
        for (t in tries) {
            if (t.start === label && t.start !== t.end) {
                active += t
                opened = true
            }
        }
        if (closed || opened) caught = active.mapTo(HashSet()) { it.type }
        super.visitLabel(label)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        owner.addDescriptor(RefKind.ANNOTATION, descriptor, member, emptySet())
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor? {
        owner.addDescriptor(RefKind.ANNOTATION, descriptor, member, emptySet())
        return super.visitParameterAnnotation(parameter, descriptor, visible)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        owner.addType(RefKind.TYPE_INSN, type, member, caught)
        super.visitTypeInsn(opcode, type)
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        owner.addDescriptor(RefKind.TYPE_INSN, descriptor, member, caught)
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
    }

    override fun visitFieldInsn(opcode: Int, fieldOwner: String, name: String, descriptor: String) {
        elementClass(fieldOwner)?.let {
            owner.refs += ScannedRef(RefTarget(RefKind.FIELD_ACCESS, it, name, descriptor, opcode), member, caught)
        }
        super.visitFieldInsn(opcode, fieldOwner, name, descriptor)
    }

    override fun visitMethodInsn(opcode: Int, methodOwner: String, name: String, descriptor: String, isInterface: Boolean) {
        if (methodOwner.startsWith("[")) {
            // 배열의 clone() 등 — 멤버는 항상 존재, 원소 타입만 의미 있다
            owner.addType(RefKind.TYPE_INSN, methodOwner, member, caught)
        } else {
            owner.refs += ScannedRef(RefTarget(RefKind.METHOD_INVOKE, methodOwner, name, descriptor, opcode, isInterface), member, caught)
        }
        val lookupApi = isReflectiveLookup(methodOwner, name, descriptor)
        val target = lastClassString
        if (lookupApi && target != null) {
            owner.reflective += ReflectiveLookup(target.replace('.', '/'), "$methodOwner.$name", member, caught)
            lastClassString = null
        }
        super.visitMethodInsn(opcode, methodOwner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any?) {
        owner.addDescriptor(RefKind.METHOD_HANDLE, descriptor, member, caught)
        addHandle(bootstrapMethodHandle)
        bootstrapMethodArguments.forEach(::addConstant)
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitLdcInsn(value: Any?) {
        when (value) {
            is String -> if (CLASS_NAME_STRING.matches(value)) {
                owner.strings.add(value.replace('.', '/'))
                lastClassString = value
            }

            is Type -> if (value.sort == Type.OBJECT || value.sort == Type.ARRAY) {
                owner.addType(RefKind.TYPE_INSN, value.internalName, member, caught)
            } else {
                addConstant(value)
            }

            else -> addConstant(value)
        }
        super.visitLdcInsn(value)
    }

    private fun addHandle(h: Handle) {
        elementClass(h.owner)?.let {
            owner.refs += ScannedRef(RefTarget(RefKind.METHOD_HANDLE, it, h.name, h.desc, h.tag, h.isInterface), member, caught)
        }
    }

    private fun addConstant(c: Any?) {
        when (c) {
            is Handle -> addHandle(c)

            is Type -> when (c.sort) {
                Type.METHOD -> owner.addDescriptor(RefKind.METHOD_HANDLE, c.descriptor, member, caught)
                Type.OBJECT, Type.ARRAY -> owner.addType(RefKind.METHOD_HANDLE, c.internalName, member, caught)
                else -> Unit
            }

            is ConstantDynamic -> {
                addHandle(c.bootstrapMethod)
                for (i in 0 until c.bootstrapMethodArgumentCount) addConstant(c.getBootstrapMethodArgument(i))
            }

            else -> Unit
        }
    }

    private fun isReflectiveLookup(owner: String, name: String, desc: String): Boolean =
        (owner == "java/lang/Class" && name == "forName" && desc.contains("Ljava/lang/String;")) ||
            (owner == "java/lang/ClassLoader" && name == "loadClass" && desc.startsWith("(Ljava/lang/String;"))
}
