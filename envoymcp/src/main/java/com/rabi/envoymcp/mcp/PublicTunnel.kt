package com.rabi.envoymcp.mcp

import android.content.Context
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 外 (Claude のコネクターなど) から、このスマホの MCP サーバーへつなぐ中継 (ngrok)。アプリに同梱した実行ファイル
 * (scripts/build_ngrok_tunnel.sh で tunnel/ngrok からビルドした libngroktunnel.so。ngrok-go、MIT) を子プロセスとして動かす。
 * - ngrok の決まったドメイン (https://<ドメイン>) に届いたものを、http://127.0.0.1:<ポート> へ取り次ぐ (Host ヘッダーはそのまま)
 * - 認証トークンは環境変数でだけ渡す (引数・ファイルには書かない)
 * - 記録はメモリに少しだけ持ち、トークンらしきものは伏せる
 * - アプリが終わったら中継も終わる (標準入力が閉じたら終わるように頼む)。落ちたら間を空けて動かし直す (止めるまで)
 */
class PublicTunnel(private val context: Context) {
    /** token は ngrok の認証トークン (toString には出さない) */
    data class Config(val token: String, val domain: String, val port: Int) {
        override fun toString() = "Config(domain=$domain, port=$port)"
    }

    sealed class State(val label: String) {
        data object Off : State("止まっています")
        data class NotReady(val why: String) : State("動かせません: $why")
        data object Starting : State("つないでいます")
        data object Ready : State("つながっています")
        data class Trouble(val why: String) : State("うまくいっていません: $why")

        /** 状態の色: つながっていれば true、止まっている・つないでいる途中は null、それ以外は false */
        val ok: Boolean? get() = when (this) { Ready -> true; Off, Starting -> null; else -> false }
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** 最近の記録 (新しいものが先頭。トークンらしきものは伏せてある) */
    val log: StateFlow<List<String>> = _log
    /** 最後に外からの依頼を MCP サーバーへ取り次いだ時刻 */
    @Volatile var lastRequestAt = 0L; private set

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libngroktunnel.so")
    fun installed() = binary.exists()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var current: Config? = null
    @Volatile private var process: Process? = null

    // 記録から読み取った様子 (状態の判断に使う)。動かし直すたびに番号を変え、止めた古い方の行では変えない
    @Volatile private var generation = 0
    @Volatile private var ready = false
    /** ngrok とのつながりの失敗 (次に READY が来るまで出し続ける) */
    @Volatile private var fault: String? = null
    /** MCP サーバーに届かない (次にうまく取り次げるまで) */
    @Volatile private var upstreamDown = false

    /** 動かす (同じ設定で動いていれば何もしない。設定が変わっていれば動かし直す) */
    @Synchronized fun start(cfg: Config) {
        if (job?.isActive == true && cfg == current) return
        stop()
        current = cfg
        job = scope.launch {
            var backoff = FIRST_BACKOFF_MS
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                val code = runCatching { runOnce(cfg) }.getOrElse { e ->
                    RabiLog.w("ngrok", "start failed: ${e.javaClass.simpleName}")
                    addLog("起動できませんでした (${e.javaClass.simpleName})"); -1
                }
                if (!isActive) break
                if (System.currentTimeMillis() - startedAt > STABLE_MS) backoff = FIRST_BACKOFF_MS
                // トークン・アカウントの問題は、すぐに試し直しても直らないので長めに待つ
                if (code == EXIT_ACCOUNT) backoff = backoff.coerceAtLeast(ACCOUNT_BACKOFF_MS)
                val why = fault ?: "中継が終わりました (終了コード $code)"
                _state.value = State.Trouble("$why。${backoff / 1000} 秒後に動かし直します")
                delay(backoff)
                backoff = (backoff * 3).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** 動かせない理由を出す (動いていれば止める) */
    @Synchronized fun notReady(why: String) { stop(); _state.value = State.NotReady(why) }

    @Synchronized fun stop() {
        job?.cancel(); job = null; current = null
        process?.let { p ->
            // 中継は止める合図を受けると、ngrok との接続を切ってから終わる (次にすぐ同じドメインを使えるように)
            p.destroy()
            scope.launch { if (!p.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
        process = null
        _state.value = State.Off
    }

    /** 1 回動かし、終わるまで記録を読む。終了コードを返す */
    private fun runOnce(cfg: Config): Int {
        val dir = File(context.noBackupFilesDir, "ngrok").apply { mkdirs() }
        // 標準入力はつないだままにする (アプリが終わって閉じたら、中継も終わる)
        val pb = ProcessBuilder(binary.absolutePath, "-domain", cfg.domain, "-port", cfg.port.toString(), "-watch-stdin")
            .directory(dir).redirectErrorStream(true)
        val env = pb.environment()
        // 端末の環境にあるかもしれない別のトークンや設定は使わせない
        env.keys.filter { it.startsWith("NGROK_") || it == "MCP_PORT" }.toList().forEach { env.remove(it) }
        env["HOME"] = dir.path
        env["TMPDIR"] = context.cacheDir.path
        env["NGROK_AUTHTOKEN"] = cfg.token
        val gen = ++generation
        ready = false; fault = null; upstreamDown = false
        _state.value = State.Starting
        RabiLog.i("ngrok", "starting public tunnel")
        val p = pb.start()
        process = p
        try {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine(it, gen, cfg.token) } }
            return p.waitFor()
        } finally {
            if (p.isAlive) p.destroy()
            if (process === p) process = null
        }
    }

    private fun onLine(line: String, gen: Int, token: String) {
        addLog(NgrokLog.redact(line, token))
        // 止めた古い方が最後に出す行で、今の状態を変えない
        if (gen != generation) return
        when (val e = NgrokLog.event(line)) {
            is NgrokLog.Event.Ready -> { ready = true; fault = null }
            is NgrokLog.Event.Retry -> { ready = false; fault = e.why }
            is NgrokLog.Event.Failed -> { ready = false; fault = e.why }
            is NgrokLog.Event.Forwarded -> {
                lastRequestAt = System.currentTimeMillis()
                if (e.status in 1..499) upstreamDown = false
            }
            NgrokLog.Event.NoUpstream -> upstreamDown = true
            null -> return
        }
        evaluate()
    }

    /** 記録から読み取った様子で、状態を決める */
    private fun evaluate() {
        if (process == null || job?.isActive != true) return
        val f = fault
        _state.value = when {
            f != null -> State.Trouble(f)
            upstreamDown -> State.Trouble("ENVOY MCP に届きません")
            ready -> State.Ready
            else -> State.Starting
        }
    }

    private fun addLog(s: String) = _log.update { (listOf("${LocalTime.now().format(CLOCK)} $s") + it).take(MAX_LOG) }

    companion object {
        private const val FIRST_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val ACCOUNT_BACKOFF_MS = 60_000L
        /** これより長く動いていたら、次に落ちたときの待ちを最初に戻す */
        private const val STABLE_MS = 300_000L
        private const val STOP_WAIT_MS = 3_000L
        private const val MAX_LOG = 200
        /** 中継の終了コード: 認証トークンかアカウントの問題 */
        private const val EXIT_ACCOUNT = 3
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** 外からつなぐ中継が出す行の読み取りと伏せ方 (試験のため Android と切り離してある) */
internal object NgrokLog {
    sealed interface Event {
        /** つながった (つなぎ直せたときも) */
        data class Ready(val url: String) : Event
        /** ngrok とのつながりが切れた。中継がつなぎ直している */
        data class Retry(val why: String) : Event
        /** 続けられない (このあと中継が終わる) */
        data class Failed(val why: String) : Event
        /** 外からの依頼を取り次いだ (status は MCP サーバーの答えの番号) */
        data class Forwarded(val status: Int) : Event
        /** MCP サーバーに届かない */
        data object NoUpstream : Event
    }

    fun event(line: String): Event? {
        val rest = line.substringAfter(' ', "").trim()
        return when (line.substringBefore(' ')) {
            "READY" -> Event.Ready(rest)
            "RETRY" -> Event.Retry(reason(rest))
            "ERROR" -> Event.Failed(reason(rest))
            "REQ" -> Event.Forwarded(rest.split(' ').getOrNull(2)?.toIntOrNull() ?: 0)
            "NOUPSTREAM" -> Event.NoUpstream
            else -> null
        }
    }

    private val CODE = Regex("ERR_NGROK_\\d+")
    private val NET = Regex("(?i)failed to dial|dial tcp|no such host|i/o timeout|network is unreachable|connection (?:refused|reset)|" +
        "session closed|heartbeat timeout|failed to send authentication request|\\bEOF\\b")

    /** 中継が出した理由を、やさしい言葉にする */
    fun reason(rest: String): String {
        val code = CODE.find(rest)?.value
        return when {
            rest.startsWith("NO_TOKEN") -> "ngrok の認証トークンがありません"
            rest.startsWith("BAD_DOMAIN") -> "ドメインの形が違います"
            rest.startsWith("BAD_PORT") -> "MCP サーバーのポートが違います"
            rest.startsWith("ENDPOINT_CLOSED") -> "ngrok の入口が閉じられました"
            code in TOKEN_CODES || (code == null && "authentication failed" in rest) -> "ngrok の認証トークンが違います"
            code == "ERR_NGROK_108" -> "同じ ngrok のアカウントで、ほかの所でも動いています (無料のプランは 1 か所だけ)"
            code == "ERR_NGROK_334" -> "このドメインは、ほかの所でもう使われています"
            code != null -> "ngrok から断られました ($code)"
            NET.containsMatchIn(rest) -> "ngrok につながりません (ネットを確かめてください)"
            else -> "中継がうまくいっていません"
        }
    }

    private val TOKEN_CODES = setOf("ERR_NGROK_105", "ERR_NGROK_107", "ERR_NGROK_4018")

    /** ngrok の認証トークンの形 (数字で始まり、途中に _ がひとつ) */
    private val TOKEN_LIKE = Regex("\\b[0-9][A-Za-z0-9]{20,}_[A-Za-z0-9]{10,}\\b")
    private val BEARER = Regex("(?i)bearer\\s+[^\\s\"]+")
    /** 長くてでたらめな文字の並び (合言葉・トークンのたぐい) */
    private val LONG = Regex("[A-Za-z0-9_\\-]{40,}")

    /** 画面に出す形: 保存した認証トークン・トークンらしきもの・合言葉を伏せ、長すぎる行を切る */
    fun redact(line: String, token: String? = null): String {
        var s = line
        if (!token.isNullOrEmpty()) s = s.replace(token, "…")
        return s.replace(TOKEN_LIKE, "…").replace(BEARER, "Bearer …").replace(LONG, "…").take(MAX_LINE)
    }

    private const val MAX_LINE = 300
}

/** 外からつなぐ設定の形 (試験のため Android と切り離してある) */
internal object NgrokSetup {
    /** Claude のカスタムコネクターに貼る URL (秘密ではない)。ドメインが無い・形が違うときは null */
    fun connectorUrl(domain: String): String? = domainOf(domain)?.let { "https://$it/mcp" }

    private val HOST = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    /** 入れた文字からドメインだけを取り出す (https:// や / の後ろは落とし、小文字にする)。形が違えば null */
    fun domainOf(input: String): String? {
        val s = input.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
        return s.takeIf { it.length <= 253 && HOST.matches(it) }
    }

    /**
     * クリップボードの文字から認証トークンを取り出す (前後の空白は落とす)。
     * ngrok のページの「ngrok config add-authtoken <トークン>」をそのまま写したときは、最後の部分を使う
     */
    fun tokenFrom(text: String?): String {
        val t = text?.trim().orEmpty()
        return if ("add-authtoken" in t) t.split(Regex("\\s+")).last() else t
    }

    /** 認証トークンの形でなければ、その一言 (形が合えば null) */
    fun tokenProblem(token: String): String? = when {
        token.isEmpty() -> "クリップボードが空です。ngrok のページで認証トークンをコピーしてから押してください"
        token.length !in MIN_TOKEN..MAX_TOKEN || token.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-') } ->
            "クリップボードの中身が、ngrok の認証トークンの形ではありません"
        else -> null
    }

    /** 画面に出すトークンの印 (最後の 4 文字だけ) */
    fun tail(token: String): String = "…" + token.takeLast(4)

    private const val MIN_TOKEN = 20
    private const val MAX_TOKEN = 200
}
