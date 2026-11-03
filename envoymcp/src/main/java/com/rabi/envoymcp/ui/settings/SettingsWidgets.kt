package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 設定の検索 (SettingsSearch) と、今の言葉 (変わったら、カードと項目を見直す) */
internal val LocalSearch = staticCompositionLocalOf { SettingsSearch() }
internal val LocalQuery = compositionLocalOf { "" }
/** 今のカードの見出しと、見出しが検索に合っているか (合っていれば中は全部出す) */
internal val LocalCard = compositionLocalOf { "" }
internal val LocalShowAll = compositionLocalOf { true }

/** この項目を出すか (文字はカードごとに覚えておき、カードを出すかの判断に使う) */
@Composable internal fun shown(vararg text: String): Boolean {
    val s = LocalSearch.current
    LocalQuery.current
    val card = LocalCard.current
    if (card.isNotEmpty()) text.forEach { s.add(card, it) }
    return LocalShowAll.current || s.itemHit(*text)
}

/**
 * 設定のカード。見出しの行 (右に今の状態を一言) を押すと開く・閉じる (開いたものは覚えておく)。探している間は、合うカードを開いて出す。
 * 閉じているときも中身を高さ 0 で作っておく (検索のために、項目の文字を覚えておくため。見えず、押せず、読み上げもしない)
 */
@Composable internal fun Card(title: String, status: String = "", ok: Boolean? = null, content: @Composable () -> Unit) {
    val s = LocalSearch.current
    LocalQuery.current
    if (!s.cardVisible(title)) return
    val open = LocalOpen.current
    val expanded = s.active || open.isOpen(title)
    CompositionLocalProvider(LocalCard provides title, LocalShowAll provides s.titleHit(title)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF14171E))) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !s.active) { open.toggle(title) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color(0xFFFFD86B), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (status.isNotBlank()) Text((ok?.let { if (it) "● " else "○ " } ?: "") + status, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when (ok) { true -> Color(0xFF8BE39A); false -> Color(0xFFFFB4A8); null -> Color(0xFFAEB4C0) },
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
                if (!s.active) Text(if (expanded) "▾" else "▸", color = Color(0xFF7F8796), fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
            }
            val body = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            if (expanded) Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            else Hidden { Column(body) { content() } }
        }
    }
}

/** 開いているカード (見出し)。変えたら覚えておく */
internal class OpenCards(initial: Set<String>, private val save: (Set<String>) -> Unit) {
    private val titles = mutableStateListOf<String>().apply { addAll(initial) }
    fun isOpen(title: String) = title in titles
    fun toggle(title: String) {
        if (title in titles) titles.remove(title) else titles.add(title)
        save(titles.toSet())
    }
}
internal val LocalOpen = staticCompositionLocalOf { OpenCards(emptySet()) { } }
/** カードの中の小見出し */
@Composable internal fun Sub(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFFFFD86B), fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
@Composable internal fun Line(k: String, v: String, ok: Boolean? = null) {
    if (!shown(k, v)) return
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = Color(0xFFAEB4C0), fontSize = 14.sp, modifier = Modifier.width(90.dp))
        Text((ok?.let { if (it) "● " else "○ " } ?: "") + v, color = when (ok) { true -> Color(0xFF8BE39A); false -> Color(0xFFFFB4A8); null -> Color.White }, fontSize = 14.sp)
    }
}
@Composable internal fun Note(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFF9AA3B5), fontSize = 13.sp, lineHeight = 19.sp)
}
@Composable internal fun Btn(s: String, primary: Boolean = false, onClick: () -> Unit) {
    if (!shown(s)) return
    if (primary) Button(onClick = onClick) { Text(s) } else OutlinedButton(onClick = onClick) { Text(s, color = Color.White) }
}
@Composable internal fun Mono(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFFCFD5E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}

@Composable internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    if (!shown(label)) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** 見えず・押せず・読み上げもしない形で作る (検索のために、項目の文字を覚えるだけ) */
@Composable internal fun Hidden(content: @Composable () -> Unit) {
    Box(Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }) { Column { content() } }
}
