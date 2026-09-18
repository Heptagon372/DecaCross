package kr.decacross.analysis.testutil

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ── 테스트용 jar·class 생성기 ──────────────────────────────────────────────
// 내려받은 jar 를 쓰지 않는다: 전부 메모리에서 ASM ClassWriter + ZipOutputStream 으로 만든다.
// 임시 파일은 JVM 종료 시 지운다 (deleteOnExit) — 분석 API 가 핸들을 닫으므로 Windows 에서도 지워진다.

const val SERVICES_MANAGER: String = "org/bukkit/plugin/ServicesManager"
const val SIMPLE_SERVICES_MANAGER: String = "org/bukkit/plugin/SimpleServicesManager"
const val REGISTER_DESC: String =
    "(Ljava/lang/Class;Ljava/lang/Object;Lorg/bukkit/plugin/Plugin;Lorg/bukkit/plugin/ServicePriority;)V"
const val SERVICE_PRIORITY: String = "org/bukkit/plugin/ServicePriority"
const val VAULT_ECONOMY: String = "net/milkbowl/vault/economy/Economy"
const val VAULT_PERMISSION: String = "net/milkbowl/vault/permission/Permission"

/** zip 바이트 (중첩 아카이브용). [manifest] 가 있으면 `META-INF/MANIFEST.MF` 를 맨 앞에 쓴다. */
fun zipBytes(vararg entries: Pair<String, ByteArray>, manifest: Map<String, String>? = null): ByteArray {
    val out = ByteArrayOutputStream()
    val stream = if (manifest != null) JarOutputStream(out, manifestOf(manifest)) else ZipOutputStream(out)
    stream.use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun manifestOf(attributes: Map<String, String>): Manifest = Manifest().apply {
    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    for ((key, value) in attributes) mainAttributes[Attributes.Name(key)] = value
}

/** 임시 jar 파일. 디렉터리 항목은 이름이 `/` 로 끝나면 빈 항목으로 쓴다. */
fun jar(vararg entries: Pair<String, ByteArray>, manifest: Map<String, String>? = null): Path =
    tempFile(zipBytes(*entries, manifest = manifest))

/** 임시 파일에 [bytes] 를 쓴다. */
fun tempFile(bytes: ByteArray, suffix: String = ".jar"): Path {
    val path = Files.createTempFile("decacross-ja-test-", suffix)
    path.toFile().deleteOnExit()
    Files.write(path, bytes)
    return path
}

/** ASM 으로 class 바이트를 만든다. 프레임은 계산하지 않는다 (로드·검증하지 않으므로). */
fun classBytes(
    name: String,
    superName: String? = "java/lang/Object",
    interfaces: List<String> = emptyList(),
    version: Int = Opcodes.V1_8,
    access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
    body: ClassWriter.() -> Unit = {},
): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(version, access, name, null, superName, interfaces.toTypedArray())
    writer.body()
    writer.visitEnd()
    return writer.toByteArray()
}

/** `(minor, major)` 를 덮어쓴 class 바이트 (헤더 전용 케이스). */
fun withVersion(bytes: ByteArray, major: Int, minor: Int = 0): ByteArray = bytes.copyOf().also {
    it[4] = (minor shr 8).toByte()
    it[5] = minor.toByte()
    it[6] = (major shr 8).toByte()
    it[7] = major.toByte()
}

/** 텍스트 항목. */
fun textEntry(name: String, value: String): Pair<String, ByteArray> = name to value.toByteArray(Charsets.UTF_8)

/** `plugin.yml` 항목. */
fun pluginYml(text: String): Pair<String, ByteArray> = textEntry("plugin.yml", text)

/** 기본 plugin.yml (name 만 바꿔 쓴다). */
fun simplePluginYml(name: String = "TestPlugin"): Pair<String, ByteArray> =
    pluginYml("name: $name\nversion: 1.0\nmain: com.example.Main\napi-version: '1.20'\n")

/** 메서드 하나를 가진 class 에 [code] 를 쓴다. */
fun ClassWriter.method(
    name: String,
    descriptor: String = "()V",
    access: Int = Opcodes.ACC_PUBLIC,
    code: MethodVisitor.() -> Unit,
) {
    val mv = visitMethod(access, name, descriptor, null, null)
    mv.visitCode()
    mv.code()
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

/** 빈 생성자 `<init>(Lorg/bukkit/plugin/Plugin;)V` 를 가진 구현 class. */
fun implClass(name: String, superName: String = "java/lang/Object", interfaces: List<String> = emptyList()): ByteArray =
    classBytes(name, superName = superName, interfaces = interfaces) {
        method("<init>", "(Lorg/bukkit/plugin/Plugin;)V") {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
        }
    }

/** 인터페이스 class. */
fun interfaceClass(name: String, interfaces: List<String> = emptyList()): ByteArray = classBytes(
    name,
    interfaces = interfaces,
    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
)

/** ServicesManager 를 스택에 올리는 방법. */
enum class ManagerSource {
    /** `aload0; invokevirtual getServer(); invokeinterface Server.getServicesManager()` */
    GET_SERVER,

    /** `invokestatic Bukkit.getServicesManager()` */
    BUKKIT_STATIC,

    /** `getServer().getServicesManager()` 후 `checkcast SimpleServicesManager` (invokevirtual 로 호출) */
    SIMPLE_MANAGER,
}

/** register 의 구현체 인자를 만드는 방법. */
enum class ImplSource {
    /** `new Impl; dup; aload0; invokespecial Impl.<init>(Plugin)` */
    NEW,

    /** `invokestatic com/example/Factory.create()Ljava/lang/Object;` — 추적 불가 */
    METHOD_RETURN,

    /** `new` 로 만든 값을 DUP_X1 로 끼워 넣는다 — 추적 불가 */
    DUP_X1,

    /** 메서드 앞에서 `new Impl; dup; aload0; invokespecial; astore 3` 후 인자로 `aload 3` — ALOAD → ASTORE 추적 */
    LOCAL,

    /** `new Impl; dup; aload0; invokespecial; dup; astore 3` 의 스택 top(DUP 출처)을 인자로 — DUP 추적 */
    DUP_ASTORE,
}

/** [ImplSource.LOCAL]·[ImplSource.DUP_ASTORE] 가 구현체를 담는 지역변수. */
private const val IMPL_LOCAL: Int = 3

/** register 의 우선순위 인자를 만드는 방법. */
sealed interface PrioritySource {
    /** `getstatic ServicePriority.<name>` */
    data class Static(val name: String) : PrioritySource

    /** 메서드 앞에서 `getstatic Normal; astore 2` 후 `aload 2` */
    data object Local : PrioritySource

    /** `aload0; getfield <this>.priority` */
    data object Field : PrioritySource

    /** 파라미터 `(Lorg/bukkit/plugin/ServicePriority;)V` 의 `aload 1` */
    data object Parameter : PrioritySource
}

/** register 의 서비스 인자를 만드는 방법. */
enum class ServiceSource {
    /** `ldc Type(S)` */
    LDC,

    /** 파라미터 `aload 1` (Class) — 추적 불가 */
    PARAMETER,

    /** `ldc "a.b.S"; invokestatic Class.forName` — 추적 불가 */
    FOR_NAME,
}

/** register 호출 하나를 기술한다. */
data class RegisterSpec(
    val service: String,
    val impl: String,
    val priority: PrioritySource = PrioritySource.Static("Normal"),
    val manager: ManagerSource = ManagerSource.GET_SERVER,
    val implSource: ImplSource = ImplSource.NEW,
    val serviceSource: ServiceSource = ServiceSource.LDC,
)

private fun MethodVisitor.pushManager(owner: String, manager: ManagerSource) {
    when (manager) {
        ManagerSource.GET_SERVER, ManagerSource.SIMPLE_MANAGER -> {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "getServer", "()Lorg/bukkit/Server;", false)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/bukkit/Server", "getServicesManager", "()L$SERVICES_MANAGER;", true)
            if (manager == ManagerSource.SIMPLE_MANAGER) visitTypeInsn(Opcodes.CHECKCAST, SIMPLE_SERVICES_MANAGER)
        }

        ManagerSource.BUKKIT_STATIC -> visitMethodInsn(Opcodes.INVOKESTATIC, "org/bukkit/Bukkit", "getServicesManager", "()L$SERVICES_MANAGER;", false)
    }
}

private fun MethodVisitor.pushService(spec: RegisterSpec) {
    when (spec.serviceSource) {
        ServiceSource.LDC -> visitLdcInsn(Type.getObjectType(spec.service))

        ServiceSource.PARAMETER -> visitVarInsn(Opcodes.ALOAD, 1)

        ServiceSource.FOR_NAME -> {
            visitLdcInsn(spec.service.replace('/', '.'))
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
        }
    }
}

private fun MethodVisitor.newImpl(impl: String) {
    visitTypeInsn(Opcodes.NEW, impl)
    visitInsn(Opcodes.DUP)
    visitVarInsn(Opcodes.ALOAD, 0)
    visitMethodInsn(Opcodes.INVOKESPECIAL, impl, "<init>", "(Lorg/bukkit/plugin/Plugin;)V", false)
}

private fun MethodVisitor.pushImpl(spec: RegisterSpec) {
    when (spec.implSource) {
        ImplSource.NEW -> newImpl(spec.impl)

        ImplSource.METHOD_RETURN -> visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Factory", "create", "()Ljava/lang/Object;", false)

        ImplSource.DUP_X1 -> {
            // [.., S] → aload0 → [.., S, this] → new → [.., S, this, impl] → dup_x1 → [.., S, impl', this, impl] → pop pop
            visitVarInsn(Opcodes.ALOAD, 0)
            newImpl(spec.impl)
            visitInsn(Opcodes.DUP_X1)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.POP)
        }

        // registerCall 이 매니저를 올리기 전에 astore 해 두었다
        ImplSource.LOCAL -> visitVarInsn(Opcodes.ALOAD, IMPL_LOCAL)

        ImplSource.DUP_ASTORE -> {
            newImpl(spec.impl)
            visitInsn(Opcodes.DUP)
            visitVarInsn(Opcodes.ASTORE, IMPL_LOCAL)
        }
    }
}

private fun MethodVisitor.pushPriority(owner: String, priority: PrioritySource) {
    when (priority) {
        is PrioritySource.Static -> visitFieldInsn(Opcodes.GETSTATIC, SERVICE_PRIORITY, priority.name, "L$SERVICE_PRIORITY;")

        PrioritySource.Local, PrioritySource.Parameter -> visitVarInsn(Opcodes.ALOAD, if (priority == PrioritySource.Local) 2 else 1)

        PrioritySource.Field -> {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, owner, "priority", "L$SERVICE_PRIORITY;")
        }
    }
}

/** 등록 호출 한 번을 [MethodVisitor] 에 쓴다. */
fun MethodVisitor.registerCall(owner: String, spec: RegisterSpec) {
    if (spec.implSource == ImplSource.LOCAL) {
        // `Perms perms = new Perms(this);` 를 먼저 둔다
        newImpl(spec.impl)
        visitVarInsn(Opcodes.ASTORE, IMPL_LOCAL)
    }
    pushManager(owner, spec.manager)
    pushService(spec)
    pushImpl(spec)
    visitVarInsn(Opcodes.ALOAD, 0)
    pushPriority(owner, spec.priority)
    if (spec.manager == ManagerSource.SIMPLE_MANAGER) {
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, SIMPLE_SERVICES_MANAGER, "register", REGISTER_DESC, false)
    } else {
        visitMethodInsn(Opcodes.INVOKEINTERFACE, SERVICES_MANAGER, "register", REGISTER_DESC, true)
    }
}

/**
 * `onEnable` 에서 [specs] 순서대로 `ServicesManager.register` 를 부르는 플러그인 메인 class.
 * 우선순위가 파라미터/서비스가 파라미터면 메서드 기술자가 `(Ljava/lang/Class;Lorg/bukkit/plugin/ServicePriority;)V` 형태가 된다.
 */
fun registerCallClass(
    vararg specs: RegisterSpec,
    name: String = "com/example/plugin/Main",
    consumerOf: String? = null,
): ByteArray = classBytes(name, superName = "org/bukkit/plugin/java/JavaPlugin") {
    visitField(Opcodes.ACC_PRIVATE, "priority", "L$SERVICE_PRIORITY;", null, null).visitEnd()
    val usesParameter = specs.any { it.priority == PrioritySource.Parameter || it.serviceSource == ServiceSource.PARAMETER }
    val descriptor = if (usesParameter) "(Ljava/lang/Object;)V" else "()V"
    method(if (usesParameter) "enable" else "onEnable", descriptor) {
        if (specs.any { it.priority == PrioritySource.Local }) {
            visitFieldInsn(Opcodes.GETSTATIC, SERVICE_PRIORITY, "Normal", "L$SERVICE_PRIORITY;")
            visitVarInsn(Opcodes.ASTORE, 2)
        }
        for (spec in specs) registerCall(name, spec)
        if (consumerOf != null) {
            pushManager(name, ManagerSource.GET_SERVER)
            visitLdcInsn(Type.getObjectType(consumerOf))
            visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                SERVICES_MANAGER,
                "getRegistration",
                "(Ljava/lang/Class;)Lorg/bukkit/plugin/RegisteredServiceProvider;",
                true,
            )
            visitInsn(Opcodes.POP)
        }
        visitInsn(Opcodes.RETURN)
    }
}

/**
 * 병합된 지역변수 사슬 끝에서 `register(service, aload, this, Normal)` 을 부르는 class (값 추적 작업량 회귀용).
 * `enable(I)V` 안에서 [levels] 단계마다 [fanOut] 개의 조건 분기와 기본 분기가 지역변수 `2 + 단계` 에 저장한다:
 * 0 단계는 `new Impl`, 그 뒤 단계는 `aload (앞 단계 변수)`. 경로 수는 (fanOut+1)^levels 이지만 값은 전부 같은 구현체다.
 */
fun mergedLocalsRegisterClass(
    impl: String,
    service: String,
    fanOut: Int,
    levels: Int,
    name: String = "com/example/plugin/Main",
): ByteArray = classBytes(name, superName = "org/bukkit/plugin/java/JavaPlugin") {
    method("enable", "(I)V") {
        for (level in 0 until levels) {
            val target = 2 + level
            val join = Label()
            for (branch in 0..fanOut) {
                val conditional = branch < fanOut
                val next = Label()
                if (conditional) {
                    visitVarInsn(Opcodes.ILOAD, 1)
                    visitLdcInsn(branch * 1000 + level)
                    visitJumpInsn(Opcodes.IF_ICMPNE, next)
                }
                if (level == 0) newImpl(impl) else visitVarInsn(Opcodes.ALOAD, target - 1)
                visitVarInsn(Opcodes.ASTORE, target)
                if (conditional) {
                    visitJumpInsn(Opcodes.GOTO, join)
                    visitLabel(next)
                }
            }
            visitLabel(join)
        }
        visitMethodInsn(Opcodes.INVOKESTATIC, "org/bukkit/Bukkit", "getServicesManager", "()L$SERVICES_MANAGER;", false)
        visitLdcInsn(Type.getObjectType(service))
        visitVarInsn(Opcodes.ALOAD, 1 + levels)
        visitVarInsn(Opcodes.ALOAD, 0)
        visitFieldInsn(Opcodes.GETSTATIC, SERVICE_PRIORITY, "Normal", "L$SERVICE_PRIORITY;")
        visitMethodInsn(Opcodes.INVOKEINTERFACE, SERVICES_MANAGER, "register", REGISTER_DESC, true)
        visitInsn(Opcodes.RETURN)
    }
}
