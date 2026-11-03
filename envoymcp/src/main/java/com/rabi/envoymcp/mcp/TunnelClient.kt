package com.rabi.envoymcp.mcp

import android.content.Context
import com.rabi.launcher.core.RabiLog
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
import java.util.concurrent.TimeUnit

/**
 * スマホの中の tunnel-client (OpenAI の Secure MCP Tunnel の中継、Apache 2.0)。アプリに同梱した実行ファイル
 * (scripts/build_tunnel_client.sh でビルドした libtunnelclient.so) を子プロセスとして動かす。
 * - 実行用キーは環境変数でだけ渡す (引数・ファイルには書かない)。合言葉は Authorization ヘッダーで ENVOY MCP に渡させる
 * - 状態確認の口は TCP を開けず、アプリ専用の場所の Unix ソケットにする (同じ端末の他のアプリから届かないように)
 * - 記録はメモリに少しだけ持ち、キーらしきものは伏せる
 * - 落ちたら間を空けて動かし直す (止めるまで)
 */
class TunnelClient(private val context: Context) {
    data class Config(val apiKey: String, val tunnelId: String, val port: Int, val token: String)

    sealed class State(val label: String) {
        data object Off : State("止まっています")
        data class NotReady(val why: String) : State("動かせません: $why")
        data object Starting : State("つないでいます")
        data object Ready : State("つながっています")
        data class Trouble(val why: String) : State("うまくいっていません: $why")
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** 最近の記録 (新しいものが先頭。キーらしきものは伏せてある) */
    val log: StateFlow<List<String>> = _log
    /** 最後に ChatGPT からの依頼を ENVOY MCP へ取り次いだ時刻 */
    @Volatile var lastForwardAt = 0L; private set

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libtunnelclient.so")
    fun installed() = binary.exists()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var current: Config? = null
    @Volatile private var process: Process? = null

    // 記録から読み取った様子 (状態の判断に使う)。動かし直すたびに番号を変え、止めた古い方の行では変えない
    @Volatile private var generation = 0
    @Volatile private var pollerStartedAt = 0L
    @Volatile private var mcpOk: Boolean? = null
    @Volatile private var pollFailAt = 0L
    @Volatile private var pollFailWhy = ""
    @Volatile private var badKey = false

    /** 動かす (同じ設定で動いていれば何もしない。設定が変わっていれば動かし直す) */
    @Synchronized fun start(cfg: Config) {
        if (job?.isActive == true && cfg == current) return
        stop()
        current = cfg
        job = scope.launch {
            // 記録が来ない間も、時間がたてば状態を見直す (つながったまま静かなとき など)
            launch { while (isActive) { delay(TICK_MS); evaluate() } }
            var backoff = FIRST_BACKOFF_MS
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                val code = runCatching { runOnce(cfg) }.getOrElse { e ->
                    RabiLog.w("tunnel", "start failed: ${e.javaClass.simpleName}")
                    addLog("起動できませんでした (${e.javaClass.simpleName})"); -1
                }
                if (!isActive) break
                if (System.currentTimeMillis() - startedAt > STABLE_MS) backoff = FIRST_BACKOFF_MS
                val why = if (badKey) "実行用キーが違います (401)" else "tunnel-client が終わりました (終了コード $code)"
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
            p.destroy()
            scope.launch { if (!p.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
        process = null
        _state.value = State.Off
    }

    /** 1 回動かし、終わるまで記録を読む。終了コードを返す */
    private fun runOnce(cfg: Config): Int {
        val dir = File(context.noBackupFilesDir, "tunnel").apply { mkdirs() }
        val sock = File(dir, "health.sock").apply { delete() }
        val pb = ProcessBuilder(binary.absolutePath, "run").directory(dir).redirectErrorStream(true)
        val env = pb.environment()
        // 端末の環境にあるかもしれない別のキーや設定は使わせない
        env.keys.filter { it.startsWith("OPENAI_") || it.startsWith("CONTROL_PLANE_") || it.startsWith("MCP_") }.toList().forEach { env.remove(it) }
        env["HOME"] = dir.path
        env["TMPDIR"] = context.cacheDir.path
        env["CONTROL_PLANE_API_KEY"] = cfg.apiKey
        env["CONTROL_PLANE_TUNNEL_ID"] = cfg.tunnelId
        env["MCP_SERVER_URL"] = "http://127.0.0.1:${cfg.port}/mcp"
        env["ENVOY_MCP_AUTH"] = "Bearer ${cfg.token}"
        env["MCP_EXTRA_HEADERS"] = "Authorization: env:ENVOY_MCP_AUTH"
        env["MCP_DISCOVERY_EXTRA_HEADERS"] = "Authorization: env:ENVOY_MCP_AUTH"
        env["HEALTH_UNIX_SOCKET"] = sock.path
        val gen = ++generation
        pollerStartedAt = 0L; mcpOk = null; pollFailAt = 0L; pollFailWhy = ""; badKey = false
        _state.value = State.Starting
        RabiLog.i("tunnel", "starting tunnel-client")
        val p = pb.start()
        process = p
        try {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine(it, gen) } }
            return p.waitFor()
        } finally {
            if (p.isAlive) p.destroy()
            if (process === p) process = null
        }
    }

    private fun onLine(line: String, gen: Int) {
        addLog(TunnelLog.redact(line))
        // 止めた古い方が最後に出す行 (接続を切った などの失敗) で、今の状態を変えない
        if (gen != generation) return
        val now = System.currentTimeMillis()
        when (TunnelLog.event(line)) {
            TunnelLog.Event.POLLING -> pollerStartedAt = now
            TunnelLog.Event.MCP_READY -> mcpOk = true
            TunnelLog.Event.MCP_FAILED -> mcpOk = false
            TunnelLog.Event.BAD_KEY -> { badKey = true; pollFailAt = now; pollFailWhy = "実行用キーが違います (401)" }
            TunnelLog.Event.POLL_FAILED -> { pollFailAt = now; pollFailWhy = "OpenAI につながりません" }
            TunnelLog.Event.RECOVERED -> { pollFailAt = 0L; badKey = false }
            TunnelLog.Event.FORWARDED -> { lastForwardAt = now; badKey = false; pollFailAt = 0L; mcpOk = true }
            null -> return
        }
        evaluate()
    }

    /** 記録から読み取った様子で、状態を決める */
    private fun evaluate() {
        if (process == null || job?.isActive != true) return
        val now = System.currentTimeMillis()
        _state.value = when {
            // OpenAI への接続の失敗は、回復の行 (poller recovered) か取り次ぎが来るまで出し続ける
            badKey || pollFailAt > 0 -> State.Trouble(pollFailWhy)
            mcpOk == false -> State.Trouble("ENVOY MCP に届きません")
            lastForwardAt > 0 && lastForwardAt > pollerStartedAt -> State.Ready
            pollerStartedAt > 0 && mcpOk == true && now - pollerStartedAt > SETTLE_MS -> State.Ready
            else -> State.Starting
        }
    }

    private fun addLog(s: String) = _log.update { (listOf(s) + it).take(MAX_LOG) }

    companion object {
        private const val FIRST_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        /** これより長く動いていたら、次に落ちたときの待ちを最初に戻す */
        private const val STABLE_MS = 300_000L
        private const val STOP_WAIT_MS = 3_000L
        private const val TICK_MS = 5_000L
        /** 起動してから、失敗が出なければ「つながった」とみなすまで */
        private const val SETTLE_MS = 15_000L
        private const val MAX_LOG = 200
    }
}

/** tunnel-client の記録の読み取り (試験のため Android と切り離してある) */
internal object TunnelLog {
    enum class Event { POLLING, MCP_READY, MCP_FAILED, BAD_KEY, POLL_FAILED, RECOVERED, FORWARDED }

    fun event(line: String): Event? = when {
        "dispatcher forwarded command to MCP server" in line -> Event.FORWARDED
        "mcp session initialized" in line -> Event.MCP_READY
        "failed to connect to mcp" in line -> Event.MCP_FAILED
        "poll failed" in line && ("status_code=401" in line || "invalid_api_key" in line) -> Event.BAD_KEY
        "poll failed" in line -> Event.POLL_FAILED
        "poller recovered" in line -> Event.RECOVERED
        "poller started" in line -> Event.POLLING
        else -> null
    }

    private val SK = Regex("sk-[A-Za-z0-9_*\\-]+")
    private val BEARER = Regex("(?i)bearer\\s+[^\\s\"]+")
    private val NOISE = Regex(" client_instance_id=\\S+| tunnel_id=\\S+")

    /** 画面に出す形: キーらしきもの・合言葉を伏せ、毎行同じ識別子を除いて短くする */
    fun redact(line: String): String = line
        .replace(SK, "sk-…")
        .replace(BEARER, "Bearer …")
        .replace(NOISE, "")
        .take(MAX_LINE)

    private const val MAX_LINE = 300
}
