package com.rabi.envoymcp.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.call.CALL_DIM
import com.rabi.envoymcp.call.CALL_GREEN
import com.rabi.envoymcp.call.CALL_RIGEL
import com.rabi.envoymcp.call.CALL_SUB
import com.rabi.envoymcp.mcp.ActivityLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * したこと: AI エージェント (ChatGPT など) とリゲルが、このスマホでしたことを、日ごとに新しい順で見る。
 * 1 行は、時刻・何をしたか・だれが・承認した / 断った / 止めた / うまくいかなかった。見ただけのもの (画面を読んだ など) は、ふだんは出さない。
 * 中身はこのスマホの中だけ (ActivityLog)。ホームの左下の「したこと」と、設定の「安心 (承認)」から開く
 */
class ActivityActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ActivityScreen(g) { finish() } }
    }
}

private val PAGE_BG = Brush.radialGradient(listOf(Color(0xFF0E1621), Color(0xFF05080C)), radius = 2400f)
private val CARD_BG = Color(0xEB1E2C40)
private val CARD_LINE = Color(0x389FD8FF)
private val WARN = Color(0xFFFFC46B)
private val BAD = Color(0xFFFF9C9C)
private val TIME_FMT = SimpleDateFormat("H:mm", Locale.JAPAN)
private val DAY_FMT = SimpleDateFormat("M/d", Locale.JAPAN)

@Composable
private fun ActivityScreen(g: McpGraph, onClose: () -> Unit) {
    val entries by g.activity.entries.collectAsState()
    var showReads by rememberSaveable { mutableStateOf(false) }
    // 日ごとに分ける (新しい日が先。中も新しい順)
    val days = remember(entries, showReads) {
        entries.filter { showReads || it.kind != ActivityLog.KIND_READ }.groupBy { dayStart(it.at) }.toList()
    }
    Box(Modifier.fillMaxSize().background(PAGE_BG).systemBarsPadding()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("したこと", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("閉じる", color = CALL_RIGEL, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("AI エージェントとリゲルが、このスマホでしたことです。このスマホの中だけに、30 日まで残します。入れた文字・画面の中身・メッセージ・電話番号は残しません。",
                    color = CALL_DIM, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).toggleable(showReads, role = Role.Switch) { showReads = it }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("見ただけのものも出す", color = CALL_SUB, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showReads, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2F6FED), checkedThumbColor = Color.White))
                }
            }
            if (days.isEmpty()) item {
                Text("まだありません", color = CALL_SUB, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 48.dp))
            }
            days.forEach { (day, list) ->
                item { Text(dayLabel(day), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp)) }
                items(list) { e -> EntryRow(e) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(e: ActivityLog.Entry) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(CARD_BG).border(1.dp, CARD_LINE, shape).padding(14.dp)) {
        Text(TIME_FMT.format(Date(e.at)), color = CALL_SUB, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(e.sentence, color = Color.White, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(e.agent, CALL_RIGEL)
                badge(e)?.let { (text, color) -> Tag(text, color) }
                if (e.kind == ActivityLog.KIND_READ) Tag("見ただけ", CALL_DIM)
            }
        }
    }
}

/** 承認した・断った・止めた・うまくいかなかった (どれでもなければ出さない) */
private fun badge(e: ActivityLog.Entry): Pair<String, Color>? = when {
    e.outcome == ActivityLog.REFUSED -> "断った" to WARN
    e.outcome == ActivityLog.STOPPED -> "止めた" to WARN
    e.outcome == ActivityLog.FAILED -> "うまくいかなかった" to BAD
    e.approved == true -> "本人が承認" to CALL_GREEN
    else -> null
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** その日の 0 時 */
private fun dayStart(at: Long): Long = Calendar.getInstance().apply {
    timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 今日・昨日・M/d */
private fun dayLabel(day: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    return when (day) {
        today -> "今日"
        yesterday -> "昨日"
        else -> DAY_FMT.format(Date(day))
    }
}
