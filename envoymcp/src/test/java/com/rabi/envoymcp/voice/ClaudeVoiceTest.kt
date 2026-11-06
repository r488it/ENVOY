package com.rabi.envoymcp.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Claude のアプリの画面の見分け: 部品の説明の合わせ方と、音声モードの画面か */
class ClaudeVoiceTest {
    @Test fun labelsMatchWholeDescription() {
        assertTrue(ClaudeVoice.matches("音声モード", ClaudeVoice.VOICE_MODE))
        assertTrue(ClaudeVoice.matches(" Voice mode ", ClaudeVoice.VOICE_MODE))
        assertTrue(ClaudeVoice.matches("voice MODE", ClaudeVoice.VOICE_MODE))
        assertTrue(ClaudeVoice.matches("送信", ClaudeVoice.SEND))
        assertTrue(ClaudeVoice.matches("新規セッション", ClaudeVoice.NEW_CHAT))
        assertTrue(ClaudeVoice.matches("New chat", ClaudeVoice.NEW_CHAT))
        assertTrue(ClaudeVoice.matches("停止", ClaudeVoice.STOP))
        // 一部だけ合うもの (書き取りのマイク「音声入力を開始」・音声のミュートなど) は違う
        assertFalse(ClaudeVoice.matches("音声入力を開始", ClaudeVoice.VOICE_MODE))
        assertFalse(ClaudeVoice.matches("音声モードを開始", ClaudeVoice.VOICE_MODE))
        assertFalse(ClaudeVoice.matches("Claude の音声をミュート", ClaudeVoice.MUTE_MIC))
        assertFalse(ClaudeVoice.matches("停止する", ClaudeVoice.STOP))
        assertFalse(ClaudeVoice.matches(null, ClaudeVoice.STOP))
        assertFalse(ClaudeVoice.matches("  ", ClaudeVoice.STOP))
    }

    @Test fun voiceScreenNeedsStopAndNoTextInput() {
        // 音声モード: 「停止」と「マイクをミュート」があり、入力欄が無い
        assertTrue(ClaudeVoice.isVoiceScreen(listOf("設定", "マイクをミュート", "Claude の音声をミュート", "停止"), hasInput = false))
        // 「マイクをミュート」の名前が分からなくても、入力欄が無く「停止」があれば音声モード
        assertTrue(ClaudeVoice.isVoiceScreen(listOf("Stop"), hasInput = false))
        // 文字のチャットで返事を止める「停止」(入力欄がある) は音声モードではない
        assertFalse(ClaudeVoice.isVoiceScreen(listOf("停止", "ファイルなどを追加"), hasInput = true))
        // チャットの画面 (「音声モード」がある) は音声モードではない
        assertFalse(ClaudeVoice.isVoiceScreen(listOf("音声モード", "音声入力を開始", "新規セッション"), hasInput = true))
        // Claude の窓が見えない
        assertFalse(ClaudeVoice.isVoiceScreen(emptyList(), hasInput = false))
    }

    /** 先に送る文: リゲルの役 → いつもの最初の文 (返事の仕方の一文は外す) → お知らせ → 返事の仕方、の順 */
    @Test fun promptStartsWithRigelPersona() {
        val plain = ClaudeVoice.prompt(VoicePrimer.DEFAULT_TEXT, null)
        assertTrue(plain, plain.startsWith(ClaudeVoice.PERSONA))
        assertTrue(plain, plain.endsWith(ClaudeVoice.REPLY_YES))
        assertFalse(plain, plain.contains(VoicePrimer.REPLY_HINT))
        assertTrue(plain, plain.contains("list_parts"))
        val withNotices = ClaudeVoice.prompt("", "私が最初に何か話したら、まず次のお知らせを。\n〔中身〕")
        assertEquals(ClaudeVoice.PERSONA + " 私が最初に何か話したら、まず次のお知らせを。 〔中身〕 " + ClaudeVoice.REPLY_YES, withNotices)
    }
}
