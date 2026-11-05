package com.rabi.envoymcp

import android.content.Context
import androidx.core.content.edit
import com.rabi.envoymcp.voice.VoicePrimer
import java.security.SecureRandom

/** ENVOY MCP の設定 (この端末の中だけ) */
class McpSettings(context: Context) {
    private val p = context.getSharedPreferences("envoymcp", Context.MODE_PRIVATE)

    /** MCP サーバーを動かすか。本人が設定の画面でオンにしたときだけ動く (最初はオフ) */
    var serverEnabled: Boolean
        get() = p.getBoolean("server_enabled", false)
        set(v) = p.edit { putBoolean("server_enabled", v) }

    /** 待ち受けるポート (127.0.0.1 だけ) */
    var port: Int
        get() = p.getInt("port", DEFAULT_PORT)
        set(v) = p.edit { putInt("port", v) }

    /** 合言葉 (Authorization: Bearer)。同じ端末の他のアプリから勝手に使われないように */
    val token: String
        get() = p.getString("token", null) ?: newToken()

    fun newToken(): String {
        val t = randomToken()
        p.edit { putString("token", t) }
        return t
    }

    /**
     * 開発モード: 止めていないエージェントには、権限の制限 (道具のまとまり・読むだけ・期限) をかけない (最初はオフ)。
     * 確定の前の承認 (承認画面・指紋など) はそのまま
     */
    var devNoAgentLimits: Boolean
        get() = p.getBoolean("dev_no_agent_limits", false)
        set(v) = p.edit { putBoolean("dev_no_agent_limits", v) }

    /** スマホの中で tunnel-client を動かすか (本人が設定でオンにしたときだけ。MCP サーバーが動いているときだけ動く) */
    var tunnelEnabled: Boolean
        get() = p.getBoolean("tunnel_enabled", false)
        set(v) = p.edit { putBoolean("tunnel_enabled", v) }

    /** つなぐトンネル (Platform のトンネル設定の tunnel_...)。秘密ではない。実行用キーは SecureStore に別に置く */
    var tunnelId: String
        get() = p.getString("tunnel_id", "") ?: ""
        set(v) = p.edit { putString("tunnel_id", v) }

    /**
     * 確定 (送信・投稿など) の前に、指紋などの本人確認をするか (最初はオン)。
     * 承認画面を出すか (approvalScreen) と組み合わせる
     */
    var approvalBiometric: Boolean
        get() = p.getBoolean("approval_biometric", true)
        set(v) = p.edit { putBoolean("approval_biometric", v) }

    /**
     * 確定の前に承認画面 (内容を見て「確認して実行」) を出すか (最初はオフ: 指紋などだけで承認する)。
     * 本人確認と組み合わせる: 画面オン・確認オン = 画面のあと指紋など / 画面オフ・確認オン = 確定しようとしている画面のまま指紋など /
     * 画面オン・確認オフ = 画面で「確認して実行」 / 画面オフ・確認オフ = 承認なしでそのまま確定
     */
    var approvalScreen: Boolean
        get() = p.getBoolean("approval_screen", false)
        set(v) = p.edit { putBoolean("approval_screen", v) }

    /** 端末の操作の方式 (最初は ADB のみ) */
    var controlMode: ControlMode
        get() = ControlMode.entries.firstOrNull { it.id == p.getString("control_mode", null) } ?: ControlMode.ADB
        set(v) = p.edit { putString("control_mode", v.id) }

    /**
     * 画面の画像を AI に渡してよいか (最初はオン。設定で切れる)。get_screenshot (ChatGPT へ) と、
     * look_at_screen (画面の理解の AI へ。ChatGPT でも Claude でも) の両方を止める
     */
    var allowScreenshots: Boolean
        get() = p.getBoolean("allow_screenshots", true)
        set(v) = p.edit { putBoolean("allow_screenshots", v) }

    /** 画面の理解 (look_at_screen) で、画面の画像を読む AI (最初は ChatGPT) */
    var visionProvider: VisionProvider
        get() = VisionProvider.from(p.getString("vision_provider", null))
        set(v) = p.edit { putString("vision_provider", v.id) }

    /** 画面の理解で Claude を使うときのモデル。API キーは SecureStore に別に置く (ここには置かない) */
    var claudeModel: ClaudeModel
        get() = ClaudeModel.from(p.getString("claude_model", null))
        set(v) = p.edit { putString("claude_model", v.id) }

    /** ChatGPT の音声モードの真ん中に、リゲルを重ねる */
    var voiceStage: Boolean
        get() = p.getBoolean("voice_stage", true)
        set(v) = p.edit { putBoolean("voice_stage", v) }

    /**
     * リゲルのタップで開いた音声モードに、最初の一言 (この会話では ENVOY MCP を使う、と伝える文) を文字で送る
     * (最初はオン。ユーザー補助が要る)
     */
    var voicePrimer: Boolean
        get() = p.getBoolean("voice_primer", true)
        set(v) = p.edit { putBoolean("voice_primer", v) }

    /** 最初の一言の文 (空にすると送らない) */
    var voicePrimerText: String
        get() = p.getString("voice_primer_text", null) ?: VoicePrimer.DEFAULT_TEXT
        set(v) = p.edit { putString("voice_primer_text", v) }

    /**
     * モニター (PC モード) につないだら、モニターに全画面のリゲルを出し、ChatGPT の道具の操作先をモニターにする
     * (最初はオン)
     */
    var monitorStage: Boolean
        get() = p.getBoolean("monitor_stage", true)
        set(v) = p.edit { putBoolean("monitor_stage", v) }

    /** 重ねたリゲルを ChatGPT の声に合わせて動かす (試験。端末の出力の波形を読むため、マイクの許可が要る。最初はオフ) */
    var voiceStageLevel: Boolean
        get() = p.getBoolean("voice_stage_level", false)
        set(v) = p.edit { putBoolean("voice_stage_level", v) }

    /** 操作中、他のアプリの上にリゲルを出す */
    var bubbleEnabled: Boolean
        get() = p.getBoolean("bubble", true)
        set(v) = p.edit { putBoolean("bubble", v) }
    var bubbleX: Float
        get() = p.getFloat("bubble_x", -1f)
        set(v) = p.edit { putFloat("bubble_x", v) }
    var bubbleY: Float
        get() = p.getFloat("bubble_y", -1f)
        set(v) = p.edit { putFloat("bubble_y", v) }

    /**
     * AgentARM (スマホを載せる腕) を BLE でつないで動かすか (本人が設定でオンにしたときだけ。最初はオフ)。
     * オンのときは、MCP サーバーが動いている間、腕とつながり、マグネット充電器の付け外しで腕を動かす
     */
    var armEnabled: Boolean
        get() = p.getBoolean("arm_enabled", false)
        set(v) = p.edit { putBoolean("arm_enabled", v) }

    /**
     * 音楽が流れたら、AgentARM が自動で踊る (本人が設定でオンにしたときだけ。最初はオフ)。
     * スマホが腕に付いていて (ワイヤレス充電中)、顔を追っていない・通話をしていないときだけ (ChatGPT との音声の会話の間は踊る)
     */
    var armAutoDance: Boolean
        get() = p.getBoolean("arm_auto_dance", false)
        set(v) = p.edit { putBoolean("arm_auto_dance", v) }

    /** 音楽が流れたら、リゲルの画面 (まわりにカラフルなスペクトラム) を出す (最初はオン) */
    var musicStage: Boolean
        get() = p.getBoolean("music_stage", true)
        set(v) = p.edit { putBoolean("music_stage", v) }

    /** 音楽の画面で、拍に合わせて光らせる (オフでも、スペクトラムとヘッドホンは出る。光に敏感な人のため。最初はオン) */
    var musicFlash: Boolean
        get() = p.getBoolean("music_flash", true)
        set(v) = p.edit { putBoolean("music_flash", v) }

    /**
     * 音楽の画面に歌詞を出す (LRCLIB から、曲名・アーティスト・長さで時刻付きの歌詞を取る。外のサービスに送るのは曲名などだけ。
     * 歌詞が後ろに降ってくるキネティック・タイポグラフィ。最初はオン)
     */
    var musicLyrics: Boolean
        get() = p.getBoolean("music_lyrics", true)
        set(v) = p.edit { putBoolean("music_lyrics", v) }

    /**
     * 呼び名 (「ねえ、リゲル」) で起きたら、ChatGPT の音声モードが開くまでの間、リゲルがランダムな表情を見せてから
     * 利用者の方を向く (音声モードが開くまでの間をつなぐ。最初はオン)
     */
    var wakeGreet: Boolean
        get() = p.getBoolean("wake_greet", true)
        set(v) = p.edit { putBoolean("wake_greet", v) }

    /**
     * 呼び名のしきい値 (ボードが「本人のリゲル」とみなす声の近さ。低いほど反応しやすく、ほかの人・似た言葉にも反応しやすい)。
     * 腕に送り (obs voice の wth)、ボードの聞き取りがすぐ使う
     */
    var wakeThreshold: Float
        get() = p.getFloat("wake_threshold", 0.58f)
        set(v) = p.edit { putFloat("wake_threshold", v) }

    /** リゲルが代わりに電話するとき、「〇〇さんの代わりに」と名乗る利用者の呼び名 (ChatGPT が caller を渡さないとき) */
    var rigelCallerName: String
        get() = p.getString("rigel_caller_name", "") ?: ""
        set(v) = p.edit { putString("rigel_caller_name", v.trim()) }

    /**
     * かかってきた電話にリゲルが代わりに出るか、本人に選ばせる方法。
     * off: しない / buttons: 着信の画面に「リゲルが代わりに出る」「自分で出る」を重ねる / voice: ChatGPT の音声モードを開いて尋ねる
     */
    var rigelIncoming: String
        get() = p.getString("rigel_incoming", "buttons") ?: "buttons"
        set(v) = p.edit { putString("rigel_incoming", v) }

    /** 設定の画面で開いているカードの見出し (項目が多いので、カードを折りたたむ。最初は全部閉じる) */
    var settingsOpen: Set<String>
        get() = p.getStringSet("settings_open", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit { putStringSet("settings_open", v) }

    /**
     * かかってきた電話に、本人が出ず選びもしないまま rigelAutoAnswerSec 秒たったら、リゲルが代わりに出るか
     * (最初はオフ。代わりに出るかを選ばせる設定 rigelIncoming が「しない」のときは出ない)
     */
    var rigelAutoAnswer: Boolean
        get() = p.getBoolean("rigel_auto_answer", false)
        set(v) = p.edit { putBoolean("rigel_auto_answer", v) }

    /** 着信から自動で出るまでの秒数 (留守番電話につながるより短く) */
    var rigelAutoAnswerSec: Int
        get() = p.getInt("rigel_auto_answer_sec", 10)
        set(v) = p.edit { putInt("rigel_auto_answer_sec", v) }

    /**
     * リゲルが代わりに出た電話のあと、ChatGPT の音声モードを開いて用件を伝えさせるか (最初はオフ。
     * オフなら通話の記録に残すだけ)
     */
    var rigelIncomingReport: Boolean
        get() = p.getBoolean("rigel_incoming_report", false)
        set(v) = p.edit { putBoolean("rigel_incoming_report", v) }

    /**
     * リゲルが代わりに電話している間、電話のアプリの画面の番号をモザイクで隠すか (NumberMask)。代行中はリゲルの通話の画面 (CallScreen) で
     * 覆うので、最初はオフ
     */
    var numberMask: Boolean
        get() = p.getBoolean("number_mask", false)
        set(v) = p.edit { putBoolean("number_mask", v) }

    /** リゲルが代わりにかけた・出た電話を録音して、通話の記録に残すか (相手には伝えない。最初はオン) */
    var rigelRecord: Boolean
        get() = p.getBoolean("rigel_record", true)
        set(v) = p.edit { putBoolean("rigel_record", v) }

    /** 前につないだ AgentARM の Bluetooth のアドレス (次は探さずにつなぐ。秘密ではない) */
    var armAddress: String
        get() = p.getString("arm_address", "") ?: ""
        set(v) = p.edit { putString("arm_address", v) }

    /** ホーム画面のリゲルを 3D で描く */
    var avatar3d: Boolean
        get() = p.getBoolean("avatar_3d", true)
        set(v) = p.edit { putBoolean("avatar_3d", v) }

    /** アバターの見た目 */
    val avatar: String get() = "orb"

    companion object {
        const val DEFAULT_PORT = 8765
        const val TOKEN_LENGTH = 32
        private const val TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        /** 新しい合言葉 (本体の合言葉と、足したエージェントの合言葉で同じ作り方・同じ長さ) */
        fun randomToken(): String {
            val r = SecureRandom()
            return (1..TOKEN_LENGTH).map { TOKEN_CHARS[r.nextInt(TOKEN_CHARS.length)] }.joinToString("")
        }
    }
}

/**
 * 端末の操作の方式。
 * - ADB: ワイヤレスデバッグで、この端末の adbd にシェルのコマンドを送る (Wi-Fi が要る)
 * - HYBRID: 画面を読むのは ADB (漏れなし)、押す・入力・前面のアプリの判定はユーザー補助。どちらかが使えなければもう一方。
 *   ChatGPT の強制停止は ADB がつながっていれば ADB
 * - A11Y: ユーザー補助だけ (Wi-Fi もペアリングも要らない。他のアプリの強制停止はできない)
 */
enum class ControlMode(val id: String, val label: String) {
    ADB("adb", "ADB のみ"),
    HYBRID("hybrid", "ADB + ユーザー補助"),
    A11Y("a11y", "ユーザー補助のみ"),
}

/** 画面の理解 (look_at_screen) で画面の画像を読む AI。ChatGPT は Sign in with ChatGPT、Claude は本人の Anthropic の API キー */
enum class VisionProvider(val id: String, val label: String) {
    CHATGPT("chatgpt", "ChatGPT（Sign in with ChatGPT）"),
    CLAUDE("claude", "Claude（API キー）");

    companion object {
        /** 保存した値から (無い・知らない値なら ChatGPT) */
        fun from(id: String?): VisionProvider = entries.firstOrNull { it.id == id } ?: CHATGPT
    }
}

/** 画面の理解で使う Claude のモデル。effort は考える深さ (output_config.effort) を指定できるか */
enum class ClaudeModel(val id: String, val label: String, val effort: Boolean) {
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5（おすすめ）", true),
    OPUS("claude-opus-5-5", "Claude Opus 5.5（いちばん賢い・遅め）", true),
    HAIKU("claude-haiku-4-5-20251001", "Claude Haiku 4.5（速い・安い）", false);

    companion object {
        /** 保存した値から (無い・知らない値なら Sonnet) */
        fun from(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: SONNET
    }
}
