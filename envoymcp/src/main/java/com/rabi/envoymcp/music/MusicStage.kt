package com.rabi.envoymcp.music

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarGeometry
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import com.rabi.envoymcp.ui.avatar.Groove
import com.rabi.envoymcp.voice.HOME_BG
import com.rabi.envoymcp.voice.RigelCloseButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 端末で音楽が流れているか (YouTube Music・Spotify など。用途が媒体 (USAGE_MEDIA) で、中身が音楽 (CONTENT_TYPE_MUSIC) の再生)。
 * 動画 (YouTube など) は中身が MOVIE なので入らない。一般のアプリに届く再生の一覧は、再生中のものだけ (Android が止まっているものを除く)。
 * 音楽の画面と、AgentARM の「音楽が流れたら踊る」で共有する。要るときだけ見張る (refresh)
 */
class MusicWatch(private val context: Context) {
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private var cb: AudioManager.AudioPlaybackCallback? = null
    /** 見張っているか (見張っていなければ playing はいつも false) */
    val active get() = cb != null

    fun refresh(want: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (want && cb == null) {
            val c = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) { update(configs) }
            }
            runCatching { am.registerAudioPlaybackCallback(c, Handler(Looper.getMainLooper())) }
                .onSuccess { cb = c; update(am.activePlaybackConfigurations) }
                .onFailure { RabiLog.w("music", "playback callback failed: ${it.javaClass.simpleName}") }
        } else if (!want && cb != null) {
            cb?.let { runCatching { am.unregisterAudioPlaybackCallback(it) } }
            cb = null
            _playing.value = false
        }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        _playing.value = configs.any { c ->
            c.audioAttributes.usage == AudioAttributes.USAGE_MEDIA && c.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
        }
    }
}

/**
 * 音楽が流れたら、リゲルの画面 (MusicStageActivity) を出す (設定で切れる)。
 * - 少し流れ続けてから出す。画面が消えている・ロック中・通話や音声の会話の最中・ChatGPT の音声モードの覆いが出ているときは出さない
 * - × (や戻る・ホーム) で閉じたら、その曲の間は出し直さない (音楽が止まると解ける)
 * - 音楽が止まって少したったら、自分で閉じる
 */
class MusicStage(private val g: McpGraph) {
    /** 画面が出ているか (MusicStageActivity が知らせる) */
    @Volatile var visible = false
    private var dismissed = false
    private var closingByMusic = false
    private var job: Job? = null
    private val _close = MutableStateFlow(0)
    /** 増えたら、画面を閉じる */
    val close: StateFlow<Int> = _close

    fun start() {
        g.scope.launch { g.musicWatch.playing.collect { on(it) } }
    }

    private fun on(playing: Boolean) {
        job?.cancel()
        if (playing) {
            job = g.scope.launch { delay(SHOW_DELAY_MS); if (g.musicWatch.playing.value) maybeShow() }
        } else {
            dismissed = false
            job = g.scope.launch {
                delay(CLOSE_AFTER_MS)
                if (!g.musicWatch.playing.value && visible) { closingByMusic = true; _close.value++ }
            }
        }
    }

    private fun maybeShow() {
        val why = blocker() ?: return show()
        RabiLog.i("music", "音楽が流れていますが、リゲルの画面は出しません ($why)")
    }

    private fun blocker(): String? {
        val ctx = g.context
        return when {
            !g.settings.musicStage -> "設定がオフ"
            dismissed -> "この曲の間は閉じられた"
            visible -> "もう出ている"
            !ctx.getSystemService(PowerManager::class.java).isInteractive -> "画面が消えている"
            ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "ロック中"
            ctx.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_COMMUNICATION -> "通話・音声の会話の最中"
            !g.overlay.canShow() -> "他のアプリの上に表示の許可が無い (裏から画面を出せない)"
            else -> null
        }
    }

    // ---------------------------------------------------------------- 音楽の表情の試し (設定の「音楽の表情を試す」)
    // 本物の音の代わりに、決まった筋書きの拍と音の強さで、音楽の画面を動かす (背景・ヘッドホン・リゲルの表情が本物と同じように動く)

    /** 試しを始めた時刻 (elapsedRealtime)。0 なら試していない */
    @Volatile private var demoStart = 0L
    private val _demo = MutableStateFlow(false)
    /** 試している最中か */
    val demo: StateFlow<Boolean> = _demo
    val demoActive get() = demoStart != 0L

    /** 試しを始める: 音楽の画面を出し、筋書きを流す (終わったら自分で閉じる) */
    fun showDemo() {
        demoStart = SystemClock.elapsedRealtime()
        _demo.value = true
        RabiLog.i("music", "demo start")
        show()
    }

    /** 筋書きの経過 (秒) */
    private fun demoT(ms: Long) = (ms - demoStart) / 1000.0

    /**
     * 筋書きの拍 (128 BPM)。静かなイントロ (0〜5 秒) と、静かな終わり (47 秒〜) は拍なし。
     * 5〜24 秒 ノり → 24〜30 秒 だんだん強く (ため) → 30〜38 秒 盛り上がり → 38〜47 秒 ノり → 52 秒で終わり
     */
    fun demoBeat(ms: Long): BeatTracker.Beat? {
        val t = demoT(ms)
        if (t < DEMO_BEAT_FROM || t >= DEMO_BEAT_UNTIL) return null
        val period = 60_000.0 / DEMO_BPM
        val first = demoStart + (DEMO_BEAT_FROM * 1000).toLong()
        val last = first + (floor((ms - first) / period) * period).toLong()
        return BeatTracker.Beat(DEMO_BPM, last, 1.0, demoEnergy(ms))
    }

    /** 筋書きの音の強さ (0..1。この 1 秒の大きさ ÷ 最近の最大) */
    fun demoEnergy(ms: Long): Double {
        val t = demoT(ms)
        return when {
            t < 5 -> 0.12
            t < 24 -> 0.6
            t < 30 -> 0.6 + 0.24 * (t - 24) / 6
            t < 38 -> 0.97
            t < 47 -> 0.6
            else -> 0.1
        }
    }

    /** 毎フレーム呼ぶ: 筋書きが終わったら、試しをやめて画面を閉じる */
    fun demoTick(ms: Long) {
        if (demoStart == 0L || demoT(ms) < DEMO_LENGTH_S) return
        endDemo()
        closingByMusic = true
        _close.value++
    }

    private fun endDemo() {
        if (demoStart == 0L) return
        demoStart = 0L
        _demo.value = false
        RabiLog.i("music", "demo end")
    }

    /** 画面を出す (設定の画面の「試しに出す」からも) */
    fun show() {
        closingByMusic = false
        runCatching {
            g.context.startActivity(Intent(g.context, MusicStageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { RabiLog.w("music", "cannot show stage: ${it.javaClass.simpleName}") }
    }

    /** 画面が隠れた (× で閉じた・戻る・ホーム)。本人が閉じたなら、その曲の間は出し直さない */
    fun left() {
        endDemo()
        visible = false
        if (!closingByMusic) dismissed = true
        closingByMusic = false
    }

    companion object {
        private const val SHOW_DELAY_MS = 1_500L
        private const val CLOSE_AFTER_MS = 8_000L
        private const val DEMO_BPM = 128.0
        private const val DEMO_BEAT_FROM = 5.0
        private const val DEMO_BEAT_UNTIL = 47.0
        private const val DEMO_LENGTH_S = 52.0
    }
}

/** 音楽が流れている間の、リゲルの画面 (画面いっぱいのリゲルと、そのまわりのカラフルなスペクトラム) */
class MusicStageActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { MusicStageContent(g) { finish() } }
    }

    override fun onStart() {
        super.onStart()
        g.musicStage.visible = true
        // 前に出ている間は、端末で鳴っている音を読む (マイクの許可が無ければ、スペクトラムは出ない)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) g.music.hold(LISTEN_STAGE)
    }

    override fun onStop() {
        g.music.release(LISTEN_STAGE)
        if (!isChangingConfigurations) g.musicStage.left()
        super.onStop()
    }

    companion object {
        private const val LISTEN_STAGE = "stage"
    }
}

@Composable
private fun MusicStageContent(g: McpGraph, close: () -> Unit) {
    val spectrum by g.music.spectrum.collectAsState()
    val beat by g.music.beat.collectAsState()
    val demoOn by g.musicStage.demo.collectAsState()
    val arm by g.arm.status.collectAsState()
    val track by g.nowPlaying.track.collectAsState()
    // × と、音楽が止まって閉じるとき
    LaunchedEffect(Unit) { g.musicStage.close.drop(1).collect { close() } }
    // 拍ごとにリゲルを脈打たせる
    var pulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var lastBeat = Long.MIN_VALUE
        while (true) {
            withFrameNanos { }
            val ms = SystemClock.elapsedRealtime()
            (if (g.musicStage.demoActive) g.musicStage.demoBeat(ms) else g.music.beat.value)?.let { b ->
                val idx = floor((ms - b.lastBeatMs) / b.periodMs).toLong()
                if (idx != lastBeat) { if (lastBeat != Long.MIN_VALUE) pulse++; lastBeat = idx }
            }
        }
    }

    val dj = rememberDjState(g)
    // リゲルが音楽に合わせてノる元 (拍の中の位置・テンポ・キック・低音・盛り上がり)。毎フレーム書く
    val groove = remember { Groove() }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val ms = SystemClock.elapsedRealtime()
            val demo = g.musicStage.demoActive
            val b = if (demo) g.musicStage.demoBeat(ms) else g.music.beat.value
            // 試しでは、ノり方を 8 拍ごとに替え、小節の頭の仕草を 2 小節ごとに順に出す (短い間に全部見られるように)
            groove.styleBeats = if (demo) 8 else 16
            groove.demoFlourish = demo
            g.musicStage.demoTick(ms)
            groove.on = b != null
            if (b != null) { groove.phase = b.phaseAt(ms).toFloat(); groove.bpm = b.bpm.toFloat() }
            groove.beats = dj.beats; groove.kick = dj.kick; groove.bass = dj.bass; groove.hype = dj.hype
            groove.energyFast = dj.energyFast; groove.energySlow = dj.energySlow
        }
    }
    // 設定は、前に出るたびに読み直す (設定の画面で変えて戻ってきたとき)
    var flash by remember { mutableStateOf(g.settings.musicFlash) }
    var lyricsOn by remember { mutableStateOf(g.settings.musicLyrics) }
    LifecycleResumeEffect(Unit) {
        flash = g.settings.musicFlash; lyricsOn = g.settings.musicLyrics
        onPauseOrDispose { }
    }
    // 歌詞: 曲が替わったら取り直す (設定がオンのときだけ。出ている間だけ)
    LaunchedEffect(track?.key, track?.durationMs, lyricsOn) { if (lyricsOn) g.lyrics.load(track) }
    val lyrics by g.lyrics.state.collectAsState()
    val density = LocalDensity.current
    val rain = rememberLyricsRain(g, dj)
    val lyricMode = lyricsOn && lyrics.status == LyricsRepo.Status.FOUND
    // 歌詞のずれを直す: 左へスワイプで早める、右へで遅らせる (48dp ごとに 0.1 秒。その曲用に覚える)
    val offset by g.lyrics.offset.collectAsState()
    var nudged by remember { mutableIntStateOf(0) }
    var showOffset by remember { mutableStateOf(false) }
    LaunchedEffect(nudged) { if (nudged > 0) { showOffset = true; delay(1_600); showOffset = false } }
    val stepPx = with(density) { 48.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize().background(HOME_BG).pointerInput(lyricMode) {
        if (!lyricMode) return@pointerInput
        var acc = 0f
        detectHorizontalDragGestures(onDragStart = { acc = 0f }) { _, dx ->
            acc += dx
            while (acc <= -stepPx) { acc += stepPx; g.lyrics.nudge(+100); nudged++ }
            while (acc >= stepPx) { acc -= stepPx; g.lyrics.nudge(-100); nudged++ }
        }
    }) {
        // 歌詞がある間は、リゲルと輪を少し下げて、画面の上を歌詞の舞台にする
        val lift by animateFloatAsState(if (lyricMode) constraints.maxHeight * LYRIC_LIFT else 0f, tween(900), label = "lift")
        // 盛り上がったときのレーザーとステージの照明 (リゲルの球より奥)
        DjBackdrop(dj, flash, Modifier.fillMaxSize())
        // 歌詞の奥の層: 光の粒・巨大な薄い今の行・奥へ降っていく前の行 (リゲルの球より奥)
        if (lyricsOn) LyricsBack(rain, dj, lift, Modifier.fillMaxSize())
        Avatar(
            if (beat != null || demoOn) Mood.SPEAKING else Mood.IDLE, AvatarStyle.byId(g.settings.avatar), 0f,
            Modifier.fillMaxSize().padding(top = with(density) { lift.toDp() }).semantics { contentDescription = "リゲル。音楽に合わせて光ります" },
            signals = AvatarSignals(pulse = pulse),
            fullScreen = true, allow3d = g.settings.avatar3d, groove = groove,
        )
        // クラブ DJ 風のスペクトラム・波形の輪・火花・リゲルの DJ のヘッドホン・テンポの表示 (音声モードのリゲルは SpectrumRing のまま)
        DjSpectrum(dj, flash, lift, lyricMode, Modifier.fillMaxSize(), groove)
        // 歌詞の前の層: 今の行 (キネティック・タイポグラフィ)
        if (lyricsOn) LyricsFront(rain, dj, flash, lift, Modifier.fillMaxSize())
        if (showOffset) Text(
            when {
                offset > 0 -> "歌詞を %.1f 秒早めています".format(offset / 1000f)
                offset < 0 -> "歌詞を %.1f 秒遅らせています".format(-offset / 1000f)
                else -> "歌詞のずれ: なし"
            }, color = Color(0xFFEAF2FF), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp).background(Color(0xCC0B111A), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (lyricsOn) LyricsNote(g, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            track?.let {
                // 曲名は、クラブの電光掲示板のように横に流し続ける (短い曲名でも流れるよう、くり返してつなぐ)
                val one = "♪ ${it.title}" + if (it.artist.isNotBlank()) "  ・  ${it.artist}" else ""
                Text(List(3) { one }.joinToString("        "), color = Color(0xFFD9FAFF), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    style = TextStyle(shadow = Shadow(Color(0xFF20E3FF), blurRadius = 18f)),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = one }
                        .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 0, initialDelayMillis = 600, spacing = MarqueeSpacing(64.dp), velocity = 48.dp))
                Spacer(Modifier.height(10.dp))
            }
            val line = when {
                spectrum.isEmpty() && !g.music.hasPermission() -> "マイクの許可があると、音に合わせて光ります (ENVOY MCP の設定)"
                arm.dancing -> "テンポ ${beat?.bpm?.toInt() ?: "—"}  AgentARM が踊っています"
                g.arm.hasReservation() -> "音楽が流れたら、AgentARM が踊ります"
                beat != null -> "テンポ ${beat!!.bpm.toInt()}"
                track == null -> "♪"
                else -> ""
            }
            if (line.isNotBlank()) Text(line, color = Color(0xFFB8C4D6), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
            Spacer(Modifier.height(20.dp))
            RigelCloseButton(g, Modifier.size(72.dp).semantics { contentDescription = "リゲルの画面を閉じる" }.clickable { close() })
        }
    }
}

/**
 * リゲルのまわりのカラフルなスペクトラム (音楽の画面と、音声モードの全画面のリゲルで使う)。
 * 端末の出力の音を聞いている間だけ出る (聞いていなければ何も描かない)
 */
@Composable
internal fun SpectrumRing(g: McpGraph, modifier: Modifier) {
    val density = LocalDensity.current
    // 表示の値: 上がるときはすぐ、下がるときはゆっくり (毎フレーム)
    val shown = remember { FloatArray(SPECTRUM_BANDS) }
    var frame by remember { mutableLongStateOf(0L) }
    var hue by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var any by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val src = g.music.spectrum.value
                var lit = 0
                for (i in 0 until SPECTRUM_BANDS) {
                    val target = if (src.size == SPECTRUM_BANDS) src[i] else 0f
                    shown[i] = if (target > shown[i]) shown[i] + (target - shown[i]) * minOf(1f, dt * 25f) else maxOf(target, shown[i] - dt * 1.4f)
                    if (shown[i] > 0.001f) lit++
                }
                hue = (hue + dt * 18f) % 360f
                any = if (src.isNotEmpty() || lit > 0) 1 else 0
                frame = now
            }
        }
    }
    Canvas(modifier) {
        frame                                                        // 毎フレーム描き直す
        if (any == 0) return@Canvas                                   // 音を聞いていない
        val c = AvatarGeometry.center(size.width, size.height, true)
        val orb = AvatarGeometry.radius(size.width, size.height, true, with(density) { 360.dp.toPx() })
        val inner = orb * 1.22f
        val maxLen = minOf(size.width, size.height) * 0.22f
        val n = SPECTRUM_BANDS * 2                                            // 左右に鏡写し (低い音が上、高い音が下)
        val w = (2 * PI.toFloat() * inner / n) * 0.55f
        for (k in 0 until n) {
            val band = if (k < SPECTRUM_BANDS) k else n - 1 - k
            val v = shown[band]
            val ang = (-PI / 2 + (k + 0.5) * 2 * PI / n).toFloat()
            val dir = Offset(cos(ang), sin(ang))
            val len = maxLen * (0.03f + 0.97f * v)                     // 2 乗すると小さい帯がほぼ平らに見えるので、そのまま
            val from = c + dir * inner
            val to = c + dir * (inner + len)
            val col = Color.hsv((hue + band * 300f / SPECTRUM_BANDS) % 360f, 0.75f, 1f)
            drawLine(col.copy(alpha = 0.22f), from, to, strokeWidth = w * 2.4f, cap = StrokeCap.Round)   // ぼかし (光)
            drawLine(col.copy(alpha = 0.92f), from, to, strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** スペクトラムの帯の数 (OnsetDetector と同じ) */
internal const val SPECTRUM_BANDS = 48
/** 歌詞がある間、リゲルを下げる量 (画面の高さの割合)。上が歌詞の舞台になる */
private const val LYRIC_LIFT = 0.12f
