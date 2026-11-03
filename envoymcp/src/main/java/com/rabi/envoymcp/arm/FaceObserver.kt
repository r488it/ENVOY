package com.rabi.envoymcp.arm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Size
import android.util.SizeF
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rabi.launcher.core.RabiLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.tan

/**
 * 前面カメラ (カメラマン役のときは背面カメラ) で顔を探す (CameraX + ML Kit。通信なし)。使うときだけ動かす (呼びかけのあと、向け終わるまで)。
 * 画像は「スマホを縦にした向き」に起こしてから調べるので、座標の上はスマホの +y (腕が横にしても同じ)。
 * 見つかったら一番大きい顔の中心を、見つからなければ null を onSample に渡す (メインスレッド)。
 * 顔を追い続けることがあるので、腕に顔の向きを送っていない間は、調べる回数を減らせる (minIntervalMs。電池と熱のため)。
 * 撮りながら顔を追う: 1 つのカメラは 1 つのアプリしか開けないので、カメラのアプリで撮りながらでは顔を追えない。
 * そこで、同じ前面カメラに録画 (VideoCapture) もつなぎ、同じ映像から動画の保存と顔の検出をする (startRecording)。
 * 動画は「フォト」に出る Movies/ENVOY に保存する。マイクの許可があれば音も録る (ChatGPT と話している間は、音が入らないことがある)
 * 録画の画面 (RecordActivity) の映像は、顔の検出の画像 (640×480) を起こして渡す (previewSink)。映像の部品 (Preview) はつながない:
 * Preview もつなぐと録画と 1 本の流れを分け合い (StreamSharing)、カメラを切り替えたあと録画に映像が届かなくなるため
 */
@SuppressLint("UnsafeOptInUsageError")
class FaceObserver(private val context: Context, private val onSample: (Sample?) -> Unit) {
    /** 縦に起こした画像の中の、顔の中心 (px, py) と大きさ。atMs は撮った時刻 (elapsedRealtime) */
    class Sample(val px: Double, val py: Double, val w: Int, val h: Int, val fPx: Double, val atMs: Long, val sizeRatio: Double,
                 /** 背面カメラの画像 (カメラマン役) */
                 val back: Boolean = false)

    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )
    /** 背面カメラ (カメラマン役) は人が離れていて顔が小さく写るので、小さい顔も探す */
    private val backDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f)
                .build()
        )
    }
    /** 背面カメラを使う (カメラマン役)。切り替えるときは止めてから変えて、start し直す */
    @Volatile var back = false
    private val selector get() = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    /** 録画の画面に映す画像を受け取るもの (画面が出ている間だけ。画像は縦に起こし、前面は鏡のように左右を反転する) */
    @Volatile var previewSink: ((Bitmap) -> Unit)? = null
    private var lastPreviewMs = 0L
    /** 録画している (Finalize を待つ間も含む) */
    @Volatile var recordingNow = false
        private set
    @Volatile private var focalMm: Float? = null
    @Volatile private var sensorMm: SizeF? = null
    @Volatile var running = false
        private set
    /** 画像を調べる間隔の下限 (ms)。0 なら届いた画像を全部調べる */
    @Volatile var minIntervalMs = 0L
    /** 最後にカメラから画像が届いた時刻 (elapsedRealtime。調べなかった画像も含む。カメラが動いているかを見る) */
    @Volatile var lastFrameMs = 0L
        private set
    private var lastAnalyzedMs = 0L

    @ExperimentalCamera2Interop
    fun start(owner: LifecycleOwner) {
        if (running) return
        running = true
        this.owner = owner
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                if (!running) return@runCatching
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(Surface.ROTATION_0)      // スマホの縦の向きで起こす (画面の回転に合わせない)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                this.analysis = analysis
                p.unbindAll()
                readOptics(p.bindToLifecycle(owner, selector, analysis))
                RabiLog.i("face", "${if (back) "背面" else "前面"}カメラを始めました (焦点 ${focalMm} mm、センサー ${sensorMm})")
            }.onFailure { RabiLog.w("face", "カメラを始められません: ${it.message}"); running = false }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 焦点距離とセンサーの大きさ (顔の向きの計算に使う。カメラごとに違う) */
    @ExperimentalCamera2Interop
    private fun readOptics(cam: Camera) {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        focalMm = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        sensorMm = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    }

    fun stop() {
        running = false
        runCatching { recording?.stop() }
        recording = null
        runCatching { provider?.unbindAll() }
    }

    /** 録画の結果: 保存した場所 (失敗なら null)、ファイル名、長さ (ms)、うまくいかなかったわけ */
    class Saved(val uri: Uri?, val names: List<String>, val ms: Long, val error: String?) {
        val name get() = names.joinToString("・")
    }

    /** 録れた長さ (録画の途中の知らせ Status から。切り替えたあとも伸びているかを見る) */
    @Volatile private var recordedMs = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 録画を始める (顔の検出は続ける)。カメラが動いていないときは始められない (先に start)。
     * カメラをつなぎ直すので、一瞬だけ画像が途切れる。rotation は動画の向き (Surface.ROTATION_*。始めたときの向きで固定)。
     * メインスレッドで呼ぶ。終わったら (stopRecording・stop・失敗) onDone が 1 回呼ばれる
     */
    @SuppressLint("MissingPermission")   // 音はマイクの許可を確かめてから
    @androidx.annotation.OptIn(ExperimentalPersistentRecording::class, ExperimentalCamera2Interop::class)
    fun startRecording(rotation: Int, onDone: (Saved) -> Unit): String? {
        val p = provider ?: return "カメラが動いていません"
        val o = owner ?: return "カメラが動いていません"
        val a = analysis ?: return "カメラが動いていません"
        if (recording != null) return "もう録画しています"
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val video = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
        try {
            p.unbindAll()
            readOptics(p.bindToLifecycle(o, selector, a, video))
        } catch (e: Exception) {
            RabiLog.w("face", "録画と顔の検出を同時につなげません: ${e.javaClass.simpleName} ${e.message}")
            runCatching { p.unbindAll(); readOptics(p.bindToLifecycle(o, selector, a)) }
            return "このスマホでは、録画と顔の検出を同時にできません (${e.javaClass.simpleName})"
        }
        val name = "ENVOY_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ENVOY")
        }
        val out = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        val pending = recorder.prepareRecording(context, out).apply {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withAudioEnabled()
        }
        recordingNow = true
        recordedMs = 0L
        videoCapture = video
        // 止めずに続ける録画: 録画の途中でカメラ (前面・背面) をつなぎ替えても、1 本の動画のまま続ける (switchCamera)
        recording = pending.asPersistentRecording().start(ContextCompat.getMainExecutor(context)) { ev ->
            recordedMs = ev.recordingStats.recordedDurationNanos / 1_000_000
            if (ev !is VideoRecordEvent.Finalize) return@start
            recording = null
            recordingNow = false
            videoCapture = null
            main.removeCallbacksAndMessages(null)
            val ms = recordedMs
            val err = if (ev.hasError()) "録画の誤り ${ev.error}" else null
            RabiLog.i("face", "録画を終えました (${ms / 1000} 秒${err?.let { "、$it" } ?: ""})")
            // 録画をつないだままにしない (顔の検出だけに戻す)
            if (running) runCatching { p.unbindAll(); readOptics(p.bindToLifecycle(o, selector, a)) }
            onDone(Saved(ev.outputResults.outputUri.takeIf { it != Uri.EMPTY }, listOf("$name.mp4"), ms, err))
        }
        RabiLog.i("face", "録画を始めました (${if (back) "背面" else "前面"})")
        return null
    }

    /** 録画を止める (保存し終わると、startRecording の onDone が呼ばれる) */
    fun stopRecording() { runCatching { recording?.stop() } }

    /**
     * 録画を止めずに、前面・背面を切り替える (1 本の動画のまま)。顔の検出・映像も切り替えたカメラにつなぎ直す。
     * つなぎ替える間 (一瞬) は映像が止まる。うまくいかなければ元のカメラに戻して、わけを返す。メインスレッドで呼ぶ。
     * 切り替えたあとの映像が録画に入っているかを見るため、切り替えて 3 秒後に、録れた長さが伸びているかを記録に残す
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun switchCamera(toBack: Boolean): String? {
        val p = provider ?: return "カメラが動いていません"
        val o = owner ?: return "カメラが動いていません"
        val a = analysis ?: return "カメラが動いていません"
        val v = videoCapture ?: return "録画していません"
        if (recording == null) return "録画していません"
        if (back == toBack) return null
        val old = back
        val cases = arrayOf(a, v)
        back = toBack
        val atSwitch = recordedMs
        return try {
            p.unbindAll()
            readOptics(p.bindToLifecycle(o, selector, *cases))
            RabiLog.i("face", "録画したまま${if (toBack) "背面" else "前面"}カメラに切り替えました (それまでに録れた長さ ${atSwitch / 1000.0} 秒)")
            main.postDelayed({
                if (recording == null) return@postDelayed
                val now = recordedMs
                RabiLog.i("face", "切り替えて 3 秒: 録れた長さ ${now / 1000.0} 秒 (切り替えたとき ${atSwitch / 1000.0} 秒) → " +
                    if (now > atSwitch + 1_500) "切り替えたあとも録れています" else "切り替えたあとの映像が録れていません")
            }, SWITCH_CHECK_MS)
            null
        } catch (e: Exception) {
            RabiLog.w("face", "カメラを切り替えられません: ${e.javaClass.simpleName} ${e.message}")
            back = old
            runCatching { p.unbindAll(); readOptics(p.bindToLifecycle(o, selector, *cases)) }
            "カメラを切り替えられません (${e.javaClass.simpleName})"
        }
    }

    /** 顔の検出の画像を、録画の画面に映す向き (縦に起こす。前面は鏡のように左右を反転) の画像にする */
    private fun uprightBitmap(proxy: ImageProxy, rot: Int, mirror: Boolean): Bitmap {
        val src = proxy.toBitmap()
        if (rot == 0 && !mirror) return src
        val m = Matrix().apply { postRotate(rot.toFloat()); if (mirror) postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || !running) { proxy.close(); return }
        val now = SystemClock.elapsedRealtime()
        lastFrameMs = now
        // 録画の画面が出ていれば、映す画像を渡す (顔を調べる回数を減らしている間も、映像はなめらかに)
        val sink = previewSink
        if (sink != null && now - lastPreviewMs >= PREVIEW_FRAME_MS) {
            lastPreviewMs = now
            runCatching { sink(uprightBitmap(proxy, proxy.imageInfo.rotationDegrees, mirror = !back)) }
        }
        if (now - lastAnalyzedMs < minIntervalMs) { proxy.close(); return }
        lastAnalyzedMs = now
        val rot = proxy.imageInfo.rotationDegrees
        val upW = if (rot % 180 == 0) proxy.width else proxy.height
        val upH = if (rot % 180 == 0) proxy.height else proxy.width
        val atMs = captureMs(proxy.imageInfo.timestamp)
        val isBack = back
        (if (isBack) backDetector else detector).process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { faces ->
                if (!running) return@addOnSuccessListener
                val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                onSample(f?.let {
                    Sample(it.boundingBox.exactCenterX().toDouble(), it.boundingBox.exactCenterY().toDouble(), upW, upH,
                        focalPx(upW, upH), atMs, it.boundingBox.width().toDouble() / upW, isBack)
                })
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** 撮った時刻。カメラの時刻が起動からの時間 (elapsedRealtimeNanos) なら使い、違えば今にする */
    private fun captureMs(tsNs: Long): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        return if (tsNs in (now - 2_000_000_000L)..now) tsNs / 1_000_000 else SystemClock.elapsedRealtime()
    }

    /** 焦点距離 (ピクセル)。分からなければ、長い辺の画角を 75° とみなす */
    private fun focalPx(w: Int, h: Int): Double {
        val f = focalMm
        val s = sensorMm
        val long = max(w, h).toDouble()
        return if (f != null && f > 0f && s != null) f / max(s.width, s.height) * long else (long / 2.0) / tan(Math.toRadians(37.5))
    }

    private companion object {
        /** カメラを切り替えてから、録れた長さが伸びているかを見るまで */
        const val SWITCH_CHECK_MS = 3_000L
        /** 録画の画面に映す間隔 (1 秒に約 15 枚) */
        const val PREVIEW_FRAME_MS = 66L
    }
}
