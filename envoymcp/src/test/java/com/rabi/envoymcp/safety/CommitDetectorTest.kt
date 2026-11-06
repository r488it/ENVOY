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

    private fun commit(text: String) = CommitDetector.looksLikeCommit(node(text = text))

    /** お金を動かす・アプリや端末を変える・同意するボタンは確定 (「決済」の「済」で外れない) */
    @Test fun moneyAndAppButtonsNeedApproval() {
        for (t in listOf("決済する", "決済を確定", "振込実行", "振り込む", "送金する", "出金", "チャージする", "入札する", "寄付する",
            "インストール", "アンインストール", "設定をリセット", "初期化する", "通知を許可", "同意する", "解約する", "退会する", "実行")) {
            assertTrue(t, commit(t))
        }
        assertEquals(CommitKind.PURCHASE, CommitDetector.kindOf(node(text = "決済する")))
        assertEquals(CommitKind.PURCHASE, CommitDetector.kindOf(node(text = "振込実行")))
        assertEquals(CommitKind.DELETE, CommitDetector.kindOf(node(text = "アンインストール")))
        assertEquals(CommitKind.DELETE, CommitDetector.kindOf(node(text = "設定をリセット")))
    }

    /** 英語の語は単語として見る: Delete playlist は確定、Blog・Login・Playlist・Deleted は確定ではない */
    @Test fun englishWordsAreWholeWords() {
        for (t in listOf("Delete playlist", "Send draft", "Delete folder", "Reset settings", "Install", "Uninstall", "Pay now", "Checkout",
            "Place order", "Transfer", "Allow", "Accept", "I agree", "Subscribe", "Withdraw", "Donate", "Erase all data", "Buy now")) {
            assertTrue(t, commit(t))
        }
        for (t in listOf("Blog", "Login", "Consent", "Present", "Posts", "Deleted", "Playlist", "Call history", "Order history", "Purchase history",
            "Don't allow", "Settings", "Sent")) {
            assertFalse(t, commit(t))
        }
    }

    /** 済んだこと・途中・しないことの知らせは確定ではない */
    @Test fun statusIsNotCommit() {
        for (t in listOf("送信済み", "決済済み", "支払い済み", "送信済", "写真を送信しました", "注文を確定しました", "送信中", "許可しない", "同意しません")) {
            assertFalse(t, commit(t))
        }
    }

    /** 一覧・設定の語は、語として見る。後ろに確定の語があれば確定 (「設定」は入口、「設定をリセット」は確定) */
    @Test fun navigationWordsDoNotHideLaterCommit() {
        for (t in listOf("設定", "通話履歴", "購入履歴", "注文履歴を見る", "送信トレイ", "転送設定", "公開設定", "一覧")) assertFalse(t, commit(t))
        for (t in listOf("設定をリセット", "閲覧履歴を消去", "追加で購入", "下書きを送信", "一覧から削除", "通話履歴を削除")) assertTrue(t, commit(t))
    }

    /** ID の語は、文字の除外より強い (文字が「送信済み」でも ID が checkout なら確定)。ID の大文字の区切りも語の区切り */
    @Test fun idWinsOverLabel() {
        assertTrue(CommitDetector.looksLikeCommit(node(text = "送信済み", id = "com.shop:id/checkout_button")))
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.chat:id/sendButton")))
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.store:id/uninstall_button")))
        assertTrue(CommitDetector.looksLikeCommit(node(id = "com.android.permissioncontroller:id/permission_allow_button")))
        assertFalse(CommitDetector.looksLikeCommit(node(id = "com.camera:id/preset_list")))
        assertFalse(CommitDetector.looksLikeCommit(node(id = "com.android.permissioncontroller:id/permission_deny_button")))
    }

    /** 24 字より長い文字でも、頭か終わりに確定の語があれば確定 */
    @Test fun longLabelWithCommitAtEdge() {
        val head = "注文を確定する (お支払い金額 ¥12,800 税込・送料無料・明日お届け予定)"
        assertTrue(com.rabi.envoymcp.core.Text.norm(head).length > 24)
        assertTrue(commit(head))
        assertTrue(commit("ご確認のうえ、内容に問題がなければ、こちらのボタンからお支払いを確定する"))
        // 本文の途中の語では止めない
        assertFalse(commit("きょうの散歩の写真です。公園で撮りました。あとでポストの続きを書きます。また見てください"))
    }

    /** 見本のアプリ (まいにちマート・パズル・音楽) のボタン: 確定は「注文を確定する」だけ */
    @Test fun demoAppButtons() {
        assertTrue(commit("注文を確定する"))
        for (t in listOf("選ぶ", "買い物を続ける", "今すぐ無料で遊ぶ", "広告を閉じる", "つぎのステージへ", "再生", "一時停止", "いつものお米 5kg")) {
            assertFalse(t, commit(t))
        }
    }
}
