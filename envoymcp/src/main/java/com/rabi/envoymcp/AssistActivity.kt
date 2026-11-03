package com.rabi.envoymcp

import android.app.Activity
import android.os.Bundle
import com.rabi.launcher.core.RabiLog

/**
 * デジタル アシスタントの受け口。
 * Android の設定で ENVOY MCP を「デジタル アシスタント アプリ」に選ぶと、アシスタントを呼ぶ操作
 * (電源ボタンの長押し・画面の下の角からのスワイプなど) でここが開く。
 * リゲルのタップと同じく、ChatGPT の音声モードを開く (全画面のリゲル・最初の一言も同じ。操作の途中なら止める)。
 * 自分の画面は出さず、すぐ閉じる
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RabiLog.i("assist", "invoked")
        (application as McpApp).graph.tapRigel("assist")
        finish()
    }
}
