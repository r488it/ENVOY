package com.rabi.envoymcp.ui.scamtrial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.call.CALL_BG
import com.rabi.envoymcp.call.CALL_DIM
import com.rabi.envoymcp.call.CALL_GREEN
import com.rabi.envoymcp.call.CALL_SUB
import com.rabi.envoymcp.call.CALL_TEXT
import com.rabi.envoymcp.call.CallLine
import com.rabi.envoymcp.call.CallTranscript
import com.rabi.envoymcp.call.CallerCircle
import com.rabi.envoymcp.call.HangupButton
import com.rabi.envoymcp.call.MiniOrb
import com.rabi.envoymcp.call.ScamBanner
import com.rabi.envoymcp.call.ScamCheck
import com.rabi.envoymcp.call.ScamTrial
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import java.util.Locale

/**
 * 詐欺の電話の試し (設定の「電話」から開く。本物の電話ではない)。試す人が電話の相手の役になって台本を声に出して読み、
 * リゲルが聞き取って、本物の電話と同じ見分け・同じ帯とやり取りの画面で、詐欺の疑いを出す。腕のボード・API キーは要らない。
 * - 聞き取りは Android の音声認識。端末の中で聞き取れるならそれを使い、使えなければ Google の音声認識を使う (画面に、どちらかを出す)
 * - 試しの言葉は、どこにも残さない (通話の記録にも入れない)
 */
class ScamTrialActivity : ComponentActivity() {
    private enum class Phase { READY, GREETING, LISTENING, DONE }

    private var phase by mutableStateOf(Phase.READY)
    private var lines by mutableStateOf<List<CallLine>>(emptyList())
    private var partial by mutableStateOf("")
    private var result by mutableStateOf(ScamCheck.Result(ScamCheck.Level.NONE, emptyList()))
    private var summary by mutableStateOf("")
    private var earNote by mutableStateOf("")
    private var micOk by mutableStateOf(false)

    private lateinit var ear: TrialEar
    private lateinit var voice: TrialVoice

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micOk = ok
        if (ok) start() else earNote = "マイクの許可がないので、試せません"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val glow = AvatarStyle.byId((application as McpApp).graph.settings.avatar).glow
        voice = TrialVoice(this)
        ear = TrialEar(this, onPartial = { partial = it }, onFinal = ::heard, onNote = { earNote = it }, onStopped = { if (phase == Phase.LISTENING) end() })
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        earNote = ear.describe()
        setContent { Screen(glow) }
    }

    /** 画面が隠れたら、試しを終える */
    override fun onPause() {
        super.onPause()
        if (phase == Phase.GREETING || phase == Phase.LISTENING) end(speak = false)
    }

    override fun onDestroy() {
        ear.destroy()
        voice.shutdown()
        super.onDestroy()
    }

    private fun start() {
        if (!micOk) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        result = ScamCheck.Result(ScamCheck.Level.NONE, emptyList())
        summary = ""; partial = ""
        lines = listOf(CallLine("rigel", ScamTrial.GREETING, true, System.currentTimeMillis()))
        phase = Phase.GREETING
        // リゲルが名乗り終えてから聞き始める (読み上げの声を聞き取らないように)
        voice.say(ScamTrial.GREETING) { if (phase == Phase.GREETING) { phase = Phase.LISTENING; ear.start() } }
    }

    private fun heard(text: String) {
        if (phase != Phase.LISTENING) return
        partial = ""
        lines = lines + CallLine("peer", text, true, System.currentTimeMillis())
        result = ScamCheck.check(lines)
    }

    private fun end(speak: Boolean = true) {
        ear.stop()
        if (phase == Phase.LISTENING && partial.isNotBlank()) lines = lines + CallLine("peer", partial, true, System.currentTimeMillis())
        partial = ""
        result = ScamCheck.check(lines)
        val heardN = lines.count { it.who == "peer" }
        summary = ScamTrial.summary(result, heardN)
        val closing = ScamTrial.closing(result, heardN)
        if (closing.isNotEmpty()) lines = lines + CallLine("rigel", closing, true, System.currentTimeMillis())
        phase = Phase.DONE
        if (speak && closing.isNotEmpty()) voice.say(closing) else voice.stop()
        RabiLog.i(TAG, "trial done heard=$heardN level=${result.level}")
    }

    @Composable
    private fun Screen(glow: Color) {
        Box(Modifier.fillMaxSize().background(CALL_BG).systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (result.level != ScamCheck.Level.NONE) ScamBanner(result.level, result.reasons, Modifier.fillMaxWidth())
                else {
                    Text("詐欺の電話の試し", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("本物の電話ではありません", color = CALL_SUB, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                when (phase) {
                    Phase.READY -> Ready()
                    else -> Talking(glow)
                }
            }
        }
    }

    @Composable
    private fun Ready() {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            CallerCircle(ringing = true, diameter = 96.dp)
            Spacer(Modifier.height(8.dp))
            Panel {
                Text("あなたが、電話の相手の役です。「始める」を押し、リゲルが名乗ったら、詐欺の電話の台本を声に出して読んでください。" +
                    "リゲルが聞き取って、本物の電話と同じしくみで、詐欺の疑いを見分けます。", color = CALL_TEXT, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("たとえば", color = CALL_DIM, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("「${ScamTrial.EXAMPLE}」", color = CALL_GREEN, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("ほかの台本 (詐欺 30 本・ふつうの電話 30 本) を開く", color = Color(0xFF9FD8FF), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clickable { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ScamTrial.SCRIPTS_URL))) } })
            }
            Spacer(Modifier.height(10.dp))
            Text(earNote, color = CALL_DIM, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::start, enabled = ear.available(), modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E6A))) {
                Text("始める", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("閉じる", color = Color.White) }
        }
    }

    @Composable
    private fun ColumnScope.Talking(glow: Color) {
        val listening = phase == Phase.LISTENING
        val label = when (phase) {
            Phase.GREETING -> "リゲルが話しています"
            Phase.LISTENING -> if (partial.isNotBlank()) "相手の声を聞いています" else "台本を読んでください"
            else -> "試しを終えました"
        }
        Row(Modifier.width(272.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x382B86D9))
            .border(1.dp, Color(0x809FD8FF), RoundedCornerShape(24.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniOrb(glow, phase == Phase.GREETING, Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = CALL_TEXT, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        if (phase == Phase.DONE) {
            Panel { Text(summary, color = Color.White, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(10.dp))
        }
        val shown = if (listening && partial.isNotBlank()) lines + CallLine("peer", "$partial…", false, 0L) else lines
        CallTranscript(shown, Modifier.weight(1f).fillMaxWidth(), scroll = rememberScrollState())
        Spacer(Modifier.height(14.dp))
        if (phase == Phase.DONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = ::start, modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E6A))) {
                    Text("もう一度", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("閉じる", color = Color.White, fontSize = 18.sp) }
            }
        } else {
            HangupButton(Modifier.size(72.dp).semantics { contentDescription = "試しを終える" }.clickable { end() })
            Text("読み終えたら、赤いボタンで終えます", color = CALL_DIM, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun Panel(content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(17.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xD9080D16)).border(1.dp, Color(0x2978B4FF), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
    }

    private companion object {
        const val TAG = "scamtrial"
    }
}

/**
 * 声を聞き取り続ける (Android の音声認識)。端末の中で聞き取れるならそれを使い、日本語が使えなければ Google の音声認識に切り替える。
 * 1 回聞き取るたびに、すぐ聞き直す (止めるまで)
 */
private class TrialEar(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onNote: (String) -> Unit,
    private val onStopped: () -> Unit,
) : RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var onDevice = false
    private var fellBack = false
    private var active = false
    /** 続けてつながらなかった回数 (多ければあきらめる) */
    private var netErrors = 0

    fun available(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) || SpeechRecognizer.isRecognitionAvailable(ctx)

    /** 画面に出す、聞き取りのしかた */
    fun describe(): String = when {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) && !fellBack -> "聞き取りも見分けも、このスマホの中で行います (声はどこにも送りません)"
        SpeechRecognizer.isRecognitionAvailable(ctx) -> "聞き取りに Google の音声認識を使います (声が Google に送られます)。見分けは、このスマホの中で行います"
        else -> "このスマホでは、声の聞き取りが使えません"
    }

    fun start() {
        active = true; netErrors = 0
        if (sr == null) create(preferOnDevice = !fellBack)
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel() }
    }

    fun destroy() {
        stop()
        runCatching { sr?.destroy() }
        sr = null
    }

    private fun create(preferOnDevice: Boolean) {
        runCatching { sr?.destroy() }
        onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        sr = (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx))
            .also { it.setRecognitionListener(this) }
        onNote(describe())
    }

    private fun listen() {
        if (!active) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        runCatching { sr?.startListening(i) }.onFailure { again(500) }
    }

    private fun again(ms: Long) { if (active) main.postDelayed({ listen() }, ms) }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    override fun onResults(results: Bundle?) {
        netErrors = 0
        best(results)?.let(onFinal)
        again(100)
    }

    override fun onPartialResults(partialResults: Bundle?) { best(partialResults)?.let(onPartial) }

    override fun onError(error: Int) {
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { active = false; onNote("マイクの許可がないので、聞き取れません"); onStopped() }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                if (onDevice && !fellBack && SpeechRecognizer.isRecognitionAvailable(ctx)) { fellBack = true; create(preferOnDevice = false); again(300) }
                else { active = false; onNote("このスマホでは、日本語の聞き取りが使えません"); onStopped() }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                if (++netErrors >= MAX_NET_ERRORS) { active = false; onNote("聞き取りの通信ができませんでした"); onStopped() } else again(800)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { runCatching { sr?.cancel() }; again(600) }
            // 黙っていた・聞き取れなかった: 聞き直す
            else -> again(200)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private companion object {
        const val MAX_NET_ERRORS = 4
    }
}

/** リゲルの読み上げ (Android の日本語の読み上げ)。言い終えたら then を呼ぶ。読み上げが使えなければ、すぐ then を呼ぶ */
private class TrialVoice(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var waiting: Pair<String, () -> Unit>? = null
    private val done = HashMap<String, () -> Unit>()
    private var seq = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        val ok = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.JAPAN).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
        if (!ok) { failed = true; waiting?.second?.invoke(); waiting = null; return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { done.remove(utteranceId)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { done.remove(utteranceId)?.invoke() } }
        })
        ready = true
        waiting?.let { waiting = null; say(it.first, it.second) }
    }

    fun say(text: String, then: () -> Unit = {}) {
        if (failed) { then(); return }
        if (!ready) { waiting = text to then; return }
        val id = "trial-${++seq}"
        done.clear()
        done[id] = then
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) { done.remove(id); then() }
    }

    fun stop() {
        waiting = null
        done.clear()
        if (ready) runCatching { tts.stop() }
    }

    fun shutdown() {
        stop()
        ready = false
        runCatching { tts.shutdown() }
    }
}
