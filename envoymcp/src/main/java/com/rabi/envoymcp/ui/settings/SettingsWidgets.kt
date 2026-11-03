package com.rabi.envoymcp.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 設定の画面の色 (暗い背景・黄色の見出し・状態の色) */
internal object Palette {
    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF14171E)
    val Divider = Color(0xFF232833)
    val Title = Color(0xFFFFD86B)
    val Accent = Color(0xFFA8DFFF)
    val Primary = Color(0xFFFFC93C)
    val Label = Color(0xFFAEB4C0)
    val Muted = Color(0xFF9AA3B5)
    val Chevron = Color(0xFF7F8796)
    val Ok = Color(0xFF8BE39A)
    val Warn = Color(0xFFFFC46B)
    val Off = Color(0xFF8A92A3)
}

/** 状態の色: true は使える (緑)、false は見てほしい (黄)、null はオフ・どちらでもない (灰) */
internal fun stateColor(ok: Boolean?) = when (ok) { true -> Palette.Ok; false -> Palette.Warn; null -> Palette.Off }

/** 設定の検索 (SettingsSearch) と、今の言葉 (変わったら、カードと項目を見直す) */
internal val LocalSearch = staticCompositionLocalOf { SettingsSearch() }
internal val LocalQuery = compositionLocalOf { "" }
/** 今のカードの見出しと、見出しが検索に合っているか (合っていれば中は全部出す) */
internal val LocalCard = compositionLocalOf { "" }
internal val LocalShowAll = compositionLocalOf { true }
/** 今作っているカードが入っている区分 (一覧の 1 行) */
internal val LocalSection = compositionLocalOf { Section.CONNECT }
/** 探した結果のカードを押したとき: その区分を開き、そのカードまで動かす */
internal val LocalJump = staticCompositionLocalOf<(Section, String) -> Unit> { { _, _ -> } }
/**
 * 開いた区分で目立たせるカード (n は飛ぶたびに変わる。同じカードへもう一度飛んでも動かすため)。
 * done は一度動かしたら立てる (カードが作り直されても、もう一度は動かさない)
 */
internal data class Focus(val card: String, val n: Int) { var done = false }
internal val LocalFocus = compositionLocalOf { Focus("", 0) }

/** この項目を出すか (文字はカードごとに覚えておき、カードを出すかの判断に使う) */
@Composable internal fun shown(vararg text: String): Boolean {
    val s = LocalSearch.current
    LocalQuery.current
    val card = LocalCard.current
    if (card.isNotEmpty()) text.forEach { s.add(card, it) }
    return LocalShowAll.current || s.itemHit(*text)
}

/** 状態の色の点 */
@Composable internal fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** 状態の札 (点と一言)。色は stateColor のとおり */
@Composable internal fun Chip(text: String, ok: Boolean?, modifier: Modifier = Modifier) {
    val c = stateColor(ok)
    Row(modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(c, Modifier.padding(end = 6.dp), 7.dp)
        Text(text, color = c, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 設定のカード。見出しの左にアイコン (icon)、右に今の状態を札で出す。探している間は合うカードだけを出し、見出しを押すとそのカードの区分を開く。
 * folded のカードは、見出しを押すと開く・閉じる (開いたものは覚えておく)。閉じているときも中身を高さ 0 で作っておく
 * (検索のために、項目の文字を覚えておくため。見えず、押せず、読み上げもしない)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun Card(title: String, status: String = "", ok: Boolean? = null, folded: Boolean = false, icon: ImageVector? = null,
                              content: @Composable () -> Unit) {
    val s = LocalSearch.current
    LocalQuery.current
    val section = LocalSection.current
    s.place(title, section.title)
    if (!s.cardVisible(title)) return
    val open = LocalOpen.current
    val expanded = !folded || s.active || open.isOpen(title)
    val jump = LocalJump.current
    val focus = LocalFocus.current
    val bring = remember { BringIntoViewRequester() }
    var glow by remember { mutableStateOf(false) }
    // 一覧・探した結果から飛んできたときは、このカードまで動かして、少しの間ふちを光らせる
    LaunchedEffect(focus) {
        if (focus.card != title || focus.done) return@LaunchedEffect
        focus.done = true
        if (folded && !open.isOpen(title)) open.toggle(title)
        delay(150)
        bring.bringIntoView()
        glow = true; delay(1_800); glow = false
    }
    val edge by animateColorAsState(if (glow) Palette.Accent else Color.Transparent, label = "focus")
    val shape = RoundedCornerShape(16.dp)
    CompositionLocalProvider(LocalCard provides title, LocalShowAll provides s.titleHit(title)) {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(shape).background(Palette.Surface).border(2.dp, edge, shape)) {
            val head = when {
                s.active -> Modifier.clickable { jump(section, title) }
                folded -> Modifier.clickable { open.toggle(title) }
                else -> Modifier
            }
            Row(Modifier.fillMaxWidth().then(head).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) IconTile(icon, Palette.Accent, size = 30.dp)
                Text(title, color = Palette.Title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = if (icon != null) 12.dp else 0.dp))
                if (status.isNotBlank()) Chip(status, ok, Modifier.padding(start = 8.dp).widthIn(max = 180.dp))
                when {
                    s.active -> Text("›", color = Palette.Chevron, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
                    folded -> Text(if (expanded) "▾" else "▸", color = Palette.Chevron, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            val body = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            if (expanded) Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
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
    Text(s, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

/** 名前と値の行。ok が true / false のときは、値を状態の色にして前に点を出す */
@Composable internal fun Line(k: String, v: String, ok: Boolean? = null) {
    if (!shown(k, v)) return
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = Palette.Label, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.width(104.dp).padding(end = 8.dp))
        Row(Modifier.weight(1f)) {
            if (ok != null) Dot(stateColor(ok), Modifier.padding(top = 6.dp, end = 7.dp))
            Text(v, color = if (ok == null) Color.White else stateColor(ok), fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable internal fun Note(s: String) {
    if (!shown(s)) return
    Text(s, color = Palette.Muted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable internal fun Btn(s: String, primary: Boolean = false, onClick: () -> Unit) {
    if (!shown(s)) return
    if (primary) Button(onClick = onClick) { Text(s) } else OutlinedButton(onClick = onClick) { Text(s, color = Color.White) }
}

/** ボタンを横に並べる (入りきらなければ折り返す) */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Btns(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable internal fun Mono(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFFCFD5E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}

/** スイッチの行 (文字を押しても切り替わる) */
@Composable internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    if (!shown(label)) return
    Row(Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** 見えず・押せず・読み上げもしない形で作る (検索のために、項目の文字を覚えるだけ) */
@Composable internal fun Hidden(content: @Composable () -> Unit) {
    Box(Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }) { Column { content() } }
}
