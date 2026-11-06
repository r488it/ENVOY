package com.rabi.envoymcp.ui.settings

import com.rabi.envoymcp.kids.KidsRules
import com.rabi.envoymcp.mcp.Agent
import com.rabi.envoymcp.mcp.AgentPolicy
import com.rabi.envoymcp.mcp.ToolGroups

/**
 * 安全の設定の変更が、守りをゆるめるか。ゆるめる変更は、ご本人か確かめてから行う (OwnerCheck)。
 * 守りを強める変更 (承認をオン・開発モードをオフ・権限を狭める・止める・消す・外からの入口を閉じる・子どもモードを入れる) は、確かめずにすぐ行う。端末に触れない (試験する)
 */
object SafetyChange {
    /** 確定の前の承認をゆるめるか: 承認画面か本人確認のどちらかをオフにする (両方オフなら、承認なしで確定する) */
    fun loosensApproval(screen: Boolean, biometric: Boolean, nextScreen: Boolean, nextBiometric: Boolean): Boolean =
        (screen && !nextScreen) || (biometric && !nextBiometric)

    /** 開発モードをオンにするか (止めていないエージェントの、権限の制限と期限を外す) */
    fun loosensDevMode(on: Boolean, nextOn: Boolean): Boolean = !on && nextOn

    /** 外からつなぐ入口 (ngrok) を開けるか (インターネットから、このスマホの MCP サーバーに届くようになる) */
    fun opensEntrance(on: Boolean, nextOn: Boolean): Boolean = !on && nextOn

    /**
     * 子どもモードを切るか (子どもが自分で切れないように、切るときだけ確かめる)。
     * 入れるのは守りを強めるので、すぐ
     */
    fun leavesKidMode(on: Boolean, nextOn: Boolean): Boolean = on && !nextOn

    /**
     * 子どもモードの間に、まなびの決まりをゆるめるか: 1 日の時間をのばす・AI の先生を入れる (子どもの声が AI に届く)・画面の固定を切る。
     * 子どもモードがオフのとき・時間を短くする・題を変えるだけのときは、すぐ変える
     */
    fun loosensKids(kidMode: Boolean, cur: KidsRules, next: KidsRules): Boolean =
        kidMode && (next.limitMin > cur.limitMin || (!cur.aiTeacher && next.aiTeacher) || (cur.pin && !next.pin))

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
