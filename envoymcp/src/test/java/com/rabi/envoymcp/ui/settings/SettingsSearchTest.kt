package com.rabi.envoymcp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 設定の画面の検索: 見出しが合えばカードを丸ごと、合わなければ合う項目だけ。合うカードのある区分を出す。表記のゆれは区別しない */
class SettingsSearchTest {
    private fun search() = SettingsSearch().apply {
        add("AgentARM (スマホを載せる腕)", "AgentARM を使う")
        add("音楽に合わせて踊る", "音楽が流れたら踊る")
        add("試す", "撮りながら追う")
        add("音声モードの画面", "音声モードの間は画面全体をリゲルにする")
        add("トンネル (スマホの中の tunnel-client)", "tunnel_id を保存")
        place("AgentARM (スマホを載せる腕)", "AgentARM")
        place("音楽に合わせて踊る", "AgentARM")
        place("試す", "AgentARM")
        place("音声モードの画面", "リゲル")
        place("トンネル (スマホの中の tunnel-client)", "つながり")
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
        assertFalse(s.cardVisible("音声モードの画面"))
    }

    @Test fun itemHitShowsOnlyMatchingItems() {
        val s = search()
        s.setQuery("踊る")
        assertTrue(s.cardVisible("音楽に合わせて踊る"))
        assertTrue(s.itemHit("音楽が流れたら踊る"))
        assertFalse(s.itemHit("撮りながら追う"))
        s.setQuery("追う")
        assertTrue(s.cardVisible("試す"))
        assertFalse(s.titleHit("試す"))
        s.setQuery("リゲル")
        assertTrue(s.cardVisible("音声モードの画面"))
        assertFalse(s.cardVisible("AgentARM (スマホを載せる腕)"))
    }

    @Test fun sectionShowsWhenACardInsideMatches() {
        val s = search()
        s.setQuery("撮りながら")
        assertTrue(s.sectionVisible("AgentARM"))
        assertFalse(s.sectionVisible("リゲル"))
        assertFalse(s.sectionVisible("つながり"))
        // 区分の名前が合えば、中のカードが合わなくても出す
        s.setQuery("つながり")
        assertTrue(s.sectionVisible("つながり"))
        assertFalse(s.cardVisible("トンネル (スマホの中の tunnel-client)"))
        assertTrue(s.anyVisible())
        s.setQuery("")
        assertTrue(s.sectionVisible("どこか"))
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
