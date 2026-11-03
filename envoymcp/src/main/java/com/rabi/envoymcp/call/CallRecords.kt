package com.rabi.envoymcp.call

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.edit
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.R
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.calllog.CallLogActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 代行の通話のやり取りの 1 行 (who: rigel・peer。at: その言葉が出始めた時刻) */
data class CallLine(val who: String, val text: String, val final: Boolean, val at: Long)

/**
 * 代行の通話 1 本 (いま話している最中のもの・保存したもの)。dir: out (かけた)・in (出た)。app: phone・line。
 * name は相手 (名前、または「登録されていない番号（末尾 1234）」。電話番号の全体は持たない)。message はかけるときに伝える用件
 */
data class CallInfo(
    val id: String,
    val dir: String,
    val app: String,
    val name: String,
    val message: String = "",
    val state: String = "preparing",
    val startedAt: Long,
    val answeredAt: Long = 0L,
    val endedAt: Long = 0L,
    val outcome: String = "",
    val summary: String = "",
    val reason: String = "",
    val lines: List<CallLine> = emptyList(),
    /** 最後に言葉が届いた時刻と、話した人 (話している最中かを見る) */
    val lastTextAt: Long = 0L,
    val lastWho: String = "",
    /** 録音のファイル (記録のフォルダの中の名前。無ければ空) */
    val audio: String = "",
    /** 詐欺の疑い (かかってきた電話で、相手の言葉から見る。ScamCheck) と、その理由 (話の種類の短い名前) */
    val risk: ScamCheck.Level = ScamCheck.Level.NONE,
    val riskReasons: List<String> = emptyList(),
) {
    val talking get() = answeredAt > 0L
    val ended get() = endedAt > 0L
    /** 話していた長さ (秒。相手が出てから) */
    val talkSec get() = if (answeredAt > 0L) (((if (endedAt > 0L) endedAt else System.currentTimeMillis()) - answeredAt) / 1000).coerceAtLeast(0) else 0

    /**
     * やり取りの言葉が届いた (ボードは、言い終えるまで、ここまでの言葉を何度も送ってくる): その人の言い終えていない行を書き換える
     * (無ければ足す)。final なら、その行を言い終えたことにする (次の言葉は新しい行)
     */
    fun withText(who: String, text: String, final: Boolean, now: Long): CallInfo {
        if (text.isBlank() || (who != "rigel" && who != "peer")) return this
        val next = lines.toMutableList()
        val i = next.indexOfLast { it.who == who && !it.final }
        if (i >= 0) next[i] = next[i].copy(text = text, final = final) else next += CallLine(who, text, final, now)
        return copy(lines = next, lastTextAt = now, lastWho = who, answeredAt = if (answeredAt == 0L) now else answeredAt)
    }

    /** 詐欺の疑いを、ここまでの相手の言葉から見直す */
    fun withRisk(): CallInfo = ScamCheck.check(lines).let { copy(risk = it.level, riskReasons = it.reasons) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("dir", dir).put("app", app).put("name", name).put("message", message)
        .put("started_at", startedAt).put("answered_at", answeredAt).put("ended_at", endedAt)
        .put("outcome", outcome).put("summary", summary).put("reason", reason).put("audio", audio)
        .put("risk", risk.name.lowercase()).put("risk_reasons", JSONArray(riskReasons))
        .put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("who", it.who).put("text", it.text).put("at", it.at)) } })

    companion object {
        fun of(o: JSONObject) = CallInfo(
            id = o.optString("id"), dir = o.optString("dir", "out"), app = o.optString("app", "phone"), name = o.optString("name"),
            message = o.optString("message"), state = "done", startedAt = o.optLong("started_at"), answeredAt = o.optLong("answered_at"),
            endedAt = o.optLong("ended_at"), outcome = o.optString("outcome"), summary = o.optString("summary"), reason = o.optString("reason"),
            audio = o.optString("audio"),
            // risk の無い記録は「なし」
            risk = ScamCheck.Level.of(o.optString("risk")),
            riskReasons = o.optJSONArray("risk_reasons")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) }
                .map { CallLine(it.optString("who"), it.optString("text"), true, it.optLong("at")) } }.orEmpty(),
        )

        fun newId(at: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(at))
    }
}

/** 結果の言葉 (RigelCaller の報告と同じ分け方) */
fun CallInfo.outcomeLabel(): String = if (dir == "in") when (outcome) {
    "message", "done", "conveyed" -> "用件を伺いました"
    "no_message" -> "用件を聞けないまま終わりました"
    "hung_up" -> "途中で切れました"
    "timeout" -> "長くなったので切りました"
    "stopped" -> "途中でやめました"
    "failed" -> "出られませんでした"
    else -> "用件がはっきりしませんでした"
} else when (outcome) {
    "conveyed", "done" -> "伝えました"
    "voicemail" -> "留守番電話に残しました"
    "refused" -> "断られました"
    "unclear" -> "伝わったか不明"
    "no_answer" -> "出ませんでした"
    "hung_up" -> "途中で切れました"
    "stopped" -> "取りやめました"
    "timeout" -> "長くなったので切りました"
    else -> "かけられませんでした"
}

fun CallInfo.outcomeOk() = outcome in setOf("conveyed", "done", "message", "voicemail")

/**
 * 代行の通話の記録 (やり取りと録音を保存して、あとから見る)。
 * アプリの中のフォルダ (files/calls) に、1 本ずつ JSON と録音 (m4a。作れなければ wav) を置く。外には送らない。消すのは本人が記録の画面で
 * - まだ見ていない記録 (記録の画面を最後に開いたあとのもの) の数を unseen に出し、新しい記録ができたら通知を出す
 *   (アプリのアイコンに点、長押しで件数。押すと記録の画面。ロック画面には中身を出さない)。記録の画面を開いたら消す
 */
class CallRecords(private val context: Context) {
    private val dir get() = File(context.filesDir, "calls").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("call_records", Context.MODE_PRIVATE)
    private val _unseen = MutableStateFlow(0)
    /** まだ見ていない記録の数 (ホームの「通話の記録」に出す) */
    val unseen: StateFlow<Int> = _unseen

    /** まだ見ていない数を数え直す (ファイルを読むので、裏で呼ぶ)。初めてのときは、今ある記録を見たことにする */
    fun refreshUnseen() {
        if (!prefs.contains(SEEN_AT)) prefs.edit { putLong(SEEN_AT, System.currentTimeMillis()) }
        val seen = prefs.getLong(SEEN_AT, 0L)
        _unseen.value = list().count { it.startedAt > seen }
    }

    /** 記録の画面を開いた: 全部見たことにして、通知を消す */
    fun markSeen() {
        prefs.edit { putLong(SEEN_AT, System.currentTimeMillis()) }
        _unseen.value = 0
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID) }
    }

    /** 新しい記録ができた: 通知を出す (同じ通知を書き換える。件数はまだ見ていない数) */
    private fun notifyNew(c: CallInfo) {
        refreshUnseen()
        val n = _unseen.value
        if (n == 0) return
        val title = (if (c.dir == "in") "リゲルが代わりに出た電話: " else "リゲルがかけた電話: ") + c.name
        val text = c.summary.ifBlank { c.outcomeLabel() }
        val open = PendingIntent.getActivity(context, 0, Intent(context, CallLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = Notification.Builder(context, McpApp.CH_CALLS).setSmallIcon(R.drawable.ic_stat_orb)
            .setContentTitle("通話の記録").setContentText("新しい記録が $n 件あります").build()
        val note = Notification.Builder(context, McpApp.CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_orb)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setNumber(n)
            .apply { if (n > 1) setSubText("新しい記録 $n 件") }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTICE_ID, note) }
            .onFailure { RabiLog.w("callrec", "notify failed: ${it.javaClass.simpleName}") }
    }

    /** 新しい順 */
    fun list(): List<CallInfo> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { CallInfo.of(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.startedAt }

    fun audioFile(c: CallInfo): File? = c.audio.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }

    /** 保存する。pcm はボードから届いた録音 (16 ビット・モノラル。無ければ null)。保存したら pcm は消す */
    fun save(c: CallInfo, pcm: File?, rate: Int): CallInfo {
        var audio = ""
        if (pcm != null && pcm.length() > 0) {
            val m4a = File(dir, "${c.id}.m4a")
            audio = if (encodeM4a(pcm, rate, m4a)) m4a.name else {
                m4a.delete()
                val wav = File(dir, "${c.id}.wav")
                if (runCatching { writeWav(pcm, rate, wav) }.isSuccess) wav.name else ""
            }
            pcm.delete()
        }
        val saved = c.copy(audio = audio, lines = c.lines.filter { it.text.isNotBlank() }.map { it.copy(final = true) })
        File(dir, "${c.id}.json").writeText(saved.toJson().toString())
        RabiLog.i("callrec", "saved ${c.dir} ${c.outcome} (${saved.lines.size} lines, audio ${if (audio.isEmpty()) "none" else audio.substringAfterLast('.')})")
        notifyNew(saved)
        return saved
    }

    fun delete(c: CallInfo) {
        File(dir, "${c.id}.json").delete()
        audioFile(c)?.delete()
        refreshUnseen()
    }

    /** 録音を m4a (AAC) にする (容量を wav の 1/10 ほどに)。うまくいかなければ false */
    private fun encodeM4a(pcm: File, rate: Int, out: File): Boolean {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, IN_CHUNK)
        }
        var codec: MediaCodec? = null
        var mux: MediaMuxer? = null
        var started = false
        return runCatching {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mux = it }
            var track = -1
            val info = MediaCodec.BufferInfo()
            val buf = ByteArray(IN_CHUNK)
            var samples = 0L
            var inputDone = false
            var outputDone = false
            val t0 = System.currentTimeMillis()
            pcm.inputStream().buffered().use { ins ->
                while (!outputDone) {
                    check(System.currentTimeMillis() - t0 < ENCODE_MAX_MS) { "encode timeout" }
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val b = c.getInputBuffer(i)!!
                            b.clear()
                            val n = readFully(ins, buf, minOf(buf.size, b.remaining()) / 2 * 2)
                            val pts = samples * 1_000_000L / rate
                            if (n <= 0) { c.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { b.put(buf, 0, n); c.queueInputBuffer(i, 0, n, pts, 0); samples += n / 2 }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = m.addTrack(c.outputFormat); m.start(); started = true }
                    else if (o >= 0) {
                        val ob = c.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); m.writeSampleData(track, ob, info) }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            started
        }.onFailure { RabiLog.w("callrec", "m4a failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
            .also {
                runCatching { codec?.stop() }; runCatching { codec?.release() }
                runCatching { if (started) mux?.stop() }; runCatching { mux?.release() }
            }.getOrDefault(false)
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) { val k = ins.read(buf, got, n - got); if (k <= 0) break; got += k }
        return got / 2 * 2
    }

    private fun writeWav(pcm: File, rate: Int, out: File) {
        val len = pcm.length().toInt()
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            fun s(v: String) = f.write(v.toByteArray(Charsets.US_ASCII))
            fun i32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            s("RIFF"); i32(36 + len); s("WAVE"); s("fmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            s("data"); i32(len)
            pcm.inputStream().use { ins -> val b = ByteArray(65536); while (true) { val k = ins.read(b); if (k <= 0) break; f.write(b, 0, k) } }
        }
    }

    private companion object {
        const val SEEN_AT = "seen_at"
        const val NOTICE_ID = 4201
        const val AAC_BITRATE = 24_000
        const val IN_CHUNK = 4096
        const val TIMEOUT_US = 10_000L
        /** 変換にかける最長 (5 分の通話で 1〜2 秒ほど。止まったら wav にする) */
        const val ENCODE_MAX_MS = 60_000L
    }
}
