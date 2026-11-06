package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.call.NumberMask
import org.json.JSONObject

/**
 * したことの記録 (ActivityLog) の文。道具ごとに、引数と結果から本人が読める短い文を作る。
 * - うまくいったら「LINE を開いた」、うまくいかなかった・断った・止めたら「LINE を開こうとした」
 * - 入れた文字・画面の中身・メッセージ・用件・要点・合言葉は入れない (入れた文字は数だけ)
 * - 電話番号らしいものは末尾 4 桁だけ。押した部品の名前は短いものだけ
 */
internal object ActivitySentence {
    /** したとき (done) と、しようとしたとき (tried) の言い方 */
    private class V(val done: String, val tried: String)

    private val SEE = V("見た", "見ようとした")
    private val READ = V("読んだ", "読もうとした")
    private val OPEN = V("開いた", "開こうとした")
    private val PRESS = V("押した", "押そうとした")
    private val DO = V("した", "しようとした")
    private val START = V("始めた", "始めようとした")
    private val STOP = V("止めた", "止めようとした")

    /**
     * 道具の文。title は道具の日本語の名前 (知らない道具はこれを使う)、result は道具の結果 (断ったときは null でもよい)、
     * done はうまくいったか、appLabel はパッケージ名からアプリの名前を引く
     */
    fun build(tool: String, title: String, args: JSONObject, result: ToolResult?, done: Boolean, appLabel: (String) -> String? = { null }): String {
        fun s(head: String, v: V) = head + if (done) v.done else v.tried
        fun app(key: String) = appName(args.optString(key), appLabel)
        val first = result?.text?.lineSequence()?.firstOrNull().orEmpty()
        return when (tool) {
            "get_state" -> s("スマホの状態を", SEE)
            "read_screen" -> s("画面を", READ)
            "get_screenshot" -> s("画面の画像を", SEE)
            "look_at_screen" -> s("画面を見て", V("調べた", "調べようとした"))
            "list_apps" -> s("アプリの一覧を", SEE)
            "list_shortcuts" -> s("ショートカットの一覧を", SEE)
            "list_parts" -> s("部品の一覧を", SEE)
            "open_app" -> {
                val name = Regex("^(.{1,30}) を開きました").find(first)?.groupValues?.get(1)?.let { clean(it) } ?: app("app")
                s(if (name != null) "$name を" else "アプリを", OPEN)
            }
            "open_shortcut" -> s(app("package")?.let { "$it のショートカットを" } ?: "ショートカットを", OPEN)
            "call_phone" -> s("${person(args.optString("contact").ifBlank { args.optString("number") })}に電話を", V("かけた", "かけようとした"))
            "rigel_call" -> s("リゲルが" + (if (args.optString("app") == "line") " LINE で" else "") +
                "${person(args.optString("contact").ifBlank { args.optString("number") })}に", V("電話した", "電話しようとした"))
            "rigel_call_status" -> s("リゲルの電話の様子を", SEE)
            "rigel_call_hangup" -> s("リゲルの電話を", V("切った", "切ろうとした"))
            "rigel_incoming" -> when {
                !done -> "かかってきた電話の出方を伝えようとした"
                args.optString("choice") == "rigel" -> "かかってきた電話に、リゲルが代わりに出ることにした"
                else -> "かかってきた電話に、自分で出ることにした"
            }
            "share_call_record" -> s("電話の記録を${shareTo(app("app"))}", OPEN)
            "share_screenshot" -> s("画面の画像を${shareTo(app("app"))}", OPEN)
            "share_photo" -> s("写真を${shareTo(app("app"))}", OPEN)
            "end_voice_chat" -> s("音声の会話を", V("終えた", "終えようとした"))
            "search_photos" -> s("写真を", V("探した", "探そうとした")) + when (val n = result?.structured?.optJSONArray("photos")?.length()) {
                null -> ""
                0 -> "（見つからなかった）"
                else -> "（$n 枚）"
            }
            "show_photo" -> s("写真を大きく", V("見せた", "見せようとした"))
            "open_panel" -> when (args.optString("which")) {
                "quick_settings" -> s("クイック設定を", OPEN)
                "notifications" -> s("通知を", OPEN)
                "close" -> s("通知・クイック設定を", V("閉じた", "閉じようとした"))
                else -> s("画面の上を", OPEN)
            }
            "tap" -> {
                val label = (if (done) quoted(first) else null) ?: clean(args.optString("text")) ?: quoted(first)
                s(if (label != null) "『$label』を" else "画面の部品を", PRESS)
            }
            "point_at" -> {
                val label = clean(result?.structured?.optString("label").orEmpty()) ?: clean(args.optString("text"))
                s(if (label != null) "『$label』の場所を指して" else "押す場所を指して", V("教えた", "教えようとした"))
            }
            "type_text" -> s("文字を", V("入れた", "入れようとした")) + "（${args.optString("text").codePointCount()} 文字）"
            "scroll" -> s("画面を" + when (args.optString("direction")) { "down" -> "下へ"; "up" -> "上へ"; "left" -> "左へ"; "right" -> "右へ"; else -> "" },
                V("スクロールした", "スクロールしようとした"))
            "back" -> s("「戻る」を", PRESS)
            "home" -> s("ホームに", V("戻った", "戻ろうとした"))
            "press_key" -> {
                val key = args.optString("key").trim().takeIf { k -> k.length in 1..16 && k.all { it.isLetterOrDigit() || it == '_' } }
                val times = args.optInt("times", 1)
                s(if (key != null) "$key キーを" else "キーを", PRESS) + if (times > 1) "（$times 回）" else ""
            }
            "wait" -> s("少し", V("待った", "待とうとした")) + (args.optDouble("seconds").takeIf { !it.isNaN() && it > 0 }?.let { sec ->
                "（${if (sec % 1.0 == 0.0) sec.toLong().toString() else "%.1f".format(sec)} 秒）"
            } ?: "")
            "arm_portrait" -> s("スマホを縦に", DO)
            "arm_landscape" -> s("スマホを横に", DO)
            "arm_look_at_me" -> s("スマホをあなたの方に", V("向けた", "向けようとした"))
            "arm_follow_me" -> s("あなたの顔を追い", START)
            "arm_stop_following" -> s("顔を追うのを", V("やめた", "やめようとした"))
            "arm_record_video" -> s(if (args.optBoolean("follow", true)) "動画を撮りながら顔を追い" else "動画を撮り", START)
            "arm_switch_camera" -> s("撮りながらカメラを", V("切り替えた", "切り替えようとした"))
            "arm_stop_recording" -> s("録画を", STOP)
            "arm_move" -> s("腕を", V("動かした", "動かそうとした"))
            "arm_motion" -> s("腕で動きを", DO)
            "arm_dance" -> s("音楽に合わせて踊り", START)
            "arm_stop" -> s("腕を", STOP)
            "rigel_expression" -> s("リゲルが表情を", V("見せた", "見せようとした"))
            "rigel_notify" -> s("お知らせを", V("預かった", "預かろうとした"))
            "get_notices" -> s("お知らせを", READ)
            "run_part" -> s((quoted(first) ?: clean(args.optString("part_id")))?.let { "部品「$it」を" } ?: "部品を", V("動かした", "動かそうとした"))
            "save_part" -> s(clean(args.optString("name"))?.let { "部品「$it」を" } ?: "部品を", V("登録した", "登録しようとした"))
            "delete_part" -> s((if (done) quoted(first) else null)?.let { "部品「$it」を" } ?: "部品を", V("消した", "消そうとした"))
            else -> title.ifBlank { tool }
        }
    }

    /** リゲルがかかってきた電話に代わりに出た (risk は「詐欺の疑い: 高い」など。無ければ空) */
    fun incoming(from: String, answered: Boolean, risk: String = ""): String =
        "リゲルが${person(from)}からの電話に代わりに" + (if (answered) "出た" else "出ようとした") + if (risk.isNotBlank()) "（$risk）" else ""

    /** Claude のコネクターをつないだ・つながなかった (why は「許可しなかった」「時間切れ」など。無ければ空) */
    fun connector(done: Boolean, why: String = ""): String =
        "Claude のコネクターを" + (if (done) "つないだ" else "つながなかった") + if (why.isNotBlank()) "（$why）" else ""

    /** どうなったか: 本人が承認しなかった → refused、うまくいった → ok、それ以外 → failed (止めた・断ったは呼ぶ側が決める) */
    fun outcome(r: ToolResult): String = when {
        approved(r) == false -> ActivityLog.REFUSED
        !r.isError -> ActivityLog.OK
        else -> ActivityLog.FAILED
    }

    /** 本人が承認したか (結果の approved。無くても、承認されなかったと書いてあれば false。承認の無いものは null) */
    fun approved(r: ToolResult): Boolean? {
        val s = r.structured
        if (s != null && s.has("approved")) return s.optBoolean("approved")
        if (r.isError && r.text.lineSequence().firstOrNull().orEmpty().contains("承認しなかった")) return false
        return null
    }

    /** 電話の相手の呼び方: 番号らしければ末尾 4 桁だけ、名前なら「さん」を付ける (付いていれば付けない) */
    fun person(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return "相手"
        if (isNumber(s)) return "末尾 ${s.filter { it.isDigit() }.takeLast(4)} の番号"
        val n = s.take(MAX_NAME)
        return if (HONORIFICS.any { n.endsWith(it) } || n.contains("番号") || n.endsWith("相手")) n else "${n}さん"
    }

    /** 電話番号らしい (6 桁以上の並び、または数字と記号だけで 3 桁以上) */
    private fun isNumber(s: String): Boolean =
        NumberMask.looksLikeNumber(s) || (s.count { it.isDigit() } >= 3 && s.all { it.isDigit() || it in "+＋-‐－–()（） .・" })

    /** 部品の名前などを、記録に残せる短い一行にする (長いもの・空なら null。番号らしければ末尾 4 桁だけ) */
    private fun clean(raw: String): String? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty() || s.length > MAX_LABEL) return null
        if (isNumber(s)) return "末尾 ${s.filter { it.isDigit() }.takeLast(4)} の番号"
        return s
    }

    /** 結果の最初の行の「」の中 (押した部品・部品の名前) */
    private fun quoted(line: String): String? = Regex("「([^」]{1,40})」").find(line)?.groupValues?.get(1)?.let { clean(it) }

    /** アプリの名前 (パッケージ名ならアプリの名前を引く) */
    private fun appName(raw: String, appLabel: (String) -> String?): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val label = if (s.contains('.') && !s.contains(' ')) appLabel(s) ?: s else s
        return clean(label)
    }

    private fun shareTo(app: String?) = if (app != null) " $app の共有で" else "共有の画面で"

    private fun String.codePointCount() = codePointCount(0, length)

    private val HONORIFICS = listOf("さん", "様", "さま", "くん", "君", "ちゃん", "先生")
    private const val MAX_NAME = 20
    private const val MAX_LABEL = 16
}
