package kr.decacross.compat

import kr.decacross.compat.ResolveFixture.db
import kr.decacross.compat.ResolveFixture.req
import kr.decacross.compat.ResolveFixture.want
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** 명세 §3.1 불변식 I1~I6. 테스트 이름은 명세 표 그대로. */
class InvariantTest {
    private val conflictRequests = listOf(
        req(McSelector.Exact("26.3"), want("protocollib", "5.1.0")),
        req(McSelector.Any, want("itemsadder"), want("oraxen")),
        req(McSelector.Exact("26.3"), want("oldplugin")),
        req(McSelector.Exact("1.21.8"), want("nope-does-not-exist")),
        req(McSelector.Exact("26.3"), want("itemsadder", pinned = true), want("oraxen", pinned = true)),
    )

    private val okRequests = listOf(
        req(McSelector.Any, want("essentialsx")),
        req(McSelector.Exact("1.20.4"), want("essentialsx")),
        req(McSelector.Any, want("essentialsx"), want("protocollib"), want("worldguard"), want("luckperms")),
        req(McSelector.Any, want("itemsadder")),
        req(McSelector.Family("1.21"), want("placeholderapi")),
        req(McSelector.Latest),
    )

    @Test
    fun invariant_conflictAlwaysHasFix() {
        for (r in conflictRequests) {
            val out = resolve(r, db)
            val c = assertIs<ResolveOutcome.Conflict>(out, "충돌이어야 한다: $r")
            assertTrue(c.explanation.fixes.isNotEmpty(), "fix 0개 = 엔진 버그: ${c.explanation}")
            assertTrue(c.explanation.headlineKo.isNotBlank())
            // ★ 불변식 8: 제시한 fix 는 실제로 된다 (RemoveContent / ChangeMc / BumpContent 를 적용해 재해결)
            for (f in c.explanation.fixes) {
                val applied = apply(r, f.action) ?: continue
                assertIs<ResolveOutcome.Ok>(resolve(applied, db), "검증되지 않은 fix 가 제시됨: ${f.labelKo} ($f)")
            }
        }
    }

    @Test
    fun invariant_depsClosed() {
        for (r in okRequests) {
            val plan = assertIs<ResolveOutcome.Ok>(resolve(r, db), "해가 있어야 한다: $r").plan
            val slugs = plan.items.map { it.content.slug }.toSet()
            for (item in plan.items) {
                for (d in item.content.deps.filter { it.kind == DepKind.REQUIRE }) {
                    val target = (d.target as DepTarget.Slug).value
                    assertTrue(target in slugs, "${item.content.slug} 가 요구하는 $target 이 plan.items 에 없다")
                }
            }
        }
    }

    @Test
    fun invariant_capabilityExclusive() {
        for (r in okRequests + listOf(req(McSelector.Any, want("oraxen"), want("luckperms")))) {
            val plan = assertIs<ResolveOutcome.Ok>(resolve(r, db)).plan
            val providers = plan.items.flatMap { it.content.deps }.filter { it.kind == DepKind.PROVIDES }.map { (it.target as DepTarget.Cap).value }
            assertEquals(providers.size, providers.toSet().size, "같은 Capability 제공자가 둘 이상: $providers")
        }
        assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Any, want("itemsadder"), want("oraxen")), db))
        assertEquals(listOf("itemsadder", "oraxen"), db.providersOf(Capability.CustomItemFramework))
    }

    @Test
    fun invariant_javaFloor() {
        for (r in okRequests) {
            val plan = assertIs<ResolveOutcome.Ok>(resolve(r, db)).plan
            assertTrue(plan.java.feature >= plan.mc.javaMin, "${plan.mc.label}: java ${plan.java.feature} < min ${plan.mc.javaMin}")
            for (item in plan.items) {
                item.content.javaMajor?.let { assertTrue(plan.java.feature >= it, "${item.content.slug} 요구 Java $it > 선택 ${plan.java.feature}") }
            }
        }
    }

    @Test
    fun invariant_ramAccountsHost() {
        val base = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Latest, ramMb = 16 * 1024, hostOverheadMb = 0), db)).plan
        val loaded = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Latest, ramMb = 16 * 1024, hostOverheadMb = 1024), db)).plan
        assertTrue(loaded.recommendedRamMb < base.recommendedRamMb, "${base.recommendedRamMb} → ${loaded.recommendedRamMb}")
    }

    @Test
    fun bench_resolve30() {
        val r = req(McSelector.Any, *(1..30).map { want("p%02d".format(it)) }.toTypedArray())
        repeat(5) { assertIs<ResolveOutcome.Ok>(resolve(r, db)) } // JIT 예열
        val t = measureTime { repeat(10) { resolve(r, db) } } / 10
        println("bench_resolve30: $t (목표 < 50ms)")
        assertTrue(t.inWholeMilliseconds < 200, "너무 느림: $t")
    }

    private fun apply(r: ResolveRequest, a: FixAction): ResolveRequest? = when (a) {
        is FixAction.RemoveContent -> r.copy(wants = r.wants.filter { it.slug !in a.slugs })
        is FixAction.ChangeMc -> r.copy(mc = McSelector.Exact(a.to), wants = r.wants)
        is FixAction.BumpContent -> r.copy(wants = r.wants.map { if (it.slug == a.slug) it.copy(version = a.to) else it })
        else -> null
    }
}
