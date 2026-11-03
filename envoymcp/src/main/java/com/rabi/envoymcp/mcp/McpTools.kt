package com.rabi.envoymcp.mcp

import com.rabi.launcher.conversation.Expression
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import com.rabi.envoymcp.BuildConfig
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.arm.ArmController
import com.rabi.envoymcp.arm.ArmResult
import com.rabi.launcher.core.RabiLog
import com.rabi.launcher.core.Text
import com.rabi.launcher.device.ActionResult
import com.rabi.launcher.observe.Bounds
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode
import com.rabi.launcher.parts.ApprovalLevel
import com.rabi.launcher.parts.ArgSpec
import com.rabi.launcher.parts.ArgType
import com.rabi.launcher.parts.ArgValue
import com.rabi.launcher.parts.Matcher
import com.rabi.launcher.parts.Part
import com.rabi.launcher.parts.PartMeta
import com.rabi.launcher.parts.RunListener
import com.rabi.launcher.parts.RunOutcome
import com.rabi.launcher.parts.Selector
import com.rabi.launcher.parts.Step
import com.rabi.launcher.parts.TraceCompiler
import com.rabi.launcher.parts.TraceStep
import com.rabi.envoymcp.PhoneContacts
import com.rabi.launcher.parts.CommitKind
import com.rabi.launcher.safety.ApprovalDecision
import com.rabi.launcher.safety.ApprovalRequest
import com.rabi.launcher.safety.CommitDetector
import com.rabi.launcher.safety.ConsumeResult
import com.rabi.launcher.safety.CommitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.time.temporal.ChronoUnit

/**
 * ChatGPT に出す道具。端末の操作 (ADB・ユーザー補助)・部品・確定の承認を、MCP の道具として出す。考えるのは ChatGPT 側。
 * - 読む: スマホの状態・画面の部品・画面の画像 (設定で切れる)・アプリ・ショートカット・部品の一覧
 * - 操作する: アプリ・ショートカット・クイック設定/通知を開く、タップ、文字入力、スクロール、戻る、ホーム、待つ
 * - 部品: 動かす・直前の操作を部品として登録・覚えた部品を消す
 * 安全の決まり:
 * - 送信・投稿・購入・削除・発信らしいボタンや設定のスイッチは、タップでも部品でも、スマホの承認画面で本人が承認してから押す
 *   (CommitFlow。承認したあと画面が変わっていれば押さない。結果が分からなければ再送しない)
 * - タップは画面の部品の番号か文字で指定する (座標では押さない)。番号は直前の read_screen のもの。画面が変わっていれば押さない
 * - 操作する道具は、止められるリゲル (他のアプリの上に表示) を出せるときだけ動く。本人がリゲルをタップして止めた直後は受け付けない
 * - 結果は ok / failed / unknown を分けて返す。操作を送っただけで、アプリ側の処理が終わったとはみなさない
 * - どの道具も時間を区切る (MCP の呼び出しを開いたままにしない)
 */
class McpTools(private val g: McpGraph) : ToolHost {

    private class Tool(
        val name: String,
        val title: String,
        val description: String,
        /** スマホを操作するか (操作する道具は、縁を光らせ、止められるリゲルを出す) */
        val act: Boolean,
        /** 動いている間、リゲルの下に出す言葉 */
        val caption: String,
        val params: JSONObject = JSONObject(),
        val required: List<String> = emptyList(),
        /** 端末の操作 (ADB かユーザー補助) が要るか */
        val adb: Boolean = true,
        val destructive: Boolean = false,
        /** 読むだけか (スマホも、このアプリの中身も変えない) */
        val readOnly: Boolean = !act,
        val timeoutMs: Long = TOOL_TIMEOUT_MS,
        /** リゲルを操作中の顔にしない (表情の道具: 表情そのものを見せるため) */
        val quiet: Boolean = false,
        val run: suspend (JSONObject) -> ToolResult,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name).put("title", title).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)).put("additionalProperties", false))
            .put("annotations", JSONObject().put("title", title).put("readOnlyHint", readOnly).put("destructiveHint", destructive)
                .put("idempotentHint", readOnly).put("openWorldHint", act))
    }

    private val tools = listOf(
        // ---------------------------------------------------------------- 読む
        Tool("get_state", "スマホの状態",
            "利用者の Android スマホの今の状態を返します: 時刻、電池、画面の点灯とロック、前面のアプリ、ENVOY MCP 自身の状態。読むだけです。",
            act = false, caption = "スマホの状態を確かめています", adb = false) { getState() },
        Tool("read_screen", "画面の部品を読む",
            "今の画面に出ている部品 (文字・ボタン・入力欄) を番号つきで返します。tap と type_text の element_id には、ここで返った番号を使います。" +
                "操作の道具の結果にも同じ形の一覧 (操作のあとの画面) が付くので、続けて操作するときは読み直さなくて構いません。読むだけです。",
            act = false, caption = "画面を読んでいます", params = props("max_items" to int("返す部品の数の上限 (10〜150、既定 80)"))) { readScreen(it) },
        Tool("get_screenshot", "画面の画像",
            "今の画面の画像 (縮小した JPEG) を返します。部品の文字だけでは分からないとき (写真・地図・図) に使います。利用者が設定で切っていると使えません。読むだけです。",
            act = false, caption = "画面を見ています") { screenshot() },
        Tool("look_at_screen", "画面を見て説明する",
            "画面の画像を ENVOY MCP の中で AI に読ませ、画面の説明と、押せそうなもの・入力欄 (部品の一覧に無いものも含む) を、名前・種類・位置つきで返します。" +
                "部品の一覧で見つからないとき・名前の無いアイコンを押したいとき・Web やゲームの画面のときに使います。返った visual_id は tap に使えます。" +
                "画像は ChatGPT には届かないので、画面の中身を知りたいときは get_screenshot ではなくこれを使ってください。数秒かかります。読むだけです。",
            act = false, caption = "画面を見ています", timeoutMs = VISION_TIMEOUT_MS) { lookAtScreen() },
        Tool("list_apps", "アプリの一覧",
            "このスマホに入っている、開けるアプリの名前とパッケージ名を返します。読むだけです。",
            act = false, caption = "アプリを調べています", adb = false) { listApps() },
        Tool("list_shortcuts", "アプリのショートカットの一覧",
            "アプリのショートカット (アイコンの長押しで出る項目。X の「ポスト」など) を返します。open_shortcut で開けます。読むだけです。" +
                "ENVOY MCP が既定のホームアプリでないと空になります。",
            act = false, caption = "ショートカットを調べています", adb = false,
            params = props("package" to str("このアプリの分だけ返す (省略可)"))) { listShortcuts(it) },
        Tool("list_parts", "部品の一覧",
            "登録済みの部品 (手順をまとめたもの) の ID・名前・説明・引数を返します。run_part で動かせます。読むだけです。",
            act = false, caption = "部品を調べています", adb = false) { listParts() },

        // ---------------------------------------------------------------- 操作する
        Tool("open_app", "アプリを開く",
            "アプリを開きます。app にはアプリの名前かパッケージ名 (list_apps のもの) を入れます。",
            act = true, caption = "アプリを開いています", params = props("app" to str("アプリの名前かパッケージ名")), required = listOf("app")) { openApp(it) },
        Tool("open_shortcut", "ショートカットを開く",
            "アプリのショートカット (list_shortcuts のもの) を開きます。投稿の画面などをすぐ開けます。開くだけで、送信・投稿はしません。",
            act = true, caption = "ショートカットを開いています",
            params = props("package" to str("アプリのパッケージ名"), "shortcut_id" to str("ショートカットの ID")), required = listOf("package", "shortcut_id")) { openShortcut(it) },
        Tool("call_phone", "電話をかける",
            "電話をかけます (「〇〇さんに電話して」「090... に電話」)。電話のアプリは操作せず、連絡先から番号を探し、スマホで利用者が承認したら (設定しだいで指紋など) すぐ発信します。" +
                "電話をかけるときは、open_app で電話のアプリを開いて押すのではなく、これを使います。" +
                "contact に連絡先の名前 (さん・様は付けなくてよい。ニックネーム・読みでもよい)、または number に番号を入れます。" +
                "同じ名前の人が何人かいれば、候補の名前を返すので、利用者にどなたか聞いて、名前を詳しくして頼み直してください。" +
                "番号の種類は phone_type (mobile・home・work。省略すると、よく使う番号 → 携帯)。緊急の番号 (110・119 など) にはかけません。連絡先の一覧や番号は返しません (番号は下 4 桁だけ)。",
            act = true, caption = "電話をかけています", adb = false, readOnly = false, timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("contact" to str("連絡先の名前 (例: 田中太郎・お母さん)"), "number" to str("電話番号 (連絡先にない番号のとき)"),
                "phone_type" to enumOf("番号の種類 (省略すると、よく使う番号 → 携帯)", "mobile", "home", "work"))) { callPhone(it) },
        Tool("rigel_call", "リゲルが代わりに電話して用件を伝える",
            "リゲル (AI アシスタント) が利用者の代わりに電話をかけ、用件を伝えて、相手の返事を聞いてきます (「〇〇さんに電話して XX と伝えて」)。" +
                "必ず先に、相手と伝える用件を利用者にはっきり確かめてから呼んでください。スマホで利用者の承認 (相手・用件) が要ります。" +
                "リゲルは電話の最初に、AI であることと、利用者の代わりにかけたことを名乗ります。約束・お金・個人情報のやり取りはしません。" +
                "contact に連絡先の名前、message に伝える用件 (相手に伝わる言い方で、具体的に)、caller に利用者の呼び名 (省略すると設定の名前) を入れます。" +
                "かけ始めたらすぐ戻ります。1〜3 分ほどしたら rigel_call_status で結果 (伝わったか・相手の返事の要点・やり取り) を確かめて、利用者に伝えてください。" +
                "app=line なら LINE の音声通話でかけます (contact に LINE の友だちの表示名。スマホのロックが解除されている必要があります)。" +
                "UNO Q (腕) につながっていないと使えません。緊急の番号にはかけません。",
            act = false, caption = "リゲルが電話をかけています", adb = false, readOnly = false, timeoutMs = APPROVAL_TIMEOUT_MS + 60_000L,
            params = props("contact" to str("連絡先の名前 (例: 田中太郎・お母さん)。app=line なら LINE の友だちの表示名"), "number" to str("電話番号 (連絡先にない番号のとき)"),
                "message" to str("相手に伝える用件 (例: 明日の会議は 10 時からに変わりました)"),
                "caller" to str("利用者の呼び名 (リゲルが「〇〇さんの代わりに」と名乗る。省略すると設定の名前)"),
                "app" to enumOf("かけ方 (省略すると電話。line なら LINE の音声通話)", "phone", "line"),
                "phone_type" to enumOf("番号の種類 (省略すると、よく使う番号 → 携帯)", "mobile", "home", "work")),
            required = listOf("message")) { rigelCall(it) },
        Tool("rigel_call_status", "リゲルの代わりの電話の結果",
            "rigel_call でリゲルがかけた電話の様子と結果を返します: state (preparing・ready・dialing・ringing・talking・done・failed)、" +
                "終わっていれば outcome (conveyed 伝わった・voicemail 留守番電話に残した・refused 断られた・no_answer 出なかった・hung_up 相手が切った・failed など)、" +
                "summary (相手の返事の要点)、transcript (やり取り)。まだ話していれば、少し待ってからもう一度確かめてください。読むだけです。",
            act = false, caption = "", adb = false) { ok("リゲルの電話の様子です。", g.rigelCaller.statusJson()) },
        Tool("rigel_call_hangup", "リゲルの代わりの電話をやめる",
            "rigel_call でリゲルがかけている電話を、途中でやめて切ります (利用者に「やめて」と言われたとき)。",
            act = false, caption = "リゲルの電話を切っています", adb = false, readOnly = false) {
            if (!g.rigelCaller.busy) ok("リゲルは電話をしていません。", g.rigelCaller.statusJson())
            else { g.rigelCaller.hangup(); ok("リゲルの電話を切るよう頼みました。", JSONObject().put("hangup", true)) }
        },
        Tool("rigel_incoming", "かかってきた電話にリゲルが代わりに出るかを知らせる",
            "電話がかかってきて、ENVOY MCP から「代わりに出るか、自分で出るか、尋ねて」と頼まれたときに使います。利用者の答えを choice で知らせてください " +
                "(rigel: リゲル (AI) が代わりに出て用件を聞く、self: 利用者が自分で出る)。rigel なら、この音声の会話はすぐ終わり、電話のあとに開き直して結果を伝えます。" +
                "利用者に尋ねていないときは使わないでください。",
            act = false, caption = "", adb = false, readOnly = false,
            params = props("choice" to enumOf("利用者の答え", "rigel", "self")), required = listOf("choice")) { a ->
            val rigel = a.optString("choice") == "rigel"
            val msg = g.incomingCall.choose(rigel)
            if (!g.incomingCall.asking && msg.startsWith("今は")) failed(msg)
            else ok(msg + if (rigel) "今は返事をせず黙っていてください (この音声の会話はすぐ終わります。電話のあとに開き直して、結果を伝えてもらいます)。" else "",
                JSONObject().put("choice", if (rigel) "rigel" else "self"))
        },
        Tool("share_screenshot", "画面の画像を共有する",
            "今の画面の画像を撮って、この端末の写真 (Pictures/ENVOY MCP) に保存し、指定したアプリの共有に画像を添えて開きます。" +
                "例: 画面のスクリーンショットを X に投稿したいとき (X の投稿の画面が、画像が付いた状態で開きます)。text を入れると、一緒に入れます。" +
                "開くだけで、投稿・送信はしません (投稿ボタンを押すときは、スマホでの承認が要ります)。",
            act = true, caption = "画面の画像を共有しています",
            params = props("app" to str("共有先のアプリの名前かパッケージ名 (X など)"), "text" to str("一緒に入れる文 (省略可)"),
                "target" to enumOf("アプリの中の共有先 (省略すると post: 投稿。message: メッセージ・チャット)", "post", "message")),
            required = listOf("app")) { shareScreenshot(it) },
        Tool("end_voice_chat", "音声の会話を終える",
            "利用者が会話そのものを終えたいと言ったとき (「ばいばい」「またね」「おやすみ」「もういいよ」「終わり」など) に呼びます。" +
                "呼んだら、ひとこと短く別れの挨拶をしてください。挨拶を言い終えたら、スマホが ChatGPT の音声モードを終えて閉じ、リゲルの画面に戻します。" +
                "頼みごとの途中の言葉 (「じゃあね、田中さんに伝えておいて」など) や、ほかの人への挨拶では呼ばないでください。",
            act = false, caption = "会話を終えます", adb = false, readOnly = false) { endVoiceChat() },
        Tool("search_photos", "写真を言葉で探す",
            "このスマホの写真を、言葉で探します (「猫の写真」「夕焼けの海」「ホワイトボードを撮ったもの」。日本語でよい)。" +
                "写真を探すときは、フォト・ギャラリー・Files などのアプリを開いて探すのではなく、これを使います。" +
                "スマホの中の AI (EmbeddingGemma 2。Pixel の TPU で動く) が写真と言葉の近さで探すので、写真はスマホの外に出ません。" +
                "返すのは、近い順の写真の photo_id・撮った日時・フォルダ・近さ (0〜1。大きいほど近い。いちばん近いものより 0.2 以上低いもの・0.4 未満のものは違う写真かもしれません) だけで、写真そのものは返しません。" +
                "見つけた写真 (最初の 5 枚) は、スマホの画面の下に、返した一覧と同じ番号 (1〜5) を付けて並べます。利用者は画面を見て「2 番を送って」のように番号で選べるので、番号と photo_id を取り違えないでください。" +
                "見つけた写真を X に投稿する・LINE で送るなどは、share_photo に photo_id を渡して、そのアプリの共有の画面を写真付きで開いてから続けます。" +
                "「昨日の」「先週の」など日付の手がかりがあれば、taken_after・taken_before (YYYY-MM-DD) で絞れます。" +
                "初めてのときや写真が増えたときは、先に写真の索引を作るので、少し (最長 20 秒ほど) かかります。",
            act = false, caption = "写真を探しています", adb = false, readOnly = true, timeoutMs = PHOTO_TIMEOUT_MS,
            params = props("query" to str("探す写真の説明 (例: 猫・夕焼けの海・ホワイトボードの写真)"),
                "limit" to int("返す枚数 (1〜10。省略すると 5)"),
                "taken_after" to str("この日以降に撮ったもの (YYYY-MM-DD。省略可)"),
                "taken_before" to str("この日より前に撮ったもの (YYYY-MM-DD。省略可)")),
            required = listOf("query")) { searchPhotos(it) },
        Tool("show_photo", "写真を大きく見せる",
            "search_photos で見つけた写真 (photo_id) を、スマホの画面いっぱいに大きく出します (「2 番を大きく見せて」)。" +
                "画面に出すだけで、写真は ChatGPT には渡しません。利用者が画面を押すと、並べた写真の帯に戻ります。",
            act = false, caption = "写真を見せています", adb = false, readOnly = true,
            params = props("photo_id" to int("search_photos が返した写真の番号 (photo_id。画面の 1〜5 の番号ではない)")),
            required = listOf("photo_id")) { showPhoto(it) },
        Tool("share_photo", "写真を共有する",
            "search_photos で見つけた写真 (photo_id) を、指定したアプリの共有に添えて開きます。開くだけで、投稿・送信はしません。" +
                "例: X に投稿 → app=X (target=post)。text を入れると本文に入ります。開いたら、利用者に確かめてから投稿ボタンを tap します (スマホでの承認が要ります)。" +
                "LINE で誰かに送る → app=LINE、target=message。送り先を選ぶ画面が開くので、read_screen で見て、検索の欄に相手の名前を type_text し、相手を tap して選び、" +
                "送信 (転送) を tap します (スマホでの承認が要ります)。写真が違うと言われたら、search_photos の次の候補で開き直してください。",
            act = true, caption = "写真を共有しています",
            params = props("photo_id" to int("search_photos が返した写真の番号"), "app" to str("共有先のアプリの名前かパッケージ名 (X・LINE など)"),
                "text" to str("一緒に入れる文 (省略可)"),
                "target" to enumOf("アプリの中の共有先 (省略すると post: 投稿。message: メッセージ・チャット・LINE の送り先の選択)", "post", "message")),
            required = listOf("photo_id", "app")) { sharePhoto(it) },
        Tool("open_panel", "クイック設定・通知を開く",
            "画面の上から引き出す画面を開きます (quick_settings: クイック設定、notifications: 通知、close: 閉じる)。",
            act = true, caption = "画面の上を開いています",
            params = props("which" to enumOf("開く画面", "quick_settings", "notifications", "close")), required = listOf("which")) { openPanel(it) },
        Tool("tap", "タップする",
            "画面の部品を 1 回タップします。element_id (部品の一覧の番号)・text (部品の文字)・visual_id (look_at_screen の番号) のどれかで指定します。座標は直接指定できません。" +
                "visual_id で部品の一覧に無いものを押すとき、確定・設定の切り替えらしいものは押しません (利用者に押してもらってください)。" +
                "送信・投稿・購入・削除・発信らしいボタンや設定のスイッチは、スマホの承認画面や指紋などで利用者が承認してから押します (承認を待つので時間がかかります。利用者が設定で承認をオフにしていれば、承認なしで押します。そのときは押す前に利用者に声で確かめてください)。" +
                "結果が unknown のときは、二重にならないよう、同じ確定を繰り返さないでください。",
            act = true, caption = "画面を操作しています", timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("element_id" to int("部品の一覧の番号"), "text" to str("押す部品の文字か説明 (element_id が無いとき)"),
                "visual_id" to str("look_at_screen が返した番号 (v1 など)"))) { tap(it) },
        Tool("type_text", "文字を入力する",
            "入力欄に文字を入れます。element_id (read_screen の番号) を指定すると、その欄を押してから入れます (中身があれば消してから)。" +
                "改行は入れません (送信になることがあるため)。パスワード欄には入れません。入れたあと、画面で確かめた結果を返します。" +
                "submit を true にすると、検索欄 (YouTube・Chrome などの検索・アドレス欄) なら入れたあと検索を実行します。メッセージ・コメントなどの欄では実行しません (送信のボタンは tap で押す)。",
            act = true, caption = "文字を入力しています",
            params = props("text" to str("入れる文字"), "element_id" to int("入力欄の番号 (省略すると、今選ばれている欄)"),
                "submit" to bool("検索欄なら、入れたあと検索を実行するか (省略すると false)")), required = listOf("text")) { typeText(it) },
        Tool("scroll", "スクロールする",
            "画面をスクロールします。down: 下の内容を見る、up: 上の内容を見る、left / right: 横。",
            act = true, caption = "画面を動かしています",
            params = props("direction" to enumOf("見たい方向", "down", "up", "left", "right")), required = listOf("direction")) { scroll(it) },
        Tool("back", "戻る", "戻るボタンを押します。", act = true, caption = "戻っています") { simple("back") { g.device.back() } },
        Tool("home", "ホームに戻る", "ホームボタンを押して、ホーム画面に戻ります。", act = true, caption = "ホームに戻っています") { simple("home") { g.device.home() } },
        Tool("press_key", "キーを押す",
            "キーボードのキーを 1 つ押します (今選ばれている窓に届く。モニターにつながっていればモニター)。" +
                "YouTube なら f で全画面の切り替え、k で再生・一時停止、left / right で巻き戻し・早送り、m で消音。" +
                "key は英字 1 文字・数字 1 文字か、space, escape, tab, backspace, delete, up, down, left, right, page_up, page_down, move_home, move_end, " +
                "f1〜f12, play_pause, next, previous, volume_up, volume_down, mute のどれか。times で続けて押す回数 (1〜10)。" +
                "Enter は送信になることがあるので押しません (確定のボタンは tap で押してください)。",
            act = true, caption = "キーを押しています",
            params = props("key" to str("押すキー (例: f, k, space, escape, right)"), "times" to int("続けて押す回数 (省略すると 1)")), required = listOf("key")) { pressKey(it) },
        Tool("wait", "待つ", "アプリの読み込みなどを待ちます (最大 10 秒)。待ったあとの画面の様子を返します。",
            act = false, caption = "待っています", params = props("seconds" to JSONObject().put("type", "number").put("description", "待つ秒数 (0.5〜10)"))) { waitFor(it) },

        // ---------------------------------------------------------------- AgentARM (スマホを載せる腕)
        // 画面は操作しないので、端末の操作の接続も、止められるリゲルも要らない。腕が動き終わるまで待って結果を返す
        Tool("arm_portrait", "スマホを縦にする",
            "AgentARM (利用者のスマホを載せている腕) で、スマホを縦向きにします。腕が回し終わるまで待ちます (十数秒まで)。" +
                "AgentARM がつながっていなければ failed を返します。",
            act = false, caption = "スマホを縦にしています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS) { arm(g.arm.orient(ArmController.PORTRAIT)) },
        Tool("arm_landscape", "スマホを横にする",
            "AgentARM で、スマホを横向きにします (横長の動画・写真を見るときなど)。腕が回し終わるまで待ちます (十数秒まで)。" +
                "顔を追っている途中なら、追うのは続けます。",
            act = false, caption = "スマホを横にしています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS) { arm(g.arm.orient(ArmController.LANDSCAPE)) },
        Tool("arm_look_at_me", "こっちを向く",
            "スマホの前面カメラで利用者の顔を探し、AgentARM で画面を利用者の顔の方へ 1 回向けます (向け終わったら止まります)。" +
                "orientation を付けると、向けながら縦・横にも合わせます。顔を追い続けるときは arm_follow_me を使います。" +
                "カメラの映像はスマホの中だけで使い、外には出しません。",
            act = false, caption = "利用者の方に向けています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("orientation" to enumOf("向けながら合わせる向き (省略すると今のまま)", ArmController.PORTRAIT, ArmController.LANDSCAPE))) {
            arm(g.arm.lookAtMe(orientation(it)))
        },
        Tool("arm_follow_me", "顔を追い続ける",
            "利用者の顔を追い続けます: まず画面を顔に向け、そのあとも顔の位置がずれたら向け直します (動画を見ている間など)。" +
                "横長の動画を見るときは orientation に landscape を付けます (YouTube の横長の動画など)。" +
                "最初に向け終わったら返り、そのあとはスマホの中で追い続けます。arm_stop_following を呼ぶか、スマホを腕から外すまで続けます " +
                "(顔が 10 分見えないとき・スマホが熱いときもやめます)。追っている間は前面カメラを使います (映像は外に出しません)。",
            act = false, caption = "利用者の顔を追っています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("orientation" to enumOf("合わせる向き。横長の動画は landscape (省略すると今のまま)", ArmController.PORTRAIT, ArmController.LANDSCAPE))) {
            arm(g.arm.follow(orientation(it)))
        },
        Tool("arm_stop_following", "顔を追うのをやめる",
            "arm_follow_me で始めた顔の追跡をやめ、前面カメラを止めます。腕はその場で止まります (向きは戻しません)。",
            act = false, caption = "顔を追うのをやめています", adb = false, readOnly = false) { arm(g.arm.stopFollowingByTool()) },
        Tool("arm_record_video", "撮りながら顔を追う",
            "前面カメラで動画を撮り始め、同時に利用者の顔を追い続けます (「撮りながらこっちを見て」など)。" +
                "カメラのアプリで撮っている間は前面カメラを ENVOY MCP が使えず顔を追えないので、撮りながら追うときはカメラのアプリではなくこれを使います。" +
                "動画はスマホの「フォト」の Movies/ENVOY に保存します (外には送りません)。マイクの許可があれば音も録ります。" +
                "follow を false にすると、顔は追わずに撮るだけです。max_minutes 分 (既定 ${ArmController.RECORD_DEFAULT_MIN}、${ArmController.RECORD_MAX_MIN} まで) で自動で止まります。" +
                "camera を back にすると背面カメラで撮り、腕が背面カメラを利用者に向けて追います (カメラマン役。「背面で撮って」「カメラマンになって」など)。" +
                "そのとき画面は利用者と反対を向き、背面カメラは水平より約 9° 上までしか向けられないので、利用者がスマホと同じ高さか低い所にいるときに合います。" +
                "止めるときは arm_stop_recording。録画中の arm_look_at_me も使えます。",
            act = false, caption = "撮りながら顔を追っています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("follow" to bool("顔を追い続けるか (省略すると true)"),
                "camera" to enumOf("撮るカメラ (省略すると front)", "front", "back"),
                "max_minutes" to int("自動で止めるまでの分 (省略すると ${ArmController.RECORD_DEFAULT_MIN})"))) { a ->
            arm(g.arm.recordStart(a.optBoolean("follow", true), a.optNum("max_minutes")?.toInt() ?: ArmController.RECORD_DEFAULT_MIN, a.optString("camera") == "back"))
        },
        Tool("arm_switch_camera", "撮りながらカメラを切り替える",
            "arm_record_video で撮っている途中に、録画を止めずに前面・背面のカメラを切り替えます (1 本の動画のまま。切り替える瞬間は映像が少し止まります)。" +
                "「背面にして」「カメラマンになって」は back、「前のカメラに戻して」は front、省略すると反対のカメラにします。" +
                "すぐ返り、顔を追っているときは、そのあと裏で腕を 180° 回して切り替えたカメラを利用者に向け、顔に向け直します (10 秒ほど)。撮っていないときは何もしません。",
            act = false, caption = "カメラを切り替えています", adb = false, readOnly = false, timeoutMs = ARM_MOVE_TIMEOUT_MS,
            params = props("camera" to enumOf("切り替える先 (省略すると反対のカメラ)", "front", "back"))) { a ->
            arm(g.arm.recordSwitch(when (a.optString("camera")) { "back" -> true; "front" -> false; else -> null }))
        },
        Tool("arm_stop_recording", "録画を止める",
            "arm_record_video で始めた録画を止めて保存します (録画のために顔を追い始めていたら、追うのもやめます)。保存したファイル名と長さを返します。",
            act = false, caption = "録画を止めています", adb = false, readOnly = false) { arm(g.arm.recordStop()) },
        Tool("arm_move", "腕を動かす",
            "AgentARM の 3 つの関節で、スマホの向きを角度で変えます (「左に 30° 向いて」「少し上を向いて」など)。" +
                "turn: 左右 (度。+ は画面の前にいる利用者から見て右へ向く)、tilt: 上下 (度。+ は上を向く)、roll: 画面を回す (度。回る向きは取り付けで変わる)。" +
                "relative が true (省略時) なら今の向きからの差、false なら腕の原点 (0, 0, 0) からの角度です。省いた関節は動かしません。" +
                "関節の範囲の外は端まで、速さは 1 秒に約 28° までです。動き終わるまで待ち、今の角度を返します。",
            act = false, caption = "腕を動かしています", adb = false, readOnly = false, timeoutMs = ARM_MOVE_TIMEOUT_MS,
            params = props("turn" to num("左右の角度 (度。+ は利用者から見て右)"), "tilt" to num("上下の角度 (度。+ は上)"),
                "roll" to num("画面を回す角度 (度)"), "relative" to bool("今の向きからの差か (省略すると true)"),
                "speed" to enumOf("速さ (省略すると normal)", "slow", "normal"))) { a ->
            arm(g.arm.move(a.optNum("turn"), a.optNum("tilt"), a.optNum("roll"), a.optBoolean("relative", true), a.optString("speed") == "slow"))
        },
        Tool("arm_motion", "腕で動きをする",
            "AgentARM で、決まった動き (うなずく・首を振る・首をかしげる・小さな振り付けなど) をします。" +
                "keyframes は、始めた向きからの差 (turn・tilt・roll。arm_move と同じ向き) と、そこへ動く長さ ms の並び (${ArmController.MOTION_KEYS} 個まで)。" +
                "最後の姿勢を 0 にすると、始めた向きに戻ります。repeat 回くり返します (${ArmController.MOTION_REPEAT} 回まで)。全体で 25 秒までです。" +
                "速さは 1 秒に約 28° までで、それより速い動きは自動で遅くなります (例: うなずき [{tilt:-5,ms:400},{tilt:0,ms:400}] を repeat 2)。",
            act = false, caption = "腕で動きをしています", adb = false, readOnly = false, timeoutMs = ARM_MOVE_TIMEOUT_MS,
            params = props("keyframes" to JSONObject().put("type", "array").put("maxItems", ArmController.MOTION_KEYS).put("minItems", 1)
                .put("description", "姿勢の並び (始めた向きからの差)")
                .put("items", JSONObject().put("type", "object").put("properties", props(
                    "turn" to num("左右 (度)"), "tilt" to num("上下 (度)"), "roll" to num("回す (度)"), "ms" to int("その姿勢へ動く長さ (ms。100〜10000)")))
                    .put("required", JSONArray(listOf("ms")))),
                "repeat" to int("くり返す回数 (1〜${ArmController.MOTION_REPEAT}。省略すると 1)")),
            required = listOf("keyframes")) { a -> armMotion(a) },
        Tool("arm_dance", "音楽に合わせて踊る",
            "スマホで流れている音楽の拍に合わせて、AgentARM が踊ります (音楽はスマホで流しておきます。YouTube Music・Spotify など)。" +
                "拍が取れたら返り、そのあとはスマホの中で踊り続けます。音楽が止まると、踊り始めた向きに戻って終わります。やめるときは arm_stop。" +
                "style: chill (ゆったり揺れる) / groove (ふつう) / party (振りを細かく替える)。size: 振りの大きさ。" +
                "曲名が分かるときは、曲ごとの専用の振り付けで踊ります (曲が替わると振り付けも替わります)。style を指定すると、その曲の踊り方として覚え、" +
                "次からは指定しなくてもその踊り方になります (省略すると、覚えた踊り方か groove)。音が強いところ (サビなど) では大きく踊ります。" +
                "スマホにリゲルの音楽の画面 (まわりにカラフルなスペクトラム) を出します。" +
                "会話中 (この音声の会話など) や音楽が止まっているときは、音楽が流れたら踊る予約にします (3 分まで。会話中は音楽が止まることが多いので、" +
                "利用者に、会話を終えるか音楽を流すよう伝えてください)。再生中の曲は get_state の now_playing で分かります。" +
                "踊るときの速さは 1 秒に約 46° までなので、速い曲ほど小さく揺れます。顔を追っている途中なら、追うのはやめます。",
            act = false, caption = "音楽に合わせて踊っています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("style" to enumOf("踊り方 (省略すると、その曲に覚えた踊り方か groove。指定するとその曲の踊り方として覚える)", ArmController.STYLE_CHILL, ArmController.STYLE_GROOVE, ArmController.STYLE_PARTY),
                "size" to enumOf("振りの大きさ (省略すると、覚えた大きさか medium)", "small", "medium", "large"))) { a ->
            val style = a.optString("style").takeIf { it in listOf(ArmController.STYLE_CHILL, ArmController.STYLE_GROOVE, ArmController.STYLE_PARTY) }
            val size = when (a.optString("size")) { "small" -> 0.35; "medium" -> 0.65; "large" -> 1.0; else -> null }
            arm(g.arm.dance(style, size))
        },
        // ---------------------------------------------------------------- リゲル
        // 表情を見せるだけ (スマホも腕も動かさない)。操作中の顔にすると表情が見えないので、quiet にする
        Tool("rigel_expression", "リゲルの表情",
            "スマホのリゲル (音声モードの全画面・ホーム・モニター) に、感情の表情を一度だけ見せます (2 秒ほどで元に戻ります)。" +
                "happy うれしい・laugh 笑う・surprised 驚き・sad 悲しい・shy 照れ・wink ウインク。" +
                "会話で気持ちが動くところ (よい知らせ・お礼・ほめられた・冗談・驚く話・残念な話) で、返事を話し始める前に 1 回だけ呼びます。" +
                "呼んだことは口に出しません。1 回の返事で 1 回まで。操作の途中では呼びません。",
            act = false, caption = "", adb = false, readOnly = false, quiet = true,
            params = props("expression" to enumOf("見せる表情", *Expression.entries.map { it.id }.toTypedArray())),
            required = listOf("expression")) { a -> express(a) },

        Tool("arm_stop", "腕を止める",
            "AgentARM の今の動きを止めます: 踊っていれば踊り始めた向きに戻して終え、顔を追っていればやめ、動いている途中ならその場で止めます。" +
                "「止まって」「やめて」と言われたときに使います。",
            act = false, caption = "腕を止めています", adb = false, readOnly = false) { arm(g.arm.stopByTool()) },

        // ---------------------------------------------------------------- 部品
        Tool("run_part", "部品を動かす",
            "登録済みの部品 (list_parts のもの) を動かします。args には部品の引数を {名前: 値} で入れます (アプリはアプリの名前かパッケージ名)。" +
                "途中で画面が想定と違えば止めて、そのときの画面を返します (そこからは個別の道具で続けられます)。確定のある部品は、スマホでの承認を待ちます。",
            act = true, caption = "部品を動かしています", timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("part_id" to str("部品の ID"), "args" to JSONObject().put("type", "object").put("description", "引数 {名前: 値}")),
            required = listOf("part_id")) { runPart(it) },
        Tool("save_part", "直前の操作を部品として登録",
            "この会話で行った操作 (open_app・tap・type_text・scroll など、前回の登録より後のもの) を、次から run_part で使える部品として登録します。" +
                "うまくいった操作のあとにだけ使ってください。入れた文字を毎回変えたいときは args に {入れた文字: 引数の名前} を入れます。" +
                "確定らしい手は、動かすたびに承認が要る形で登録されます。",
            act = false, caption = "部品を登録しています", adb = false, readOnly = false,
            params = props("name" to str("部品の名前 (短く)"), "description" to str("何をする部品か"),
                "examples" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "頼み方の例"),
                "args" to JSONObject().put("type", "object").put("description", "{記録の中の具体的な文字: 引数の名前}")),
            required = listOf("name", "description")) { savePart(it) },
        Tool("delete_part", "覚えた部品を消す",
            "save_part で登録した部品を消します (最初から入っている部品は消せません)。",
            act = false, caption = "部品を消しています", adb = false, destructive = true, readOnly = false,
            params = props("part_id" to str("部品の ID")), required = listOf("part_id")) { deletePart(it) },
    )
    private val byName = tools.associateBy { it.name }

    /** 直前の read_screen の画面 (tap・type_text の番号はこの画面のもの) */
    @Volatile private var lastScreen: Observation? = null
    /** 直前に look_at_screen で見た画面と、押せそうなもの (visual_id で押すため) */
    private class VisionMemo(val obs: Observation, val targets: Map<String, ScreenVision.Target>)
    @Volatile private var lastVision: VisionMemo? = null

    /** 前回の部品の登録より後の操作の記録 (save_part で部品にする) */
    private val trace = ArrayList<TraceStep>()

    override fun list(): JSONArray = JSONArray().apply { tools.forEach { put(it.toJson()) } }

    override suspend fun call(name: String, args: JSONObject): ToolResult? {
        val tool = byName[name] ?: return null
        if (g.stoppedByUser()) {
            g.log.add(name, false, "本人が止めた直後なので受け付けませんでした")
            return ToolResult("利用者がスマホの画面でリゲルをタップして止めました。しばらく道具を受け付けません。利用者に確かめてから頼み直してください。", isError = true)
        }
        // 承認を待っている間は、ほかの操作をしない (承認画面を押されたり、画面を変えられたりしないように)
        if (tool.act && g.approvals.current.value != null) {
            g.log.add(name, false, "承認を待っている")
            return failed("スマホで承認を待っています。承認が終わるまで、ほかの操作はしません。")
        }
        if (tool.act && !g.overlay.canShow()) {
            g.log.add(name, false, "他のアプリの上に表示の許可がない")
            return failed("「他のアプリの上に表示」の許可が無いため、操作中に止められるリゲルを出せません。操作はしません (スマホの ENVOY MCP の設定で許可できます)。")
        }
        if (tool.adb) needDevice()?.let { g.log.add(name, false, it); return failed(it) }
        // ADB で操作するときは、しばらく使っていなかった接続を、画面を変える前に確かめ直す (切れていればつなぎ直す)
        if (tool.act && g.usingAdb() && !g.adb.probeIfIdle()) { g.log.add(name, false, "接続を確かめられない"); return failed("端末の操作の接続を確かめられませんでした。少し待ってから頼み直してください。") }
        RabiLog.i("mcp", "tool $name")
        if (!tool.quiet) g.beginWork(tool.caption, tool.act)
        // 道具は別の仕事として動かす (リゲルのタップで止めると、この仕事だけが止まり、ChatGPT には「止めた」と返す)
        val job = g.scope.async(Dispatchers.IO) { withTimeout(tool.timeoutMs) { tool.run(args) } }
        g.currentJob = job
        return try {
            job.await().also {
                g.log.add(name, !it.isError, it.text.lineSequence().firstOrNull().orEmpty())
                RabiLog.i("mcp", "tool $name -> ${it.structured?.optString("result")?.takeIf { r -> r.isNotEmpty() } ?: if (it.isError) "failed" else "ok"}")
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()   // 接続ごと切れたときは、そのまま終える
            val why = if (e is TimeoutCancellationException) "時間内に終わりませんでした" else "利用者がリゲルをタップして止めました"
            g.log.add(name, false, why)
            unknown("$why。どこまで進んだかは確かめていません。read_screen で今の画面を確かめてください。")
        } catch (e: Exception) {
            g.log.add(name, false, e.javaClass.simpleName)
            failed("うまくいきませんでした (${e.javaClass.simpleName})。")
        } finally {
            if (g.currentJob === job) g.currentJob = null
            if (!tool.quiet) g.endWork()
        }
    }

    // ---------------------------------------------------------------- リゲル

    private fun express(a: JSONObject): ToolResult {
        val e = Expression.byId(a.optString("expression"))
            ?: return failed("expression は ${Expression.entries.joinToString("・") { it.id }} のどれかです。")
        g.express(e)
        return ok("リゲルが「${e.label}」の表情をしました (このことは利用者に言わなくて構いません)。", JSONObject().put("expression", e.id))
    }

    // ---------------------------------------------------------------- 読む

    private suspend fun getState(): ToolResult {
        val ctx = g.context
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val fg = foreground()
        val o = JSONObject()
            .put("time", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("battery", battery())
            .put("screen", JSONObject().put("on", pm.isInteractive).put("locked", km.isKeyguardLocked))
            .put("foreground_app", fg ?: JSONObject.NULL)
            .put("device_control", JSONObject().put("connected", g.device.isConnected).put("mode", g.settings.controlMode.id)
                .put("using", if (g.usingAdb()) "adb" else "accessibility").put("detail", g.deviceStatus()))
            .put("arm", g.arm.statusJson())
            .put("monitor", g.monitorStage.external()?.let { d ->
                JSONObject().put("connected", true).put("name", d.name).put("operating", g.monitorStage.target == d.displayId)
            } ?: JSONObject().put("connected", false))
            .put("now_playing", g.nowPlaying.track.value?.toJson()
                ?: if (g.nowPlaying.hasAccess()) JSONObject.NULL else "unknown (通知へのアクセスが未許可)")
            .put("envoy_mcp", JSONObject().put("version", BuildConfig.VERSION_NAME).put("home_visible", g.homeVisible)
                .put("can_operate", g.overlay.canShow() && g.device.isConnected).put("screenshots_allowed", g.settings.allowScreenshots)
                .put("tools", JSONArray(tools.map { it.name })))
        if (fg == null) o.put("note", "端末の操作 (ADB・ユーザー補助) が使えないので、前面のアプリは分かりません。")
        return ToolResult(o.toString(), structured = o)
    }

    /**
     * 電池 (Android が配る最新の電池の状態から)。BatteryManager.isCharging は、つないでいても false のことがある
     * (Pixel 10 で、充電中・電源ありなのに false になる)
     */
    private fun battery(): JSONObject {
        val b = g.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject()
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
        return JSONObject()
            .put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING)
            .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged ?: JSONObject.NULL)
    }

    /** 前面のアプリ (ユーザー補助か ADB で読む。使えなければ null。ADB の接続はここでは始めない: 待たせないため) */
    private suspend fun foreground(): JSONObject? {
        // モニターを操作している間は、モニターのいちばん上のアプリ (無ければデスクトップ = 全画面のリゲル)
        if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) {
            val pkg = g.monitorStage.topApp()
            return JSONObject().put("display", "monitor").put("package", pkg ?: JSONObject.NULL)
                .put("label", pkg?.let { g.appLabel(it) } ?: "デスクトップ (全画面のリゲル)")
        }
        val pkgAct = g.foregroundActivity() ?: return null
        val (pkg, act) = pkgAct
        return JSONObject().put("package", pkg).put("label", g.appLabel(pkg) ?: JSONObject.NULL).put("activity", act)
    }

    private suspend fun readScreen(a: JSONObject): ToolResult {
        val o = g.device.observe()
        if (!o.ok) return failed("画面を読み取れません (${o.error})")
        lastScreen = o
        val j = o.toModelJson(a.optInt("max_items", 80).coerceIn(10, 150))
        j.put("app_label", g.appLabel(o.pkg) ?: JSONObject.NULL)
        return ToolResult(j.toString(), structured = j)
    }

    private suspend fun screenshot(): ToolResult {
        if (!g.settings.allowScreenshots) return failed("利用者が設定で、画面の画像を渡さないようにしています。read_screen を使ってください。")
        val o = g.device.observe(screenshot = true)
        val img = o.screenshotPng ?: return failed("画面の画像を取れませんでした (${o.error ?: "不明"})")
        val s = summary(o)
        return ToolResult("画面の画像です。\n$s", s.put("result", "ok"), images = listOf(ToolResult.Image(Base64.encodeToString(img, Base64.NO_WRAP), o.screenshotMime)))
    }

    private suspend fun lookAtScreen(): ToolResult {
        if (!g.settings.allowScreenshots) return failed("利用者が設定で、画面の画像を使わないようにしています。read_screen を使ってください。")
        val st = g.auth.status.value
        if (!st.signedIn) return failed("スマホの ENVOY MCP で「ChatGPT でサインイン」が済んでいないので、画面を見られません。read_screen を使ってください。")
        val t0 = System.currentTimeMillis()
        val o = g.device.observe(screenshot = true)
        RabiLog.i("vision", "capture ${System.currentTimeMillis() - t0}ms")
        if (o.screenshotPng == null) return failed("画面の画像を取れませんでした (${o.error ?: "不明"})。read_screen を使ってください。")
        ownScreen(o)?.let { return it }
        val r = try { g.vision.look(o) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return failed("画面を見られませんでした (${e.message ?: e.javaClass.simpleName})。read_screen を使ってください。")
        }
        lastVision = VisionMemo(o, r.targets.associateBy { it.visualId })
        val targets = JSONArray(r.targets.map { t ->
            JSONObject().put("visual_id", t.visualId).put("label", t.label).put("kind", t.kind).put("element_id", t.elementId ?: JSONObject.NULL)
                .put("x", t.bounds.cx).put("y", t.bounds.cy).put("b", t.bounds.toString()).put("commit_like", t.commit)
        })
        val s = summary(o).put("description", r.summary).put("visual_targets", targets)
        return ok("画面を見ました: ${r.summary.take(80)}", s)
    }

    private fun listApps(): ToolResult {
        val apps = g.launcherApps().sortedBy { it.second }
        val arr = JSONArray(apps.map { (pkg, label) -> JSONObject().put("label", label).put("package", pkg) })
        val o = JSONObject().put("apps", arr)
        return ToolResult(o.toString(), o)
    }

    private fun listShortcuts(a: JSONObject): ToolResult {
        val pkg = a.optString("package").takeIf { it.isNotBlank() }
        val list = if (pkg != null) g.shortcuts.of(pkg) else g.shortcuts.all()
        val arr = JSONArray(list.map { JSONObject().put("package", it.pkg).put("app", g.appLabel(it.pkg) ?: it.pkg).put("shortcut_id", it.id).put("label", it.label) })
        val o = JSONObject().put("shortcuts", arr)
        if (list.isEmpty()) o.put("note", "ショートカットを読めませんでした (ENVOY MCP が既定のホームアプリのときだけ読めます)")
        return ToolResult(o.toString(), o)
    }

    private fun listParts(): ToolResult {
        val arr = JSONArray(g.parts.usable().map { p ->
            JSONObject().put("part_id", p.id).put("name", p.name).put("description", p.description)
                .put("args", JSONArray(p.args.map { JSONObject().put("name", it.name).put("type", it.type.name.lowercase()).put("required", it.required).put("description", it.description) }))
                .put("needs_approval", p.hasCommit).put("learned", p.source == "learned")
        })
        val o = JSONObject().put("parts", arr)
        return ToolResult(o.toString(), o)
    }

    // ---------------------------------------------------------------- 操作する

    private suspend fun openApp(a: JSONObject): ToolResult {
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val before = g.device.observe()
        val r = g.device.launchApp(pkg)
        if (!r.ok) return fromAction(r, "アプリを開けませんでした")
        val after = waitScreen(4_000) { it.pkg == pkg }
        record(TraceStep("launch", pkg = pkg, before = before, after = after, ok = after?.pkg == pkg))
        return if (after?.pkg == pkg) ok("${g.appLabel(pkg) ?: pkg} を開きました。", summary(after))
        else unknown("開く操作は送りましたが、${g.appLabel(pkg) ?: pkg} の画面になったか確かめられません。", after?.let { summary(it) } ?: JSONObject())
    }

    private suspend fun openShortcut(a: JSONObject): ToolResult {
        val pkg = a.optString("package"); val id = a.optString("shortcut_id")
        val before = g.device.observe()
        val r = g.device.openShortcut(pkg, id)
        if (!r.ok) return fromAction(r, "ショートカットを開けませんでした")
        val after = waitScreen(4_000) { it.pkg == pkg }
        record(TraceStep("shortcut", pkg = pkg, text = id, before = before, after = after, ok = after?.pkg == pkg))
        return if (after?.pkg == pkg) ok("ショートカットを開きました (まだ送信・投稿はしていません)。", summary(after))
        else unknown("開く操作は送りましたが、画面を確かめられません。", after?.let { summary(it) } ?: JSONObject())
    }

    /** 画面を撮って写真に保存し、アプリの共有 (ACTION_SEND) に添えて開く。投稿・送信はしない */
    private suspend fun shareScreenshot(a: JSONObject): ToolResult {
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val label = g.appLabel(pkg) ?: pkg
        val png = g.device.captureFullPng() ?: return failed("画面の画像を撮れませんでした。")
        val uri = withContext(Dispatchers.IO) { saveToPictures(png) } ?: return failed("写真に保存できませんでした。")
        return shareImage(uri, "image/png", pkg, a.optString("text").trim(), a.optString("target", "post"), "画面の画像を写真に保存し、", "画像は写真に保存してあります。")
    }

    /**
     * 画像を添えて、アプリの共有の画面を開く (share_screenshot・share_photo)。done は成功の言葉の頭、keep は失敗のときに添える言葉
     */
    private suspend fun shareImage(uri: Uri, mime: String, pkg: String, text: String, target: String, done: String, keep: String = ""): ToolResult {
        val label = g.appLabel(pkg) ?: pkg
        val i = Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
        // アプリの中に共有先が複数あると、Android がどれで開くかを尋ねる画面を出す (X は投稿とチャット)。
        // 投稿かメッセージかを決めて、その共有先を直接開く
        val targets = runCatching { g.context.packageManager.queryIntentActivities(i, 0).map { it.activityInfo } }.getOrDefault(emptyList())
        if (targets.isEmpty()) return failed("$label は画像の共有を受け付けません。$keep")
        val chosen = pickShareTarget(targets, target)
        i.component = ComponentName(chosen.packageName, chosen.name)
        val started = withContext(Dispatchers.Main) { runCatching { g.context.startActivity(i) } }
        if (started.isFailure) return failed("$label は画像の共有を受け付けませんでした (${started.exceptionOrNull()?.javaClass?.simpleName})。$keep")
        val after = waitScreen(SHARE_WAIT_MS) { it.pkg == pkg }
        return if (after != null) ok("$done$label の共有の画面を開きました (画像を添えてあります)。まだ投稿・送信はしていません。", summary(after))
        else unknown("共有の画面を開く操作は送りましたが、$label の画面になったか確かめられません。$keep")
    }

    /** 「ばいばい」: 挨拶を言い終えたら、音声モードを終えて閉じる */
    private fun endVoiceChat(): ToolResult {
        g.endVoiceAfterGoodbye()
        return ok("わかりました。ひとこと短く別れの挨拶をしてください。言い終えたら、スマホが音声モードを終えて閉じます。")
    }

    /** 写真を言葉で探す (端末の中の EmbeddingGemma 2)。写真そのものは返さない */
    private suspend fun searchPhotos(a: JSONObject): ToolResult {
        val q = a.optString("query").trim()
        if (q.isEmpty()) return failed("探す写真の説明 (query) を入れてください。")
        val ps = g.photos
        if (!ps.hasPermission()) return failed("写真へのアクセスが許可されていません。利用者に、スマホの ENVOY MCP の設定の「写真を探す」で許可してもらってください。")
        if (!ps.modelReady()) return failed("写真を探す AI (EmbeddingGemma 2) がまだ入っていません。利用者に、スマホの ENVOY MCP の設定の「写真を探す」でモデルを入れてもらってください (${ps.spec.size / 1_000_000} MB)。")
        fun day(k: String): Long? = a.optString(k).trim().takeIf { it.isNotEmpty() }?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).parse(it)?.time }.getOrNull() }
        val limit = a.optInt("limit", 5).coerceIn(1, 10)
        val hits = runCatching { ps.search(q, limit, day("taken_after"), day("taken_before")) }
            .getOrElse { return failed("写真を探せませんでした (${it.javaClass.simpleName})。") }
        val st = ps.status.value
        val idx = "索引 ${st.indexed}/${if (st.total >= 0) st.total else "?"} 枚" + if (st.indexing) " (残りは作っているところ)" else ""
        if (hits.isEmpty()) return ok("「$q」に近い写真は見つかりませんでした ($idx)。", JSONObject().put("photos", JSONArray()))
        val fmt = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN)
        val top = hits.first().score
        val arr = JSONArray()
        val lines = hits.mapIndexed { n, h ->
            val weak = h.score < PHOTO_WEAK || h.score < top - PHOTO_GAP
            arr.put(JSONObject().put("photo_id", h.id).put("taken", fmt.format(Date(h.takenAt))).put("folder", h.folder)
                .put("score", Math.round(h.score * 100) / 100.0).put("weak", weak))
            "${n + 1}. photo_id ${h.id}: ${fmt.format(Date(h.takenAt))} 撮影 (${h.folder.ifBlank { "フォルダ不明" }})・近さ ${"%.2f".format(h.score)}" + if (weak) " (違うかもしれない)" else ""
        }
        RabiLog.i("photos", "search: ${hits.size} hits (top ${"%.2f".format(top)})")
        g.photoStrip.show(q, hits)      // 画面の下に、同じ番号で並べる
        return ok("「$q」に近い写真 (近い順。$idx):\n" + lines.joinToString("\n") +
            "\n画面の下に、同じ番号で写真を並べました (写真の中身は返していません)。共有するときは share_photo に photo_id を渡してください。", JSONObject().put("photos", arr))
    }

    /** 見つけた写真を、画面いっぱいに大きく出す (ChatGPT には渡さない) */
    private suspend fun showPhoto(a: JSONObject): ToolResult {
        val id = a.optLong("photo_id", -1L)
        if (id < 0) return failed("photo_id を入れてください (search_photos が返した番号)。")
        val p = withContext(Dispatchers.IO) { g.photos.photo(id) } ?: return failed("photo_id $id の写真が見つかりません (消えたか、番号が違います)。")
        val n = g.photoStrip.enlarge(p)
        val when_ = SimpleDateFormat("M月d日", Locale.JAPAN).format(Date(p.takenAt))
        return ok((if (n != null) "$n 番の写真" else "写真") + " ($when_ 撮影) を、スマホの画面に大きく出しました。利用者が画面を押すと、並べた写真に戻ります。")
    }

    /** 見つけた写真を、アプリの共有の画面に添えて開く */
    private suspend fun sharePhoto(a: JSONObject): ToolResult {
        val id = a.optLong("photo_id", -1L)
        if (id < 0) return failed("photo_id を入れてください (search_photos が返した番号)。")
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val p = withContext(Dispatchers.IO) { g.photos.photo(id) } ?: return failed("photo_id $id の写真が見つかりません (消えたか、番号が違います)。")
        val when_ = SimpleDateFormat("M月d日", Locale.JAPAN).format(Date(p.takenAt))
        g.photoStrip.hide()             // 共有の画面を見せる (並べた写真を消す)
        return shareImage(p.uri, p.mime, pkg, a.optString("text").trim(), a.optString("target", "post"), "写真 ($when_ 撮影) を添えて、")
    }

    /** 共有先を選ぶ: post は投稿の画面、message はメッセージ・チャット。名前 (クラス名と表示名) で決め、決まらなければ最初のもの */
    private fun pickShareTarget(list: List<ActivityInfo>, target: String): ActivityInfo {
        if (list.size == 1) return list[0]
        val pm = g.context.packageManager
        fun words(a: ActivityInfo) = (a.name + " " + runCatching { a.loadLabel(pm).toString() }.getOrDefault("")).lowercase()
        val msg = listOf("chat", "message", "msg", "direct", "dm", "チャット", "メッセージ")
        val post = listOf("compos", "post", "tweet", "publish", "upload", "feed", "story", "投稿", "ポスト")
        val want = if (target == "message") msg else post
        val avoid = if (target == "message") post else msg
        return list.firstOrNull { a -> want.any { words(a).contains(it) } && avoid.none { words(a).contains(it) } }
            ?: list.firstOrNull { a -> avoid.none { words(a).contains(it) } }
            ?: list[0]
    }

    /** PNG を端末の写真 (Pictures/ENVOY MCP) に保存する。保存した画像の URI */
    private fun saveToPictures(png: ByteArray): Uri? {
        val cr = g.context.contentResolver
        val name = "envoy_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".png"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ENVOY MCP")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) }.getOrNull() ?: return null
        return runCatching {
            cr.openOutputStream(uri)!!.use { it.write(png) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse { runCatching { cr.delete(uri, null, null) }; null }
    }

    private suspend fun openPanel(a: JSONObject): ToolResult {
        val which = a.optString("which")
        val before = g.device.observe()
        val r = g.device.openPanel(which)
        if (!r.ok) return fromAction(r, "開けませんでした")
        delay(700)
        val after = g.device.observe()
        record(TraceStep("panel", text = which, before = before, after = after))
        return ok("開きました。", summary(after))
    }

    private suspend fun tap(a: JSONObject): ToolResult {
        val cur = g.device.observe()
        if (!cur.ok) return failed("画面を読み取れません (${cur.error})")
        ownScreen(cur)?.let { return it }
        val node: UiNode = when {
            a.has("element_id") -> {
                val prev = lastScreen ?: return failed("先に read_screen で画面を読んでください (番号はそのときのものです)。")
                val want = prev.nodeById(a.optInt("element_id")) ?: return failed("その番号の部品はありません。read_screen で読み直してください。")
                // 番号は画面ごとに変わる: 同じ位置・同じ文字の部品が今もあるときだけ押す
                cur.nodes.firstOrNull { it.bounds == want.bounds && it.label == want.label && it.cls == want.cls }
                    ?: return failed("画面が変わったので押していません。返した今の画面の一覧から選び直してください。", summary(cur))
            }
            a.optString("visual_id").isNotBlank() -> {
                val memo = lastVision ?: return failed("先に look_at_screen で画面を見てください。")
                val t = memo.targets[a.optString("visual_id")] ?: return failed("その visual_id はありません。look_at_screen で見直してください。")
                if (cur.signature() != memo.obs.signature()) return failed("画面が変わったので押していません。look_at_screen で見直してください。", summary(cur))
                // 部品の一覧の部品か、その場所に同じくらいの大きさの名前のある部品があれば、部品として押す (確定なら承認の流れ)
                val listed = t.elementId?.let { id -> memo.obs.nodeById(id) }?.let { w -> cur.nodes.firstOrNull { it.bounds == w.bounds && it.label == w.label && it.cls == w.cls } }
                    ?: cur.nodeAt(t.bounds.cx, t.bounds.cy)?.takeIf { it.label.isNotBlank() && overlap(it.bounds, t.bounds) >= SAME_THING }
                listed ?: return visualTap(cur, t)
            }
            a.optString("text").isNotBlank() -> {
                val t = a.optString("text")
                when (val f = Matcher.find(cur, Selector(text = listOf(t), desc = listOf(t)), emptyMap())) {
                    is Matcher.Found.One -> f.node
                    is Matcher.Found.Many -> {
                        lastScreen = cur
                        val c = JSONArray(f.nodes.take(6).map { JSONObject().put("element_id", it.index).put("label", it.label).put("b", it.bounds.toString()) })
                        return failed("「$t」が ${f.nodes.size} 個あるので押していません。element_id で選んでください。", JSONObject().put("candidates", c))
                    }
                    else -> return failed("「$t」が画面に見つかりません。", summary(cur))
                }
            }
            else -> return failed("element_id・text・visual_id のどれかを指定してください。")
        }
        if (node.bounds.area <= 0) return failed("その部品は画面に出ていません。")
        // 確定らしいボタン・設定のスイッチは、承認画面で本人が承認してから押す (部品と同じ流れ)
        if (CommitDetector.looksLikeCommit(node) || CommitDetector.togglesSetting(cur, node)) return commitTap(cur, node)
        val r = g.device.tap(node.bounds.cx, node.bounds.cy)
        if (!r.ok) return fromAction(r, "押せませんでした")
        delay(600)
        val after = g.device.observe()
        record(TraceStep("tap", pkg = cur.pkg, node = node, before = cur, after = after))
        val changed = after.ok && after.signature() != cur.signature()
        return ok("「${node.label.ifBlank { node.shortId.ifBlank { "部品" } }}」を押しました。" + if (changed) "画面が変わりました。" else "画面の文字は変わっていません (読み込み中かもしれません)。",
            summary(after).put("screen_changed", changed))
    }

    /**
     * 部品の一覧に無いもの (look_at_screen で見つけたもの) を位置で押す。確定・設定の切り替えらしいものは押さない
     * (承認の流れは部品を前提にしていて、位置だけでは承認のあとに同じものを確かめられないため)。部品 (save_part) には残さない
     */
    private suspend fun visualTap(cur: Observation, t: ScreenVision.Target): ToolResult {
        val probe = UiNode(-1, t.label, "", "", if (t.kind == "switch") "android.widget.Switch" else "android.view.View", cur.pkg.orEmpty(), t.bounds,
            clickable = true, enabled = true, focused = false, scrollable = false, checked = false, password = false, hint = "", depth = 0)
        if (t.commit || t.kind == "switch" || CommitDetector.looksLikeCommit(probe) || CommitDetector.togglesSetting(cur, probe))
            return failed("「${t.label}」は確定・設定の切り替えらしく、画面の部品として読めないので押しません。利用者に押してもらってください。", summary(cur))
        val r = g.device.tap(t.bounds.cx, t.bounds.cy)
        if (!r.ok) return fromAction(r, "押せませんでした")
        delay(600)
        val after = g.device.observe()
        lastVision = null
        val changed = after.ok && after.signature() != cur.signature()
        return ok("「${t.label}」の位置を押しました。" + if (changed) "画面が変わりました。" else "画面の文字は変わっていません (読み込み中かもしれません)。",
            summary(after).put("screen_changed", changed))
    }

    /** 2 つの枠の重なり (小さい方の面積に対する割合) */
    private fun overlap(a: Bounds, b: Bounds): Float {
        val w = minOf(a.r, b.r) - maxOf(a.l, b.l); val h = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (w <= 0 || h <= 0) return 0f
        return w.toFloat() * h / minOf(a.area, b.area).coerceAtLeast(1)
    }

    /** 確定の流れ: 画面確認 → スマホで本人が承認 → 画面が変わっていないか確かめる → 押す → 結果を確かめる */
    private suspend fun commitTap(cur: Observation, node: UiNode): ToolResult {
        val label = node.label.ifBlank { "確定" }
        val app = g.appLabel(cur.pkg) ?: cur.pkg ?: ""
        val fields = linkedMapOf("操作" to label, "アプリ" to app)
        // 何が送られるか (入力欄の中身) を承認画面に出す。下書きが残っていた などにも、承認の前に気づけるように (パスワード欄は出さない)
        val content = cur.nodes.filter { it.editable && !it.password && it.text.isNotBlank() && it.text != it.hint }.joinToString("\n") { it.text }
        if (content.isNotBlank()) fields["内容"] = content.take(MAX_CONTENT)
        val sel = TraceCompiler.selectorFor(node, emptyMap(), cur)
        val summary = "${app}で「$label」を押します"
        val r = g.commitFlow.execute(node, sel, emptyMap(), cur, CommitDetector.kindOf(node), ApprovalLevel.BIOMETRIC, summary, fields, emptyList(), null)
        val after = runCatching { g.device.observe() }.getOrNull()
        val s = after?.let { summary(it) } ?: JSONObject()
        return when (r) {
            is CommitResult.Done -> {
                record(TraceStep("tap", pkg = cur.pkg, node = node, before = cur, after = after, commit = true))
                ok("利用者がスマホで承認したので「$label」を押し、画面が変わったことを確かめました。", s.put("approved", true))
            }
            is CommitResult.Rejected -> failed("利用者が承認しなかったので押していません (${r.reason})。", s.put("approved", false))
            is CommitResult.Changed -> failed("押していません: ${r.reason}。", s)
            is CommitResult.NotReady -> failed("押していません: ${r.reason}。", s)
            is CommitResult.Unknown -> unknown("${r.reason}。二重にならないよう、同じ確定を繰り返さないでください。利用者に画面で確かめてもらってください。", s)
        }
    }

    /**
     * 電話をかける (電話のアプリを操作すると手順が多いので、直接かける)。連絡先から番号を探し → 承認 (設定しだいで
     * 指紋など・承認画面・承認なし) → TelecomManager で発信。番号は ChatGPT に返さない (選んだ人の名前と、種類・下 4 桁だけ)
     */
    /** 電話の相手 (名前・番号の種類・番号)。名前が無いのは、連絡先にない番号 */
    private class PhoneTarget(val name: String, val kind: String, val number: String) {
        /** 本人確認や返事に出す相手 (番号は下 4 桁だけ) */
        val who get() = name.ifEmpty { "連絡先にない番号 (下 4 桁 ${PhoneContacts.tail(number)})" }
        val shown get() = if (name.isNotEmpty()) "$name ($kind・下 4 桁 ${PhoneContacts.tail(number)})" else "番号 下 4 桁 ${PhoneContacts.tail(number)}"
    }

    /** contact (連絡先の名前) か number から、電話の相手を決める。決められなければ、そのまま返す失敗 */
    private suspend fun resolvePhone(a: JSONObject): Pair<PhoneTarget?, ToolResult?> {
        val ctx = g.context
        val q = a.optString("contact").trim()
        val given = a.optString("number").trim().ifEmpty { q.takeIf { PhoneContacts.isNumber(it) }.orEmpty() }
        val t = if (given.isNotEmpty()) {
            if (!PhoneContacts.isNumber(given)) return null to failed("番号の形ではありません (数字・+・-・括弧だけにしてください)。")
            PhoneTarget("", "", given)
        } else {
            if (q.isEmpty()) return null to failed("相手の名前 (contact) か番号 (number) を入れてください。")
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return null to failed("連絡先を読む許可がありません。利用者に、スマホの ENVOY MCP の設定の「電話」で許可してもらってください。番号が分かれば number でかけられます。")
            val people = withContext(Dispatchers.IO) { PhoneContacts.find(PhoneContacts.load(ctx), q) }
            when {
                people.isEmpty() -> return null to failed("連絡先に「$q」が見つかりません。利用者に、連絡先での名前を聞いて頼み直してください。")
                people.size > 1 -> return null to failed("「$q」に当たる人が ${people.size} 人います (${people.take(6).joinToString("・") { it.name }})。利用者にどなたか聞いて、名前を詳しくして頼み直してください。",
                    JSONObject().put("candidates", JSONArray(people.take(6).map { it.name })))
            }
            val p = people.single()
            val type = a.optString("phone_type").ifBlank { null }
            val e = PhoneContacts.pick(p, type) ?: return null to failed("${p.name} さんには、その種類の番号がありません (${p.numbers.joinToString("・") { it.type }})。")
            PhoneTarget(p.name, e.type, e.number)
        }
        if (PhoneContacts.isEmergency(ctx, t.number)) return null to failed("緊急の番号には、ENVOY MCP からはかけません。利用者に、電話のアプリから自分でかけてもらってください。")
        return t to null
    }

    /** 電話をかけられる状態か (許可・今の通話)。かけられなければ失敗 */
    private fun phoneBlocker(): ToolResult? {
        val ctx = g.context
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return failed("電話をかける許可がありません。利用者に、スマホの ENVOY MCP の設定の「電話」で許可してもらってください。")
        // 通話中・呼び出し中はかけない (同じ電話を二度かけない。通話の画面が前にあると本人確認も取り消される)
        val mode = ctx.getSystemService(android.media.AudioManager::class.java).mode
        if (mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_RINGTONE)
            return failed("今、電話で話しているか、呼び出しの最中です。もう一度かけるなら、利用者に今の電話を切ってもらってから頼み直してください。")
        return null
    }

    /** 発信する (かけられなければ、その理由) */
    private suspend fun placeCall(number: String): Throwable? = withContext(Dispatchers.Main) {
        runCatching {
            g.context.getSystemService(android.telecom.TelecomManager::class.java).placeCall(Uri.fromParts("tel", PhoneContacts.digits(number), null), android.os.Bundle())
        }.recoverCatching {
            g.context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", PhoneContacts.digits(number), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.exceptionOrNull()
    }

    private suspend fun callPhone(a: JSONObject): ToolResult {
        phoneBlocker()?.let { return it }
        val (t, err) = resolvePhone(a)
        if (t == null) return err!!
        // 本人確認の画面には番号を出さない。相手の名前と番号の種類だけ。連絡先にない番号は下 4 桁だけ。
        // 番号は承認と照らし合わせる内側の事実 (screenFacts) にだけ入れる (承認した番号と違う番号にはかけられない)
        val fields = linkedMapOf("相手" to t.who)
        if (t.kind.isNotEmpty()) fields["種類"] = t.kind
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "${t.who} に電話をかけます",
            fields = fields, targetPkg = null, screenFacts = mapOf("number" to PhoneContacts.digits(t.number)))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        placeCall(t.number)?.let {
            RabiLog.w("mcp", "call failed: ${it.javaClass.simpleName}")
            return failed("電話をかけられませんでした (${it.javaClass.simpleName})。")
        }
        return ok("${t.shown} に電話をかけました。", JSONObject().put("called", true))
    }

    /**
     * リゲルが代わりに電話して用件を伝える。本人の承認 (相手・用件) のあと、UNO Q の電話の代役に頼み、
     * UNO Q がヘッドセットとしてつながったら発信する。すぐ戻り、結果は rigel_call_status で返す
     */
    private suspend fun rigelCall(a: JSONObject): ToolResult {
        val app = a.optString("app").ifBlank { "phone" }
        val message = a.optString("message").trim()
        if (message.isEmpty()) return failed("伝える用件 (message) を入れてください。")
        val caller = a.optString("caller").trim().ifEmpty { g.settings.rigelCallerName }
        if (g.rigelCaller.busy) return failed("リゲルはほかの電話をしています。rigel_call_status で様子を確かめてください。")
        if (app == "line") return rigelLineCall(a, message, caller)
        phoneBlocker()?.let { return it }
        val (t, err) = resolvePhone(a)
        if (t == null) return err!!
        // 本人確認: 相手 (番号は出さない)・用件・名乗る名前。番号と用件は承認と照らし合わせる内側の事実にする
        val fields = linkedMapOf("相手" to t.who)
        if (t.kind.isNotEmpty()) fields["種類"] = t.kind
        fields["用件"] = message
        fields["名乗り"] = "AI アシスタントのリゲル (${caller.ifEmpty { "あなた" }} の代わり)"
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "リゲルが ${t.who} に電話して、用件を伝えます",
            fields = fields, targetPkg = null, screenFacts = mapOf("number" to PhoneContacts.digits(t.number), "message" to message))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        val name = t.name.ifEmpty { "お相手" }
        g.rigelCaller.start("phone", name, message, caller, place = {
            placeCall(t.number)?.let { "電話をかけられませんでした (${it.javaClass.simpleName})" }
        }, watch = { send -> g.rigelCaller.watchPhone(send) })?.let { return failed("リゲルの電話を始められませんでした: $it") }
        return ok("リゲルが ${t.shown} に電話をかけ始めました。相手が出たら、リゲル (AI) が名乗って用件を伝え、返事を聞いて切ります。" +
            CALL_QUIET + "1〜3 分ほどしたら rigel_call_status で結果を確かめ、利用者に伝えてください。", JSONObject().put("calling", true))
    }

    /** リゲルが LINE の音声通話で代わりに話す: LINE の友だち (表示名) を本人に承認してもらい、LINE を操作してかける */
    private suspend fun rigelLineCall(a: JSONObject, message: String, caller: String): ToolResult {
        val friend = a.optString("contact").trim()
        if (friend.isEmpty()) return failed("LINE の友だちの名前 (contact。LINE の表示名) を入れてください。")
        if (A11yService.instance == null) return failed("ユーザー補助がオフなので、LINE を操作できません。利用者に、ENVOY MCP のユーザー補助をオンにしてもらってください。")
        if (g.context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            return failed("スマホのロックが掛かっているので、LINE を操作できません。利用者にロックを解除してもらってから頼み直してください。")
        val mode = g.context.getSystemService(android.media.AudioManager::class.java).mode
        if (mode != android.media.AudioManager.MODE_NORMAL)
            return failed("今、通話か音声の会話をしています (ChatGPT の音声モードを含む)。終えてから頼み直してください (LINE の通話とマイクを取り合うため)。")
        val fields = linkedMapOf("相手" to "LINE の友だち: $friend", "用件" to message, "名乗り" to "AI アシスタントのリゲル (${caller.ifEmpty { "あなた" }} の代わり)")
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "リゲルが LINE で $friend に電話して、用件を伝えます",
            fields = fields, targetPkg = "jp.naver.line.android", screenFacts = mapOf("line_friend" to friend, "message" to message))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        g.rigelCaller.start("line", friend, message, caller,
            place = { g.lineCaller.call(friend) },
            end = { g.lineCaller.end() },
            watch = { send -> g.lineCaller.watch(send) })?.let { return failed("リゲルの LINE 通話を始められませんでした: $it") }
        return ok("リゲルが LINE で $friend さんに電話をかけ始めました。相手が出たら、音をリゲルに回し、リゲル (AI) が名乗って用件を伝え、返事を聞いて切ります。" +
            CALL_QUIET + "1〜3 分ほどしたら rigel_call_status で結果を確かめ、利用者に伝えてください。", JSONObject().put("calling", true))
    }

    private suspend fun typeText(a: JSONObject): ToolResult {
        val text = a.optString("text")
        if (text.isEmpty()) return failed("入れる文字がありません。")
        if (text.contains('\n') || text.contains('\r')) return failed("改行は送信になることがあるので入れません。改行なしで入れてください。")
        val before = g.device.observe()
        ownScreen(before)?.let { return it }
        var target: UiNode? = null
        if (a.has("element_id")) {
            val prev = lastScreen ?: return failed("先に read_screen で画面を読んでください。")
            val want = prev.nodeById(a.optInt("element_id")) ?: return failed("その番号の部品はありません。read_screen で読み直してください。")
            target = before.nodes.firstOrNull { it.bounds == want.bounds && it.cls == want.cls }
                ?: return failed("画面が変わったので入れていません。返した今の画面の一覧から選び直してください。", summary(before))
        } else {
            target = before.nodes.firstOrNull { it.focused && it.editable }
        }
        if (target?.password == true) return failed("パスワード欄には入れません。")
        if (target != null && a.has("element_id")) {
            val tr = g.device.tap(target.bounds.cx, target.bounds.cy)
            if (!tr.ok) return fromAction(tr, "入力欄を押せませんでした")
            delay(300)
            if (target.text.isNotEmpty() && target.text != target.hint) g.device.clearFocusedField()
        }
        val r = g.device.typeText(text)
        if (!r.ok) return fromAction(r, "入れられませんでした")
        // 日本語の入力が本当に入ったかを画面で確かめる
        val after = waitScreen(2_000) { o -> o.nodes.any { it.editable && Text.norm(it.text).contains(Text.norm(text)) } }
        record(TraceStep("type", pkg = before.pkg, node = target, text = text, before = before, after = after, ok = after != null))
        if (after == null) return unknown("入れる操作は送りましたが、画面で確かめられません。read_screen で確かめてください。")
        if (a.optBoolean("submit")) return submitSearch(text, after)
        return ok("「${text.take(40)}」を入れ、画面で確かめました。まだ送信はしていません。", summary(after))
    }

    /** 検索欄なら、入れた文字で検索を実行する (キーボードの実行。だめなら検索欄のときだけ Enter)。検索欄でなければ実行しない */
    private suspend fun submitSearch(text: String, cur: Observation): ToolResult {
        val field = cur.nodes.firstOrNull { it.editable && it.focused && Text.norm(it.text).contains(Text.norm(text)) }
            ?: cur.nodes.firstOrNull { it.editable && Text.norm(it.text).contains(Text.norm(text)) }
        if (field == null || !SearchField.isSearch(field.resId, field.hint, field.desc, field.cls))
            return ok("「${text.take(40)}」を入れました。検索欄ではないので、実行 (Enter) はしていません。送信のボタンなどは tap で押してください。", summary(cur))
        val done = com.rabi.envoymcp.a11y.A11yService.instance?.imeEnterOnFocused() == true ||
            (g.adb.isConnected && runCatching { g.adb.exec("input -d ${g.monitorStage.target} keyevent KEYCODE_ENTER", 5_000) }.isSuccess)
        if (!done) return failed("「${text.take(40)}」は入れましたが、検索を実行できませんでした。検索のボタンを tap で押してください。", summary(cur))
        delay(800)
        val after = g.device.observe()
        return ok("「${text.take(40)}」で検索しました。", summary(after))
    }

    private suspend fun scroll(a: JSONObject): ToolResult {
        val dir = a.optString("direction", "down")
        val o = g.device.observe()
        val w = o.screenW.takeIf { it > 0 } ?: 1080; val h = o.screenH.takeIf { it > 0 } ?: 2400
        val r = when (dir) {
            "up" -> g.device.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)      // 指を下へ (上の内容を見る)
            "left" -> g.device.swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
            "right" -> g.device.swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
            else -> g.device.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)      // 指を上へ (下の内容を見る)
        }
        if (!r.ok) return fromAction(r, "スクロールできませんでした")
        delay(500)
        val after = g.device.observe()
        // 部品の「swipe」は指の向き (up = 指を上へ = 下の内容)
        record(TraceStep("swipe", direction = when (dir) { "up" -> "down"; "down" -> "up"; "left" -> "right"; else -> "left" }, before = o, after = after))
        return ok("スクロールしました。", summary(after).put("screen_changed", after.signature() != o.signature()))
    }

    private suspend fun simple(op: String, act: suspend () -> ActionResult): ToolResult {
        val before = g.device.observe()
        val r = act()
        if (!r.ok) return fromAction(r, "できませんでした")
        delay(500)
        val after = g.device.observe()
        record(TraceStep(op, before = before, after = after))
        return ok(if (op == "home") "ホームに戻りました。" else "戻りました。", summary(after))
    }

    /**
     * キーを押す (モニターの YouTube を f で全画面にするなど)。操作先の画面 (モニターにつながっていればモニター) に、
     * ADB の input keyevent で送る (ユーザー補助では文字以外のキーを送れない)。Enter は送信になることがあるので送らない
     */
    private suspend fun pressKey(a: JSONObject): ToolResult {
        val raw = a.optString("key").trim().lowercase()
        if (raw in setOf("enter", "return", "numpad_enter", "\n")) return failed("Enter は送信になることがあるので押しません。確定のボタンは tap で押してください (承認のあとに押します)。")
        val code = KEYS[raw] ?: when {
            raw.length == 1 && raw[0] in 'a'..'z' -> "KEYCODE_${raw.uppercase()}"
            raw.length == 1 && raw[0] in '0'..'9' -> "KEYCODE_$raw"
            raw.matches(Regex("f([1-9]|1[0-2])")) -> "KEYCODE_${raw.uppercase()}"
            else -> null
        } ?: return failed("押せないキーです ($raw)。英字・数字 1 文字か、space・escape・矢印 (up/down/left/right) などを指定してください。")
        if (!g.adb.isConnected && !(g.adb.paired && g.adb.probeIfIdle())) return failed("キーを送るには、端末の操作の接続 (ADB) が要ります。")
        val times = a.optInt("times", 1).coerceIn(1, 10)
        val display = g.monitorStage.target
        repeat(times) {
            val r = runCatching { g.adb.exec("input -d $display keyevent $code", 5_000) }
            if (r.isFailure) return failed("キーを送れませんでした (${r.exceptionOrNull()?.javaClass?.simpleName})。")
        }
        delay(400)
        val after = g.device.observe()
        // キーの手順は部品 (save_part) にできないので、操作の記録には入れない
        val where = if (display != android.view.Display.DEFAULT_DISPLAY) "モニター" else "スマホ"
        return ok("${where}で $raw キーを${if (times > 1) " $times 回" else ""}押しました。", summary(after))
    }

    private suspend fun waitFor(a: JSONObject): ToolResult {
        val ms = (a.optDouble("seconds", 2.0) * 1000).toLong().coerceIn(500, 10_000)
        delay(ms)
        val o = g.device.observe()
        record(TraceStep("wait"))
        return ok("${ms / 1000.0} 秒待ちました。", summary(o))
    }

    // ---------------------------------------------------------------- 部品

    private suspend fun runPart(a: JSONObject): ToolResult {
        val id = a.optString("part_id")
        val part = g.parts.usable().firstOrNull { it.id == id } ?: return failed("その部品はありません (list_parts で確かめてください)。")
        val given = a.optJSONObject("args") ?: JSONObject()
        val args = HashMap<String, ArgValue>()
        for (spec in part.args) {
            val raw = given.optString(spec.name).trim()
            if (raw.isEmpty()) { if (spec.required) return failed("引数「${spec.name}」がありません。") else continue }
            args[spec.name] = when (spec.type) {
                ArgType.APP -> {
                    val (pkg, why) = resolveApp(raw)
                    pkg ?: return failed(why)
                    val label = g.appLabel(pkg) ?: pkg
                    ArgValue(ArgType.APP, label, mapOf("package" to pkg, "label" to label))
                }
                ArgType.CONTACT, ArgType.PLACE -> return failed("連絡先・場所の引数は、ENVOY MCP ではまだ使えません。")
                ArgType.TEXT -> ArgValue.text(raw)
                else -> ArgValue(spec.type, raw, mapOf("value" to raw, "label" to raw))
            }
        }
        val listener = object : RunListener {
            override fun onStep(index: Int, step: Step) { g.beginWork("${part.name} (${index + 1}/${part.steps.size})", true) }
        }
        val out = g.runner.run(part, args, listener)
        val after = runCatching { g.device.observe() }.getOrNull()
        val s = after?.let { summary(it) } ?: JSONObject()
        return when (out) {
            is RunOutcome.Success -> { g.parts.recordSuccess(part); ok("「${part.name}」が終わり、画面で確かめました。", s) }
            is RunOutcome.Escalate -> { g.parts.recordEscalation(part, out.reason); failed("「${part.name}」の ${out.stepIndex + 1} 番目の手順で止めました: ${out.reason}。ここからは read_screen と個別の道具で続けられます。", s) }
            is RunOutcome.Rejected -> failed("利用者が承認しなかったので、確定はしていません (${out.reason})。", s)
            is RunOutcome.ContentChanged -> failed("確定はしていません: ${out.reason}。", s)
            is RunOutcome.Unknown -> unknown("${out.reason}。二重にならないよう、同じ部品を繰り返さないでください。", s)
            is RunOutcome.Failed -> { g.parts.recordFailure(part, out.reason); failed("「${part.name}」はできませんでした: ${out.reason}。", s) }
        }
    }

    private fun savePart(a: JSONObject): ToolResult {
        val steps = synchronized(trace) { trace.toList() }
        if (steps.none { it.ok && it.op != "wait" }) return failed("部品にできる操作の記録がありません (前回の登録より後に、操作をしていません)。")
        val argMap = a.optJSONObject("args") ?: JSONObject()
        val args = argMap.keys().asSequence().map { lit -> lit to ArgSpec(argMap.getString(lit), ArgType.TEXT) }.toList()
        val name = a.optString("name").trim().take(40)
        val examples = a.optJSONArray("examples")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        val meta = PartMeta(null, name, a.optString("description").trim(), examples, args)
        val draft = TraceCompiler.compile(name, steps, meta) ?: return failed("この操作の記録は部品にできません (名前も ID も無い部品を押した手が含まれている など)。")
        val p: Part = g.parts.saveLearned(draft)
        synchronized(trace) { trace.clear() }
        val o = JSONObject().put("part_id", p.id).put("name", p.name).put("steps", p.steps.size).put("needs_approval", p.hasCommit)
        return ok("部品「${p.name}」を登録しました (手順 ${p.steps.size} 個)。run_part で使えます。", o)
    }

    // ---------------------------------------------------------------- AgentARM

    private suspend fun armMotion(a: JSONObject): ToolResult {
        val arr = a.optJSONArray("keyframes") ?: return failed("keyframes がありません。")
        val keys = (0 until arr.length()).map { i ->
            val k = arr.optJSONObject(i) ?: return failed("keyframes の ${i + 1} 番目が形になっていません。")
            ArmController.Key(k.optNum("turn") ?: 0.0, k.optNum("tilt") ?: 0.0, k.optNum("roll") ?: 0.0, k.optInt("ms", 0))
        }
        return arm(g.arm.motion(keys, a.optInt("repeat", 1)))
    }

    /** 数 (無い・数でなければ null) */
    private fun JSONObject.optNum(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun orientation(a: JSONObject): String? = a.optString("orientation").takeIf { it == ArmController.PORTRAIT || it == ArmController.LANDSCAPE }

    private fun arm(r: ArmResult): ToolResult {
        val s = JSONObject().put("arm", g.arm.statusJson())
        return when (r.result) {
            "ok" -> ok(r.message, s)
            "unknown" -> unknown("${r.message}。腕がどこまで動いたかは確かめていません。", s)
            else -> failed(r.message, s.put("why", r.code))
        }
    }

    private fun deletePart(a: JSONObject): ToolResult {
        val id = a.optString("part_id")
        val p = g.parts.all().firstOrNull { it.id == id } ?: return failed("その部品はありません。")
        if (p.source != "learned") return failed("最初から入っている部品は消せません。")
        g.parts.deleteLearned(id)
        return ok("部品「${p.name}」を消しました。", JSONObject().put("part_id", id))
    }

    // ---------------------------------------------------------------- 共通

    /** 端末の操作が使えるか。使えなければ理由 (ADB を使う方式なら、つなぎ直してみる) */
    /** ENVOY MCP 自身の画面 (承認画面・設定など) は、ChatGPT の道具では押さない・入れない (承認を自分で押せないように) */
    private fun ownScreen(o: Observation): ToolResult? =
        if (o.pkg == g.context.packageName) failed("ENVOY MCP 自身の画面 (承認画面・設定など) は操作しません。利用者が自分で操作します。", summary(o)) else null

    private suspend fun needDevice(): String? {
        if (g.device.isConnected) return null
        val mode = g.settings.controlMode
        if (mode != com.rabi.envoymcp.ControlMode.A11Y && g.adb.paired && withTimeoutOrNull(ADB_CONNECT_MS) { g.adb.ensureConnected() } == true && g.device.isConnected) return null
        return when (mode) {
            com.rabi.envoymcp.ControlMode.ADB -> "端末の操作の接続 (ワイヤレスデバッグ) がありません。スマホの ENVOY MCP の設定で接続してください。"
            com.rabi.envoymcp.ControlMode.A11Y -> "ENVOY MCP のユーザー補助がオフです。スマホの設定の「ユーザー補助」でオンにしてください。"
            com.rabi.envoymcp.ControlMode.HYBRID -> "ENVOY MCP のユーザー補助がオフで、ワイヤレスデバッグもつながっていません。スマホの ENVOY MCP の設定を確かめてください。"
        }
    }

    /** アプリの名前かパッケージ名から、パッケージ名を決める。決められなければ (null, 理由) */
    private fun resolveApp(q: String): Pair<String?, String> {
        val s = q.trim()
        if (s.isEmpty()) return null to "アプリの指定がありません。"
        return when (val r = AppMatcher.resolve(s, g.launcherApps())) {
            is AppMatcher.Result.Found -> r.pkg to ""
            is AppMatcher.Result.NotFound -> null to "「$s」というアプリが見つかりません (list_apps で確かめてください)。"
            is AppMatcher.Result.Many -> null to "「$s」に当たるアプリが複数あります: ${r.apps.take(6).joinToString("、") { "${it.second} (${it.first})" }}。パッケージ名で指定してください。"
        }
    }

    private suspend fun waitScreen(timeoutMs: Long, cond: (Observation) -> Boolean): Observation? {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val o = g.device.observe()
            if (o.ok && cond(o)) return o
            if (System.currentTimeMillis() >= until) return null
            delay(350)
        }
    }

    /**
     * 操作のあとに返す画面: read_screen と同じ番号つきの部品の一覧。直前に読んだ画面として覚えるので、
     * ChatGPT は読み直さずに、次の tap・type_text でこの番号を使える (押す前に、同じ部品が今もあるかは確かめる)
     */
    private fun summary(o: Observation): JSONObject {
        if (!o.ok) return JSONObject().put("app", o.pkg ?: JSONObject.NULL).put("error", o.error ?: "画面を読み取れません")
        lastScreen = o
        return o.toModelJson(AFTER_ITEMS).put("app_label", g.appLabel(o.pkg) ?: JSONObject.NULL)
    }

    private fun record(t: TraceStep) = synchronized(trace) { trace += t; while (trace.size > MAX_TRACE) trace.removeAt(0) }

    private fun fromAction(r: ActionResult, what: String): ToolResult =
        if (r.unknown) unknown("$what: 操作を送ったあと接続が切れました。できたかどうか分かりません。") else failed("$what (${r.detail})。")

    private fun ok(msg: String, o: JSONObject = JSONObject()) = o.put("result", "ok").let { ToolResult("$msg\n$it", it) }
    private fun failed(msg: String, o: JSONObject = JSONObject()) = o.put("result", "failed").let { ToolResult("$msg\n$it", it, isError = true) }
    private fun unknown(msg: String, o: JSONObject = JSONObject()) = o.put("result", "unknown").let { ToolResult("$msg\n$it", it, isError = true) }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun enumOf(desc: String, vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList())).put("description", desc)
    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

    companion object {
        /**
         * リゲルの電話の間、ChatGPT に黙っていてもらう (ChatGPT の返事は、つながっている「リゲル」に流れて通話の音に入り、
         * リゲル (Gemini) がそれに答えて、相手に向けない言葉を話してしまう。利用者にも聞こえない)
         */
        private const val CALL_QUIET = "今は返事をせず、電話が終わるまで黙って待ってください (話すと、その声はリゲルの通話の線に入り、" +
            "相手との会話のじゃまになります。利用者にも聞こえません)。"
        /** 1 つの道具にかける時間の上限 */
        const val TOOL_TIMEOUT_MS = 45_000L
        /** 共有の画面が開くのを待つ長さ */
        private const val SHARE_WAIT_MS = 6_000L
        /** 写真を探す最長 (はじめにモデルを開くのと、新しい写真の索引を作る 20 秒を含む) */
        private const val PHOTO_TIMEOUT_MS = 60_000L
        /** これより近さが低い・いちばん近いものからこれ以上離れた写真は「違うかもしれない」(Edge Gallery の薄く出す基準と同じ) */
        private const val PHOTO_WEAK = 0.40f
        private const val PHOTO_GAP = 0.20f
        /** 画面を見る道具の上限 (AI が画像を読むのに数秒〜十数秒かかる) */
        const val VISION_TIMEOUT_MS = 60_000L
        /** 位置で見つけたものと部品が「同じもの」とみなす重なり */
        private const val SAME_THING = 0.6f
        /** 承認画面に出す内容 (入力欄の中身) の長さの上限 */
        private const val MAX_CONTENT = 600
        /** 操作のあとに返す部品の数の上限 */
        private const val AFTER_ITEMS = 80
        /** press_key で押せる名前つきのキー (Enter は送信になるので入れない) */
        private val KEYS = mapOf(
            "space" to "KEYCODE_SPACE", "escape" to "KEYCODE_ESCAPE", "esc" to "KEYCODE_ESCAPE", "tab" to "KEYCODE_TAB",
            "backspace" to "KEYCODE_DEL", "delete" to "KEYCODE_FORWARD_DEL",
            "up" to "KEYCODE_DPAD_UP", "down" to "KEYCODE_DPAD_DOWN", "left" to "KEYCODE_DPAD_LEFT", "right" to "KEYCODE_DPAD_RIGHT",
            "page_up" to "KEYCODE_PAGE_UP", "page_down" to "KEYCODE_PAGE_DOWN", "move_home" to "KEYCODE_MOVE_HOME", "move_end" to "KEYCODE_MOVE_END",
            "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE", "next" to "KEYCODE_MEDIA_NEXT", "previous" to "KEYCODE_MEDIA_PREVIOUS",
            "volume_up" to "KEYCODE_VOLUME_UP", "volume_down" to "KEYCODE_VOLUME_DOWN", "mute" to "KEYCODE_VOLUME_MUTE",
        )
        /** スマホでの承認を待つ道具の上限 (承認画面は 120 秒で閉じる) */
        const val APPROVAL_TIMEOUT_MS = 130_000L
        /** 腕の道具の上限 (カメラの準備 3 秒 + 縦・横も合わせて顔に向ける 18 秒より長く。踊るときは拍が取れるまで 9 秒) */
        const val ARM_TIMEOUT_MS = 30_000L
        /** 腕を動かす道具の上限 (大きく動かすと、速さの上限で 30 秒ほどかかる) */
        const val ARM_MOVE_TIMEOUT_MS = 45_000L
        private const val ADB_CONNECT_MS = 8_000L
        private const val MAX_TRACE = 40
    }
}
