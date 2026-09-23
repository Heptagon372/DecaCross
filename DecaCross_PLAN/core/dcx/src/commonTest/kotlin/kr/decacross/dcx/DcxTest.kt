package kr.decacross.dcx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DcxTest {
    private val sample =
        """
        {
          "dcx": "1.1",
          "id": "sowl-survival-2026",
          "name": "S.OWL 서바이벌",
          "author": { "handle": "heptagon", "verified": true },
          "license": "CC-BY-NC-4.0",
          "target": { "minecraft": "1.21.8", "core": { "type": "paper", "build": "latest-stable" }, "java": { "feature": 21 } },
          "runtime": { "memory": { "min": "4G", "max": "4G", "auto": true }, "flags": "aikar", "extra_args": ["-Dfile.encoding=UTF-8"] },
          "variables": [
            { "key": "SERVER_NAME", "label": "서버 이름", "default": "S.OWL 서바이벌" },
            { "key": "MAX_PLAYERS", "label": "최대 인원", "type": "number", "default": 20 },
            { "key": "USE_ECONOMY", "label": "경제 시스템 포함", "type": "bool", "default": true }
          ],
          "content": [
            { "kind": "plugin", "source": "modrinth", "slug": "essentialsx", "version": "2.21.0" },
            { "kind": "plugin", "source": "url", "url": "https://github.com/x/Vault.jar", "sha256": "${"a".repeat(64)}", "license": "LGPL-3.0", "trust": "external" },
            { "kind": "plugin", "source": "modrinth", "slug": "coinsengine", "when": "USE_ECONOMY == true" },
            { "kind": "resourcepack", "source": "bundled", "file": "packs/sowl.zip", "pack_format": "auto", "serve": "auto" }
          ],
          "config": { "server.properties": { "motd": "§6{{SERVER_NAME}}", "max-players": "{{MAX_PLAYERS}}", "online-mode": true, "view-distance": 10 } },
          "network": { "expose": "tunnel", "port": 25565 },
          "compat": { "resolved_at": "2026-09-16T12:00:00+09:00", "engine": "1.4.2", "status": "green", "verified_installs": 34 }
        }
        """.trimIndent()

    @Test
    fun parses_designDocExample() {
        val r = assertIs<DcxParseResult.Ok>(parseDcx(sample)).recipe
        assertEquals("sowl-survival-2026", r.id)
        assertEquals("paper", r.target.core.type)
        assertEquals(4, r.content.size)
        assertEquals("USE_ECONOMY == true", r.content[2].whenCond)
        assertTrue(r.content[1].isExternal)
        assertEquals(listOf("https://github.com/x/Vault.jar"), r.summary().externalUrls)
        assertEquals(3, r.summary().pluginCount)
    }

    @Test
    fun evaluate_appliesVariablesAndConditions() {
        val r = assertIs<DcxParseResult.Ok>(parseDcx(sample)).recipe
        val on = r.evaluate(mapOf("SERVER_NAME" to "우리 서버", "MAX_PLAYERS" to "8"))
        assertEquals("§6우리 서버", on.serverProperties["motd"])
        assertEquals("8", on.serverProperties["max-players"])
        assertEquals("true", on.serverProperties["online-mode"])
        assertTrue(on.content.any { it.slug == "coinsengine" }, "기본값 USE_ECONOMY=true → 포함")
        val off = r.evaluate(mapOf("USE_ECONOMY" to "false"))
        assertTrue(off.content.none { it.slug == "coinsengine" })
        assertEquals("S.OWL 서바이벌", off.vars["SERVER_NAME"], "기본값 사용")
    }

    @Test
    fun roundTrip() {
        val r = assertIs<DcxParseResult.Ok>(parseDcx(sample)).recipe
        val again = assertIs<DcxParseResult.Ok>(parseDcx(r.toJson())).recipe
        assertEquals(r, again)
    }

    @Test
    fun rejects_invalidRecipes() {
        fun errorsOf(json: String) = assertIs<DcxParseResult.Invalid>(parseDcx(json)).errorsKo
        assertTrue(errorsOf("""{"dcx":"1.0","id":"x","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[]}""").any { "dcx" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"Bad Id","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[]}""").any { "id" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"ok-id","name":"n","target":{"minecraft":"1.21","core":{"type":"bukkitx"}},"content":[]}""").any { "core.type" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"ok-id","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[{"kind":"plugin","source":"url","url":"https://x/y.jar"}]}""").any { "sha256" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"ok-id","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[{"kind":"plugin","source":"modrinth","slug":"a","when":"nonsense"}]}""").any { "when" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"ok-id","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[],"config":{"server.properties":{"motd":"{{NOPE}}"}}}""").any { "NOPE" in it })
        assertTrue(errorsOf("""{"dcx":"1.1","id":"ok-id","name":"n","target":{"minecraft":"1.21","core":{"type":"paper"}},"content":[],"unknown":1}""").isNotEmpty(), "additionalProperties=false")
    }
}
