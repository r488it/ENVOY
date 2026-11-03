package com.rabi.envoymcp.mcp

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** エージェントの権限の決まり (まとまり・読むだけ・止める・期限・開発モード) と、エージェントの一覧 */
class AgentPolicyTest {
    private val now = 10_000_000L
    private fun agent(readOnly: Boolean = false, groups: Set<String>? = null, enabled: Boolean = true, expiresAt: Long = 0L) =
        Agent("a1", "テスト", token = "t", enabled = enabled, readOnly = readOnly, groups = groups, expiresAt = expiresAt)

    @Test fun mainAgentMayUseEverything() {
        for (tool in ToolGroups.groups.flatMap { it.second } + "some_new_tool") {
            assertTrue(tool, AgentPolicy.allows(Agent.MAIN, tool, readOnly = false, now = now))
        }
    }

    @Test fun groupsLimitTools() {
        val a = agent(groups = setOf("様子・画面", "アプリ"))
        assertTrue(AgentPolicy.allows(a, "read_screen", true, now))
        assertTrue(AgentPolicy.allows(a, "open_app", false, now))
        assertFalse(AgentPolicy.allows(a, "tap", false, now))
        assertFalse(AgentPolicy.allows(a, "call_phone", false, now))
        // どのまとまりにも入らない道具は、すべてのまとまりを許したときだけ
        assertFalse(AgentPolicy.allows(a, "some_new_tool", true, now))
        assertTrue(AgentPolicy.allows(agent(groups = ToolGroups.groups.map { it.first }.toSet()), "some_new_tool", true, now))
        assertTrue(AgentPolicy.allows(agent(groups = null), "some_new_tool", true, now))
        assertFalse(AgentPolicy.allows(agent(groups = emptySet()), "get_state", true, now))
    }

    @Test fun readOnlyAllowsOnlyReadingTools() {
        val a = agent(readOnly = true)
        assertTrue(AgentPolicy.allows(a, "read_screen", readOnly = true, now = now))
        assertFalse(AgentPolicy.allows(a, "tap", readOnly = false, now = now))
        assertFalse(AgentPolicy.allows(a, "call_phone", readOnly = false, now = now))
        // 読むだけ + まとまり: 両方に合うものだけ
        val b = agent(readOnly = true, groups = setOf("写真"))
        assertTrue(AgentPolicy.allows(b, "search_photos", true, now))
        assertFalse(AgentPolicy.allows(b, "share_photo", false, now))
        assertFalse(AgentPolicy.allows(b, "read_screen", true, now))
    }

    @Test fun disabledAndExpiredAgentsMayUseNothing() {
        assertEquals(AgentPolicy.Status.OFF, AgentPolicy.status(agent(enabled = false), now))
        assertFalse(AgentPolicy.allows(agent(enabled = false), "get_state", true, now))
        val exp = agent(expiresAt = now + 1)
        assertEquals(AgentPolicy.Status.OK, AgentPolicy.status(exp, now))
        assertTrue(AgentPolicy.allows(exp, "get_state", true, now))
        assertEquals(AgentPolicy.Status.EXPIRED, AgentPolicy.status(exp, now + 1))
        assertFalse(AgentPolicy.allows(exp, "get_state", true, now + 1))
        // 期限 0 はいつまでも
        assertEquals(AgentPolicy.Status.OK, AgentPolicy.status(agent(expiresAt = 0L), Long.MAX_VALUE))
    }

    @Test fun devModeIgnoresLimitsButNotOff() {
        val limited = agent(readOnly = true, groups = setOf("写真"), expiresAt = now - 1)
        assertFalse(AgentPolicy.allows(limited, "call_phone", false, now))
        assertEquals(AgentPolicy.Status.OK, AgentPolicy.status(limited, now, unlimited = true))
        assertTrue(AgentPolicy.allows(limited, "call_phone", false, now, unlimited = true))
        assertTrue(AgentPolicy.allows(limited, "some_new_tool", false, now, unlimited = true))
        // 止めているものは、開発モードでもだめ
        assertEquals(AgentPolicy.Status.OFF, AgentPolicy.status(limited.copy(enabled = false), now, unlimited = true))
        assertFalse(AgentPolicy.allows(limited.copy(enabled = false), "get_state", true, now, unlimited = true))
    }

    @Test fun refusalIsAToolError() {
        val r = AgentPolicy.refusal(agent())
        assertTrue(r.isError)
        assertTrue(r.text.contains("このエージェント (テスト) に許されていません"))
    }

    @Test fun summaries() {
        assertEquals("すべて", AgentPolicy.summary(Agent.MAIN))
        assertEquals("読むだけ", AgentPolicy.summary(agent(readOnly = true)))
        assertEquals("読むだけ・画面とアプリだけ", AgentPolicy.summary(agent(readOnly = true, groups = setOf("アプリ", "様子・画面"))))
        assertEquals("使える道具なし", AgentPolicy.summary(agent(groups = emptySet())))
    }

    @Test fun registryKeepsMainAndSafeDefaults() {
        val store = Agents.Memory()
        val agents = Agents(store)
        assertEquals(listOf(Agent.MAIN_ID), agents.list.value.map { it.id })
        val a = agents.add(now)
        assertEquals(Agents.NEW_NAME, a.name)
        assertTrue(a.readOnly)
        assertNull(a.groups)
        assertEquals(now + Agents.WEEK_MS, a.expiresAt)
        assertEquals(32, a.token.length)
        assertNotEquals(a.token, agents.add(now).token)
        // 本体は消せない。足したものは消せる
        agents.remove(Agent.MAIN_ID)
        assertTrue(agents.list.value.first().main)
        agents.remove(a.id)
        assertNull(agents.get(a.id))
        // 直しても合言葉は変わらない
        val b = agents.list.value.last()
        agents.update(b.copy(name = "PC のエージェント", token = "changed"))
        assertEquals(b.token, agents.get(b.id)!!.token)
        assertEquals("PC のエージェント", agents.get(b.id)!!.name)
        // 置き場所から読み直しても同じ。本体の合言葉は置かない
        agents.update(Agent.MAIN.copy(readOnly = true, token = "main-secret"))
        val again = Agents(store)
        assertEquals(agents.list.value.map { it.id to it.token }, again.list.value.map { it.id to it.token })
        assertTrue(again.list.value.first().readOnly)
        assertEquals("", again.list.value.first().token)
        val stored = JSONArray(store.read())
        assertFalse(stored.getJSONObject(0).has("token"))
        assertFalse(store.read()!!.contains("main-secret"))
        // 表示用の文字には合言葉を出さない
        assertFalse(b.toString().contains(b.token))
    }
}
