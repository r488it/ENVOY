package com.rabi.envoymcp.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 検索欄なら入れたあと実行してよい。メッセージ・コメントの欄は実行しない (送信になって承認を飛ばすため) */
class SearchFieldTest {
    @Test fun searchFieldsAreFound() {
        assertTrue(SearchField.isSearch("com.google.android.youtube:id/search_edit_text", "YouTube を検索", "", "android.widget.EditText"))
        assertTrue(SearchField.isSearch("com.android.chrome:id/url_bar", "検索または URL を入力", "", "android.widget.EditText"))
        assertTrue(SearchField.isSearch("", "", "", "android.widget.SearchView\$SearchAutoComplete"))
        assertTrue(SearchField.isSearch("", "Search", "", "android.widget.EditText"))
    }

    @Test fun messageAndCommentFieldsAreNot() {
        assertFalse(SearchField.isSearch("com.whatsapp:id/entry", "メッセージ", "", "android.widget.EditText"))
        assertFalse(SearchField.isSearch("", "コメントを追加...", "", "android.widget.EditText"))
        assertFalse(SearchField.isSearch("com.twitter.android:id/tweet_text", "いまどうしてる?", "", "android.widget.EditText"))
        assertFalse(SearchField.isSearch("", "メッセージを検索", "", "android.widget.EditText"))   // 送信の言葉があれば、検索欄とみなさない (安全側)
        assertFalse(SearchField.isSearch("", "名前", "", "android.widget.EditText"))
    }
}
