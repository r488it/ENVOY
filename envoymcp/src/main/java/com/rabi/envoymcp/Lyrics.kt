package com.rabi.envoymcp

import android.content.Context
import com.rabi.launcher.core.RabiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt

/** 歌詞の 1 行 (曲の頭からの時刻 ms と、文。空の文は間奏の印) */
data class LyricLine(val ms: Long, val text: String)

/** 時刻付きの歌詞 (LRC) を読む・時刻から行を探す・曲名を検索向けにする。どれも端末の中だけの計算 */
object Lyrics {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val CREDIT = Regex("""^(作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|監製|Lyrics?\s*by|Written\s*by|Composed\s*by|Arranged\s*by|Produced\s*by|Composer|Lyricist|Arranger|Producer)\s*[:：]""", RegexOption.IGNORE_CASE)
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** LRC を読む。1 行にいくつ時刻があってもよい。[offset:+n] は n ms 早める。時刻の順に並べる */
    fun parseLrc(lrc: String): List<LyricLine> {
        val offset = OFFSET.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            val times = TIME.findAll(line).toList()
            if (times.isEmpty()) continue
            val text = WORD_TIME.replace(TIME.replace(line, ""), "").trim()
            if (CREDIT.containsMatchIn(text)) continue                 // 「作詞 : 〜」などのクレジットは歌詞にしない
            for (m in times) {
                val (mm, ss, frac) = m.destructured
                val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LyricLine((mm.toLong() * 60_000 + ss.toLong() * 1_000 + f - offset).coerceAtLeast(0), text)
            }
        }
        out.sortBy { it.ms }
        return out
    }

    /** 時刻 ms に歌っている行の番号 (最初の行より前なら -1) */
    fun lineAt(lines: List<LyricLine>, ms: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lines[mid].ms <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    /** 行 i を歌っている長さ (次の行まで。最後の行や長い間奏は MAX_LINE_MS まで) */
    fun spanMs(lines: List<LyricLine>, i: Int): Long {
        val next = lines.getOrNull(i + 1)?.ms ?: (lines[i].ms + MAX_LINE_MS)
        return (next - lines[i].ms).coerceIn(MIN_LINE_MS, MAX_LINE_MS)
    }

    /** 曲名を検索向けにする: 「(Official Music Video)」「【MV】」「feat. 〜」などを外す。「アーティスト - 曲名」なら曲名だけ */
    fun cleanTitle(title: String, artist: String = ""): String {
        var s = title.trim()
        val a = cleanArtist(artist)
        val dash = s.indexOf(" - ")
        if (dash > 0 && (a.isBlank() || s.substring(0, dash).trim().equals(a, ignoreCase = true))) s = s.substring(dash + 3)
        s = BRACKET_EXTRA.replace(s, " ")
        s = FEAT.replace(s, "")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.ifBlank { title.trim() }
    }

    /** アーティストを検索向けにする: YouTube の「〜 - Topic」を外し、何人もいれば最初の人だけ */
    fun cleanArtist(artist: String): String {
        val s = artist.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace(Regex("""VEVO$"""), "").trim()
        return s.split(Regex("""\s*(,|、|&|＆|/|\s+x\s+|\s+×\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)).first().trim().ifBlank { s }
    }

    /** 歌詞の候補 (LRCLIB の答え 1 つ) */
    data class Candidate(val durationS: Double, val synced: String?, val plain: String?, val instrumental: Boolean)

    fun candidateOf(o: JSONObject) = Candidate(
        o.optDouble("duration", 0.0),
        o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() },
        o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() },
        o.optBoolean("instrumental", false),
    )

    /**
     * 候補から選ぶ: 時刻付きのもので、長さが近い (4 秒以内) もの。無ければ 10 秒以内でいちばん近いもの
     * (YouTube Music と LRCLIB で、版の違いで長さが少しずれることがある)。曲の長さが分からなければ最初の時刻付きのもの
     */
    fun pickBest(cands: List<Candidate>, durationS: Double): Candidate? {
        val ok = cands.filter { it.synced != null || it.instrumental }
        if (durationS <= 0) return ok.firstOrNull()
        val diff = { c: Candidate -> if (c.durationS <= 0) 99.0 else abs(c.durationS - durationS) }
        return ok.filter { diff(it) <= 4.0 }.minByOrNull(diff) ?: ok.filter { diff(it) <= 10.0 }.minByOrNull(diff)
    }

    private val BRACKET_EXTRA = Regex(
        """[(\[【（「『<〈][^)\]】）」』>〉]*(official|music\s*video|lyric|lyrics|audio|video|\bmv\b|m/v|\bpv\b|公式|歌詞|ver\.?|version|remaster|full|short|live|\bhd\b|4k)[^)\]】）」』>〉]*[)\]】）」』>〉]""",
        RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s*[(\[（]?\s*(feat\.?|ft\.?|featuring)\s[^)\]）]*[)\]）]?\s*$""", RegexOption.IGNORE_CASE)
    const val MIN_LINE_MS = 800L
    const val MAX_LINE_MS = 7_000L
}

/**
 * 再生中の曲の、時刻付きの歌詞を LRCLIB (lrclib.net。無料・鍵なし) から取る (設定「音楽の画面に歌詞を出す」がオンで、音楽の画面が出ている間だけ)。
 * 送るのは曲名・アーティスト・アルバム・長さだけ。取ったもの (見つからなかったことも) は端末にためて、同じ曲では取りに行かない
 */
class LyricsRepo(context: Context, private val scope: CoroutineScope) {
    enum class Status { IDLE, LOADING, FOUND, NONE, INSTRUMENTAL, ERROR }
    data class State(val key: String? = null, val status: Status = Status.IDLE, val lines: List<LyricLine> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** 今の曲の歌詞を早める量 (ms。+ で早く、- で遅く。音楽の画面を左右にスワイプして直す。曲ごとに覚える) */
    private val _offset = MutableStateFlow(0L)
    val offset: StateFlow<Long> = _offset
    private val offsets = context.getSharedPreferences("envoymcp_lyric_offsets", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "lyrics")
    private val mem = HashMap<String, State>()
    private var job: Job? = null

    /** この曲の歌詞にする (null ならやめる)。メインスレッドで呼ぶ */
    fun load(t: Track?) {
        if (t == null) { job?.cancel(); _state.value = State(); return }
        val key = "${t.key}|${t.durationMs / 1000}"
        val cur = _state.value
        _offset.value = offsets.getLong(key, 0L)
        if (cur.key == key && cur.status != Status.ERROR) return
        mem[key]?.let { _state.value = it; return }
        job?.cancel()
        _state.value = State(key, Status.LOADING)
        job = scope.launch {
            // 通信できなかったときは、20 秒後に 1 回だけやり直す (LRCLIB が一時的に混んでいることがある)
            for (attempt in 0..1) {
                if (attempt > 0) delay(RETRY_MS)
                val s = withContext(Dispatchers.IO) { readCache(key) ?: fetch(t, key)?.also { writeCache(key, it) } } ?: State(key, Status.ERROR)
                if (s.status != Status.ERROR) mem[key] = s
                if (_state.value.key == key) _state.value = s
                RabiLog.i("lyrics", "${s.status} lines=${s.lines.size}")
                if (s.status != Status.ERROR) break
            }
        }
    }

    /** 1 回の問い合わせの結果 (reached: サーバーから答えがあった。404 や 503 でも true) */
    private class Reply(val body: String?, val reached: Boolean)

    /** 今の曲の歌詞を、delta ms だけ早める (- なら遅らせる)。±5 秒まで。その曲用に覚える */
    fun nudge(delta: Long) {
        val key = _state.value.key ?: return
        val v = (_offset.value + delta).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        _offset.value = v
        offsets.edit().putLong(key, v).apply()
    }

    private fun fetch(t: Track, key: String): State? {
        // 手順のどれかが失敗しても、ほかで取れた候補は使う。全部の手順で通信できなかったときだけ失敗 (null) にする
        var reached = 0
        val get = { url: String -> request(url).also { if (it.reached) reached++ }.body }
        return search(t, key, get).takeIf { reached > 0 }
    }

    private fun search(t: Track, key: String, get: (String) -> String?): State {
        val title = Lyrics.cleanTitle(t.title, t.artist)
        val artist = Lyrics.cleanArtist(t.artist)
        val dur = t.durationMs / 1000.0
        val cands = ArrayList<Lyrics.Candidate>()
        if (dur > 0) {
            val q = "track_name=${enc(title)}&artist_name=${enc(artist)}" + (if (t.album.isNotBlank()) "&album_name=${enc(t.album)}" else "") + "&duration=${dur.roundToInt()}"
            get("$BASE/api/get?$q")?.let { body -> parse("get") { cands += Lyrics.candidateOf(JSONObject(body)) } }
        }
        if (Lyrics.pickBest(cands, dur)?.synced == null) {
            get("$BASE/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")?.let { body -> parse("search") { cands += list(body) } }
        }
        // アーティスト名が長い・表記が違うと見つからないので、自由な言葉でも探す (曲名とアーティスト → 曲名だけ)
        for (q in listOf("$title $artist", title)) {
            if (Lyrics.pickBest(cands, dur)?.synced != null) break
            get("$BASE/api/search?q=${enc(q)}")?.let { body -> parse("search(q)") { cands += list(body) } }
        }
        val best = Lyrics.pickBest(cands, dur)
        return when {
            best == null -> State(key, Status.NONE)
            best.synced == null -> State(key, Status.INSTRUMENTAL)
            else -> Lyrics.parseLrc(best.synced).let { if (it.isEmpty()) State(key, Status.NONE) else State(key, Status.FOUND, it) }
        }
    }

    private fun list(body: String): List<Lyrics.Candidate> { val arr = JSONArray(body); return (0 until minOf(arr.length(), 20)).map { Lyrics.candidateOf(arr.getJSONObject(it)) } }

    /** 答えの中身が壊れていても、ほかの手順は続ける */
    private inline fun parse(step: String, block: () -> Unit) { runCatching(block).onFailure { RabiLog.w("lyrics", "$step: parse ${it.javaClass.simpleName}") } }

    /**
     * 問い合わせる。2xx なら本文、それ以外は null (404 は「無い」、そのほかは記録に残す)。
     * 混んでいる (429・5xx) ときと、つながらない・時間切れのときは、0.7 秒待って 1 回だけやり直す
     */
    private fun request(url: String): Reply {
        val step = url.substringAfter("/api/").substringBefore("?") + (if ("q=" in url) "(q)" else "")
        var reached = false
        for (attempt in 0..1) {
            if (attempt > 0) Thread.sleep(700)
            val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                c.connectTimeout = 5_000; c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", "ENVOY-MCP/0.1 (Android; personal lyrics display)")
                val code = c.responseCode
                reached = true
                if (code in 200..299) return Reply(c.inputStream.bufferedReader().use { it.readText() }, true)
                if (code == 404) return Reply(null, true)
                RabiLog.w("lyrics", "$step: http $code")
                if (code != 429 && code < 500) return Reply(null, true)
            } catch (e: Exception) {
                RabiLog.w("lyrics", "$step: ${e.javaClass.simpleName}")
            } finally { c.disconnect() }
        }
        return Reply(null, reached)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun file(key: String) = File(dir, CRC32().apply { update(key.toByteArray()) }.value.toString(16) + ".json")

    private fun readCache(key: String): State? = runCatching {
        val f = file(key); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        if (o.optString("key") != key) return null
        val st = Status.valueOf(o.getString("status"))
        if (st == Status.NONE && System.currentTimeMillis() - o.optLong("at") > NONE_TTL_MS) return null      // 見つからなかった曲は 7 日でもう一度探す
        State(key, st, if (st == Status.FOUND) Lyrics.parseLrc(o.optString("lrc")) else emptyList())
    }.getOrNull()

    private fun writeCache(key: String, s: State) {
        runCatching {
            dir.mkdirs()
            val lrc = s.lines.joinToString("\n") { l -> "[%02d:%02d.%02d]%s".format(l.ms / 60_000, l.ms / 1000 % 60, l.ms / 10 % 100, l.text) }
            file(key).writeText(JSONObject().put("key", key).put("status", s.status.name).put("at", System.currentTimeMillis()).put("lrc", lrc).toString())
        }
    }

    companion object {
        private const val BASE = "https://lrclib.net"
        private const val NONE_TTL_MS = 7L * 24 * 3600 * 1000
        private const val RETRY_MS = 20_000L
        private const val MAX_OFFSET_MS = 5_000L
    }
}
