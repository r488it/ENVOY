package com.rabi.envoymcp.safety

import com.rabi.envoymcp.core.Text
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.parts.CommitKind
import java.security.MessageDigest
import java.text.Normalizer
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
 * - ID の語 (send_button・checkout など) があれば、文字によらず確定
 * - 日本語の語は文字の中から探す。英語の語は単語として同じときだけ (Posts・Sender・Deleted・Playlist には当てない)
 * - 語のすぐ後ろが「済み」「中」「しない」などなら、その語は数えない (「送信済み」「許可しない」)。「〜ました」で終わる文字も確定ではない
 * - 一覧・履歴・設定の語もあるときは、後ろの語で決める (「通話履歴」「転送設定」は入口、「設定をリセット」「閲覧履歴を消去」は確定)。
 *   英語の強い語 (Send・Delete・Reset など) があれば確定 (「Send draft」「Delete folder」)
 * - 長い文字 (24 字より長い) は、頭か終わりにある語だけを見る (タイムラインの本文の途中の「ポスト」では止めない)
 */
object CommitDetector {
    private val jaWords = listOf(
        "送信", "送る", "投稿", "購入", "注文", "支払", "決済", "確定", "削除", "消去", "発信", "通話", "シェア", "共有", "公開", "申し込", "申込", "予約する", "契約",
        // 日本語の X は投稿ボタンが「ポストする」(「リポスト」も公開の操作)。これが無いと、承認なしで投稿されてしまう
        "ポスト", "ツイート", "返信", "トゥート",
        // LINE の共有で送り先を選んだあとの確定は「転送」
        "転送",
        // お金を動かす・アプリや端末を変える・同意する・やめる
        "振込", "振り込", "送金", "出金", "チャージ", "入札", "寄付", "実行", "インストール", "アンインストール", "リセット", "初期化",
        "許可", "同意", "解約", "退会",
    ).map { soft(it) }
    private val enWords = setOf(
        "send", "forward", "post", "repost", "tweet", "retweet", "publish", "share", "buy", "purchase", "order", "pay", "checkout", "delete", "remove",
        "call", "submit", "confirm", "reply", "transfer", "install", "uninstall", "reset", "erase", "allow", "accept", "agree", "subscribe", "withdraw", "donate",
    )
    /** 一覧・設定の語より強い英語の語 (「Send draft」「Delete folder」「Reset settings」は確定) */
    private val enStrong = setOf("send", "delete", "remove", "erase", "reset", "install", "uninstall", "pay", "buy", "checkout", "transfer", "withdraw", "donate")
    /** 英語で、文字の終わりにあれば行の中でも確定のボタンとみなす語 (「Place order」「Proceed to checkout」) */
    private val enEndWords = setOf("order", "checkout", "purchase")

    /** 一覧・履歴・設定の入口の語 (「通話履歴」「送信トレイ」など)。「ポストを追加」(X のスレッドに足すだけ)・「下書き」も確定ではない */
    private val jaNav = listOf("履歴", "一覧", "設定", "フォルダ", "トレイ", "追加", "下書き").map { soft(it) }
    private val enNav = setOf("history", "settings", "setting", "log", "logs", "sent", "folder", "folders", "list", "lists", "draft", "drafts")

    private val idWords = listOf("send", "forward", "post", "tweet", "retweet", "repost", "reply", "submit", "buy", "purchase", "pay", "checkout", "delete", "call", "dial", "share", "publish", "confirm")
    /** ID の区切り (_ - . と、小文字・数字のあとの大文字) で分けた語が同じときだけ確定とみなす語 */
    private val idTokens = setOf("transfer", "install", "uninstall", "reset", "erase", "allow", "accept", "agree", "subscribe", "withdraw", "donate")
    private val ID_SPLIT = Regex("[_\\-.]|(?<=[a-z0-9])(?=[A-Z])")
    private val EN_WORD = Regex("[a-z]+")

    /** 語のすぐ後ろにあれば、その語を数えない (済んだ・途中・しない。「中止」「中断」は除く) */
    private val jaNegAfter = Regex("^[いりみう]?\\s*(済|完了|中(?![止断])|しない|しません|せず|できない|できません|不可)")
    /** 英語の語のすぐ前にあれば、その語を数えない (Don't allow など) */
    private val enNegBefore = Regex("(?:\\bdon['’]?t|\\bdo not|\\bnot|\\bnever)\\s+$")
    /** 文字がこれで終われば、済んだことの知らせ (確定のボタンではない) */
    private val doneEnds = listOf("ました", "でした", "済み")
    /** 押せる部品の中の文字で、確定の語のあとに付いてよいもの (「注文を確定する」「購入手続きへ」「お支払い」) */
    private val jaPhraseEnd = Regex("^[うむいみ]?(する|します|へ|へ進む|に進む|手続き|手続きへ|手続きに進む|ボタン)?$")
    /** これより長い文字は、頭か終わりにある語だけを見る */
    private const val SHORT_LEN = 24
    /** 長い文字の最初の区切り */
    private val HEAD_SPLIT = Regex("[(（\\[［【「『\\n、。,!！?？・]")

    private class Hit(val start: Int, val end: Int, val commit: Boolean, val en: Boolean = false, val strong: Boolean = false)

    /** 弱い語 (確認・OK など) はそれだけでは確定としない。迷う要素は確定側に倒す。 */
    fun looksLikeCommit(n: UiNode): Boolean = looksLikeCommit(n, inner = false)

    /**
     * inner: 押される部品の中にある部品 (行の中の名前・本文など)。行の本文 (トークの最後の言葉「通話時間 1:23」など) で止めないよう、
     * 文字が短い言い切り (「送信」「今すぐ購入」「注文を確定する」「Delete playlist」) のときだけ確定とみなす
     */
    private fun looksLikeCommit(n: UiNode, inner: Boolean): Boolean {
        // ID の語は、文字の除外より先に見る (文字が「送信済み」でも、ID が checkout_button なら確定)
        if (idCommits(n.shortId)) return true
        return labelCommits(n.text, inner, n.editable) || labelCommits(n.desc, inner, n.editable)
    }

    private fun idCommits(shortId: String): Boolean {
        if (shortId.isBlank()) return false
        val id = shortId.lowercase()
        val parts = shortId.split(ID_SPLIT).map { it.lowercase() }
        return idWords.any { w -> parts.any { it == w } || id.endsWith(w) } || parts.any { it in idTokens }
    }

    private fun labelCommits(label: String, inner: Boolean, editable: Boolean): Boolean {
        val n = Text.norm(label)
        if (n.isEmpty() || doneEnds.any { n.endsWith(it) }) return false
        val long = n.length > SHORT_LEN
        if (inner && long) {
            // 長い文字は、最初の区切り (かっこ・句読点・改行) までが言い切りのボタンの文字かで見る (「注文を確定する (お支払い金額 …)」)
            val head = label.split(HEAD_SPLIT).firstOrNull { it.isNotBlank() } ?: return false
            return Text.norm(head).length <= SHORT_LEN && labelCommits(head, inner = true, editable = editable)
        }
        val s = soft(label)
        val hits = hits(s).filter { !long || nearEdge(s, it) }
        val commits = hits.filter { it.commit }
        if (commits.isEmpty()) return false
        // 入力欄の文字は書いた文なので、一覧・設定の語では除かない
        val decisive = if (editable) commits.last()
            else commits.firstOrNull { it.strong } ?: hits.maxBy { it.end }.takeIf { it.commit } ?: return false
        return !inner || phrase(s, decisive)
    }

    /** 文字の中の確定の語と一覧・設定の語 (確定の語が先。打ち消された確定の語は除く) */
    private fun hits(s: String): List<Hit> {
        val out = ArrayList<Hit>()
        for (w in jaWords) for (i in indexes(s, w)) {
            if (!jaNegAfter.containsMatchIn(s.substring(i + w.length))) out += Hit(i, i + w.length, commit = true)
        }
        for (m in EN_WORD.findAll(s)) {
            val w = m.value
            if (w in enWords && !enNegBefore.containsMatchIn(s.substring(0, m.range.first)))
                out += Hit(m.range.first, m.range.last + 1, commit = true, en = true, strong = w in enStrong)
        }
        for (w in jaNav) for (i in indexes(s, w)) out += Hit(i, i + w.length, commit = false)
        for (m in EN_WORD.findAll(s)) if (m.value in enNav) out += Hit(m.range.first, m.range.last + 1, commit = false, en = true)
        return out
    }

    private fun indexes(s: String, w: String): List<Int> {
        val out = ArrayList<Int>()
        var i = s.indexOf(w)
        while (i >= 0) { out += i; i = s.indexOf(w, i + 1) }
        return out
    }

    /** 長い文字の頭 (前に 1 字まで。「ご注文」) か終わり (後ろに 6 字まで。「〜を確定する」) にある語か */
    private fun nearEdge(s: String, h: Hit) = Text.norm(s.substring(0, h.start)).length <= 1 || Text.norm(s.substring(h.end)).length <= 6

    /** 確定の語が、言い切りのボタンの文字になっているか (日本語は語が終わりにある。英語は語で始まるか、order などで終わる) */
    private fun phrase(s: String, h: Hit): Boolean {
        val after = Text.norm(s.substring(h.end))
        if (!h.en) return jaPhraseEnd.matches(after)
        return Text.norm(s.substring(0, h.start)).isEmpty() || (after.isEmpty() && s.substring(h.start, h.end) in enEndWords)
    }

    /** 比べるための形: NFKC・小文字・カタカナをひらがなに (空白・記号は残す。英語の単語の区切りに使う) */
    private fun soft(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (ch in n) sb.append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch)
        return sb.toString()
    }

    /**
     * 部品 n を押すと確定・設定の切り替えになりそうなら、その手がかりの部品を返す (無ければ null)。
     * n そのものに加えて、n のまん中を押したときに実際に押される部品とその中身も見る
     * (名前の無い押せる枠を選んでも中の「送信」のアイコンで、名前の無い子を選んでも押される「購入する」のボタンで分かる)
     */
    fun commitIn(obs: Observation, n: UiNode): UiNode? {
        if (looksLikeCommit(n) || togglesSetting(obs, n)) return n
        return commitAt(obs, n.bounds.cx, n.bounds.cy)
    }

    /** 位置 (x, y) を押すと確定・設定の切り替えになりそうなら、その手がかりの部品 (無ければ null) */
    fun commitAt(obs: Observation, x: Int, y: Int): UiNode? {
        val p = obs.pressAt(x, y)
        p.targets.firstOrNull { looksLikeCommit(it) || (it.clickable && togglesSetting(obs, it)) }?.let { return it }
        return p.inner.firstOrNull { looksLikeCommit(it, inner = true) }
    }

    private val switchClasses = listOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch", "ToggleButton", "CheckBox", "RadioButton", "CompoundButton")

    fun isSwitch(n: UiNode) = switchClasses.any { n.cls.endsWith(it) }

    /**
     * 設定を切り替える操作か (スイッチそのもの、またはスイッチと同じ行)。
     * Wi-Fi や機内モードを切ると端末の操作の接続そのものが切れるので、承認なしには押さない。
     */
    fun togglesSetting(obs: Observation, n: UiNode): Boolean {
        if (isSwitch(n)) return true
        if (n.editable) return false
        return obs.nodes.any { s -> isSwitch(s) && s.bounds.area > 0 && n.bounds.cy in s.bounds.t..s.bounds.b && s.bounds.h < 400 }
    }

    fun kindOf(n: UiNode): CommitKind {
        val s = Text.norm(n.text + n.desc + n.shortId)
        return when {
            // アプリを消す・初期化する・やめる (インストールの語を含むので、購入より先に見る)
            listOf("アンインストール", "リセット", "初期化", "解約", "退会", "uninstall", "reset", "erase").any { s.contains(Text.norm(it)) } -> CommitKind.DELETE
            listOf("購入", "注文", "支払", "決済", "振込", "振り込", "送金", "出金", "チャージ", "入札", "寄付",
                "buy", "purchase", "order", "pay", "checkout", "transfer", "withdraw", "donate").any { s.contains(Text.norm(it)) } -> CommitKind.PURCHASE
            listOf("削除", "消去", "delete", "remove").any { s.contains(Text.norm(it)) } -> CommitKind.DELETE
            listOf("投稿", "公開", "ポスト", "ツイート", "トゥート", "post", "publish", "tweet", "シェア", "共有", "share").any { s.contains(Text.norm(it)) } -> CommitKind.POST
            listOf("発信", "通話", "call", "dial").any { s.contains(Text.norm(it)) } -> CommitKind.CALL
            listOf("送信", "送る", "返信", "転送", "send", "forward", "reply", "submit").any { s.contains(Text.norm(it)) } -> CommitKind.SEND
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
