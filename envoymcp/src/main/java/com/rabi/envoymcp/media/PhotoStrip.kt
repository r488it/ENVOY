package com.rabi.envoymcp.media

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.overlay.OverlayOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 見つけた写真を、画面の下に番号付きで並べる (「〇〇の写真を探して」で、どの写真かを目で確かめる)。
 * 番号は search_photos が ChatGPT に返す一覧と同じ (「2 番を LINE で送って」と声で選べる)。写真の画像は ChatGPT には渡さない。
 * - 写真を押す、または声で頼む (show_photo) と、その写真を画面いっぱいに大きく出す。もう一度押すと帯に戻る
 * - ユーザー補助の窓で出す (音声モードのリゲルの画面や、ほかのアプリの上にも出る)。リゲルの画面が出ていれば、下の × の上に出す。
 *   大きく出す間だけ、窓を画面いっぱいにする (帯の間は帯の大きさなので、ほかの所は押せる)
 * - 消すのは、共有の画面を開いたとき (share_photo)・帯の右上の × ・SHOW_MS 何もしなかったとき
 */
class PhotoStrip(private val g: McpGraph) {
    /** 出している写真 (帯の中身) と、大きく出している写真の位置 (null なら帯だけ) */
    private data class Shown(val query: String, val hits: List<PhotoSearch.Hit>, val big: Int?)
    private val shown = MutableStateFlow<Shown?>(null)
    private var view: View? = null
    private var wm: WindowManager? = null
    private var owner: OverlayOwner? = null
    private var lp: WindowManager.LayoutParams? = null
    private var stripY = 0
    private var hideJob: Job? = null

    /** 見つけた写真を帯に並べる (前のものは置き換える) */
    fun show(query: String, hits: List<PhotoSearch.Hit>) {
        if (hits.isEmpty()) return
        g.scope.launch(Dispatchers.Main) {
            shown.value = Shown(query, hits.take(MAX), null)
            ensureWindow()
            applyMode()
            touch()
        }
    }

    /**
     * 写真を大きく出す (声で頼まれた: show_photo)。帯に並べた写真ならその番号で、なければその写真だけを出す。
     * 出せたら、帯の中の番号 (並べていない写真は null)
     */
    suspend fun enlarge(hit: PhotoSearch.Hit): Int? = withContext(Dispatchers.Main) {
        val cur = shown.value
        val i = cur?.hits?.indexOfFirst { it.id == hit.id }?.takeIf { it >= 0 }
        shown.value = if (i != null) cur.copy(big = i) else Shown("", listOf(hit), 0)
        ensureWindow()
        applyMode()
        touch()
        i?.plus(1)
    }

    fun hide() {
        g.scope.launch(Dispatchers.Main) { remove() }
    }

    private fun setBig(i: Int?) {
        shown.value = shown.value?.copy(big = i)
        applyMode()
        touch()
    }

    /** 窓を作る (もうあれば、そのまま) */
    private fun ensureWindow() {
        if (view != null) return
        val a11y = A11yService.instance
        val type = when {
            a11y != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(g.context) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> { RabiLog.w("photos", "strip: no a11y or overlay permission"); return }
        }
        val ctx: Context = a11y ?: g.context
        val w = ctx.getSystemService(WindowManager::class.java)
        val density = ctx.resources.displayMetrics.density
        val nav = w.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        // リゲルの画面 (音声モード) が出ていれば、下の真ん中の × (72dp。下から 32dp) の上に出す
        stripY = nav + if (g.voiceStage.isShowing) ((32 + 72 + 12) * density).toInt() else (16 * density).toInt()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM
            y = stripY
            title = "rigel-photos"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { PhotoOverlay() }
        }
        runCatching { w.addView(v, p) }
            .onSuccess { view = v; wm = w; owner = o; lp = p; RabiLog.i("photos", "strip shown") }
            .onFailure { o.stop(); RabiLog.w("photos", "strip failed: ${it.javaClass.simpleName}") }
    }

    /** 帯だけのときは帯の大きさ、大きく出すときは画面いっぱいにする */
    private fun applyMode() {
        val v = view ?: return
        val p = lp ?: return
        val big = shown.value?.big != null
        p.height = if (big) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        p.gravity = if (big) Gravity.TOP else Gravity.BOTTOM
        p.y = if (big) 0 else stripY
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** 何かしたら、消すまでの時間を数え直す */
    private fun touch() {
        hideJob?.cancel()
        hideJob = g.scope.launch { delay(SHOW_MS); hide() }
    }

    private fun remove() {
        hideJob?.cancel(); hideJob = null
        shown.value = null
        val v = view ?: return
        view = null
        runCatching { wm?.removeView(v) }
        owner?.stop(); owner = null; wm = null; lp = null
    }

    @Composable
    private fun PhotoOverlay() {
        val s by shown.collectAsState()
        val cur = s ?: return
        val big = cur.big
        if (big != null) BigPhoto(cur.hits[big], if (cur.query.isNotEmpty()) big + 1 else null) { setBig(null).also { if (cur.query.isEmpty()) hide() } }
        else Strip(cur.query, cur.hits, onPick = { setBig(it) }, onClose = { hide() })
    }

    /** 帯 (番号・写真・撮った日)。違うかもしれないものは薄く。写真を押すと大きく、右上の × で閉じる */
    @Composable
    private fun Strip(query: String, hits: List<PhotoSearch.Hit>, onPick: (Int) -> Unit, onClose: () -> Unit) {
        val fmt = remember { SimpleDateFormat("M/d", Locale.JAPAN) }
        val top = hits.first().score
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE60E1621))
                .border(1.dp, Color(0x339FD8FF), RoundedCornerShape(18.dp)).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("「$query」で見つけた写真 (押すと大きく)", color = Color(0xFF9FB0C8), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x332B86D9)).clickable { onClose() }
                    .semantics { contentDescription = "見つけた写真を閉じる" }, contentAlignment = Alignment.Center) {
                    Text("×", color = Color(0xFFD2E8FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                hits.forEachIndexed { n, h ->
                    val weak = h.score < 0.40f || h.score < top - 0.20f
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.alpha(if (weak) 0.45f else 1f).clickable { onPick(n) }.semantics { contentDescription = "${n + 1} 番の写真を大きく見る" }) {
                        Box {
                            Thumb(h, 62.dp)
                            Badge(n + 1, Modifier.align(Alignment.TopStart).padding(3.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(fmt.format(Date(h.takenAt)), color = Color(0xFFD2E8FF), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    /** 大きく出した写真 (番号と撮った日時)。押すと帯に戻る */
    @Composable
    private fun BigPhoto(h: PhotoSearch.Hit, number: Int?, onBack: () -> Unit) {
        val fmt = remember { SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN) }
        var img by remember(h.id) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(h.id) {
            img = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(g.context.contentResolver, h.uri)) { d, info, _ ->
                        val s = BIG_PX.toFloat() / maxOf(info.size.width, info.size.height)
                        if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xF2050810)).clickable { onBack() }.semantics { contentDescription = "大きく出した写真。押すと戻る" }) {
            img?.let { Image(it, contentDescription = "写真", modifier = Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit) }
            Row(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (number != null) { Badge(number, Modifier, 32.dp, 17); Spacer(Modifier.width(10.dp)) }
                Text(fmt.format(Date(h.takenAt)) + " 撮影", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text("押すと戻ります", color = Color(0xFF9FB0C8), fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
        }
    }

    @Composable
    private fun Thumb(h: PhotoSearch.Hit, size: Dp) {
        var img by remember(h.id) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(h.id) {
            img = withContext(Dispatchers.IO) {
                runCatching { g.context.contentResolver.loadThumbnail(h.uri, Size(256, 256), null).asImageBitmap() }.getOrNull()
            }
        }
        Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2430))) {
            img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }

    @Composable
    private fun Badge(n: Int, modifier: Modifier, size: Dp = 20.dp, fontSize: Int = 12) {
        Box(modifier.size(size).background(Color(0xFF2B86D9), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
        }
    }

    companion object {
        /** 並べる最多 (search_photos の既定の枚数) */
        const val MAX = 5
        /** 何もしなかったら消すまで */
        const val SHOW_MS = 90_000L
        /** 大きく出すときの長い辺 (px) */
        private const val BIG_PX = 1600
    }
}
