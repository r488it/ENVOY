package com.rabi.launcher.safety

import com.rabi.launcher.core.Text
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode
import com.rabi.launcher.parts.ApprovalLevel
import com.rabi.launcher.parts.CommitKind
import java.security.MessageDigest
import java.util.UUID

/**
 * 確定 (送信・投稿・購入・削除・発信) の承認。
 * - 承認は「特定の内容」に対して 1 回限り・短い期限 (既定 60 秒)
 * - 内容 (宛先・本文・画面の入力欄など) が変わったらトークンは使えない → 再承認
 * - ロック解除は承認ではない (このクラスはロック状態を一切見ない)
 */
data class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val kind: CommitKind,
    val level: ApprovalLevel,
    /** 読み上げと画面の見出し */
    val summary: String,
    /** 宛先・アカウント・公開範囲・内容・金額 など */
    val fields: Map<String, String>,
    val targetPkg: String?,
    /** 承認時点の画面から取った事実 (入力欄の中身など) */
    val screenFacts: Map<String, String>,
    val screenshotPng: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fingerprint: String get() = Fingerprint.of(kind, targetPkg, fields, screenFacts)
}

sealed class ApprovalDecision {
    data class Approved(val token: ApprovalToken) : ApprovalDecision()
    data class Denied(val reason: String) : ApprovalDecision()
}

data class ApprovalToken(val requestId: String, val fingerprint: String, val expiresAt: Long, val method: String)

/** 承認を求める画面 (Android では ApprovalActivity + BiometricPrompt、試験では偽物)。 */
interface ApprovalUi {
    suspend fun ask(req: ApprovalRequest, manager: ApprovalManager): ApprovalDecision
}

object Fingerprint {
    fun of(kind: CommitKind, pkg: String?, fields: Map<String, String>, screenFacts: Map<String, String>): String {
        val canon = buildString {
            append(kind.name).append('\n').append(pkg ?: "").append('\n')
            fields.toSortedMap().forEach { (k, v) -> append("f:").append(k).append('=').append(Text.norm(v)).append('\n') }
            screenFacts.toSortedMap().forEach { (k, v) -> append("s:").append(k).append('=').append(Text.norm(v)).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 画面から確定に関わる事実を取る: 入力欄の中身と、確定前に出ていなければならない文字が出ているか。
     * 入力欄は位置の順に番号を振る (要素の index は画面の更新で変わるため使わない)。
     */
    fun screenFacts(obs: Observation, mustShow: List<String>): Map<String, String> {
        val facts = LinkedHashMap<String, String>()
        facts["pkg"] = obs.pkg ?: ""
        obs.nodes.filter { it.editable && it.bounds.area > 0 }.sortedWith(compareBy({ it.bounds.t }, { it.bounds.l }))
            .forEachIndexed { i, n -> facts["edit$i"] = if (n.password) "<password>" else n.text }
        mustShow.forEachIndexed { i, s -> facts["show$i:${Text.norm(s)}"] = obs.hasText(s).toString() }
        return facts
    }
}

enum class ConsumeResult { OK, EXPIRED, MISMATCH, ALREADY_USED, UNKNOWN }

class ApprovalManager(private val clock: () -> Long = System::currentTimeMillis, val ttlMs: Long = 60_000) {
    private val issued = HashMap<String, ApprovalToken>()
    private val used = HashSet<String>()

    /** 承認画面で本人が認証できたときだけ呼ぶ。 */
    @Synchronized
    fun issue(req: ApprovalRequest, method: String): ApprovalToken {
        val t = ApprovalToken(req.id, req.fingerprint, clock() + ttlMs, method)
        issued[req.id] = t
        return t
    }

    /** 実行直前に、いまの内容の指紋と照らして 1 回だけ使う。 */
    @Synchronized
    fun consume(token: ApprovalToken, currentFingerprint: String): ConsumeResult {
        if (token.requestId in used) return ConsumeResult.ALREADY_USED
        val t = issued[token.requestId] ?: return ConsumeResult.UNKNOWN
        if (clock() > t.expiresAt) { issued.remove(token.requestId); return ConsumeResult.EXPIRED }
        if (t.fingerprint != currentFingerprint) return ConsumeResult.MISMATCH
        used += token.requestId
        issued.remove(token.requestId)
        return ConsumeResult.OK
    }

}

/**
 * 確定操作らしい要素の検出 (未知の画面操作の歯止め)。
 * 文字・説明・ID の語で判定するだけなので、すべての確定操作を見つけられるわけではない。
 */
object CommitDetector {
    private val words = listOf(
        "送信", "送る", "投稿", "購入", "注文", "支払", "決済", "確定", "削除", "消去", "発信", "通話", "シェア", "共有", "公開", "申し込", "申込", "予約する", "契約",
        // 日本語の X は投稿ボタンが「ポストする」(「リポスト」も公開の操作)。これが無いと、承認なしで投稿されてしまう
        "ポスト", "ツイート", "返信", "トゥート",
        "send", "post", "tweet", "publish", "share", "buy", "purchase", "order", "pay", "checkout", "delete", "remove", "call", "submit", "confirm", "reply",
    )
    private val idWords = listOf("send", "post", "tweet", "retweet", "repost", "reply", "submit", "buy", "purchase", "pay", "checkout", "delete", "call", "dial", "share", "publish", "confirm")

    /** 一覧・履歴・設定の入口は確定ではない (「通話履歴」「送信済み」など)。 */
    // 「ポストを追加」(X のスレッドに足すだけ)・「下書き」も確定ではない
    private val navWords = listOf("履歴", "一覧", "設定", "済み", "済", "フォルダ", "トレイ", "追加", "下書き", "history", "settings", "log", "sent", "folder", "list", "draft")

    /** 弱い語 (確認・OK など) はそれだけでは確定としない。迷う要素は確定側に倒す。 */
    fun looksLikeCommit(n: UiNode): Boolean {
        val label = Text.norm(n.text + " " + n.desc)
        val id = n.shortId.lowercase()
        if (navWords.any { label.contains(Text.norm(it)) } && !n.editable) return false
        if (words.any { w -> label.contains(Text.norm(w)) && label.length <= 24 }) return true
        if (idWords.any { w -> id.split('_', '-', '.').any { it == w } || id.endsWith(w) }) return true
        return false
    }

    private val switchClasses = listOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch", "ToggleButton", "CheckBox", "RadioButton", "CompoundButton")

    fun isSwitch(n: UiNode) = switchClasses.any { n.cls.endsWith(it) }

    /**
     * 設定を切り替える操作か (スイッチそのもの、またはスイッチと同じ行)。
     * Wi-Fi や機内モードを切ると端末の操作の接続そのものが切れるので、承認なしには押さない。
     */
    fun togglesSetting(obs: com.rabi.launcher.observe.Observation, n: UiNode): Boolean {
        if (isSwitch(n)) return true
        if (n.editable) return false
        return obs.nodes.any { s -> isSwitch(s) && s.bounds.area > 0 && n.bounds.cy in s.bounds.t..s.bounds.b && s.bounds.h < 400 }
    }

    fun kindOf(n: UiNode): CommitKind {
        val s = Text.norm(n.text + n.desc + n.shortId)
        return when {
            listOf("購入", "注文", "支払", "決済", "buy", "purchase", "order", "pay", "checkout").any { s.contains(Text.norm(it)) } -> CommitKind.PURCHASE
            listOf("削除", "消去", "delete", "remove").any { s.contains(Text.norm(it)) } -> CommitKind.DELETE
            listOf("投稿", "公開", "ポスト", "ツイート", "トゥート", "post", "publish", "tweet", "シェア", "共有", "share").any { s.contains(Text.norm(it)) } -> CommitKind.POST
            listOf("発信", "通話", "call", "dial").any { s.contains(Text.norm(it)) } -> CommitKind.CALL
            listOf("送信", "送る", "返信", "send", "reply", "submit").any { s.contains(Text.norm(it)) } -> CommitKind.SEND
            else -> CommitKind.OTHER
        }
    }
}

/**
 * 同じ確定を二度しないための記録。結果が分からないもの (UNKNOWN) は自動で再送しない。
 */
class DuplicateGuard(private val clock: () -> Long = System::currentTimeMillis, private val windowMs: Long = 10 * 60_000) {
    enum class State { ATTEMPTED, CONFIRMED, UNKNOWN }
    data class Entry(val key: String, val state: State, val at: Long, val summary: String)

    private val entries = LinkedHashMap<String, Entry>()

    /** 宛先と内容だけから作る鍵 (画面の状態は含めない)。 */
    fun keyOf(kind: CommitKind, pkg: String?, fields: Map<String, String>) = Fingerprint.of(kind, pkg, fields, emptyMap())

    @Synchronized fun recent(key: String): Entry? = entries[key]?.takeIf { clock() - it.at < windowMs }
    @Synchronized fun mark(key: String, state: State, summary: String) { entries[key] = Entry(key, state, clock(), summary) }
    @Synchronized fun all(): List<Entry> = entries.values.toList()
}
