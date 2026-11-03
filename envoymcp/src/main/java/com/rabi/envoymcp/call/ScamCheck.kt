package com.rabi.envoymcp.call

import java.text.Normalizer

/**
 * 詐欺の疑いを見る (リゲルが代わりに出た電話で、相手が言ったことから)。話の種類 (Sign) ごとの言い回し (RULES) に当たるかを見て、
 * 疑いの強さ (Level) と理由 (当たった種類の短い名前) を返す。このスマホの中だけで見る (どこにも送らない)
 * - 見るのは相手の言葉だけ。リゲルの言葉は数えない (リゲルは相手の言葉をくり返したり、お金の話を断ったりする)
 * - 言い回しに当たったかを見るだけなので、詐欺かどうかは決められない。出す言葉は「詐欺の疑い」(「詐欺です」とは言わない)
 * - 比べる前に、文字をそろえる (fold: 全角・半角をそろえ、英字を小文字に、カタカナをひらがなに。相手の言葉は、空白と読点も除く)。
 *   言い回しも同じようにそろえてから使うので、カタカナで書けばひらがなの言葉にも当たる。漢字の書き方の違い (振り込・振込) は並べて書く
 * - 強さ:
 *   - 高い: 「お金の話」か「大事な番号を聞く」が、「急がせる」「警察などを名乗る」「おどす」「秘密にさせる」「身内を名乗る」「自動音声」の
 *     どれかと一緒に出た。または、種類が 3 つ以上
 *   - 気をつけて: それ以外で、種類が 1〜2 つ (ふつうの電話でも「お支払い」「期限」などは出るので、1 つだけでは高くしない)
 *   - なし: どれにも当たらない
 */
object ScamCheck {
    /** 疑いの強さ。label は画面・報告に出す言葉 */
    enum class Level(val label: String) {
        NONE(""), CAUTION("気をつけて"), HIGH("高い");

        companion object {
            /** 記録の文字から (無い・分からなければ NONE) */
            fun of(s: String?): Level = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NONE
        }
    }

    /** 話の種類。label は理由として出す短い名前 (並びはこの順) */
    enum class Sign(val label: String) {
        MONEY("お金の話"), NUMBERS("大事な番号を聞く"), URGENT("急がせる"), AUTHORITY("警察などを名乗る"),
        THREAT("おどす"), SECRET("秘密にさせる"), KIN("身内を名乗る"), ROBOT("自動音声"),
    }

    data class Result(val level: Level, val reasons: List<String>)

    /**
     * 種類ごとの言い回し (正規表現)。カタカナ・全角はそのまま書いてよい (fold でそろえる)。
     * そろえると英字は小文字になるので、\D・\S などの大文字の記号は使わない
     */
    private val RULES: Map<Sign, List<String>> = mapOf(
        Sign.MONEY to listOf(
            "振り込", "振込", "ふりこ[みまむめん]", "送金", "そうきん", "口座", "現金", "げんきん", "支払", "しはらい", "払い込", "払込",
            "手数料", "てすうりょう", "電子マネー", "ギフトカード", "ギフト券", "ギフトコード", "プリペイド", "還付", "かんぷきん",
            "返金", "へんきん", "未納", "みのう", "滞納", "たいのう", "保証金", "示談", "仮想通貨", "暗号資産", "ビットコイン",
            "ATM", "エーティーエム", "通帳",
            "コンビニ.{0,8}(払|はら)",
            // お金・現金・料金などを「用意して」「送って」「下ろして」…、お金が「必要」「足りない」「戻る」
            "金を(用意|準備|貸|借|振|送|渡|払|はら|下ろ|おろ|引き出|ひきだ|預|あず|立て替|たてか|使い込|つかいこ)",
            "金が(必要|ひつよう|要る|いる|足りな|たりな|戻|もど)",
        ),
        Sign.NUMBERS to listOf(
            "暗証", "あんしょうばんごう", "キャッシュカード", "カードの?(番号|ばんごう)", "口座の?番号", "パスワード", "ワンタイム",
            "認証コード", "確認コード", "セキュリティコード",
            // マイナンバーカード (を持ってきて) は病院・役所のふつうの話にも出るので数えない。カードの番号は上で数える
            "マイナンバー(?!カード)",
            "カードを(預か|あずか|受け取|うけと|回収|かいしゅう|取りに|とりに)",
        ),
        Sign.URGENT to listOf(
            "今すぐ", "いますぐ", "至急", "今日中", "きょうじゅう", "本日中", "ほんじつじゅう", "今日まで", "きょうまで", "本日まで",
            "急いで", "いそいで", "急ぎで", "期限",
            // すぐ (に) 振り込んで・手続きして・電話して…。「まっすぐ行って」(道案内) は数えない
            "(?<!まっ)すぐに?(振|払|手続|てつづ|行って|いって|来て|きて|連絡|れんらく|お?電話|でんわ)",
        ),
        Sign.AUTHORITY to listOf(
            "警察", "けいさつ", "警視庁", "刑事", "検察", "検事", "裁判所", "弁護士", "べんごし", "法務省", "税務署", "役所", "やくしょ",
            "年金事務所", "年金機構", "社会保険事務所", "金融庁", "財務局", "厚生労働省", "総務省",
            "消費者センター", "消費生活センター", "国民生活センター", "銀行協会",
        ),
        Sign.THREAT to listOf(
            // 「裁判所」は名乗り (上) なので、ここでは数えない
            "逮捕", "たいほ", "訴え", "うったえ", "訴訟", "裁判(?!所)", "さいばん(?!しょ)", "差し押さ", "差押", "さしおさえ",
            "犯罪", "はんざい", "事件", "詐欺", "捜査", "容疑", "罰金", "前科", "法的", "ほうてき", "停止", "止まります", "止まってしま",
            "(口座|カード|資産|財産)(が|の|を)?凍結", "(カード|口座|電話|携帯|スマホ)が?(使|つか)えなくな",
        ),
        Sign.SECRET to listOf(
            "内緒", "ないしょ", "秘密", "ひみつ", "口外", "他言",
            "(誰|だれ)にも(言|い[わえ]|話|はな|相談|そうだん|知らせ|しらせ)",
            // 家族には言わないで・銀行の人に話さずに… (「家族に話して」は数えない)
            "(家族|かぞく|身内|みうち|銀行|ぎんこう|息子|むすこ|娘|むすめ|旦那|奥さん|父さん|母さん|周り|まわり)(の人|の方|さん)?" +
                "(に|には|にも|と|とは)(言|い[わえ]|話|はな|相談|そうだん|知らせ|しらせ)[さわしせ]?(ず|な)",
        ),
        Sign.KIN to listOf(
            "オレオレ", "(俺|おれ)(だけど|だよ|です|だって)",
            "(携帯|けいたい|スマホ|電話|でんわ)(を|が)?(なくし|無くし|失くし|落とし|おとし|壊れ|こわれ|壊し|こわし|盗まれ|ぬすまれ)",
            "(番号|ばんごう)(が|を|も)?(変わ|かわ|変え|かえ)",
            "(風邪|かぜ)(で|を|ひ|引).{0,6}(声|こえ|喉|のど)",
        ),
        Sign.ROBOT to listOf(
            "自動音声", "じどうおんせい", "自動応答", "ガイダンス",
            // 「1 を押して」「番号をお押しください」(「番号を教えて」は数えない)
            "(番号|[0-9#*一二三四五六七八九〇]番?)を(お?押|おし(?!え))",
            "(オペレーター|係員)(に|へ)お?(つなぎ|繋ぎ|つなが|繋が)",
        ),
    )

    private val PATTERNS: Map<Sign, Regex> = RULES.mapValues { (_, l) -> Regex(l.joinToString("|") { "(?:${fold(it)})" }) }

    /** 「高い」になるのは、お金の話・大事な番号を聞く話が、これらのどれかと一緒に出たとき */
    private val PRESSURE = setOf(Sign.URGENT, Sign.AUTHORITY, Sign.THREAT, Sign.SECRET, Sign.KIN, Sign.ROBOT)

    /** 相手の言葉から外すもの (空白・読点・中黒。言い回しの途中に入っても当たるように) */
    private val DROP = Regex("[\\s、,・]")

    /** やり取りから (相手の行だけを見る) */
    fun check(lines: List<CallLine>): Result = checkTexts(lines.filter { it.who == "peer" }.map { it.text })

    /** 相手の言葉 (1 行ずつ) から */
    fun checkTexts(peer: List<String>): Result {
        val t = peer.joinToString("\n") { norm(it) }
        val signs = Sign.entries.filter { PATTERNS.getValue(it).containsMatchIn(t) }
        return Result(levelOf(signs), signs.map { it.label })
    }

    internal fun levelOf(signs: Collection<Sign>): Level {
        val ask = Sign.MONEY in signs || Sign.NUMBERS in signs
        return when {
            signs.isEmpty() -> Level.NONE
            ask && signs.any { it in PRESSURE } || signs.size >= 3 -> Level.HIGH
            else -> Level.CAUTION
        }
    }

    /** 詐欺の疑いがあるときに、ChatGPT に頼む伝え方 (言い切らない。お金・番号・家族への相談) */
    const val ADVICE = "詐欺の疑いがあることを、はっきり伝えてください（詐欺だと言い切らずに）。" +
        "あわせて、お金を払ったり振り込んだりしないこと、相手が言った電話番号にかけないこと、カードの番号や暗証番号を教えないことを勧め、" +
        "家族に相談して確かめるよう伝えてください。"

    /** 画面・報告・お知らせで疑いを表す言葉 */
    const val MARK = "詐欺の疑い"

    /** 「詐欺の疑い: 高い」(なしなら空) */
    fun title(level: Level): String = if (level == Level.NONE) "" else "$MARK: ${level.label}"

    /** 「詐欺の疑い: 高い（お金の話・急がせる）」(なしなら空) */
    fun label(level: Level, reasons: List<String>): String =
        title(level).let { if (it.isEmpty() || reasons.isEmpty()) it else it + reasons.joinToString("・", "（", "）") }

    /** 報告・お知らせに足す文「詐欺の疑い: 高い（お金の話・急がせる）。」(なしなら空) */
    fun sentence(level: Level, reasons: List<String>): String = label(level, reasons).let { if (it.isEmpty()) "" else "$it。" }

    /** 相手の言葉をそろえる (fold して、空白・読点を除く) */
    internal fun norm(s: String): String = fold(s).replace(DROP, "")

    /** 全角・半角をそろえ (NFKC)、英字を小文字に、カタカナをひらがなにする */
    private fun fold(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val b = StringBuilder(n.length)
        for (ch in n) b.append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
        return b.toString()
    }
}
