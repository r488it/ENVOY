package com.rabi.envoymcp

import android.app.Activity
import android.os.Bundle
import com.rabi.envoymcp.core.RabiLog

/**
 * デジタル アシスタントの受け口。
 * Android の設定で ENVOY MCP を「デジタル アシスタント アプリ」に選ぶと、アシスタントを呼ぶ操作
 * (電源ボタンの長押し・画面の下の角からのスワイプなど) でここが開く。
 * ChatGPT の音声モードを開く (全画面のリゲル・いつもの最初の一言。操作の途中なら止める)。
 * Android が開けるよう公開しているので、ほかのアプリからも開ける。そのため、リゲルのタップと違い、
 * お知らせの引き継ぎと、本人が止めた状態を解くことはしない (McpGraph.openVoiceFromAssist)。
 * 自分の画面は出さず、すぐ閉じる
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RabiLog.i("assist", "invoked")
        (application as McpApp).graph.openVoiceFromAssist()
        finish()
    }
}
