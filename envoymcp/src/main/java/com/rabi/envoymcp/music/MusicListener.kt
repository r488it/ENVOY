package com.rabi.envoymcp.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 端末で鳴っている音 (出力の波形。Visualizer のセッション 0) から、音楽の拍を見つける。マイクの音は使わない
 * (Android では、出力の波形を読むのにもマイクの許可が要る)。
 * - 周波数ごとの強さが増えた分 (spectral flux。低い音を重く) を立ち上がりの強さにし、BeatTracker でテンポと拍の位置を出す
 * - 0.25 秒ごとに、拍がはっきりしていれば onBeat を呼ぶ (メインスレッド)。AgentARM はこれに合わせて踊る
 * - 波形は 1 秒に 20 回ほど届く。届いた時刻は、その間の真ん中ほどの音なので、FRAME_DELAY_MS だけ前にずらして渡す
 * メインスレッドから使う (Visualizer の知らせも、作ったスレッド = メインに届く)。
 */
class MusicListener(private val context: Context, private val scope: CoroutineScope) {
    data class Status(
        val listening: Boolean = false,
        /** 拍がはっきりしているときのテンポ */
        val bpm: Double? = null,
        val confidence: Double = 0.0,
        val energy: Double = 0.0,
        val note: String = "",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    /** 周波数ごとの強さ (低い方から。0〜1 にならしたもの。画面のスペクトラムに使う)。聞いていないときは空 */
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum
    /** 端末の出力の波形 (-1〜1。128 点に間引いたもの。音楽の画面の波形の輪に使う)。聞いていないときは空 */
    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform
    /** 今はっきりしている拍 (画面でリゲルを拍に合わせて脈打たせる)。無ければ null */
    private val _beat = MutableStateFlow<BeatTracker.Beat?>(null)
    val beat: StateFlow<BeatTracker.Beat?> = _beat
    /** 聞いているもの (踊り・画面・試し)。だれかが聞いている間だけ Visualizer を動かす */
    private val holders = mutableSetOf<String>()

    /** 拍がはっきりしているとき 0.25 秒ごとに呼ぶ */
    var onBeat: ((BeatTracker.Beat) -> Unit)? = null
    /** 拍が取れた・取れなくなった・聞いている間の様子 (10 秒ごと) を、数つきで知らせる (腕の記録に残す) */
    var onNote: ((String) -> Unit)? = null
    private var notedAt = 0L

    private var vis: Visualizer? = null
    private val tracker = BeatTracker()
    private val lock = BeatLock(acquire = MIN_CONFIDENCE)
    private val onsets = OnsetDetector()
    private val wave = WaveSpectrum(onsets.bands)
    private val shaper = SpectrumShaper(onsets.bands)
    /** 波形が届かない端末では、getFft の帯で代わりに出す (値の大きさが違うので、ならしは別にする) */
    private val fftShaper = SpectrumShaper(onsets.bands)
    private var waveAt = 0L
    private var job: Job? = null
    private var startedAt = 0L
    private var heardAt = 0L
    /** 音色 (0〜7。周波数の重心から。腕が曲ごとに違う踊りを選ぶ種に使う。同じ曲ならほぼ同じ値) */
    val color: Int get() = ((centroid * 32).toInt() % 8).coerceAtLeast(0)
    private var centroid = 0.0

    /** 今はっきりしている拍 (無ければ null) */
    var locked: BeatTracker.Beat? = null
        private set

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val listening get() = vis != null

    /** who が聞き始める (だれかが聞いている間だけ動かす)。読めなければ false (理由は status.note) */
    fun hold(who: String): Boolean {
        if (!start()) return false
        if (holders.add(who)) RabiLog.i("music", "hold $who (${holders.joinToString()})")
        return true
    }

    /** who が聞くのをやめる (だれも聞いていなければ止める) */
    fun release(who: String) {
        if (holders.remove(who)) RabiLog.i("music", "release $who (${holders.joinToString().ifEmpty { "なし" }})")
        if (holders.isEmpty()) stop()
    }

    /** 今聞いている人たち (記録用) */
    fun holdersText() = holders.joinToString().ifEmpty { "なし" }

    fun holding(who: String) = who in holders

    /** 聞き始める。読めなければ false (理由は status.note) */
    private fun start(): Boolean {
        if (vis != null) return true
        if (!hasPermission()) { _status.value = Status(note = "マイクの許可がありません (端末の出力の音を読むのに要ります)"); return false }
        val v = try {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(CAPTURE_SIZE)
                // 音量どおりの大きさで読む (音楽が止まったら 0 になる。毎回の正規化だと、静かなときも雑音が大きく見える)
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) { wave?.let { onWave(it) } }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { fft?.let { onFft(it) } }
                }, Visualizer.getMaxCaptureRate(), true, true)
                enabled = true
            }
        } catch (e: Exception) {
            RabiLog.w("music", "visualizer failed: ${e.javaClass.simpleName}")
            _status.value = Status(note = "端末の出力の音を読めません (${e.javaClass.simpleName})")
            return false
        }
        vis = v
        tracker.clear(); lock.reset(); onsets.reset(); shaper.reset(); fftShaper.reset(); waveAt = 0L; locked = null; centroid = 0.0
        startedAt = SystemClock.elapsedRealtime(); heardAt = 0L; notedAt = startedAt
        _status.value = Status(listening = true, note = "聞いています")
        job = scope.launch { while (isActive) { delay(EVAL_MS); evaluate() } }
        RabiLog.i("music", "listening")
        return true
    }

    private fun stop() {
        holders.clear()
        job?.cancel(); job = null
        vis?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
        if (vis != null) RabiLog.i("music", "stopped")
        vis = null
        locked = null
        _status.value = Status()
        _spectrum.value = FloatArray(0)
        _waveform.value = FloatArray(0)
        _beat.value = null
    }

    private fun onFft(fft: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val f = onsets.push(fft)
        if (f.level > 0.0) {
            heardAt = now
            centroid = if (centroid == 0.0) f.centroid else centroid + (f.centroid - centroid) * 0.01      // 5 秒ほどでならす
        }
        tracker.push(now - FRAME_DELAY_MS, f.onset, f.level)
        // 画面のスペクトラムは波形から (onWave)。波形が届いていなければ、この帯で代わりに出す
        if (now - waveAt > WAVE_STALE_MS) _spectrum.value = fftShaper.push(f.bands)
    }

    /** 画面のスペクトラム: 波形から自分で FFT し (弱い高い音も残る)、帯ごとに、その帯のこの数秒の幅でならす (高い音の帯も、その帯なりに動く) */
    private fun onWave(w: ByteArray) {
        waveAt = SystemClock.elapsedRealtime()
        _spectrum.value = shaper.push(wave.push(w))
        _waveform.value = FloatArray(WAVE_POINTS) { ((w[it * w.size / WAVE_POINTS].toInt() and 0xFF) - 128) / 128f }
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val energy = tracker.energy(now)
        val raw = tracker.estimate(now)
        val was = locked
        val b = lock.update(raw, energy, tracker.audible(now), now)
        locked = b
        _beat.value = b
        if (was == null && b != null) { RabiLog.i("music", "拍が取れました"); onNote?.invoke("拍が取れました: テンポ ${b.bpm.toInt()} 確かさ ${pct(b.confidence)} 音 ${pct(energy)}") }
        if (was != null && b == null) { RabiLog.i("music", "拍が取れなくなりました: ${lock.lostWhy}"); onNote?.invoke("拍が取れなくなりました: ${lock.lostWhy}") }
        if (now - notedAt >= NOTE_EVERY_MS) {
            notedAt = now
            onNote?.invoke("聞いています: " + (b?.let { "テンポ ${it.bpm.toInt()} (取り直し ${(now - lock.refreshedAt) / 1000} 秒前)" } ?: "拍なし") +
                " 見積もり ${raw?.bpm?.toInt() ?: "—"} 確かさ ${pct(raw?.confidence ?: 0.0)} 音 ${pct(energy)} 鳴っている ${if (tracker.audible(now)) "はい" else "いいえ"}")
        }
        val note = when {
            heardAt == 0L && now - startedAt > SILENT_NOTE_MS -> "音が取れていません (音楽が流れていない、音量が 0、またはそのアプリの音は読めない)"
            heardAt > 0 && now - heardAt > SILENT_NOTE_MS -> "音が止まっています"
            b != null && (raw?.confidence ?: 0.0) < MIN_CONFIDENCE -> "拍を保っています (今は拍がはっきりしないので、前の拍を続けています)"
            b != null -> "拍が取れています"
            else -> "聞いています"
        }
        _status.value = Status(true, b?.bpm, raw?.confidence ?: 0.0, energy, note)
        if (b != null) onBeat?.invoke(b)
    }

    private fun pct(x: Double) = "${(x * 100).toInt()}%"

    companion object {
        private const val NOTE_EVERY_MS = 10_000L
        /** 1 回の波形の間の真ん中ほどの音として扱う (届く間隔の半分) */
        const val FRAME_DELAY_MS = 25L
        /** これより長く波形が届かなければ、getFft の帯で画面のスペクトラムを出す */
        private const val WAVE_STALE_MS = 500L
        private const val WAVE_POINTS = 128
        private const val CAPTURE_SIZE = 1024
        private const val EVAL_MS = 250L
        /** 拍を取り始める確かさ (模擬の信号では、拍は 0.48 以上・雑音は 0.16 以下になる。取れたあとは BeatLock が低めでも保つ) */
        const val MIN_CONFIDENCE = 0.25
        private const val SILENT_NOTE_MS = 3_000L
    }
}
