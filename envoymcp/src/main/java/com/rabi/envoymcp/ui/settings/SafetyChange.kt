package com.rabi.envoymcp.ui.settings

import com.rabi.envoymcp.mcp.Agent
import com.rabi.envoymcp.mcp.AgentPolicy
import com.rabi.envoymcp.mcp.ToolGroups

/**
 * 安全の設定の変更が、守りをゆるめるか。ゆるめる変更は、ご本人か確かめてから行う (OwnerCheck)。
 * 守りを強める変更 (承認をオン・開発モードをオフ・権限を狭める・止める・消す) は、確かめずにすぐ行う。端末に触れない (試験する)
 */
object SafetyChange {
    /** 確定の前の承認をゆるめるか: 承認画面か本人確認のどちらかをオフにする (両方オフなら、承認なしで確定する) */
    fun loosensApproval(screen: Boolean, biometric: Boolean, nextScreen: Boolean, nextBiometric: Boolean): Boolean =
        (screen && !nextScreen) || (biometric && !nextBiometric)

    /** 開発モードをオンにするか (止めていないエージェントの、権限の制限と期限を外す) */
    fun loosensDevMode(on: Boolean, nextOn: Boolean): Boolean = !on && nextOn

    /**
     * エージェントの権限を広げるか。次のどれかなら広げる:
     * 止めていたものを使えるようにする・読むだけをやめる・期限をのばす / なくす・使える道具が 1 つでも増える。
     * 名前だけの変更と、狭める変更は広げない
     */
    fun widensAgent(cur: Agent, next: Agent): Boolean =
        (!cur.enabled && next.enabled) ||
            (cur.readOnly && !next.readOnly) ||
            extendsExpiry(cur.expiresAt, next.expiresAt) ||
            addsTools(cur, next)

    /** 期限をのばす・なくす (0 は期限なし) */
    private fun extendsExpiry(cur: Long, next: Long): Boolean = cur > 0 && (next == 0L || next > cur)

    /**
     * 使える道具が増えるか (読むだけかどうかは見ない)。まとまりの道具と、1 つずつ止めていた道具を比べる。
     * どのまとまりにも入らない道具 (その他) は、すべてのまとまりを許すと使えるようになる
     */
    private fun addsTools(cur: Agent, next: Agent): Boolean {
        if (AgentPolicy.allGroups(next) && !AgentPolicy.allGroups(cur)) return true
        val names = ToolGroups.groups.flatMap { it.second } + cur.denied + next.denied
        return names.any { AgentPolicy.toolOn(next, it) && !AgentPolicy.toolOn(cur, it) }
    }
}
