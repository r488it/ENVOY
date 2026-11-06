package com.rabi.envoymcp.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** open_shortcut: 開くと電話がかかるかもしれないショートカット (電話・連絡先のアプリ、発信・通話の名前) は開かない */
class AppShortcutsTest {
    private fun mayCall(pkg: String, label: String, long: String = "") = AppShortcuts.mayCall(AppShortcut(pkg, "id", label, long))

    @Test fun dialerAndContactsShortcutsMayCall() {
        assertTrue(mayCall("com.google.android.dialer", "山田さん"))
        assertTrue(mayCall("com.android.dialer", "新しい連絡先"))
        assertTrue(mayCall("com.google.android.contacts", "お母さん"))
        assertTrue(mayCall("com.samsung.android.dialer", "Mom"))
    }

    @Test fun callLabelsInOtherAppsMayCall() {
        assertTrue(mayCall("com.example.chat", "山田さんに発信"))
        assertTrue(mayCall("com.example.chat", "ビデオ通話"))
        assertTrue(mayCall("com.example.chat", "Mom", "Call Mom"))
        assertTrue(mayCall("com.example.chat", "Dial 117"))
    }

    @Test fun otherShortcutsOpen() {
        assertFalse(mayCall("com.twitter.android", "ポスト"))
        assertFalse(mayCall("com.google.android.apps.messaging", "新しいメッセージ"))
        assertFalse(mayCall("jp.naver.line.android", "トーク"))
        assertFalse(mayCall("com.example.notes", "Recall notes"))
    }
}
