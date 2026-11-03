package com.rabi.envoymcp.parts

import com.rabi.envoymcp.core.Text
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.safety.CommitDetector

/** System 2 が画面を見ながら行った 1 操作の記録。 */
data class TraceStep(
    val op: String,
    val pkg: String? = null,
    val node: UiNode? = null,
    val text: String? = null,
    val direction: String? = null,
    val before: Observation? = null,
    val after: Observation? = null,
    val ok: Boolean = true,
    val commit: Boolean = false,
    /** Jev (TypeSafe) が選んだ手。覚えた部品に印を付ける (Jev の出力は、端末内モデルの学習・較正・比較に使わない契約のため) */
    val jev: Boolean = false,
)

/** System 2 (LLM) が付ける部品の説明。無くても部品は作れる。 */
data class PartMeta(
    val id: String?,
    val name: String,
    val description: String,
    val examples: List<String>,
    /** 記録の中の具体的な文字 → 引数 (例: "こんにちは" → message) */
    val args: List<Pair<String, ArgSpec>> = emptyList(),
    /** 始めるときの声かけ (例: 「娘さんにLINEを送りますね」)。引数は {名前} で */
    val sayStart: String? = null,
    /** 終わったときの声かけ (例: 「{contact}さんに送りました」) */
    val sayDone: String? = null,
)

/**
 * 成功した操作の記録から部品 (DRAFT) を作る。
 * - 座標ではなく、押した要素の ID・文字・説明を保存する
 * - 各操作のあとに出てきた文字を「画面確認」として保存し、違えば再実行時に止まる
 * - 確定らしい操作は commit として保存する (承認が要る)
 */
object TraceCompiler {
    private val noise = Regex("^[\\d:%.,\\s/\\-]+$")
    /** ホーム画面 (このアプリ) から始めた記録には、アプリを開く手順を足さない */
    val HOME_PKGS = setOf("com.rabi.launcher", "com.rabi.envoymcp")

    /**
     * 成功した操作の記録を部品にする。doneMessage は、そのとき実際に言った終わりの言葉 (応答も覚えて、次からも同じように話す)
     */
    fun compile(utterance: String, trace: List<TraceStep>, meta: PartMeta?, idHint: String = System.currentTimeMillis().toString(36), doneMessage: String = "",
                homePkgs: Set<String> = HOME_PKGS): Part? {
        val ok = trace.filter { it.ok }
        if (ok.isEmpty()) return null
        val literalToArg = meta?.args.orEmpty().associate { it.first to it.second }
        val steps = ArrayList<Step>()
        var commitSpec: CommitSpec? = null
        val typed = ArrayList<String>()
        var appPkg: String? = null
        ok.forEachIndexed { i, t ->
            val expect = t.after?.let { expectFor(t.before, it) }
            when (t.op) {
                "launch" -> {
                    appPkg = appPkg ?: t.pkg
                    steps += Step("s$i", "launch", pkg = t.pkg, expect = t.pkg?.let { Expect(pkg = it, timeoutMs = 6000) })
                }
                "panel" -> steps += Step("s$i", "panel", text = t.text, expect = expect)
                "shortcut" -> {
                    appPkg = appPkg ?: t.pkg
                    steps += Step("s$i", "shortcut", pkg = t.pkg, shortcut = t.text, expect = (expect ?: t.pkg?.let { Expect(pkg = it) })?.copy(timeoutMs = 6000))
                }
                "tap" -> {
                    val n = t.node ?: return@forEachIndexed
                    val sel = selectorFor(n, literalToArg, t.before)
                    // 名前も ID も無い部品を押した手は、次に同じものを探せない: 部品にしない (違う所を押さないため)
                    if (sel.isEmpty) return null
                    val isCommit = t.commit || CommitDetector.looksLikeCommit(n)
                    if (isCommit) {
                        val fields = LinkedHashMap<String, String>()
                        fields["操作"] = n.label.ifBlank { "確定" }
                        typed.forEachIndexed { k, s -> fields[if (k == 0) "内容" else "内容${k + 1}"] = s }
                        commitSpec = CommitSpec(CommitDetector.kindOf(n), ApprovalLevel.BIOMETRIC, "「${n.label.ifBlank { "確定" }}」を押して確定します", fields, typed.toList())
                    }
                    steps += Step("s$i", "tap", selector = sel, expect = if (isCommit) expect?.copy(timeoutMs = 6000) else expect, commit = isCommit)
                }
                "type" -> {
                    val raw = t.text ?: return@forEachIndexed
                    val arg = literalToArg[raw]
                    val value = if (arg != null) "{${arg.name}}" else raw
                    typed += value
                    val sel = t.node?.let { n -> Selector(id = listOfNotNull(n.shortId.takeIf { it.isNotBlank() }), editable = true, text = if (n.shortId.isBlank() && n.hint.isNotBlank()) listOf(n.hint) else emptyList()) }
                    steps += Step("s$i", "type", selector = sel?.takeIf { !it.isEmpty }, text = value)
                }
                "swipe" -> steps += Step("s$i", "swipe", direction = t.direction ?: "up", expect = null)
                "back" -> steps += Step("s$i", "back", expect = expect)
                "home" -> steps += Step("s$i", "home")
                "wait" -> steps += Step("s$i", "wait", ms = 800)
            }
        }
        if (steps.isEmpty()) return null
        // アプリの中から始めた記録 (反射でアプリを先に開いた・開いた画面から頼まれた) なら、最初にそのアプリを開く手順を足す
        // (次に使うときは、どの画面からでも同じように始められる)
        val startPkg = ok.first().before?.pkg
        if (steps.first().op !in setOf("launch", "shortcut") && startPkg != null && startPkg.contains('.') && startPkg !in homePkgs) {
            steps.add(0, Step("s_start", "launch", pkg = startPkg, expect = Expect(pkg = startPkg, timeoutMs = 6000)))
            appPkg = appPkg ?: startPkg
        }
        val last = ok.lastOrNull { it.after != null }
        val success = last?.after?.let { a -> expectFor(last.before, a)?.copy(timeoutMs = 5000) }
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: utterance.take(24)
        val id = "learned." + (meta?.id?.let { slug(it) }?.takeIf { it.isNotBlank() } ?: idHint)
        return Part(
            id = id, name = name, kind = PartKind.APP, version = 0,
            description = meta?.description?.takeIf { it.isNotBlank() } ?: "「$utterance」の操作 (覚えた手順)",
            examples = (listOf(utterance) + meta?.examples.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8),
            args = literalToArg.values.toList(),
            app = appPkg?.let { AppSpec(it, it) },
            conditions = Conditions(requiresUnlock = true, requiresAdb = true),
            preconditions = listOfNotNull(appPkg?.let { Precondition("app_installed", it) }),
            steps = steps, success = success, commit = commitSpec,
            verification = Verification(VerifyStatus.DRAFT), source = "learned", createdFrom = if (ok.any { it.jev }) "$utterance (Jev が選んだ手を含む。学習データに使わない)" else utterance,
            sayStart = (meta?.sayStart?.let { phrase(it, literalToArg) } ?: phrase(doneMessage, literalToArg)?.let { startFromDone(it) }),
            sayDone = meta?.sayDone?.let { phrase(it, literalToArg) } ?: phrase(doneMessage, literalToArg),
        )
    }

    /**
     * 声かけの文を、次からも使える形にする: 記録の中の具体的な値 (送った本文など) は {引数} に置き換え、
     * 知らない {…} は取り除く。長すぎる・空の文は使わない
     */
    fun phrase(text: String, literalToArg: Map<String, ArgSpec>): String? {
        var s = text.trim()
        if (s.isEmpty()) return null
        for ((lit, a) in literalToArg.entries.sortedByDescending { it.key.length }) if (lit.length >= 2) s = s.replace(lit, "{${a.name}}")
        val known = literalToArg.values.map { it.name }.toSet()
        s = Regex("\\{([a-zA-Z0-9_.]+)\\}").replace(s) { m -> if (m.groupValues[1].substringBefore('.') in known) m.value else "" }
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.takeIf { it.length in 2..60 }
    }

    /** 終わりの言葉から始めの言葉を作る (「〜を開きました」→「〜を開きますね」)。作れなければ null */
    fun startFromDone(done: String): String? {
        val d = done.trimEnd('。', '！', '!', ' ')
        return if (d.endsWith("ました") && d.length > 3) d.removeSuffix("ました") + "ますね" else null
    }

    /**
     * 押した要素の指定。ID があれば ID、文字があれば文字 (どちらかが合えばよい)。
     * 押した要素に名前も ID も無いとき (アイコンだけのボタンなど) は、押す前の画面 (screen) で、その中にある部品の名前を使う
     */
    fun selectorFor(n: UiNode, literalToArg: Map<String, ArgSpec> = emptyMap(), screen: Observation? = null): Selector {
        val id = n.shortId.takeIf { it.isNotBlank() && !it.startsWith("android:") }
        fun arg(s: String) = literalToArg[s]?.let { "{${it.name}}" } ?: s
        val text = n.text.takeIf { it.isNotBlank() && it.length <= 40 }?.let { arg(it) }
        val desc = n.desc.takeIf { it.isNotBlank() && it.length <= 40 }?.let { arg(it) }
        val own = Selector(text = listOfNotNull(text), desc = listOfNotNull(desc), id = listOfNotNull(id), exact = text != null && text.length <= 12)
        if (!own.isEmpty || screen == null) return own
        return screen.nodes.asSequence()
            .filter { it.index != n.index && it.depth > n.depth && it.bounds.area > 0 && n.bounds.contains(it.bounds.cx, it.bounds.cy) }
            .map { selectorFor(it, literalToArg) }.firstOrNull { !it.isEmpty } ?: own
    }

    /** 操作のあとに新しく出てきた文字 (短いもの 3 つまで) とアプリで画面を確かめる。 */
    fun expectFor(before: Observation?, after: Observation): Expect? {
        if (!after.ok) return null
        val prev = before?.texts?.map { Text.norm(it) }?.toSet().orEmpty()
        val fresh = after.texts.filter { s -> s.length in 2..20 && !noise.matches(s) && Text.norm(s) !in prev }.distinct().take(3)
        return Expect(pkg = after.pkg, anyText = fresh, timeoutMs = 4000)
    }

    private fun slug(s: String) = Text.norm(s).replace(Regex("[^a-z0-9_.]"), "_").trim('_').take(40)
}
