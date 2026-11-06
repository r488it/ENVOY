package com.rabi.envoymcp.device

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文字を貼り付けで入れるときのクリップボード (ADB 版・ユーザー補助版で共通)。
 * - 入れる文字には秘密の印を付ける (Android 13 以降の「コピーしました」の見本や、キーボードの履歴に中身を出さない)
 * - 貼ったあと少し待ってから、ENVOY MCP が入れたものが残っていれば空にする。
 *   中身を読めるとき (ENVOY MCP が前にあるとき) は、本人がその間に別のものをコピーしていれば消さない。
 *   ほかのアプリが前にある間は元の中身を読めないので、元には戻さない
 */
internal object PasteClip {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seq = AtomicInteger(0)
    /** 今クリップボードに入れているもののラベル (空にしたら null) */
    @Volatile private var mine: String? = null

    /** 文字をクリップボードに入れる。入れられなければ false */
    suspend fun put(context: Context, text: String): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val label = "ENVOY MCP ${seq.incrementAndGet()}"
            val clip = ClipData.newPlainText(label, text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            mine = label
            true
        }.getOrElse { RabiLog.w("device", "clipboard failed ${it.javaClass.simpleName}"); false }
    }

    /** 貼る操作のあとに呼ぶ。貼る側のアプリが読み終わるのを少し待ってから空にする (その間に次の文字を入れていれば、そちらに任せる) */
    fun clearSoon(context: Context) {
        val label = mine ?: return
        scope.launch {
            delay(CLEAR_AFTER_MS)
            if (mine != label) return@launch
            runCatching {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val now = cm.primaryClipDescription
                if (now == null || now.label?.toString() == label) cm.clearPrimaryClip()
            }.onFailure { RabiLog.w("device", "clipboard clear failed ${it.javaClass.simpleName}") }
            if (mine == label) mine = null
        }
    }

    /** 貼ってから空にするまで待つ長さ (Web の入力欄などは、貼る操作のあとに読みに来る) */
    private const val CLEAR_AFTER_MS = 1_000L
}
