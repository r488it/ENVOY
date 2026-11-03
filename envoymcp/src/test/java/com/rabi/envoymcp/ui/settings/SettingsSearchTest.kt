package com.rabi.envoymcp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 設定の画面の検索: 見出しが合えばカードを丸ごと、合わなければ合う項目だけ。表記のゆれは区別しない */
class SettingsSearchTest {
    private fun search() = SettingsSearch().apply {
        add("AgentARM (スマホを載せる腕)", "撮りながら追う")
        add("AgentARM (スマホを載せる腕)", "音楽が流れたら踊る")
        add("操作と表示", "音声モードの間は画面全体をリゲルにする")
        add("トンネル (スマホの中の tunnel-client)", "tunnel_id を保存")
    }

    @Test fun normalizesKanaWidthCaseAndSpaces() {
        assertEquals(SettingsSearch.norm("マイク"), SettingsSearch.norm("まいく"))
        assertEquals("tunnel", SettingsSearch.norm("ＴＵＮＮＥＬ"))
        assertEquals("agentarm", SettingsSearch.norm("Agent ARM"))
        assertEquals(SettingsSearch.norm("ﾘｹﾞﾙ"), SettingsSearch.norm("りげる"))
    }

    @Test fun titleHitShowsWholeCard() {
        val s = search()
        s.setQuery("agentarm")
        assertTrue(s.cardVisible("AgentARM (スマホを載せる腕)"))
        assertTrue(s.titleHit("AgentARM (スマホを載せる腕)"))
        assertFalse(s.cardVisible("操作と表示"))
    }

    @Test fun itemHitShowsOnlyMatchingItems() {
        val s = search()
        s.setQuery("踊る")
        assertTrue(s.cardVisible("AgentARM (スマホを載せる腕)"))
        assertFalse(s.titleHit("AgentARM (スマホを載せる腕)"))
        assertTrue(s.itemHit("音楽が流れたら踊る"))
        assertFalse(s.itemHit("撮りながら追う"))
        s.setQuery("リゲル")
        assertTrue(s.cardVisible("操作と表示"))
        assertFalse(s.cardVisible("AgentARM (スマホを載せる腕)"))
    }

    @Test fun emptyQueryShowsAllAndNothingMatchesIsKnown() {
        val s = search()
        s.setQuery("  ")
        assertFalse(s.active)
        assertTrue(s.cardVisible("どこか"))
        assertTrue(s.itemHit("なんでも"))
        s.setQuery("存在しない言葉")
        assertFalse(s.anyVisible())
        s.setQuery("TUNNEL_ID")
        assertTrue(s.anyVisible())
    }
}
