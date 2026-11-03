package com.rabi.envoymcp.arm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 腕と拍の記録 (新しい順、MAX 行まで)。アプリ専用の場所のファイルにも残す (adb がつながっていなくても、設定の画面で見られる。
 * 入れ直し・再起動のあとでも調べられる)。数 (テンポ・確かさ) もそのまま残す (画面や会話の中身は入れない)。
 */
class ArmLog(private val file: File) {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<String>> = _lines

    fun add(text: String) {
        val line = "${fmt.format(Date())} $text"
        val next = (listOf(line) + _lines.value).take(MAX)
        _lines.value = next
        runCatching { file.writeText(next.joinToString("\n")) }
    }

    private fun load(): List<String> = runCatching { file.readLines().filter { it.isNotBlank() }.take(MAX) }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 80
    }
}
