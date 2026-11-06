package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.kids.Summary
import com.rabi.envoymcp.kids.Topic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** したことの記録の文 (入れた文字・番号の全体を残さない) と、記録の残し方 */
class ActivitySentenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun args(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }
    private fun build(tool: String, a: JSONObject, r: ToolResult? = null, done: Boolean = true, label: (String) -> String? = { null }) =
        ActivitySentence.build(tool, "道具の名前", a, r, done, label)

    @Test fun phoneNumbersKeepOnlyLastFourDigits() {
        val s = build("call_phone", args("number" to "090-1234-5678"))
        assertEquals("末尾 5678 の番号に電話をかけた", s)
        assertFalse(s.contains("1234"))
        assertFalse(build("call_phone", args("contact" to "09012345678")).contains("0901234"))
        assertEquals("末尾 0117 の番号に電話をかけた", build("call_phone", args("number" to "0117")))
        assertFalse(build("rigel_call", args("number" to "+81 90 1234 5678", "message" to "明日の会議は 10 時から")).contains("1234"))
    }

    @Test fun peopleGetSan() {
        assertEquals("山田さんに電話をかけた", build("call_phone", args("contact" to "山田")))
        assertEquals("お母さんに電話をかけようとした", build("call_phone", args("contact" to "お母さん"), done = false))
        assertEquals("リゲルが山田さんに電話した", build("rigel_call", args("contact" to "山田", "message" to "明日は休みます")))
        assertEquals("リゲルが LINE で田中さんに電話した", build("rigel_call", args("contact" to "田中", "app" to "line", "message" to "x")))
        assertEquals("リゲルが山田さんからの電話に代わりに出た（詐欺の疑い: 高い）", ActivitySentence.incoming("山田", true, "詐欺の疑い: 高い"))
        assertEquals("リゲルが登録されていない番号（末尾1234）からの電話に代わりに出た", ActivitySentence.incoming("登録されていない番号（末尾1234）", true))
        assertEquals("リゲルが末尾 5678 の番号からの電話に代わりに出ようとした", ActivitySentence.incoming("03-1234-5678", false))
    }

    @Test fun messagesAreNeverStored() {
        // 用件・お知らせの中身は文に入れない
        assertFalse(build("rigel_call", args("contact" to "山田", "message" to "明日の会議は 10 時から")).contains("会議"))
        assertEquals("お知らせを預かった", build("rigel_notify", args("message" to "株価が 3% 上がりました", "title" to "ニュース")))
    }

    @Test fun typedTextIsOnlyCounted() {
        val secret = "パスワードは hunter2 です"
        val s = build("type_text", args("text" to secret, "element_id" to 3))
        assertEquals("文字を入れた（${secret.length} 文字）", s)
        assertFalse(s.contains("hunter2"))
        assertFalse(s.contains("パスワード"))
        // 結果の文に入れた文字が出ていても使わない
        val r = ToolResult("「$secret」を入れ、画面で確かめました。", JSONObject().put("result", "ok"))
        assertFalse(build("type_text", args("text" to secret), r).contains("hunter2"))
        assertEquals("文字を入れようとした（2 文字）", build("type_text", args("text" to "😀a"), done = false))
    }

    @Test fun tapUsesShortLabels() {
        val ok = ToolResult("「送信」を押しました。画面が変わりました。\n{}", JSONObject().put("result", "ok"))
        assertEquals("『送信』を押した", build("tap", args("element_id" to 4), ok))
        assertEquals("『投稿する』を押そうとした", build("tap", args("text" to "投稿する"), null, done = false))
        assertEquals("画面の部品を押した", build("tap", args("element_id" to 4), ToolResult("押しました")))
        // 長い文 (メッセージの中身かもしれない) は残さない
        val long = ToolResult("「明日の会議の資料を送っておきますね、よろしく」を押しました。")
        assertEquals("画面の部品を押した", build("tap", args("element_id" to 1), long))
    }

    @Test fun appsAndShares() {
        assertEquals("LINE を開いた", build("open_app", args("app" to "jp.naver.line.android"), ToolResult("LINE を開きました。\n{}")))
        assertEquals("LINE を開こうとした", build("open_app", args("app" to "jp.naver.line.android"), null, false) { if (it == "jp.naver.line.android") "LINE" else null })
        assertEquals("写真を LINE の共有で開いた", build("share_photo", args("photo_id" to 3, "app" to "LINE", "text" to "見て")))
        assertEquals("画面の画像を X の共有で開いた", build("share_screenshot", args("app" to "X")))
        assertEquals("電話の記録を LINE の共有で開いた", build("share_call_record", args("app" to "LINE")))
    }

    @Test fun readsAndOthers() {
        assertEquals("画面を読んだ", build("read_screen", args()))
        assertEquals("スマホの状態を見た", build("get_state", args()))
        assertEquals("写真を探した（2 枚）", build("search_photos", args("query" to "猫"),
            ToolResult("x", JSONObject().put("photos", JSONArray().put(1).put(2)))))
        assertFalse(build("search_photos", args("query" to "元カレの写真")).contains("元カレ"))
        assertEquals("画面を下へスクロールした", build("scroll", args("direction" to "down")))
        assertEquals("k キーを押した（3 回）", build("press_key", args("key" to "k", "times" to 3)))
        assertEquals("少し待った（2 秒）", build("wait", args("seconds" to 2)))
        assertEquals("スマホを縦にした", build("arm_portrait", args()))
        // 知らない道具は、道具の日本語の名前
        assertEquals("道具の名前", build("brand_new_tool", args()))
    }

    /** お勉強の道具は題だけを残す (子どもが選んだもの・正しい答えは残さない)。まとめは「まなび（かず）を 5 もん した（4 もん せいかい）」 */
    @Test fun kidsLessons() {
        assertEquals("お勉強（とけい）を始めた", build("kids_start", args("topic" to "tokei")))
        assertEquals("お勉強を始めた（題は子どもが選ぶ）", build("kids_start", args()))
        assertEquals("お勉強を始めようとした", build("kids_start", args("topic" to "eigo"), done = false))
        // 子どもの答え (声で言ったもの・選んだもの) と正しい答えは残さない
        val checked = ToolResult("ざんねん、ちがいました (声で「3じはん」)。\n{}", JSONObject().put("chosen", "3じはん").put("answer", "4じ"))
        val s = build("kids_answer", args("answer" to "さんじはん"), checked)
        assertEquals("お勉強の答えを確かめた", s)
        assertFalse(s.contains("3じ") || s.contains("4じ") || s.contains("さんじ"))
        assertEquals("子どもをほめた", build("kids_cheer", args()))
        assertEquals("お勉強をおしまいにした", build("kids_end", args()))
        val sum = Summary(listOf(Topic.KAZU), 5, 4, 5)
        assertEquals("まなび（かず）を 5 もん した（4 もん せいかい）", sum.sentence)
    }

    @Test fun outcomeAndApproval() {
        val approved = ToolResult("ok", JSONObject().put("result", "ok").put("approved", true))
        assertEquals(ActivityLog.OK, ActivitySentence.outcome(approved))
        assertEquals(true, ActivitySentence.approved(approved))
        val rejected = ToolResult("利用者が承認しなかったので押していません (取り消し)。", JSONObject().put("result", "failed").put("approved", false), isError = true)
        assertEquals(ActivityLog.REFUSED, ActivitySentence.outcome(rejected))
        assertEquals(false, ActivitySentence.approved(rejected))
        // approved が無くても、承認しなかったと書いてあれば断った
        val callDenied = ToolResult("利用者が承認しなかったので、電話はかけていません (取り消し)。\n{}", JSONObject().put("result", "failed"), isError = true)
        assertEquals(ActivityLog.REFUSED, ActivitySentence.outcome(callDenied))
        val failed = ToolResult("押せませんでした", isError = true)
        assertEquals(ActivityLog.FAILED, ActivitySentence.outcome(failed))
        assertNull(ActivitySentence.approved(failed))
    }

    @Test fun logKeepsRecentEntriesOnly() {
        var now = 100L * 24 * 60 * 60_000L
        val f = tmp.newFile("activity.json").also { it.delete() }
        val log = ActivityLog(f, max = 3, keepMs = 1_000L, now = { now })
        log.add("ChatGPT", "open_app", "LINE を開いた", ActivityLog.KIND_ACT, ActivityLog.OK)
        now += 2_000L
        log.add("ChatGPT", "tap", "『送信』を押した", ActivityLog.KIND_ACT, ActivityLog.OK, approved = true)
        // 古いもの (keepMs より前) は消える
        assertEquals(listOf("tap"), log.entries.value.map { it.tool })
        repeat(4) { log.add("Claude", "read_screen", "画面を読んだ", ActivityLog.KIND_READ, ActivityLog.OK) }
        assertEquals(3, log.entries.value.size)
        // ファイルから読み直しても同じ (承認の有無も)
        val again = ActivityLog(f, max = 3, keepMs = 1_000L, now = { now })
        assertEquals(log.entries.value, again.entries.value)
        log.add("リゲル", "incoming_call", "リゲルが山田さんからの電話に代わりに出た", ActivityLog.KIND_ACT, ActivityLog.OK, approved = false)
        assertEquals(false, ActivityLog(f, max = 3, keepMs = 1_000L, now = { now }).entries.value.first().approved)
    }
}
