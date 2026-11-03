package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.ControlMode
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.arm.ArmController
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT に出す道具。端末の操作 (ADB・ユーザー補助)・部品・確定の承認を、MCP の道具として出す。考えるのは ChatGPT 側。
 * - 読む: スマホの状態・画面の部品・画面の画像 (設定で切れる)・アプリ・ショートカット・部品の一覧
 * - 操作する: アプリ・ショートカット・クイック設定/通知を開く、タップ、文字入力、スクロール、戻る、ホーム、待つ
 * - 教える: 押す場所を指して見せる (押さない。押すのは本人)
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

    private val state = ToolState(g)
    private val screenTools = ScreenTools(state)
    private val appTools = AppTools(state)
    private val operateTools = OperateTools(state)
    private val partTools = PartTools(state)
    private val photoTools = PhotoTools(state, appTools)
    private val callTools = CallTools(state, appTools)
    private val voiceTools = VoiceTools(state)
    private val armTools = ArmTools(state)

    private val tools = listOf(
        // ---------------------------------------------------------------- 読む
        Tool("get_state", "スマホの状態",
            "利用者の Android スマホの今の状態を返します: 時刻、電池、画面の点灯とロック、前面のアプリ、ENVOY MCP 自身の状態。読むだけです。",
            act = false, caption = "スマホの状態を確かめています", adb = false) { screenTools.getState(toolNames()) },
        Tool("read_screen", "画面の部品を読む",
            "今の画面に出ている部品 (文字・ボタン・入力欄) を番号つきで返します。tap と type_text の element_id には、ここで返った番号を使います。" +
                "操作の道具の結果にも同じ形の一覧 (操作のあとの画面) が付くので、続けて操作するときは読み直さなくて構いません。読むだけです。",
            act = false, caption = "画面を読んでいます", params = props("max_items" to int("返す部品の数の上限 (10〜150、既定 80)"))) { screenTools.readScreen(it) },
        Tool("get_screenshot", "画面の画像",
            "今の画面の画像 (縮小した JPEG) を返します。部品の文字だけでは分からないとき (写真・地図・図) に使います。利用者が設定で切っていると使えません。読むだけです。",
            act = false, caption = "画面を見ています") { screenTools.screenshot() },
        Tool("look_at_screen", "画面を見て説明する",
            "画面の画像を ENVOY MCP の中で AI に読ませ、画面の説明と、押せそうなもの・入力欄 (部品の一覧に無いものも含む) を、名前・種類・位置つきで返します。" +
                "部品の一覧で見つからないとき・名前の無いアイコンを押したいとき・Web やゲームの画面のときに使います。返った visual_id は tap に使えます。" +
                "画像は ChatGPT には届かないので、画面の中身を知りたいときは get_screenshot ではなくこれを使ってください。数秒かかります。読むだけです。",
            act = false, caption = "画面を見ています", timeoutMs = VISION_TIMEOUT_MS) { screenTools.lookAtScreen() },
        Tool("list_apps", "アプリの一覧",
            "このスマホに入っている、開けるアプリの名前とパッケージ名を返します。読むだけです。",
            act = false, caption = "アプリを調べています", adb = false) { appTools.listApps() },
        Tool("list_shortcuts", "アプリのショートカットの一覧",
            "アプリのショートカット (アイコンの長押しで出る項目。X の「ポスト」など) を返します。open_shortcut で開けます。読むだけです。" +
                "ENVOY MCP が既定のホームアプリでないと空になります。",
            act = false, caption = "ショートカットを調べています", adb = false,
            params = props("package" to str("このアプリの分だけ返す (省略可)"))) { appTools.listShortcuts(it) },
        Tool("list_parts", "部品の一覧",
            "登録済みの部品 (手順をまとめたもの) の ID・名前・説明・引数を返します。run_part で動かせます。読むだけです。",
            act = false, caption = "部品を調べています", adb = false) { partTools.listParts() },

        // ---------------------------------------------------------------- 操作する
        Tool("open_app", "アプリを開く",
            "アプリを開きます。app にはアプリの名前かパッケージ名 (list_apps のもの) を入れます。",
            act = true, caption = "アプリを開いています", params = props("app" to str("アプリの名前かパッケージ名")), required = listOf("app")) { appTools.openApp(it) },
        Tool("open_shortcut", "ショートカットを開く",
            "アプリのショートカット (list_shortcuts のもの) を開きます。投稿の画面などをすぐ開けます。開くだけで、送信・投稿はしません。",
            act = true, caption = "ショートカットを開いています",
            params = props("package" to str("アプリのパッケージ名"), "shortcut_id" to str("ショートカットの ID")), required = listOf("package", "shortcut_id")) { appTools.openShortcut(it) },
        Tool("call_phone", "電話をかける",
            "電話をかけます (「〇〇さんに電話して」「090... に電話」)。電話のアプリは操作せず、連絡先から番号を探し、スマホで利用者が承認したら (設定しだいで指紋など) すぐ発信します。" +
                "電話をかけるときは、open_app で電話のアプリを開いて押すのではなく、これを使います。" +
                "contact に連絡先の名前 (さん・様は付けなくてよい。ニックネーム・読みでもよい)、または number に番号を入れます。" +
                "同じ名前の人が何人かいれば、候補の名前を返すので、利用者にどなたか聞いて、名前を詳しくして頼み直してください。" +
                "番号の種類は phone_type (mobile・home・work。省略すると、よく使う番号 → 携帯)。緊急の番号 (110・119 など) にはかけません。連絡先の一覧や番号は返しません (番号は下 4 桁だけ)。",
            act = true, caption = "電話をかけています", adb = false, readOnly = false, timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("contact" to str("連絡先の名前 (例: 田中太郎・お母さん)"), "number" to str("電話番号 (連絡先にない番号のとき)"),
                "phone_type" to enumOf("番号の種類 (省略すると、よく使う番号 → 携帯)", "mobile", "home", "work"))) { callTools.callPhone(it) },
        Tool("rigel_call", "リゲルが代わりに電話して用件を伝える",
            "リゲル (AI アシスタント) が利用者の代わりに電話をかけ、用件を伝えて、相手の返事を聞いてきます (「〇〇さんに電話して XX と伝えて」)。" +
                "必ず先に、相手と伝える用件を利用者にはっきり確かめてから呼んでください。スマホで利用者の承認 (相手・用件) が要ります。" +
                "リゲルは電話の最初に、AI であることと、利用者の代わりにかけたことを名乗ります。約束・お金・個人情報のやり取りはしません。" +
                "contact に連絡先の名前、message に伝える用件 (相手に伝わる言い方で、具体的に)、caller に利用者の呼び名 (省略すると設定の名前) を入れます。" +
                "かけ始めたらすぐ戻ります。1〜3 分ほどしたら rigel_call_status で結果 (伝わったか・相手の返事の要点・やり取り) を確かめて、利用者に伝えてください。" +
                "app=line なら LINE の音声通話でかけます (contact に LINE の友だちの表示名。スマホのロックが解除されている必要があります)。" +
                "腕のボードにつながっていないと使えません。緊急の番号にはかけません。",
            act = false, caption = "リゲルが電話をかけています", adb = false, readOnly = false, timeoutMs = APPROVAL_TIMEOUT_MS + 60_000L,
            params = props("contact" to str("連絡先の名前 (例: 田中太郎・お母さん)。app=line なら LINE の友だちの表示名"), "number" to str("電話番号 (連絡先にない番号のとき)"),
                "message" to str("相手に伝える用件 (例: 明日の会議は 10 時からに変わりました)"),
                "caller" to str("利用者の呼び名 (リゲルが「〇〇さんの代わりに」と名乗る。省略すると設定の名前)"),
                "app" to enumOf("かけ方 (省略すると電話。line なら LINE の音声通話)", "phone", "line"),
                "phone_type" to enumOf("番号の種類 (省略すると、よく使う番号 → 携帯)", "mobile", "home", "work")),
            required = listOf("message")) { callTools.rigelCall(it) },
        Tool("rigel_call_status", "リゲルの代わりの電話の結果",
            "rigel_call でリゲルがかけた電話の様子と結果を返します: state (preparing・ready・dialing・ringing・talking・done・failed)、" +
                "終わっていれば outcome (conveyed 伝わった・voicemail 留守番電話に残した・refused 断られた・no_answer 出なかった・hung_up 相手が切った・failed など)、" +
                "summary (相手の返事の要点)、transcript (やり取り)。まだ話していれば、少し待ってからもう一度確かめてください。読むだけです。",
            act = false, caption = "", adb = false) { callTools.rigelCallStatus() },
        Tool("rigel_call_hangup", "リゲルの代わりの電話をやめる",
            "rigel_call でリゲルがかけている電話を、途中でやめて切ります (利用者に「やめて」と言われたとき)。",
            act = false, caption = "リゲルの電話を切っています", adb = false, readOnly = false) { callTools.rigelCallHangup() },
        Tool("rigel_incoming", "かかってきた電話にリゲルが代わりに出るかを知らせる",
            "電話がかかってきて、ENVOY MCP から「代わりに出るか、自分で出るか、尋ねて」と頼まれたときに使います。利用者の答えを choice で知らせてください " +
                "(rigel: リゲル (AI) が代わりに出て用件を聞く、self: 利用者が自分で出る)。rigel なら、この音声の会話はすぐ終わり、電話のあとに開き直して結果を伝えます。" +
                "利用者に尋ねていないときは使わないでください。",
            act = false, caption = "", adb = false, readOnly = false,
            params = props("choice" to enumOf("利用者の答え", "rigel", "self")), required = listOf("choice")) { callTools.rigelIncoming(it) },
        Tool("share_call_record", "電話の記録を共有する",
            "リゲルが代わりにかけた・出た電話の記録 (要点と録音) を、指定したアプリの共有に添えて開きます (「さっきの電話の内容を息子に送って」など)。開くだけで、送信はしません。" +
                "添えるのは、相手 (記録の名前。電話番号は入れません)・日時・結果・要点 (と、記録にあれば詐欺の疑い) の短い文と、録音のファイル (あれば) です。text を入れると、文の最後に足します。" +
                "先に、送り先と、電話の内容を送ってよいかを利用者に確かめてください。call を省略すると、いちばん新しい記録です " +
                "(ほかの記録は、call に記録の id を入れます。違う値を入れると、最近の記録の id・日時・相手を返します)。" +
                "LINE で送る → app=LINE。送り先を選ぶ画面が開くので、read_screen で見て、検索の欄に相手の名前を type_text し、相手を tap して選び、" +
                "送信 (転送) を tap します (スマホでの承認が要ります)。" +
                "LINE などは、録音を添えると文を入れないことがあります。送ったあと、トークに文が届いていなければ、結果の text_to_type (改行なし) を" +
                "トークの入力欄に type_text で入れて、送信を tap してください。",
            act = true, caption = "電話の記録を共有しています",
            params = props("app" to str("共有先のアプリの名前かパッケージ名 (LINE など)"),
                "call" to str("共有する記録 (省略すると last: いちばん新しい記録。ほかは記録の id)"),
                "text" to str("文の最後に足す言葉 (省略可)"),
                "target" to enumOf("アプリの中の共有先 (省略すると message: メッセージ・チャット・LINE の送り先の選択。post: 投稿)", "message", "post")),
            required = listOf("app")) { callTools.shareCallRecord(it) },
        Tool("share_screenshot", "画面の画像を共有する",
            "今の画面の画像を撮って、この端末の写真 (Pictures/ENVOY MCP) に保存し、指定したアプリの共有に画像を添えて開きます。" +
                "例: 画面のスクリーンショットを X に投稿したいとき (X の投稿の画面が、画像が付いた状態で開きます)。text を入れると、一緒に入れます。" +
                "開くだけで、投稿・送信はしません (投稿ボタンを押すときは、スマホでの承認が要ります)。",
            act = true, caption = "画面の画像を共有しています",
            params = props("app" to str("共有先のアプリの名前かパッケージ名 (X など)"), "text" to str("一緒に入れる文 (省略可)"),
                "target" to enumOf("アプリの中の共有先 (省略すると post: 投稿。message: メッセージ・チャット)", "post", "message")),
            required = listOf("app")) { appTools.shareScreenshot(it) },
        Tool("end_voice_chat", "音声の会話を終える",
            "利用者が会話そのものを終えたいと言ったとき (「ばいばい」「またね」「おやすみ」「もういいよ」「終わり」など) に呼びます。" +
                "呼んだら、ひとこと短く別れの挨拶をしてください。挨拶を言い終えたら、スマホが ChatGPT の音声モードを終えて閉じ、リゲルの画面に戻します。" +
                "頼みごとの途中の言葉 (「じゃあね、田中さんに伝えておいて」など) や、ほかの人への挨拶では呼ばないでください。",
            act = false, caption = "会話を終えます", adb = false, readOnly = false) { voiceTools.endVoiceChat() },
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
            required = listOf("query")) { photoTools.searchPhotos(it) },
        Tool("show_photo", "写真を大きく見せる",
            "search_photos で見つけた写真 (photo_id) を、スマホの画面いっぱいに大きく出します (「2 番を大きく見せて」)。" +
                "画面に出すだけで、写真は ChatGPT には渡しません。利用者が画面を押すと、並べた写真の帯に戻ります。",
            act = false, caption = "写真を見せています", adb = false, readOnly = true,
            params = props("photo_id" to int("search_photos が返した写真の番号 (photo_id。画面の 1〜5 の番号ではない)")),
            required = listOf("photo_id")) { photoTools.showPhoto(it) },
        Tool("share_photo", "写真を共有する",
            "search_photos で見つけた写真 (photo_id) を、指定したアプリの共有に添えて開きます。開くだけで、投稿・送信はしません。" +
                "例: X に投稿 → app=X (target=post)。text を入れると本文に入ります。開いたら、利用者に確かめてから投稿ボタンを tap します (スマホでの承認が要ります)。" +
                "LINE で誰かに送る → app=LINE、target=message。送り先を選ぶ画面が開くので、read_screen で見て、検索の欄に相手の名前を type_text し、相手を tap して選び、" +
                "送信 (転送) を tap します (スマホでの承認が要ります)。写真が違うと言われたら、search_photos の次の候補で開き直してください。",
            act = true, caption = "写真を共有しています",
            params = props("photo_id" to int("search_photos が返した写真の番号"), "app" to str("共有先のアプリの名前かパッケージ名 (X・LINE など)"),
                "text" to str("一緒に入れる文 (省略可)"),
                "target" to enumOf("アプリの中の共有先 (省略すると post: 投稿。message: メッセージ・チャット・LINE の送り先の選択)", "post", "message")),
            required = listOf("photo_id", "app")) { photoTools.sharePhoto(it) },
        Tool("open_panel", "クイック設定・通知を開く",
            "画面の上から引き出す画面を開きます (quick_settings: クイック設定、notifications: 通知、close: 閉じる)。",
            act = true, caption = "画面の上を開いています",
            params = props("which" to enumOf("開く画面", "quick_settings", "notifications", "close")), required = listOf("which")) { operateTools.openPanel(it) },
        Tool("tap", "タップする",
            "画面の部品を 1 回タップします。element_id (部品の一覧の番号)・text (部品の文字)・visual_id (look_at_screen の番号) のどれかで指定します。座標は直接指定できません。" +
                "visual_id で部品の一覧に無いものを押すとき、確定・設定の切り替えらしいものは押しません (利用者に押してもらってください)。" +
                "送信・投稿・購入・削除・発信らしいボタンや設定のスイッチは、スマホの承認画面や指紋などで利用者が承認してから押します (承認を待つので時間がかかります。利用者が設定で承認をオフにしていれば、承認なしで押します。そのときは押す前に利用者に声で確かめてください)。" +
                "結果が unknown のときは、二重にならないよう、同じ確定を繰り返さないでください。",
            act = true, caption = "画面を操作しています", timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("element_id" to int("部品の一覧の番号"), "text" to str("押す部品の文字か説明 (element_id が無いとき)"),
                "visual_id" to str("look_at_screen が返した番号 (v1 など)"))) { operateTools.tap(it) },
        // 押さずに指して教える: 操作ではないので、縁の光・字幕・操作中の顔は出さない (quiet)
        Tool("point_at", "押す場所を指して教える",
            "画面の部品を、押さずに指して見せます (やり方を教える)。利用者が「やり方を教えて」「自分でやってみたい」「どこを押すの」と言ったときは、" +
                "tap で代わりに押すのではなく、これで押す場所を見せて、利用者に自分で押してもらいます。" +
                "指定は tap と同じで、element_id (部品の一覧の番号)・text (部品の文字)・visual_id (look_at_screen の番号) のどれかです。" +
                "部品のまわりに水色に光る枠と小さな矢印を seconds 秒 (省略すると 10 秒、30 秒まで) 出します。枠は重ねて見せるだけで、利用者はそのまま下のボタンを押せます。" +
                "呼んだら、返した場所 (position) を使って、場所を言葉でも伝えてください (例: 「画面の右下の、水色に光っている『送信』を押してください」)。" +
                "ENVOY MCP は押しません。枠は、利用者が押して画面が変わる・時間が来る・ほかの操作の道具を使うと消えます " +
                "(read_screen・look_at_screen・get_screenshot・get_state・wait では消えないので、押せたかを確かめられます)。" +
                "ChatGPT の音声モードの画面そのものは指せません (先に open_app などで、やり方を教えるアプリを開きます)。",
            act = false, caption = "", readOnly = false, quiet = true,
            params = props("element_id" to int("部品の一覧の番号"), "text" to str("指す部品の文字か説明 (element_id が無いとき)"),
                "visual_id" to str("look_at_screen が返した番号 (v1 など)"),
                "seconds" to num("指している秒数 (3〜30。省略すると 10)"))) { operateTools.pointAt(it) },
        Tool("type_text", "文字を入力する",
            "入力欄に文字を入れます。element_id (read_screen の番号) を指定すると、その欄を押してから入れます (中身があれば消してから)。" +
                "改行は入れません (送信になることがあるため)。パスワード欄には入れません。入れたあと、画面で確かめた結果を返します。" +
                "submit を true にすると、検索欄 (YouTube・Chrome などの検索・アドレス欄) なら入れたあと検索を実行します。メッセージ・コメントなどの欄では実行しません (送信のボタンは tap で押す)。",
            act = true, caption = "文字を入力しています",
            params = props("text" to str("入れる文字"), "element_id" to int("入力欄の番号 (省略すると、今選ばれている欄)"),
                "submit" to bool("検索欄なら、入れたあと検索を実行するか (省略すると false)")), required = listOf("text")) { operateTools.typeText(it) },
        Tool("scroll", "スクロールする",
            "画面をスクロールします。down: 下の内容を見る、up: 上の内容を見る、left / right: 横。",
            act = true, caption = "画面を動かしています",
            params = props("direction" to enumOf("見たい方向", "down", "up", "left", "right")), required = listOf("direction")) { operateTools.scroll(it) },
        Tool("back", "戻る", "戻るボタンを押します。", act = true, caption = "戻っています") { operateTools.back() },
        Tool("home", "ホームに戻る", "ホームボタンを押して、ホーム画面に戻ります。", act = true, caption = "ホームに戻っています") { operateTools.home() },
        Tool("press_key", "キーを押す",
            "キーボードのキーを 1 つ押します (今選ばれている窓に届く。モニターにつながっていればモニター)。" +
                "YouTube なら f で全画面の切り替え、k で再生・一時停止、left / right で巻き戻し・早送り、m で消音。" +
                "key は英字 1 文字・数字 1 文字か、space, escape, tab, backspace, delete, up, down, left, right, page_up, page_down, move_home, move_end, " +
                "f1〜f12, play_pause, next, previous, volume_up, volume_down, mute のどれか。times で続けて押す回数 (1〜10)。" +
                "Enter は送信になることがあるので押しません (確定のボタンは tap で押してください)。",
            act = true, caption = "キーを押しています",
            params = props("key" to str("押すキー (例: f, k, space, escape, right)"), "times" to int("続けて押す回数 (省略すると 1)")), required = listOf("key")) { operateTools.pressKey(it) },
        Tool("wait", "待つ", "アプリの読み込みなどを待ちます (最大 10 秒)。待ったあとの画面の様子を返します。",
            act = false, caption = "待っています", params = props("seconds" to JSONObject().put("type", "number").put("description", "待つ秒数 (0.5〜10)"))) { operateTools.waitFor(it) },

        // ---------------------------------------------------------------- AgentARM (スマホを載せる腕)
        // 画面は操作しないので、端末の操作の接続も、止められるリゲルも要らない。腕が動き終わるまで待って結果を返す
        Tool("arm_portrait", "スマホを縦にする",
            "AgentARM (利用者のスマホを載せている腕) で、スマホを縦向きにします。腕が回し終わるまで待ちます (十数秒まで)。" +
                "AgentARM がつながっていなければ failed を返します。",
            act = false, caption = "スマホを縦にしています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS) { armTools.orient(ArmController.PORTRAIT) },
        Tool("arm_landscape", "スマホを横にする",
            "AgentARM で、スマホを横向きにします (横長の動画・写真を見るときなど)。腕が回し終わるまで待ちます (十数秒まで)。" +
                "顔を追っている途中なら、追うのは続けます。",
            act = false, caption = "スマホを横にしています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS) { armTools.orient(ArmController.LANDSCAPE) },
        Tool("arm_look_at_me", "こっちを向く",
            "スマホの前面カメラで利用者の顔を探し、AgentARM で画面を利用者の顔の方へ 1 回向けます (向け終わったら止まります)。" +
                "orientation を付けると、向けながら縦・横にも合わせます。顔を追い続けるときは arm_follow_me を使います。" +
                "カメラの映像はスマホの中だけで使い、外には出しません。",
            act = false, caption = "利用者の方に向けています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("orientation" to enumOf("向けながら合わせる向き (省略すると今のまま)", ArmController.PORTRAIT, ArmController.LANDSCAPE))) { armTools.lookAtMe(it) },
        Tool("arm_follow_me", "顔を追い続ける",
            "利用者の顔を追い続けます: まず画面を顔に向け、そのあとも顔の位置がずれたら向け直します (動画を見ている間など)。" +
                "横長の動画を見るときは orientation に landscape を付けます (YouTube の横長の動画など)。" +
                "最初に向け終わったら返り、そのあとはスマホの中で追い続けます。arm_stop_following を呼ぶか、スマホを腕から外すまで続けます " +
                "(顔が 10 分見えないとき・スマホが熱いときもやめます)。追っている間は前面カメラを使います (映像は外に出しません)。",
            act = false, caption = "利用者の顔を追っています", adb = false, readOnly = false, timeoutMs = ARM_TIMEOUT_MS,
            params = props("orientation" to enumOf("合わせる向き。横長の動画は landscape (省略すると今のまま)", ArmController.PORTRAIT, ArmController.LANDSCAPE))) { armTools.follow(it) },
        Tool("arm_stop_following", "顔を追うのをやめる",
            "arm_follow_me で始めた顔の追跡をやめ、前面カメラを止めます。腕はその場で止まります (向きは戻しません)。",
            act = false, caption = "顔を追うのをやめています", adb = false, readOnly = false) { armTools.stopFollowing() },
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
                "max_minutes" to int("自動で止めるまでの分 (省略すると ${ArmController.RECORD_DEFAULT_MIN})"))) { armTools.recordStart(it) },
        Tool("arm_switch_camera", "撮りながらカメラを切り替える",
            "arm_record_video で撮っている途中に、録画を止めずに前面・背面のカメラを切り替えます (1 本の動画のまま。切り替える瞬間は映像が少し止まります)。" +
                "「背面にして」「カメラマンになって」は back、「前のカメラに戻して」は front、省略すると反対のカメラにします。" +
                "すぐ返り、顔を追っているときは、そのあと裏で腕を 180° 回して切り替えたカメラを利用者に向け、顔に向け直します (10 秒ほど)。撮っていないときは何もしません。",
            act = false, caption = "カメラを切り替えています", adb = false, readOnly = false, timeoutMs = ARM_MOVE_TIMEOUT_MS,
            params = props("camera" to enumOf("切り替える先 (省略すると反対のカメラ)", "front", "back"))) { armTools.recordSwitch(it) },
        Tool("arm_stop_recording", "録画を止める",
            "arm_record_video で始めた録画を止めて保存します (録画のために顔を追い始めていたら、追うのもやめます)。保存したファイル名と長さを返します。",
            act = false, caption = "録画を止めています", adb = false, readOnly = false) { armTools.recordStop() },
        Tool("arm_move", "腕を動かす",
            "AgentARM の 3 つの関節で、スマホの向きを角度で変えます (「左に 30° 向いて」「少し上を向いて」など)。" +
                "turn: 左右 (度。+ は画面の前にいる利用者から見て右へ向く)、tilt: 上下 (度。+ は上を向く)、roll: 画面を回す (度。回る向きは取り付けで変わる)。" +
                "relative が true (省略時) なら今の向きからの差、false なら腕の原点 (0, 0, 0) からの角度です。省いた関節は動かしません。" +
                "関節の範囲の外は端まで、速さは 1 秒に約 28° までです。動き終わるまで待ち、今の角度を返します。",
            act = false, caption = "腕を動かしています", adb = false, readOnly = false, timeoutMs = ARM_MOVE_TIMEOUT_MS,
            params = props("turn" to num("左右の角度 (度。+ は利用者から見て右)"), "tilt" to num("上下の角度 (度。+ は上)"),
                "roll" to num("画面を回す角度 (度)"), "relative" to bool("今の向きからの差か (省略すると true)"),
                "speed" to enumOf("速さ (省略すると normal)", "slow", "normal"))) { armTools.move(it) },
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
            required = listOf("keyframes")) { armTools.motion(it) },
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
                "size" to enumOf("振りの大きさ (省略すると、覚えた大きさか medium)", "small", "medium", "large"))) { armTools.dance(it) },
        // ---------------------------------------------------------------- リゲル
        // 表情を見せるだけ (スマホも腕も動かさない)。操作中の顔にすると表情が見えないので、quiet にする
        Tool("rigel_expression", "リゲルの表情",
            "スマホのリゲル (音声モードの全画面・ホーム・モニター) に、感情の表情を一度だけ見せます (2 秒ほどで元に戻ります)。" +
                "happy うれしい・laugh 笑う・surprised 驚き・sad 悲しい・shy 照れ・wink ウインク。" +
                "会話で気持ちが動くところ (よい知らせ・お礼・ほめられた・冗談・驚く話・残念な話) で、返事を話し始める前に 1 回だけ呼びます。" +
                "呼んだことは口に出しません。1 回の返事で 1 回まで。操作の途中では呼びません。",
            act = false, caption = "", adb = false, readOnly = false, quiet = true,
            params = props("expression" to enumOf("見せる表情", *Expression.entries.map { it.id }.toTypedArray())),
            required = listOf("expression")) { voiceTools.express(it) },
        // 定期の依頼 (ChatGPT のスケジュール済みタスク) の結果を預かる。ベルを付けるだけで、リゲルを操作中の顔にしない
        Tool("rigel_notify", "リゲルにお知らせを預ける",
            "利用者に伝えたいことを、スマホのリゲルに預けます。スケジュール済みタスク (定期の依頼) で調べた結果を届けるときに使います。" +
                "スマホのリゲルにベルが付き、利用者がリゲルを押すと、音声モードで伝えます。" +
                "伝えることが無いとき (新しい情報が無い・知らせる条件に当たらない) は呼びません。利用者と今話しているなら、呼ばずにそのまま伝えてください。",
            act = false, caption = "", adb = false, readOnly = false, quiet = true,
            params = props("message" to str("伝える中身 (要点を短く。500 字まで)"), "title" to str("短い見出し (省略可。例: 〇〇のニュース)")),
            required = listOf("message")) { voiceTools.notify(it) },

        Tool("arm_stop", "腕を止める",
            "AgentARM の今の動きを止めます: 踊っていれば踊り始めた向きに戻して終え、顔を追っていればやめ、動いている途中ならその場で止めます。" +
                "「止まって」「やめて」と言われたときに使います。",
            act = false, caption = "腕を止めています", adb = false, readOnly = false) { armTools.stop() },

        // ---------------------------------------------------------------- 部品
        Tool("run_part", "部品を動かす",
            "登録済みの部品 (list_parts のもの) を動かします。args には部品の引数を {名前: 値} で入れます (アプリはアプリの名前かパッケージ名)。" +
                "途中で画面が想定と違えば止めて、そのときの画面を返します (そこからは個別の道具で続けられます)。確定のある部品は、スマホでの承認を待ちます。",
            act = true, caption = "部品を動かしています", timeoutMs = APPROVAL_TIMEOUT_MS,
            params = props("part_id" to str("部品の ID"), "args" to JSONObject().put("type", "object").put("description", "引数 {名前: 値}")),
            required = listOf("part_id")) { partTools.runPart(it) },
        Tool("save_part", "直前の操作を部品として登録",
            "この会話で行った操作 (open_app・tap・type_text・scroll など、前回の登録より後のもの) を、次から run_part で使える部品として登録します。" +
                "うまくいった操作のあとにだけ使ってください。入れた文字を毎回変えたいときは args に {入れた文字: 引数の名前} を入れます。" +
                "確定らしい手は、動かすたびに承認が要る形で登録されます。",
            act = false, caption = "部品を登録しています", adb = false, readOnly = false,
            params = props("name" to str("部品の名前 (短く)"), "description" to str("何をする部品か"),
                "examples" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "頼み方の例"),
                "args" to JSONObject().put("type", "object").put("description", "{記録の中の具体的な文字: 引数の名前}")),
            required = listOf("name", "description")) { partTools.savePart(it) },
        Tool("delete_part", "覚えた部品を消す",
            "save_part で登録した部品を消します (最初から入っている部品は消せません)。",
            act = false, caption = "部品を消しています", adb = false, destructive = true, readOnly = false,
            params = props("part_id" to str("部品の ID")), required = listOf("part_id")) { partTools.deletePart(it) },
    )
    private val byName = tools.associateBy { it.name }

    private fun toolNames(): List<String> = tools.map { it.name }

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
        // 指さし (point_at) は、ほかの道具が動き始めたら消す。画面を読む・待つだけの道具では消さない (教えている途中に、押せたかを確かめられるように)
        if (name !in KEEP_POINT) operateTools.stopPointing()
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

    /** 端末の操作が使えるか。使えなければ理由 (ADB を使う方式なら、つなぎ直してみる) */
    private suspend fun needDevice(): String? {
        if (g.device.isConnected) return null
        val mode = g.settings.controlMode
        if (mode != ControlMode.A11Y && g.adb.paired && withTimeoutOrNull(ADB_CONNECT_MS) { g.adb.ensureConnected() } == true && g.device.isConnected) return null
        return when (mode) {
            ControlMode.ADB -> "端末の操作の接続 (ワイヤレスデバッグ) がありません。スマホの ENVOY MCP の設定で接続してください。"
            ControlMode.A11Y -> "ENVOY MCP のユーザー補助がオフです。スマホの設定の「ユーザー補助」でオンにしてください。"
            ControlMode.HYBRID -> "ENVOY MCP のユーザー補助がオフで、ワイヤレスデバッグもつながっていません。スマホの ENVOY MCP の設定を確かめてください。"
        }
    }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun enumOf(desc: String, vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList())).put("description", desc)
    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

    companion object {
        /** 1 つの道具にかける時間の上限 */
        const val TOOL_TIMEOUT_MS = 45_000L
        /** 写真を探す最長 (はじめにモデルを開くのと、新しい写真の索引を作る 20 秒を含む) */
        private const val PHOTO_TIMEOUT_MS = 60_000L
        /** 画面を見る道具の上限 (AI が画像を読むのに数秒〜十数秒かかる) */
        const val VISION_TIMEOUT_MS = 60_000L
        /** スマホでの承認を待つ道具の上限 (承認画面は 120 秒で閉じる) */
        const val APPROVAL_TIMEOUT_MS = 130_000L
        /** 腕の道具の上限 (カメラの準備 3 秒 + 縦・横も合わせて顔に向ける 18 秒より長く。踊るときは拍が取れるまで 9 秒) */
        const val ARM_TIMEOUT_MS = 30_000L
        /** 腕を動かす道具の上限 (大きく動かすと、速さの上限で 30 秒ほどかかる) */
        const val ARM_MOVE_TIMEOUT_MS = 45_000L
        private const val ADB_CONNECT_MS = 8_000L
        /** 動いても指さしを消さない道具 (画面を読む・待つ・表情を見せるだけ) */
        private val KEEP_POINT = setOf("get_state", "read_screen", "get_screenshot", "look_at_screen", "wait", "rigel_expression")
    }
}
