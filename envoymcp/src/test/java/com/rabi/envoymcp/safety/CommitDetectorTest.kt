package com.rabi.envoymcp.safety

import com.rabi.envoymcp.observe.Bounds
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.CommitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitDetectorTest {
    private fun node(text: String = "", desc: String = "", id: String = "", cls: String = "android.widget.Button") =
        UiNode(0, text, desc, id, cls, "com.twitter.android", Bounds(0, 0, 100, 100), clickable = true, enabled = true, focused = false,
            scrollable = false, checked = false, password = false, hint = "", depth = 0)

    /** 日本語の X の投稿ボタン「ポストする」も確定とみなし、承認を求める */
    @Test fun japaneseXPostButtonNeedsApproval() {
        assertTrue(CommitDetector.looksLikeCommit(node(text = "ポストする")))
        assertTrue(CommitDetector.looksLikeCommit(node(desc = "ポストする")))
        assertEquals(CommitKind.POST, CommitDetector.kindOf(node(text = "ポストする")))
        // リポストも公開の操作
        assertTrue(CommitDetector.looksLikeCommit(node(text = "リポスト")))
        assertTrue(CommitDetector.looksLikeCommit(node(text = "返信")))
        assertEquals(CommitKind.SEND, CommitDetector.kindOf(node(text = "返信")))
    }

    /** LINE の共有で送り先を選んだあとの「転送」も、送る操作として承認を求める */
    @Test fun lineForwardNeedsApproval() {
        assertTrue(CommitDetector.looksLikeCommit(node(text = "転送")))
        assertEquals(CommitKind.SEND, CommitDetector.kindOf(node(text = "転送")))
        assertTrue(CommitDetector.looksLikeCommit(node(text = "Forward")))
        assertFalse(CommitDetector.looksLikeCommit(node(text = "転送設定")))
    }

    @Test fun postButtonIdsNeedApproval() {
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.twitter.android:id/button_tweet")))
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.example:id/retweet")))
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.example:id/reply_button")))
    }

    @Test fun navigationIsNotCommit() {
        // 設定・一覧の入口や、長い説明 (タイムラインのポストの本文など) は確定にしない
        assertFalse(CommitDetector.looksLikeCommit(node(text = "ポストの通知設定")))
        assertFalse(CommitDetector.looksLikeCommit(node(desc = "さんのポスト。きょうは天気がよくて、公園まで散歩に行きました。写真あり")))
        assertFalse(CommitDetector.looksLikeCommit(node(text = "ホーム")))
        // X の投稿の画面の「ポストを追加」(スレッドに足すだけ。実機の X で出る文字) と下書き
        assertFalse(CommitDetector.looksLikeCommit(node(desc = "ポストを追加")))
        assertFalse(CommitDetector.looksLikeCommit(node(text = "下書きに保存")))
    }
}
