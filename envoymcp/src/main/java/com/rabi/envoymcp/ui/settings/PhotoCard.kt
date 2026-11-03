package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.media.PhotoSearch
import kotlinx.coroutines.launch

/** 写真を言葉で探す (EmbeddingGemma 2): 許可・モデル・索引・試しに探す・ライセンス */
@Composable
internal fun PhotoCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val ps = g.photos
    val st by ps.status.collectAsState()
    val perm = remember(tick) { ps.hasPermission() }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PhotoSearch.Hit>>(emptyList()) }
    var said by remember { mutableStateOf("") }
    var showLicense by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mb = ps.spec.size / 1_000_000
    // 設定を開くたびに見直す (モデルを ADB で置いたとき・写真を許可したとき)。モデルと許可があれば、新しい写真の索引を作り始める
    LaunchedEffect(tick) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ps.refreshStatus() }; ps.startIndexing() }
    val status = when {
        !perm -> "写真が未許可"
        !st.modelReady -> if (st.downloading) "モデルを落としています" else "モデルなし"
        st.indexing -> "索引 ${st.indexed}/${st.total}"
        else -> "索引 ${st.indexed} 枚"
    }
    Card("写真を探す (EmbeddingGemma 2)", status, if (!perm || !st.modelReady) false else true) {
        Note("ChatGPT に「〇〇の写真を探して、X にポストして」「LINE で〇〇さんに送って」と頼めます。写真は、スマホの中の AI (EmbeddingGemma 2。${ps.spec.label}) が言葉との近さで探し、スマホの外には出しません。ChatGPT に渡すのは、写真の番号・撮った日時・近さだけです。投稿・送信のボタンを押すときは、いつもどおりスマホでの承認が要ります。")
        Line("写真", if (perm) "許可" else "未許可", perm)
        if (!perm) Btn("写真へのアクセスを許可する (「すべて許可」を選ぶ)", true) { a.requestPhotos() }
        Line("モデル", when {
            st.modelReady -> "入っています (${ps.spec.label})"
            st.downloading -> "落としています ${(st.downloaded * 100 / ps.spec.size).coerceIn(0, 100)}%"
            else -> "入っていません ($mb MB)"
        }, if (st.modelReady) true else null)
        if (!st.modelReady) {
            if (st.downloading) Btn("止める") { ps.cancelDownload() }
            else Btn("モデルを落とす ($mb MB。Wi-Fi のときに)", true) { ps.download() }
        }
        Line("索引", "${st.indexed} / ${if (st.total >= 0) st.total else "?"} 枚" + if (st.msPerPhoto > 0) " (1 枚 ${st.msPerPhoto} ms)" else "",
            if (st.indexing) null else if (st.total in 1..st.indexed) true else null)
        if (st.modelReady && perm) {
            if (st.indexing) Btn("索引を作るのを止める") { ps.stopIndexing() } else Btn("索引を作る (新しい写真)") { ps.startIndexing() }
        }
        if (st.note.isNotBlank()) Note(st.note)
        Note("索引は、写真ごとの特徴 (数字の並び) をこのスマホの中に置いたものです。写真が増えたら、探すとき・アプリを開き直したときに足します。")
        if (st.modelReady && perm && shown("試しに探す")) {
            OutlinedTextField(q, { q = it }, label = { Text("試しに探す (例: 猫・夕焼け)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Btn("探す") {
                if (q.isNotBlank()) scope.launch {
                    said = "探しています…"; hits = emptyList()
                    hits = runCatching { ps.search(q.trim(), 6) }.getOrElse { said = "探せませんでした (${it.javaClass.simpleName})"; emptyList() }
                    if (said == "探しています…") said = if (hits.isEmpty()) "見つかりませんでした" else ""
                }
            }
            if (said.isNotBlank()) Note(said)
            if (hits.isNotEmpty()) PhotoHits(g, hits)
        }
        if (st.modelReady) Btn(if (confirmDelete) "もう一度押すとモデルを消します" else "モデルを消す") { if (confirmDelete) { ps.deleteModel(); confirmDelete = false } else confirmDelete = true }
        Btn(if (showLicense) "ライセンスを閉じる" else "ライセンス (Edge Gallery・EmbeddingGemma 2)") { showLicense = !showLicense }
        if (showLicense) {
            val text = remember { runCatching { listOf("NOTICE", "LICENSE").joinToString("\n\n") { f -> a.assets.open("licenses/edge-gallery/$f").bufferedReader().readText() } }.getOrDefault("ライセンスの表示がありません") }
            Mono(text)
        }
    }
}

/** 試しに探した写真 (近い順。小さな写真と近さ) */
@Composable
private fun PhotoHits(g: McpGraph, hits: List<PhotoSearch.Hit>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hits.forEach { h ->
            var img by remember(h.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(h.id) {
                img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { g.context.contentResolver.loadThumbnail(h.uri, android.util.Size(256, 256), null).asImageBitmap() }.getOrNull()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2430))) {
                    img?.let { androidx.compose.foundation.Image(it, contentDescription = "見つけた写真", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
                Text("%.2f".format(h.score), color = Color(0xFFAEB4C0), fontSize = 12.sp)
            }
        }
    }
}
