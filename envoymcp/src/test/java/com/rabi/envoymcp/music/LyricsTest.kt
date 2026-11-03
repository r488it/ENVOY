package com.rabi.envoymcp.music

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音楽の画面の歌詞: LRC の読み取り・時刻から行を探す・曲名を検索向けにする・LRCLIB の答えから選ぶ */
class LyricsTest {
    @Test fun parsesLrc() {
        val l = Lyrics.parseLrc(
            """
            [ar:Someone]
            [ti:Song]
            [00:12.30]はじめの行
            [00:15.5]二つめ
            [01:02.345][01:30.00]くり返し
            [01:40.00]
            [00:20.00]<00:20.00>語<00:20.50>ごと
            """.trimIndent())
        assertEquals(listOf(12_300L, 15_500L, 20_000L, 62_345L, 90_000L, 100_000L), l.map { it.ms })
        assertEquals("はじめの行", l[0].text)
        assertEquals("語ごと", l[2].text)            // 語ごとの時刻 <..> は外す
        assertEquals("くり返し", l[3].text); assertEquals("くり返し", l[4].text)
        assertEquals("", l[5].text)                  // 間奏の印
    }

    @Test fun dropsCredits() {
        val l = Lyrics.parseLrc("[00:00.00] 作词 : 米津玄師\n[00:00.50] 作曲 : 米津玄師\n[00:01.00]Lyrics by: A\n[00:12.00]夢ならばどれほど")
        assertEquals(listOf("夢ならばどれほど"), l.map { it.text })
    }

    @Test fun offsetMovesEarlier() {
        val l = Lyrics.parseLrc("[offset:+500]\n[00:10.00]a")
        assertEquals(9_500L, l[0].ms)
    }

    @Test fun findsLineAtTime() {
        val l = listOf(LyricLine(1_000, "a"), LyricLine(3_000, "b"), LyricLine(12_000, "c"))
        assertEquals(-1, Lyrics.lineAt(l, 500))
        assertEquals(0, Lyrics.lineAt(l, 1_000))
        assertEquals(1, Lyrics.lineAt(l, 8_999))
        assertEquals(2, Lyrics.lineAt(l, 60_000))
        assertEquals(2_000L, Lyrics.spanMs(l, 0))
        assertEquals(Lyrics.MAX_LINE_MS, Lyrics.spanMs(l, 1))     // 長い間奏は 7 秒まで
        assertEquals(Lyrics.MAX_LINE_MS, Lyrics.spanMs(l, 2))     // 最後の行
    }

    @Test fun cleansTitlesForSearch() {
        assertEquals("夜に駆ける", Lyrics.cleanTitle("夜に駆ける (Official Music Video)"))
        assertEquals("Lemon", Lyrics.cleanTitle("米津玄師 - Lemon", "米津玄師"))
        assertEquals("Shape of You", Lyrics.cleanTitle("Shape of You (feat. Someone)"))
        assertEquals("アイドル", Lyrics.cleanTitle("アイドル【MV】"))
        assertEquals("Song", Lyrics.cleanTitle("Song [Official Lyric Video]"))
        assertEquals("ただの (曲名)", Lyrics.cleanTitle("ただの (曲名)"))                 // 関係ない括弧は残す
        assertEquals("Ado", Lyrics.cleanArtist("Ado - Topic"))
        assertEquals("King Gnu", Lyrics.cleanArtist("King Gnu, Someone"))
    }

    @Test fun picksSyncedLyricsWithCloseDuration() {
        val arr = JSONArray(
            """[
              {"duration": 200.0, "syncedLyrics": null, "plainLyrics": "plain only", "instrumental": false},
              {"duration": 230.0, "syncedLyrics": "[00:01.00]far", "plainLyrics": "x", "instrumental": false},
              {"duration": 201.5, "syncedLyrics": "[00:01.00]near", "plainLyrics": "x", "instrumental": false}
            ]""")
        val cands = (0 until arr.length()).map { Lyrics.candidateOf(arr.getJSONObject(it)) }
        assertNull(cands[0].synced)
        assertEquals("[00:01.00]near", Lyrics.pickBest(cands, 200.0)?.synced)
        assertNull(Lyrics.pickBest(cands.take(2), 200.0))                    // 時刻付きで長さが近いものが無い
        assertTrue(Lyrics.pickBest(cands, 0.0)?.synced != null)              // 長さが分からなければ、最初の時刻付き
        // 4 秒以内が無ければ、10 秒以内でいちばん近いもの (版の違いで長さがずれる)
        val near7 = Lyrics.Candidate(207.0, "[00:01.00]seven", null, false)
        val far12 = Lyrics.Candidate(212.5, "[00:01.00]twelve", null, false)
        assertEquals("[00:01.00]seven", Lyrics.pickBest(listOf(far12, near7), 200.0)?.synced)
        assertNull(Lyrics.pickBest(listOf(far12), 200.0))
    }
}
