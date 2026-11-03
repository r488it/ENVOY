package com.rabi.envoymcp.arm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 撮りながら顔を追う間の画面。
 * 録画を始めると開き、撮っているカメラ (前面・背面) の映像を全画面に映す (顔の検出の画像を起こしたもの。FaceObserver の previewSink。
 * 映像の部品 (Preview) は、カメラを切り替えたあと録画に映像が届かなくなるのでつながない)。
 * 上に録画の時間と顔を追っているか、下に「止める」と前面・背面の切り替え (録画は止めない)。
 * 録画が終わると (止める・ChatGPT の arm_stop_recording・時間切れ) 閉じる。
 * 画面を離れても録画は続く (映すのをやめるだけ)
 */
class RecordActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    /** 映す画像 (カメラの処理の糸から届く) */
    private val frames = MutableStateFlow<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { RecordContent(g, frames) { finish() } }
    }

    override fun onStart() {
        super.onStart()
        // 音声モードの全画面のリゲルが出ていたら、すぐ消す (この画面の上に重なって、映像とボタンを隠すため)
        g.recordScreenVisible = true
        g.voiceStage.yieldToRecord()
        g.arm.setPreviewSink { frames.value = it }
    }

    override fun onStop() {
        g.recordScreenVisible = false
        g.arm.setPreviewSink(null)
        super.onStop()
    }

    companion object {
        fun open(context: Context) {
            runCatching {
                context.startActivity(Intent(context, RecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
    }
}

@Composable
private fun RecordContent(g: McpGraph, frames: StateFlow<Bitmap?>, close: () -> Unit) {
    val st by g.arm.status.collectAsState()
    val frame by frames.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    // 録画が終わったら閉じる (開いた直後で、まだ始まっていないときは少し待つ)
    LaunchedEffect(st.recording) { if (!st.recording) { delay(1_500); if (!g.arm.status.value.recording) close() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val secs = if (st.recording && st.recordingSince > 0) ((now - st.recordingSince) / 1000).coerceAtLeast(0) else 0
            Label((if (st.recording) "● 録画中${if (st.recordingBack) " (背面)" else ""} " else "録画を準備しています ") + "%d:%02d".format(secs / 60, secs % 60), Color(0xFFFF5A5A))
            Label(when {
                !st.following -> "顔は追っていません"
                st.face != null -> "顔を追っています"
                else -> "顔が見えません"
            }, Color.White)
        }
        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 36.dp)
            .size(84.dp).background(Color.White.copy(alpha = 0.92f), CircleShape)
            .semantics { contentDescription = "録画を止める" }
            .clickable { scope.launch { g.arm.recordStop() } },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp).background(Color(0xFFE53935), RoundedCornerShape(6.dp)))
        }
        // 前面・背面の切り替え (録画は止めない。顔を追っていれば、腕を回して切り替えたカメラを顔に向ける)
        if (st.recording) {
            Box(Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(end = 28.dp, bottom = 48.dp)
                .background(Color.Black.copy(alpha = if (switching) 0.3f else 0.55f), RoundedCornerShape(30.dp))
                .semantics { contentDescription = if (st.recordingBack) "前面カメラに切り替える" else "背面カメラに切り替える" }
                .clickable(enabled = !switching) { switching = true; scope.launch { try { g.arm.recordSwitch(null) } finally { switching = false } } }
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(if (switching) "切り替え中…" else if (st.recordingBack) "⇄ 前面へ" else "⇄ 背面へ", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Label(s: String, color: Color) =
    Text(s, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
