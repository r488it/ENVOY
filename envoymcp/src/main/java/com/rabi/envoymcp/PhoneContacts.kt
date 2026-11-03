package com.rabi.envoymcp

import android.content.Context
import android.provider.ContactsContract
import android.telephony.TelephonyManager

/**
 * 電話をかける相手を、連絡先から探す (電話のアプリを操作すると手順が多いので、1 回で発信する)。
 * - 名前・ニックネーム・読み (フリガナ) で探す。ひらがな・カタカナ、全角・半角、大文字・小文字、空白は区別しない。
 *   「さん」「様」などは外して探す。名前がそのまま合う人がいれば、その人だけにする
 * - 連絡先の中身は ChatGPT に渡さない (選んだ 1 人の名前と、番号の種類・下 4 桁だけ。何人か当たったときは名前だけ)
 */
object PhoneContacts {
    /** 電話番号 1 つ (同じ人に番号がいくつかあれば、1 人で何行にもなる) */
    data class Entry(val contactId: Long, val name: String, val number: String, val type: String, val mobile: Boolean,
                     val primary: Boolean, val aliases: List<String> = emptyList())

    /** 当たった 1 人と、その人の番号 */
    data class Person(val name: String, val numbers: List<Entry>)

    private val honorifics = listOf("さん", "様", "さま", "くん", "君", "ちゃん", "氏", "先生")

    /** 連絡先の電話番号を全部読む (READ_CONTACTS が要る)。ニックネームと読みも、人ごとに付ける */
    fun load(context: Context): List<Entry> {
        val cr = context.contentResolver
        val aliases = HashMap<Long, MutableList<String>>()
        cr.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
                ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val list = aliases.getOrPut(id) { ArrayList() }
                if (c.getString(1) == ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE) c.getString(2)?.let { list += it }
                else listOfNotNull(c.getString(3), c.getString(4)).joinToString("").takeIf { it.isNotBlank() }?.let { list += it }
            }
        }
        val out = ArrayList<Entry>()
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(2)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val t = c.getInt(3)
                out += Entry(id, c.getString(1).orEmpty(), number, typeLabel(t, c.getString(4)),
                    t == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, c.getInt(5) != 0, aliases[id].orEmpty())
            }
        }
        return out
    }

    private fun typeLabel(t: Int, label: String?) = when (t) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "携帯"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "自宅"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "勤務先"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "代表"
        else -> label?.takeIf { it.isNotBlank() } ?: "その他"
    }

    /** 名前の探し方に合わせて整える (敬称を外し、表記のゆれをなくす) */
    fun key(s: String): String {
        var t = s.trim()
        for (h in honorifics) if (t.length > h.length && t.endsWith(h)) { t = t.dropLast(h.length); break }
        return SettingsSearch.norm(t)
    }

    /** 名前・ニックネーム・読みに q が入っている人 (名前がそのまま合う人がいれば、その人だけ) */
    fun find(entries: List<Entry>, q: String): List<Person> {
        val k = key(q)
        if (k.isEmpty()) return emptyList()
        val people = entries.groupBy { it.contactId }.values.map { Person(it.first().name, it) }
        fun names(p: Person) = (listOf(p.name) + p.numbers.first().aliases).map { SettingsSearch.norm(it) }
        val exact = people.filter { p -> names(p).any { it == k } }
        if (exact.isNotEmpty()) return exact
        return people.filter { p -> names(p).any { it.contains(k) } }
    }

    /** 1 人の番号から選ぶ: 種類の指定 (mobile・home・work) があればそれ、なければ よく使う番号 (既定) → 携帯 → 最初 */
    fun pick(p: Person, type: String?): Entry? {
        val want = when (type) { "mobile" -> "携帯"; "home" -> "自宅"; "work" -> "勤務先"; else -> null }
        if (want != null) return p.numbers.firstOrNull { it.type == want }
        return p.numbers.firstOrNull { it.primary } ?: p.numbers.firstOrNull { it.mobile } ?: p.numbers.firstOrNull()
    }

    /** 番号らしい文字か (数字が 3 桁以上で、数字・+・-・括弧・空白だけ) */
    fun isNumber(s: String): Boolean = s.isNotBlank() && s.all { it.isDigit() || it in "+-() 　" } && s.count { it.isDigit() } >= 3

    /** 番号の数字だけ (+ は残す) */
    fun digits(s: String): String = SettingsSearch.norm(s).filter { it.isDigit() || it == '+' }

    /** 番号の下 4 桁 */
    fun tail(s: String): String = digits(s).takeLast(4)

    /** 緊急の番号か (ENVOY MCP からはかけない) */
    fun isEmergency(context: Context, number: String): Boolean {
        val d = digits(number)
        if (d in setOf("110", "119", "118", "112", "911", "999", "000")) return true
        return runCatching { context.getSystemService(TelephonyManager::class.java).isEmergencyNumber(d) }.getOrDefault(false)
    }
}
