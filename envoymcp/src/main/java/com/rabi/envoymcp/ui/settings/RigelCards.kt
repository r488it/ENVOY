package com.rabi.envoymcp.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rabi.envoymcp.McpGraph

/** 音声モードの画面: ChatGPT の音声モードの間、画面全体をリゲルにする・声に合わせて動かす */
@Composable
internal fun VoiceStageCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val stage = remember(tick) { g.settings.voiceStage }
    val level = remember(tick) { g.settings.voiceStageLevel }
    val mic = remember(tick) { a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val peak by g.voiceStage.peak.collectAsState()
    Card("音声モードの画面", if (stage) "オン" else "オフ", if (stage) true else null, icon = Icons.Rounded.GraphicEq) {
        Toggle("音声モードの間は画面全体をリゲルにする", stage) { a.setVoiceStage(it) }
        Note("ChatGPT の音声モードの間 (前面が ChatGPT で、端末が通話モードのとき)、ホームと同じリゲルで画面全体を覆います。下の真ん中の × で会話を終えて ChatGPT を閉じます。ChatGPT のマイクのオフ・文字の入力は使えなくなります。端末の操作の接続が要ります。")
        Toggle("ChatGPT の声に合わせて動かす", level) { a.setVoiceStageLevel(it) }
        if (level) {
            Line("マイクの許可", if (mic) "許可" else "未許可", mic)
            Line("取れた声の大きさ", if (peak > 0f) "最大 ${(peak * 100).toInt()}%" else "まだ 0 (取れていない)", if (peak > 0f) true else null)
        }
        Note("声に合わせて動かすときは、ChatGPT が声を流している流れを端末の操作の接続 (ADB) で見つけて、その波形を読みます (マイクの音は使いません)。Android では、その読み取りにマイクの許可が要ります。話している間は声の波でふくらみ・目を開き・首を揺らし、間でまばたき・うなずき、強く言ったところで目を見開きます。黙っている間は聞く顔です。")
    }
}

/** 最初の一言: リゲルのタップで開いた音声モードに、ENVOY MCP を使うよう伝える文を送る */
@Composable
internal fun PrimerCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val primer = remember(tick) { g.settings.voicePrimer }
    var primerText by remember(tick) { mutableStateOf(g.settings.voicePrimerText) }
    var primerSaid by remember { mutableStateOf("") }
    Card("最初の一言", if (primer) "オン" else "オフ", if (primer) true else null, icon = Icons.Rounded.Edit) {
        Toggle("音声モードの始めに、ENVOY MCP を使うよう伝える", primer) { a.setVoicePrimer(it) }
        Note("リゲルのタップで音声モードを開いたとき、ChatGPT の入力欄に下の文を入れて送ります (ユーザー補助で。覆いの下で送ります)。ChatGPT はこの文に声で答えるので、最初の挨拶になります。リゲルのタップで新しく始めた会話に 1 回だけで、ChatGPT のアプリから直接始めた会話には送りません。送った文は ChatGPT の会話の履歴に残ります。ユーザー補助の接続が要ります。")
        if (primer) {
            OutlinedTextField(primerText, { primerText = it }, label = { Text("最初の一言") }, modifier = Modifier.fillMaxWidth())
            Btns {
                Btn("保存") { primerSaid = a.saveVoicePrimerText(primerText) }
                Btn("最初の文に戻す") { primerSaid = a.saveVoicePrimerText("") }
            }
            if (primerSaid.isNotEmpty()) Note(primerSaid)
        }
    }
}

/** 音楽の画面: 音楽が流れたら、画面いっぱいのリゲルとスペクトラムを出す (光らせる・歌詞・試す) */
@Composable
internal fun MusicStageCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val musicStage = remember(tick) { g.settings.musicStage }
    val musicFlash = remember(tick) { g.settings.musicFlash }
    val musicLyrics = remember(tick) { g.settings.musicLyrics }
    val mic = remember(tick) { a.hasMic() }
    Card("音楽の画面", when { !musicStage -> "オフ"; !mic -> "マイクが未許可"; else -> "オン" }, when { !musicStage -> null; else -> mic }, icon = Icons.Rounded.MusicNote) {
        Toggle("音楽が流れたらリゲルの画面にする", musicStage) { a.setMusicStage(it) }
        Note("YouTube Music・Spotify などで音楽が流れ始めたら、画面いっぱいのリゲルと、まわりにカラフルなスペクトラムを出します (動画では出しません)。下の × で閉じると、その曲の間は出しません。音楽が止まると閉じます。音に合わせて光らせるのにマイクの許可が要ります (端末の出力の音を読むため。マイクの音は使いません)。MCP サーバーが動いている間だけ。")
        if (musicStage && !a.hasMic()) Btn("マイクを許可する", true) { a.requestMic() }
        if (musicStage) {
            Toggle("音楽の画面で、拍に合わせて光らせる", musicFlash) { a.setMusicFlash(it) }
            Note("キックのたびに、スペクトラムとヘッドホンが明るくなります。光に敏感な方はオフにしてください (オフでも、スペクトラムの動きとヘッドホンは出ます)。")
            Toggle("音楽の画面に歌詞を出す", musicLyrics) { a.setMusicLyrics(it) }
            Note("歌われる時刻に合わせて、歌詞がリゲルの後ろに降ってきます (下に読むための今の行も)。歌詞は LRCLIB (lrclib.net) から取るので、曲名・アーティスト・アルバム・曲の長さをインターネットに送ります (音は送りません)。取った歌詞は端末にためます。日本の曲は、載っていない・時刻がずれていることがあります。歌詞には著作権があるので、公開する録画に映すときは気をつけてください。")
        }
        Sub("試す")
        Btns {
            Btn("リゲルの画面を試しに出す") { g.musicStage.show() }
            Btn("音楽の表情を試す (約 50 秒)") { g.musicStage.showDemo() }
        }
        Note("音楽を流さずに、決まった筋書きで音楽の画面を動かします: 静かなイントロ (目を閉じる) → ノり (ノり方 5 種類と、小節の頭の仕草 5 種類を順に) → だんだん強く (ため) → 盛り上がり (＞＜・ヘドバン) → 盛り上がりの後のにっこり → 静かな終わり。終わると閉じます (× でも閉じます)。")
    }
}

/** リゲルの表情: ホームのリゲルで、感情の表情を順に見せる */
@Composable
internal fun ExpressionCard(a: SettingsActivity) {
    Card("表情", icon = Icons.Rounded.EmojiEmotions) {
        Btn("リゲルの表情を順に見る (ホームで)") { a.previewExpressions() }
        Note("うれしい・笑う・驚き・悲しい・照れ・ウインクを、ホームのリゲルで順に見せます (全部で 20 秒ほど)。")
    }
}
