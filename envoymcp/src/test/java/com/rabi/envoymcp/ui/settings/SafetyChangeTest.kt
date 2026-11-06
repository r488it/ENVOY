package com.rabi.envoymcp.ui.settings

import com.rabi.envoymcp.mcp.Agent
import com.rabi.envoymcp.mcp.AgentPolicy
import com.rabi.envoymcp.mcp.ToolGroups
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安全の設定の変更が守りをゆるめるか (ゆるめるときだけ、ご本人か確かめる。強めるときはすぐ変える) */
class SafetyChangeTest {
    private val hour = 60 * 60_000L
    private val now = 10_000_000L
    private fun agent(enabled: Boolean = true, readOnly: Boolean = false, groups: Set<String>? = null,
                      denied: Set<String> = emptySet(), expiresAt: Long = 0L) =
        Agent("a1", "テスト", token = "t", enabled = enabled, readOnly = readOnly, groups = groups, denied = denied, expiresAt = expiresAt)

    @Test fun turningApprovalOffLoosens() {
        // 承認画面・本人確認のどちらかをオフにする
        assertTrue(SafetyChange.loosensApproval(screen = false, biometric = true, nextScreen = false, nextBiometric = false))
        assertTrue(SafetyChange.loosensApproval(screen = true, biometric = false, nextScreen = false, nextBiometric = false))
        assertTrue(SafetyChange.loosensApproval(screen = true, biometric = true, nextScreen = false, nextBiometric = true))
        assertTrue(SafetyChange.loosensApproval(screen = true, biometric = true, nextScreen = true, nextBiometric = false))
    }

    @Test fun turningApprovalOnIsInstant() {
        assertFalse(SafetyChange.loosensApproval(screen = false, biometric = false, nextScreen = true, nextBiometric = false))
        assertFalse(SafetyChange.loosensApproval(screen = false, biometric = false, nextScreen = false, nextBiometric = true))
        assertFalse(SafetyChange.loosensApproval(screen = false, biometric = true, nextScreen = true, nextBiometric = true))
        assertFalse(SafetyChange.loosensApproval(screen = true, biometric = true, nextScreen = true, nextBiometric = true))
    }

    @Test fun onlyTurningDevModeOnLoosens() {
        assertTrue(SafetyChange.loosensDevMode(on = false, nextOn = true))
        assertFalse(SafetyChange.loosensDevMode(on = true, nextOn = false))
        assertFalse(SafetyChange.loosensDevMode(on = false, nextOn = false))
    }

    @Test fun resumingAndLeavingReadOnlyWiden() {
        assertTrue(SafetyChange.widensAgent(agent(enabled = false), agent(enabled = true)))
        assertFalse(SafetyChange.widensAgent(agent(enabled = true), agent(enabled = false)))
        assertTrue(SafetyChange.widensAgent(agent(readOnly = true), agent(readOnly = false)))
        assertFalse(SafetyChange.widensAgent(agent(readOnly = false), agent(readOnly = true)))
    }

    @Test fun extendingOrRemovingExpiryWidens() {
        val cur = agent(expiresAt = now + hour)
        assertTrue(SafetyChange.widensAgent(cur, cur.copy(expiresAt = 0L)))
        assertTrue(SafetyChange.widensAgent(cur, cur.copy(expiresAt = now + 2 * hour)))
        // 期限切れのものに期限を付け直すのも、のばす
        assertTrue(SafetyChange.widensAgent(agent(expiresAt = now - hour), agent(expiresAt = now + hour)))
        // 縮める・期限なしに期限を付けるのは、すぐ
        assertFalse(SafetyChange.widensAgent(cur, cur.copy(expiresAt = now + hour / 2)))
        assertFalse(SafetyChange.widensAgent(agent(expiresAt = 0L), agent(expiresAt = now + hour)))
        assertFalse(SafetyChange.widensAgent(cur, cur))
    }

    @Test fun turningGroupsAndToolsOnWidens() {
        val screenOnly = agent(groups = setOf("様子・画面"))
        assertTrue(SafetyChange.widensAgent(screenOnly, AgentPolicy.withGroup(screenOnly, "電話", true)))
        // オフのまとまりの道具を 1 つだけオンにする
        assertTrue(SafetyChange.widensAgent(screenOnly, AgentPolicy.withTool(screenOnly, "call_phone", true)))
        // 1 つずつ止めていた道具を戻す
        val noShot = AgentPolicy.withTool(agent(), "get_screenshot", false)
        assertTrue(SafetyChange.widensAgent(noShot, AgentPolicy.withTool(noShot, "get_screenshot", true)))
        // 残りのまとまりを全部許すと、どのまとまりにも入らない道具 (その他) も使えるようになる
        val allButArm = agent(groups = ToolGroups.groups.map { it.first }.toSet() - "AgentARM (スマホを載せる腕)")
        assertTrue(SafetyChange.widensAgent(allButArm, AgentPolicy.withGroup(allButArm, "AgentARM (スマホを載せる腕)", true)))
        // その他の道具を止めていたのを戻す
        assertTrue(SafetyChange.widensAgent(agent(denied = setOf("some_new_tool")), agent()))
        // 「すべて許可」
        val limited = agent(readOnly = true, groups = setOf("写真"), expiresAt = now + hour)
        assertTrue(SafetyChange.widensAgent(limited, limited.copy(groups = null, denied = emptySet(), readOnly = false, expiresAt = 0L)))
    }

    @Test fun narrowingIsInstant() {
        val all = agent()
        assertFalse(SafetyChange.widensAgent(all, AgentPolicy.withGroup(all, "電話", false)))
        assertFalse(SafetyChange.widensAgent(all, AgentPolicy.withTool(all, "call_phone", false)))
        assertFalse(SafetyChange.widensAgent(all, all.copy(groups = emptySet())))
        // 「読むだけ」(止めていた道具が無いとき)
        assertFalse(SafetyChange.widensAgent(all, all.copy(groups = null, denied = emptySet(), readOnly = true)))
        // まとまりの最後の道具を止めると、まとまりごとオフになる
        val photos = agent(groups = setOf("写真"))
        val one = ToolGroups.groups.first { it.first == "写真" }.second.drop(1).fold(photos) { a, t -> AgentPolicy.withTool(a, t, false) }
        assertFalse(SafetyChange.widensAgent(one, AgentPolicy.withTool(one, "search_photos", false)))
    }

    @Test fun renamingIsInstant() {
        val a = agent(readOnly = true, groups = setOf("写真"), expiresAt = now + hour)
        assertFalse(SafetyChange.widensAgent(a, a.copy(name = "家の PC")))
    }

    @Test fun mainAgentFollowsTheSameRules() {
        val main = Agent.MAIN
        assertFalse(SafetyChange.widensAgent(main, main.copy(readOnly = true)))
        assertTrue(SafetyChange.widensAgent(main.copy(readOnly = true), main))
        assertTrue(SafetyChange.widensAgent(main.copy(enabled = false), main))
    }
}
