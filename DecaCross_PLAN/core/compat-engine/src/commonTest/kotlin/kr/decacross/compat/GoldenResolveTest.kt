package kr.decacross.compat

import kr.decacross.compat.ResolveFixture.db
import kr.decacross.compat.ResolveFixture.req
import kr.decacross.compat.ResolveFixture.want
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 알려진 조합 골든 케이스. 엔진 수정 시 회귀 0 이어야 한다 (CLAUDE.md). */
class GoldenResolveTest {
    @Test
    fun essentialsx_pullsVault_autoAdded() {
        val plan = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Exact("1.21.8"), want("essentialsx")), db)).plan
        val vault = plan.items.first { it.content.slug == "vault" }
        assertTrue(vault.autoAdded)
        assertEquals("EssentialsX 가 필요로 함", vault.reason)
        assertEquals("2.21.0", plan.items.first { it.content.slug == "essentialsx" }.content.version, "1.21.8 에서는 2.21.0 (최신) 선택")
        assertEquals(21, plan.java.feature)
        assertEquals(Confidence.GREEN, plan.items.first { it.content.slug == "essentialsx" }.confidence)
        assertEquals(Confidence.YELLOW, plan.confidence, "vault 는 데이터 없음 → 🟡, 전체는 최악값")
    }

    @Test
    fun any_picksNewestMcThatFitsEverything() {
        val plan = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Any, want("essentialsx"), want("protocollib"), want("worldguard")), db)).plan
        assertEquals("26.3", plan.mc.label)
        assertEquals("5.4.0", plan.items.first { it.content.slug == "protocollib" }.content.version)
        assertEquals(25, plan.java.feature, "26.3 권장 Java")
        assertTrue(plan.items.any { it.content.slug == "worldedit" && it.autoAdded })
        assertTrue(plan.items.none { it.content.slug == "vault" && !it.autoAdded })
    }

    @Test
    fun oldPlugin_forcesOldMc_whenAny() {
        val plan = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Any, want("oldplugin"), want("essentialsx")), db)).plan
        assertEquals("1.20.4", plan.mc.label)
        assertEquals("2.20.1", plan.items.first { it.content.slug == "essentialsx" }.content.version)
        assertEquals(Confidence.RED, plan.confidence, "oldplugin 은 🔴 데이터")
    }

    /** 명세 §5.1 목표 출력의 케이스. */
    @Test
    fun protocollib510_on263_conflict_withBumpAndDowngradeFixes() {
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("26.3"), want("protocollib", "5.1.0"), want("worldguard")), db))
        val e = c.explanation
        assertTrue(e.headlineKo.contains("26.3") && e.headlineKo.contains("ProtocolLib"), e.headlineKo)
        assertTrue(e.causeChain.any { it.textKo.contains("5.1.0") && it.textKo.contains("1.21.8") }, "원인에 지원 범위가 나와야: ${e.causeChain}")
        val actions = e.fixes.map { it.action }
        assertTrue(FixAction.BumpContent("protocollib", "5.4.0") in actions, "올리기 fix: ${e.fixes.map { it.labelKo }}")
        assertTrue(FixAction.ChangeMc("1.21.8") in actions, "낮추기 fix: ${e.fixes.map { it.labelKo }}")
        assertTrue(e.fixes.first().recommended)
        assertTrue(e.fixes.none { it.action is FixAction.RemoveContent && "worldguard" in (it.action as FixAction.RemoveContent).slugs && e.fixes.size == 1 })
    }

    @Test
    fun itemsadder_and_oraxen_areExclusive() {
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("1.21.8"), want("itemsadder"), want("oraxen")), db))
        assertTrue(c.explanation.headlineKo.contains("커스텀 아이템 프레임워크"), c.explanation.headlineKo)
        val removes = c.explanation.fixes.mapNotNull { it.action as? FixAction.RemoveContent }
        assertTrue(removes.any { "itemsadder" in it.slugs } && removes.any { "oraxen" in it.slugs }, "${c.explanation.fixes}")
    }

    @Test
    fun pinned_isNeverRemoved() {
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("1.21.8"), want("itemsadder", pinned = true), want("oraxen")), db))
        val removes = c.explanation.fixes.mapNotNull { it.action as? FixAction.RemoveContent }
        assertTrue(removes.none { "itemsadder" in it.slugs })
        assertTrue(removes.any { "oraxen" in it.slugs })
    }

    @Test
    fun unknownSlug_isConflict_withRemoveFix() {
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("1.21.8"), want("nope")), db))
        assertTrue(c.explanation.headlineKo.contains("nope"))
        assertTrue(c.explanation.fixes.any { it.action == FixAction.RemoveContent(listOf("nope")) })
    }

    @Test
    fun removingExplicitRequest_letsDependencyResolveItself() {
        // protocollib 5.1.0 을 명시 요청 + itemsadder(protocollib 요구) + 26.3
        // → "ProtocolLib 요청 제외" fix 를 적용하면 ItemsAdder 가 5.4.0 을 자동으로 끌어온다 (요청을 뺐지 플러그인이 사라진 게 아니다)
        val r = req(McSelector.Exact("26.3"), want("protocollib", "5.1.0"), want("itemsadder"))
        val c = assertIs<ResolveOutcome.Conflict>(resolve(r, db))
        val remove = c.explanation.fixes.first { it.action == FixAction.RemoveContent(listOf("protocollib")) }
        assertTrue(remove.sideEffectsKo.isEmpty(), "ItemsAdder 는 남는다: $remove")
        val plan = assertIs<ResolveOutcome.Ok>(resolve(r.copy(wants = r.wants.filter { it.slug != "protocollib" }), db)).plan
        val pl = plan.items.first { it.content.slug == "protocollib" }
        assertEquals("5.4.0", pl.content.version)
        assertTrue(pl.autoAdded)
    }

    @Test
    fun bothPinnedExclusive_offersUnpinAsLastResort() {
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("26.3"), want("itemsadder", pinned = true), want("oraxen", pinned = true)), db))
        assertTrue(c.explanation.fixes.all { it.labelKo.startsWith("고정 해제 필요") }, "${c.explanation.fixes}")
        assertEquals(2, c.explanation.fixes.size)
    }

    @Test
    fun packFormat_mismatch_isWarning_notConflict() {
        val plan = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Exact("26.3"), Want("mypack", kr.decacross.compat.model.ContentKind.RESOURCE_PACK)), db)).plan
        assertTrue(plan.warnings.any { it.subject == "mypack" && it.textKo.contains("pack_format") }, "${plan.warnings}")
        val ok = assertIs<ResolveOutcome.Ok>(resolve(req(McSelector.Exact("1.21.8"), Want("mypack", kr.decacross.compat.model.ContentKind.RESOURCE_PACK)), db)).plan
        assertTrue(ok.warnings.none { it.textKo.contains("맞지 않습니다") }, "${ok.warnings}")
    }

    @Test
    fun exactMcWithoutBuild_explainsAndOffersOtherMc() {
        val noBuildDb = kr.decacross.compat.db.InMemoryCompatDb(ResolveFixture.mcs, ResolveFixture.builds.filter { it.mc != ResolveFixture.MC_26_3 }, ResolveFixture.contents, ResolveFixture.versions)
        val c = assertIs<ResolveOutcome.Conflict>(resolve(req(McSelector.Exact("26.3"), want("worldedit")), noBuildDb))
        assertTrue(c.explanation.headlineKo.contains("빌드"), c.explanation.headlineKo)
        assertTrue(c.explanation.fixes.any { it.action == FixAction.ChangeMc("1.21.8") }, "${c.explanation.fixes}")
    }
}
