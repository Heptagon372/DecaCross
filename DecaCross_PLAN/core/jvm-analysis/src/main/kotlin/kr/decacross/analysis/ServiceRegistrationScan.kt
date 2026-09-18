package kr.decacross.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

// ── ServicesManager 호출 추적 (설계 §7.6 pass 2) ────────────────────────────
// Bukkit API·VaultAPI 계약 이름(설계 AE-6)만 상수로 둔다. 수집 데이터(플러그인 이름·패키지)는 CapabilityRules 로 주입.

/** Bukkit `ServicesManager` 와 구현체 `SimpleServicesManager` (내부 이름). */
internal val SERVICES_MANAGER_OWNERS: Set<String> =
    setOf("org/bukkit/plugin/ServicesManager", "org/bukkit/plugin/SimpleServicesManager")

/** 서비스 등록 호출 기술자. */
private const val REGISTER_DESC: String =
    "(Ljava/lang/Class;Ljava/lang/Object;Lorg/bukkit/plugin/Plugin;Lorg/bukkit/plugin/ServicePriority;)V"

/** 등록 외에 서비스 타입을 소비하는 ServicesManager 메서드. */
private val CONSUMER_METHODS: Set<String> = setOf("getRegistration", "getRegistrations", "load", "isProvidedFor")

private const val SERVICE_PRIORITY_OWNER: String = "org/bukkit/plugin/ServicePriority"

/** 값 출처 추적 깊이 상한. */
private const val TRACE_DEPTH_MAX: Int = 8

/**
 * 인자 하나를 추적할 때 쓸 수 있는 작업량 (출처 명령 방문 수). 넘으면 그 인자는 추적 불가(null) — 보수적으로 STORE 없음.
 * 메모 덕분에 정상 코드는 수십 단위로 끝난다. 상한은 병합된 지역변수가 겹겹이 이어진 난독화·평탄화 코드용 방어선이다.
 */
private const val TRACE_STEP_BUDGET: Int = 4096

/** jar 하나(추론 한 번)의 모든 인자 추적이 함께 쓰는 작업량. 인자별 상한을 여러 번 채우는 jar 도 시간 예산(§7.9) 안에 끝나게 한다. */
private const val SCAN_TRACE_STEP_BUDGET: Int = 1 shl 20

/**
 * `ServicesManager.register(Class, Object, Plugin, ServicePriority)` 호출 하나.
 * 추적하지 못한 인자는 null 이다 (null priority = 설정에 따라 정해짐).
 */
internal data class RegisterCall(
    val service: String?,
    val impl: String?,
    val priority: String?,
    /** `class.method` */
    val where: String,
)

/** 후보 클래스들의 ServicesManager 호출 요약. */
internal class ServiceCallScan {
    val registerCalls: MutableList<RegisterCall> = ArrayList()

    /** 소비 호출(getRegistration 등)의 첫 인자로 추적된 서비스 타입. */
    val consumed: MutableSet<String> = HashSet()

    /** 이 스캔(jar 하나)에서 남은 값 추적 작업량. 메서드별 임시 스캔에서는 쓰지 않는다. */
    var traceStepsLeft: Int = SCAN_TRACE_STEP_BUDGET
}

/**
 * 후보 class 하나를 ASM tree 로 읽어 ServicesManager 호출을 [scan] 에 모은다.
 * ASM 이 읽지 못하는 class(너무 새로운 major 등)·분석 실패 메서드는 note 후 건너뛴다.
 */
internal fun scanServiceCalls(location: String, bytes: ByteArray, scan: ServiceCallScan, notes: MutableList<String>) {
    val node = ClassNode()
    try {
        ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
    } catch (e: RuntimeException) {
        // IllegalArgumentException(ASM 이 모르는 major)·잘못된 class 구조
        rethrowIfCancellation(e)
        notes += "ServicesManager 호출 후보 class 를 ASM 으로 읽지 못함: $location (${e.javaClass.simpleName}: ${e.message})"
        return
    }
    for (method in node.methods) {
        if (!callsServicesManager(method)) continue
        val frames = try {
            Analyzer(SourceInterpreter()).analyze(node.name, method)
        } catch (e: AnalyzerException) {
            notes += "메서드 분석 실패: ${node.name}.${method.name}${method.desc} (${e.message})"
            continue
        } catch (e: RuntimeException) {
            rethrowIfCancellation(e)
            notes += "메서드 분석 실패: ${node.name}.${method.name}${method.desc} (${e.javaClass.simpleName}: ${e.message})"
            continue
        }
        try {
            // 메서드 단위로 모았다가 성공했을 때만 합친다 (중간 실패로 일부 호출만 남지 않게)
            val methodScan = ServiceCallScan()
            val tracer = MethodTracer(method, frames, budget = scan)
            tracer.collect("${node.name}.${method.name}", methodScan)
            scan.registerCalls += methodScan.registerCalls
            scan.consumed += methodScan.consumed
            if (tracer.budgetCut) {
                notes += "값 추적 작업 상한 초과 — 일부 인자를 추적 불가로 처리: ${node.name}.${method.name}${method.desc}"
            }
        } catch (e: RuntimeException) {
            // 잘못된 기술자 등 — 이 메서드만 건너뛴다 (다른 메서드·클래스의 근거는 유지)
            rethrowIfCancellation(e)
            notes += "메서드 추적 실패: ${node.name}.${method.name}${method.desc} (${e.javaClass.simpleName}: ${e.message})"
        }
    }
}

private fun isServicesManagerCall(insn: AbstractInsnNode): Boolean =
    insn is MethodInsnNode &&
        insn.owner in SERVICES_MANAGER_OWNERS &&
        (insn.name == "register" || insn.name in CONSUMER_METHODS)

private fun callsServicesManager(method: MethodNode): Boolean = method.instructions.any { isServicesManagerCall(it) }

/** 메모 키: 출처 명령과 그 명령을 푸는 깊이. [AbstractInsnNode] 는 equals 를 재정의하지 않아 동일성으로 비교된다. */
private data class TraceKey(val insn: AbstractInsnNode, val depth: Int)

/**
 * 한 메서드의 프레임 위에서 인자 출처를 추적한다.
 *
 * @param budget jar 전체 작업량([ServiceCallScan.traceStepsLeft])을 들고 있는 스캔.
 */
private class MethodTracer(
    private val method: MethodNode,
    private val frames: Array<out Frame<SourceValue>?>,
    private val budget: ServiceCallScan,
) {
    /** 추적 결과 종류: 서비스 타입(Class 상수) / 구현체(인스턴스). 필드 타입은 구현체 추적에서만 쓴다. */
    private enum class Mode { TYPE, IMPL }

    /** 이 메서드에서 작업 상한 때문에 추적을 끊은 적이 있는가. */
    var budgetCut: Boolean = false
        private set

    fun collect(where: String, scan: ServiceCallScan) {
        for ((index, insn) in method.instructions.withIndex()) {
            if (!isServicesManagerCall(insn)) continue
            val call = insn as MethodInsnNode
            val frame = frames.getOrNull(index) ?: continue // 도달 불가 코드
            val argumentCount = Type.getArgumentTypes(call.desc).size
            val base = frame.stackSize - argumentCount
            if (base < 0) continue
            if (call.name == "register" && call.desc == REGISTER_DESC) {
                scan.registerCalls += RegisterCall(
                    service = traceArgument(frame.getStack(base), Mode.TYPE),
                    impl = traceArgument(frame.getStack(base + 1), Mode.IMPL),
                    priority = tracePriority(frame.getStack(base + 3)),
                    where = where,
                )
            } else if (call.name in CONSUMER_METHODS && call.desc.startsWith("(Ljava/lang/Class;")) {
                traceArgument(frame.getStack(base), Mode.TYPE)?.let { scan.consumed += it }
            }
        }
    }

    private fun traceArgument(value: SourceValue, mode: Mode): String? {
        val trace = ValueTrace(mode)
        val result = trace.value(value, 0)
        if (trace.cut) budgetCut = true
        return result
    }

    /**
     * 인자 하나의 출처 추적 (설계 §7.6 표). 출처 명령이 여러 개면 전부 같은 결과여야 하고, 하나라도 못 풀면 null.
     *
     * - 결과를 (명령, 깊이) 로 메모한다. 병합된 지역변수가 `ALOAD → ASTORE` 로 겹겹이 이어지면 경로 수가
     *   (출처 수)^깊이 로 늘기 때문이다. 같은 명령은 깊이마다 한 번만 푼다.
     * - 재귀마다 깊이가 1 씩 늘어 같은 키가 자기 자신을 기다리는 일은 없다. 순환은 깊이 상한에서 null 로 끊기고,
     *   null 은 곧바로 위로 전파되므로 경로 집합으로 순환을 먼저 끊던 방식과 결과가 같다.
     * - 작업 상한을 넘으면 그 자리가 null 이 되어 최상위까지 null 로 올라간다 (추적 불가 → STORE 없음).
     */
    private inner class ValueTrace(private val mode: Mode) {
        private val insnMemo = HashMap<TraceKey, String?>()
        private val storeMemo = HashMap<TraceKey, String?>()
        private var steps = 0

        /** 작업 상한 때문에 끊겼는가. */
        var cut: Boolean = false
            private set

        /** 작업 1 단위를 쓴다. 인자별·jar 전체 상한 중 하나라도 넘으면 false. */
        private fun step(): Boolean {
            if (cut) return false
            if (steps >= TRACE_STEP_BUDGET || budget.traceStepsLeft <= 0) {
                cut = true
                return false
            }
            steps++
            budget.traceStepsLeft--
            return true
        }

        fun value(value: SourceValue, depth: Int): String? {
            if (depth > TRACE_DEPTH_MAX) return null
            val sources = value.insns
            // 파라미터·this 는 출처 명령이 없다
            if (sources.isEmpty()) return null
            return resolveAll(sources) { source -> memoized(insnMemo, source, depth) { insn(source, depth) } }
        }

        /** [sources] 를 모두 풀어 하나의 결과로 모은다. 못 푼 출처가 있거나 결과가 둘 이상이면 null. */
        private inline fun resolveAll(sources: Collection<AbstractInsnNode>, resolve: (AbstractInsnNode) -> String?): String? {
            var result: String? = null
            for (source in sources) {
                if (!step()) return null
                val resolved = resolve(source) ?: return null
                if (result != null && result != resolved) return null
                result = resolved
            }
            return result
        }

        /** 재귀 중에 맵을 고치므로 computeIfAbsent 대신 명시적으로 조회·저장한다. null 결과도 메모한다. */
        private inline fun memoized(
            memo: HashMap<TraceKey, String?>,
            insn: AbstractInsnNode,
            depth: Int,
            compute: () -> String?,
        ): String? {
            val key = TraceKey(insn, depth)
            if (memo.containsKey(key)) return memo[key]
            val result = compute()
            memo[key] = result
            return result
        }

        private fun insn(insn: AbstractInsnNode, depth: Int): String? = when {
            insn is LdcInsnNode -> (insn.cst as? Type)?.takeIf { it.sort == Type.OBJECT }?.internalName

            insn is TypeInsnNode && (insn.opcode == Opcodes.NEW || insn.opcode == Opcodes.CHECKCAST) -> insn.desc

            insn is FieldInsnNode && (insn.opcode == Opcodes.GETFIELD || insn.opcode == Opcodes.GETSTATIC) ->
                if (mode == Mode.IMPL) Type.getType(insn.desc).takeIf { it.sort == Type.OBJECT }?.internalName else null

            // DUP_X1·DUP_X2·DUP2* 는 스택 위 값이 복제된 값이라는 보장이 없어 추적하지 않는다
            insn is InsnNode && insn.opcode == Opcodes.DUP -> stackTopAt(insn)?.let { value(it, depth + 1) }

            insn is VarInsnNode && insn.opcode == Opcodes.ALOAD -> local(insn, depth)

            // 메서드 반환값·Class.forName 등은 추적 불가
            else -> null
        }

        /** `ALOAD n` → 그 시점 지역변수 n 의 출처(ASTORE 들) → 각 ASTORE 시점 스택 top → 재귀. */
        private fun local(load: VarInsnNode, depth: Int): String? {
            val frame = frameAt(load) ?: return null
            if (load.`var` >= frame.locals) return null
            val stores = frame.getLocal(load.`var`).insns
            if (stores.isEmpty()) return null
            return resolveAll(stores) { store ->
                if (store !is VarInsnNode || store.opcode != Opcodes.ASTORE) {
                    null
                } else {
                    memoized(storeMemo, store, depth) { stackTopAt(store)?.let { value(it, depth + 1) } }
                }
            }
        }
    }

    /** `GETSTATIC ServicePriority.<P>` 만 인정한다. 그 외(지역변수·필드·파라미터)는 설정 의존 → null. */
    private fun tracePriority(value: SourceValue): String? {
        val names = value.insns.map { insn ->
            if (insn is FieldInsnNode && insn.opcode == Opcodes.GETSTATIC && insn.owner == SERVICE_PRIORITY_OWNER) insn.name else null
        }
        if (names.isEmpty() || names.any { it == null }) return null
        return names.distinct().singleOrNull()
    }

    private fun frameAt(insn: AbstractInsnNode): Frame<SourceValue>? {
        val index = method.instructions.indexOf(insn)
        return if (index in frames.indices) frames[index] else null
    }

    private fun stackTopAt(insn: AbstractInsnNode): SourceValue? {
        val frame = frameAt(insn) ?: return null
        if (frame.stackSize == 0) return null
        return frame.getStack(frame.stackSize - 1)
    }
}
