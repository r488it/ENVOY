package com.rabi.envoymcp.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.util.size
import com.rabi.envoymcp.core.RabiLog

/**
 * ENVOY MCP のユーザー補助のサービス (端末の操作の方式が「ユーザー補助」を含むときに使う)。
 * 本人が Android の設定の「ユーザー補助」でオンにしたときだけ動く。このサービス自身は何も操作せず、
 * A11yDeviceController が ChatGPT の道具の呼び出しに応じて、画面の読み取り・指の動き・全体の操作 (戻る・ホームなど) に使う。
 * ほかに、リゲルの × で ChatGPT の「終了」を押すのと、音声モードの最初の一言を送る (VoicePrimer) のにも使う。
 * - 前面のアプリ: 本当の画面 (Activity) に切り替わったとき (TYPE_WINDOW_STATE_CHANGED) の名前を覚えておく (前面のアプリの判定は、操作の対象の窓から読む)
 * - 内容 (画面の文字) は記録に残さない
 */
class A11yService : AccessibilityService() {
    /** 最後に前に出た画面: パッケージ名 → 画面 (Activity などのクラス名) */
    private val lastClass = HashMap<String, String>()
    /** 画面の番号ごとに、最後に前に出たアプリ (モニターにつながっているとき、スマホの前面を見分けるため) */
    private val lastByDisplay = HashMap<Int, String>()

    override fun onServiceConnected() {
        instance = this
        RabiLog.i("a11y", "connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        // 本当の画面 (Activity) に切り替わったときだけ覚える。ポップアップ・入力欄・部品の種類 (android.view.ViewGroup など) では変えない
        // (X などで承認の前後に「画面の名前」が変わり、元の画面に戻れていないと取り違えないため)
        if (!isActivity(pkg, cls)) return
        synchronized(lastClass) { lastClass[pkg] = cls; lastByDisplay[e.displayId] = pkg }
    }

    private val activityCache = HashMap<String, Boolean>()

    /** pkg のアプリの画面 (Activity) か。結果は覚えておく */
    private fun isActivity(pkg: String, cls: String): Boolean = synchronized(activityCache) {
        activityCache.getOrPut("$pkg/$cls") {
            runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        RabiLog.i("a11y", "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 部品の木の控え (キャッシュ) を捨てる。読む・押す前に呼び、今の画面を読み直す (Android 14 以降)。
     * 控えが古いと、あとから出た部品 (遅れて出るスイッチなど) が読めない
     */
    fun fresh() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { clearCache() } }

    /**
     * 前面のアプリの (パッケージ名, 画面の名前)。読めなければ null。
     * 画面に出ている「アプリの窓」のうち、いちばん上のもので決める。操作の対象の窓 (rootInActiveWindow) は、
     * ポップアップ・他のアプリの浮いた窓・読み込みの途中に引っぱられ、別のアプリと取り違えることがあるので使わない。
     * 他のアプリの上に出す窓 (ENVOY MCP の覆い・ChatGPT の音声の小窓など) は「アプリの窓」に入らない
     */
    fun foreground(): Pair<String, String>? {
        // スマホの画面 (画面 0) の窓から読む。モニター (PC モード) につながっていると、windows は操作の対象の画面 (モニターのこともある) の窓になり、
        // 読むたびにモニターとスマホの窓が入れ替わって、音声モードの全画面のリゲルが出たり消えたりするため
        val app = runCatching { windowsOn(android.view.Display.DEFAULT_DISPLAY).ifEmpty { windows } }.getOrNull()
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.maxByOrNull { it.layer }
        val pkg = app?.root?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: return null
        return pkg to (synchronized(lastClass) { lastClass[pkg] } ?: "")
    }

    /**
     * あるアプリの窓の中で、説明 (content-desc) が desc の部品を押す (押せる親があればそれを押す)。
     * 指の動きではなく部品そのものを押すので、上に ENVOY MCP の覆いがあっても押せる
     */
    fun clickByDesc(pkg: String, desc: String): Boolean {
        fresh()
        val roots = allWindows().mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
        for (root in roots) {
            val node = find(root) { it.contentDescription?.toString() == desc } ?: continue
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if ((n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /** あるアプリの窓の中に、説明 (content-desc) が desc の部品があるか */
    fun hasDesc(pkg: String, desc: String): Boolean {
        fresh()
        return allWindows().mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
            .any { root -> find(root) { it.contentDescription?.toString() == desc } != null }
    }

    /** あるアプリの窓の中で、条件に合う部品をすべて (上の窓から) */
    fun findAllIn(pkg: String, ok: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        fresh()
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (ok(n)) out.add(n)
            if (depth > MAX_DEPTH) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        allWindows().sortedByDescending { it.layer }.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }.forEach { walk(it, 0) }
        return out
    }

    /** 部品を押す (押せる親があればそれを押す) */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** あるアプリの窓の中で、最初の文字の入力欄 (覆いの下でも読める) */
    fun inputOf(pkg: String): AccessibilityNodeInfo? {
        fresh()
        return allWindows().mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
            .firstNotNullOfOrNull { root -> find(root) { it.isEditable } }
    }

    /** あるアプリの入力欄に、文字を直接入れる (今の文字は置き換える。"" で消す) */
    fun setInputText(pkg: String, text: String): Boolean {
        val n = inputOf(pkg) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------------------------------------------------------------- 画面の番号を指定して (モニター・PC モード。試験)

    /**
     * スマホの画面 (画面 0) の前面のアプリ。ownPkg (ENVOY MCP 自身の窓) は除く。
     * 覆い (音声モードの全画面のリゲル) がスマホの画面を全部隠していると、隠れたアプリの窓は窓の一覧に出ないので、
     * そのときは、スマホの画面で最後に前に出たアプリ (画面の切り替わりの知らせから) を使う
     */
    fun phoneTopApp(ownPkg: String): String? = windowsOn(android.view.Display.DEFAULT_DISPLAY)
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
        .mapNotNull { it.root?.packageName?.toString() }.firstOrNull { it != ownPkg }
        ?: synchronized(lastClass) { lastByDisplay[android.view.Display.DEFAULT_DISPLAY] }?.takeIf { it != ownPkg }

    /** 選ばれている入力欄で、キーボードの実行 (検索・決定) をする。できなければ false */
    fun imeEnterOnFocused(): Boolean {
        fresh()
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** すべての画面の窓 (アプリの窓を名前で探すとき。モニターにつながっていても、スマホの ChatGPT の窓が見つかるように) */
    private fun allWindows(): List<AccessibilityWindowInfo> = runCatching {
        val all = windowsOnAllDisplays
        (0 until all.size).flatMap { all.valueAt(it).orEmpty() }
    }.getOrNull()?.ifEmpty { null } ?: runCatching { windows }.getOrDefault(emptyList())

    /** その画面 (displayId) の窓。外部ディスプレイのデスクトップも読める (Android 11 から) */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        runCatching { windowsOnAllDisplays.get(displayId).orEmpty() }.getOrDefault(emptyList())

    private fun find(n: AccessibilityNodeInfo, depth: Int = 0, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (ok(n)) return n
        if (depth > MAX_DEPTH) return null
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c, depth + 1, ok)?.let { return it } }
        return null
    }

    companion object {
        /** オンになっているときのサービス (オフなら null) */
        @Volatile var instance: A11yService? = null; private set
        private const val MAX_DEPTH = 60
    }
}
