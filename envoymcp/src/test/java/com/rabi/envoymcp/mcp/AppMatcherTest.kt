package com.rabi.envoymcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YT Music",
        "com.google.android.apps.youtube.kids" to "YouTube Kids",
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.maps" to "マップ",
        "com.google.android.gm" to "Gmail",
        "com.twitter.android" to "X",
        "jp.naver.line.android" to "LINE",
        "com.google.android.apps.messaging" to "メッセージ",
        "com.android.chrome" to "Chrome",
    )

    private fun pick(q: String) = (AppMatcher.resolve(q, apps) as? AppMatcher.Result.Found)?.pkg

    @Test fun youtubeMusicIsNotYoutube() {
        // 「YouTube Music」で YouTube を開かない (YouTube Music のアプリの名前は「YT Music」)
        for (q in listOf("YouTube Music", "youtube music", "ユーチューブミュージック", "YTミュージック", "YT Music", "YouTubeミュージック")) {
            assertEquals(q, "com.google.android.apps.youtube.music", pick(q))
        }
        assertEquals("com.google.android.youtube", pick("YouTube"))
        assertEquals("com.google.android.youtube", pick("ユーチューブ"))
        assertEquals("com.google.android.apps.youtube.kids", pick("YouTube Kids"))
    }

    @Test fun usualNames() {
        assertEquals("com.spotify.music", pick("Spotify"))
        assertEquals("com.spotify.music", pick("スポティファイ"))
        assertEquals("com.twitter.android", pick("X"))
        assertEquals("jp.naver.line.android", pick("line"))
        assertEquals("com.google.android.apps.maps", pick("マップ"))
        assertEquals("com.android.chrome", pick("com.android.chrome"))
        assertTrue(AppMatcher.resolve("存在しないアプリ", apps) is AppMatcher.Result.NotFound)
    }

    @Test fun partOfTheNameIsNotEnough() {
        // 「Google フォト」(フォトが一覧に無い) で Google を開かない
        val withGoogle = apps + ("com.google.android.googlequicksearchbox" to "Google")
        assertTrue(AppMatcher.resolve("Google フォト", withGoogle) is AppMatcher.Result.NotFound)
        assertTrue(AppMatcher.resolve("Googleフォト", withGoogle) is AppMatcher.Result.NotFound)
        assertEquals("com.google.android.googlequicksearchbox", (AppMatcher.resolve("Google", withGoogle) as AppMatcher.Result.Found).pkg)
        assertEquals("com.google.android.apps.maps", (AppMatcher.resolve("Google マップ", withGoogle) as AppMatcher.Result.Found).pkg)
        assertEquals("jp.naver.line.android", pick("LINEアプリ"))
        assertEquals("com.android.chrome", pick("Chrome ブラウザ"))
    }
}
