package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.core.Text

/**
 * 入力欄が検索欄か (YouTube などの検索欄は、入れただけでは検索しない)。
 * 検索欄なら、入れたあと実行 (キーボードの検索ボタン・Enter) してよい。Enter は、メッセージ・コメントの欄では送信になって
 * 承認を飛ばすので、それらの欄では実行しない (送信のボタンは tap で押し、承認を通す)
 */
object SearchField {
    private val searchWords = listOf("search", "検索", "url_bar", "omnibox", "searchview", "searchautocomplete", "query")
    /** 送信になる欄の言葉 (検索の言葉があっても、これがあれば検索欄とみなさない) */
    private val sendWords = listOf("メッセージ", "message", "コメント", "comment", "返信", "reply", "ポスト", "post", "送信", "send", "chat", "チャット", "ツイート", "tweet", "投稿")

    fun isSearch(resId: String, hint: String, desc: String, cls: String): Boolean {
        val s = Text.norm("$resId $hint $desc $cls")
        if (sendWords.any { s.contains(Text.norm(it)) }) return false
        return searchWords.any { s.contains(Text.norm(it)) }
    }
}
