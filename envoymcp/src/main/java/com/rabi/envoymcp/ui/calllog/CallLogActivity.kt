package com.rabi.envoymcp.ui.calllog

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.call.CALL_DIM
import com.rabi.envoymcp.call.CALL_GREEN
import com.rabi.envoymcp.call.CALL_RIGEL
import com.rabi.envoymcp.call.CALL_SUB
import com.rabi.envoymcp.call.CallInfo
import com.rabi.envoymcp.call.CallTranscript
import com.rabi.envoymcp.call.CallerCircle
import com.rabi.envoymcp.call.MiniOrb
import com.rabi.envoymcp.call.mmss
import com.rabi.envoymcp.call.outcomeLabel
import com.rabi.envoymcp.call.outcomeOk
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通話の記録 (リゲルが代わりにかけた・出た電話のやり取りを、あとから見る。録音も聞く)。
 * 一覧 (新しい順) → 1 本のまとめ・録音・やり取り (文字)。消せる。
 * 中身はこのスマホの中だけ (CallRecords)。設定の「リゲルが代わりに電話する」から開く
 */
class CallLogActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallLogScreen(g) { finish() } }
    }

    /** 開いたら、新しい記録は見たことにする (通知・アイコンの点・ホームの件数を消す) */
    override fun onResume() {
        super.onResume()
        g.callRecords.markSeen()
    }
}

private val PAGE_BG = Brush.radialGradient(listOf(Color(0xFF0E1621), Color(0xFF05080C)), radius = 2400f)
private val CARD_BG = Color(0xEB1E2C40)
private val CARD_LINE = Color(0x389FD8FF)
private val LABEL = Color(0xFF9FB0C8)
private val DATE_FMT = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
private val DATE_LONG = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.JAPAN)

@Composable
private fun CallLogScreen(g: McpGraph, onClose: () -> Unit) {
    var items by remember { mutableStateOf<List<CallInfo>>(emptyList()) }
    var open by remember { mutableStateOf<CallInfo?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { items = withContext(Dispatchers.IO) { g.callRecords.list() } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }
    BackHandler(enabled = open != null) { open = null }
    Box(Modifier.fillMaxSize().background(PAGE_BG).systemBarsPadding()) {
        val c = open
        if (c != null) {
            CallDetail(g, c, onBack = { open = null }, onDelete = {
                scope.launch { withContext(Dispatchers.IO) { g.callRecords.delete(c) }; open = null; reload() }
            })
            return@Box
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("通話の記録", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("閉じる", color = CALL_RIGEL, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("リゲルが代わりにかけた電話・出た電話のやり取りです。このスマホの中だけに残しています。", color = CALL_DIM, fontSize = 13.sp)
            }
            if (items.isEmpty()) item {
                Text("まだありません", color = CALL_SUB, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
            }
            items(items, key = { it.id }) { r -> CallRow(r) { open = r } }
        }
    }
}

@Composable
private fun CallRow(c: CallInfo, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(CARD_BG).border(1.dp, CARD_LINE, shape).clickable { onOpen() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallerCircle(ringing = false, diameter = 44.dp, rings = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(listOfNotNull(dirLabel(c), DATE_FMT.format(Date(c.startedAt)), c.talkSec.takeIf { it > 0 }?.let { durLabel(it) }).joinToString(" ・ "),
                color = CALL_SUB, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(c.outcomeLabel(), color = if (c.outcomeOk()) CALL_GREEN else CALL_SUB, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (c.audio.isNotEmpty()) Text("録音あり", color = CALL_DIM, fontSize = 11.sp)
        }
    }
}

/** 1 本のまとめ・録音・やり取り */
@Composable
private fun CallDetail(g: McpGraph, c: CallInfo, onBack: () -> Unit, onDelete: () -> Unit) {
    val glow = remember { AvatarStyle.byId(g.settings.avatar).glow }
    var confirm by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("‹ 一覧", color = CALL_RIGEL, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start).clip(RoundedCornerShape(18.dp)).clickable { onBack() }.padding(horizontal = 8.dp, vertical = 8.dp))
        Spacer(Modifier.height(8.dp))
        MiniOrb(glow, false, Modifier.size(84.dp))
        Spacer(Modifier.height(14.dp))
        Text(if (c.dir == "in") "代わりに出た電話" else "電話のまとめ", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(18.dp))
        val shape = RoundedCornerShape(17.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(CARD_BG).border(1.dp, CARD_LINE, shape).padding(20.dp)) {
            if (c.dir == "in") {
                Text(c.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Field("用件", c.summary.ifBlank { c.outcomeLabel() })
                Field("結果", c.outcomeLabel())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.outcomeOk()) { CheckCircle(Modifier.size(34.dp)); Spacer(Modifier.width(10.dp)) }
                    Text(c.outcomeLabel(), color = if (c.outcomeOk()) CALL_GREEN else CALL_SUB, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Field("相手", c.name + if (c.app == "line") "（LINE）" else "")
                if (c.message.isNotBlank()) Field("伝えたこと", c.message)
                if (c.summary.isNotBlank()) Field("相手の返事", c.summary)
            }
            if (c.reason.isNotBlank() && !c.outcomeOk()) Field("理由", c.reason)
            Field("通話", if (c.talkSec > 0) durLabel(c.talkSec) else "つながっていません")
            Field("日時", DATE_LONG.format(Date(c.startedAt)))
        }
        g.callRecords.audioFile(c)?.let { f ->
            Spacer(Modifier.height(14.dp))
            RecordingPlayer(f)
        }
        Spacer(Modifier.height(14.dp))
        CallTranscript(c.lines, Modifier.fillMaxWidth(), hint = "やり取りの文字はありません")
        Spacer(Modifier.height(22.dp))
        Text(if (confirm) "もう一度押すと消します（録音も消えます）" else "この記録を消す", color = Color(0xFFFF8A8A), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { if (confirm) onDelete() else confirm = true }.padding(horizontal = 16.dp, vertical = 10.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Field(label: String, value: String) {
    Spacer(Modifier.height(16.dp))
    Text(label, color = LABEL, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(value, color = Color.White, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold)
}

/** 録音を聞く (再生・止める・位置) */
@Composable
private fun RecordingPlayer(file: File) {
    val player = remember(file) { runCatching { MediaPlayer().apply { setDataSource(file.path); prepare() } }.getOrNull() }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    val shape = RoundedCornerShape(17.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(Color(0xD9080D16)).border(1.dp, Color(0x2978B4FF), shape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (player == null) { Text("録音を再生できません", color = CALL_SUB, fontSize = 14.sp); return@Row }
        var playing by remember { mutableStateOf(false) }
        var pos by remember { mutableIntStateOf(0) }
        val dur = remember(player) { player.duration.coerceAtLeast(1) }
        LaunchedEffect(player) { player.setOnCompletionListener { playing = false; pos = 0; runCatching { it.seekTo(0) } } }
        LaunchedEffect(playing) { while (playing) { pos = runCatching { player.currentPosition }.getOrDefault(pos); delay(200) } }
        PlayButton(playing, Modifier.size(44.dp).semantics { contentDescription = if (playing) "止める" else "録音を再生" }.clickable {
            if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
        })
        Spacer(Modifier.width(8.dp))
        Slider(value = pos.toFloat() / dur, onValueChange = { v -> pos = (v * dur).toInt(); player.seekTo(pos) }, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = CALL_RIGEL, activeTrackColor = CALL_RIGEL, inactiveTrackColor = Color(0x339FD8FF)))
        Spacer(Modifier.width(8.dp))
        Text("${mmss(pos / 1000L)} / ${mmss(dur / 1000L)}", color = CALL_SUB, fontSize = 12.sp)
    }
}

@Composable
private fun PlayButton(playing: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Color(0x4D2B86D9), radius = r)
        drawCircle(Color(0x809FD8FF), radius = r, style = Stroke(width = 1.dp.toPx()))
        val w = Color(0xFFD2E8FF)
        if (playing) {
            for (sx in listOf(-1f, 1f)) drawLine(w, center + Offset(sx * r * 0.2f, -r * 0.32f), center + Offset(sx * r * 0.2f, r * 0.32f),
                strokeWidth = r * 0.16f, cap = StrokeCap.Round)
        } else {
            drawPath(Path().apply {
                moveTo(center.x - r * 0.22f, center.y - r * 0.36f); lineTo(center.x + r * 0.38f, center.y); lineTo(center.x - r * 0.22f, center.y + r * 0.36f); close()
            }, w)
        }
    }
}

@Composable
private fun CheckCircle(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Color(0xFF1D9A5B), radius = r)
        val p = Path().apply {
            moveTo(center.x - r * 0.42f, center.y + r * 0.02f); lineTo(center.x - r * 0.1f, center.y + r * 0.34f); lineTo(center.x + r * 0.46f, center.y - r * 0.3f)
        }
        drawPath(p, Color.White, style = Stroke(width = r * 0.18f, cap = StrokeCap.Round))
    }
}

private fun dirLabel(c: CallInfo) = (if (c.dir == "in") "代わりに出た" else "かけた") + if (c.app == "line") "（LINE）" else ""

private fun durLabel(sec: Long) = if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
