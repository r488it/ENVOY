package com.rabi.envoymcp

import java.text.Normalizer

/**
 * 設定の画面の検索 (項目が多いので、探して絞り込む)。
 * - 見出し (カード) に言葉が入っていれば、そのカードを丸ごと出す
 * - 入っていなければ、項目 (スイッチ・ボタン・行・説明) のうち、言葉が入っているものだけを出す。合う項目が無いカードは出さない
 * - カードの中の項目の文字は、出したときに覚えておく (検索の前は全部出しているので、全部覚えている)
 * - ひらがな・カタカナ、全角・半角、大文字・小文字、空白は区別しない
 */
class SettingsSearch {
    /** 正規化した検索の言葉 (空なら絞り込まない) */
    var query = ""
        private set

    /** カードの見出し → 中の項目の文字 (正規化したもの) */
    private val texts = HashMap<String, MutableSet<String>>()

    fun setQuery(q: String) { query = norm(q) }

    val active get() = query.isNotEmpty()

    /** カードの中に、この文字の項目がある (出したときに呼ぶ) */
    fun add(card: String, text: String) {
        if (text.isBlank()) return
        texts.getOrPut(card) { HashSet() }.add(norm(text))
    }

    /** 見出しに言葉が入っている (中は全部出す) */
    fun titleHit(card: String) = !active || norm(card).contains(query)

    /** このカードを出すか */
    fun cardVisible(card: String) = titleHit(card) || texts[card]?.any { it.contains(query) } == true

    /** 見出しが合っていないカードの中で、この項目を出すか */
    fun itemHit(vararg text: String) = !active || text.any { norm(it).contains(query) }

    /** 合うカードが 1 つでもあるか (覚えているカードの中で) */
    fun anyVisible() = !active || texts.keys.any { cardVisible(it) }

    companion object {
        fun norm(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            val b = StringBuilder(n.length)
            for (c in n) {
                when {
                    c.isWhitespace() -> {}
                    c in 'ァ'..'ヶ' -> b.append(c - 0x60)      // カタカナ → ひらがな
                    else -> b.append(c)
                }
            }
            return b.toString()
        }
    }
}
