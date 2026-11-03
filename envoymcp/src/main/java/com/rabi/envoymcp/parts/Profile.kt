package com.rabi.envoymcp.parts

import org.json.JSONArray
import org.json.JSONObject

/** 引数の値。fields はテンプレート {arg.field} で使う。 */
data class ArgValue(val type: ArgType, val display: String, val fields: Map<String, String>) {
    fun toJson() = JSONObject().put("type", type.name.lowercase()).put("display", display).put("fields", JSONObject(fields as Map<*, *>))
    companion object {
        fun text(s: String) = ArgValue(ArgType.TEXT, s, mapOf("value" to s, "label" to s))
        fun from(j: JSONObject): ArgValue {
            val f = j.optJSONObject("fields") ?: JSONObject()
            return ArgValue(ArgType.valueOf(j.optString("type", "text").uppercase()), j.optString("display"), f.keys().asSequence().associateWith { f.getString(it) })
        }
    }
}

/** 人の登録。key は呼び方 (娘)、aliases は他の呼び方。 */
data class PersonEntry(
    val key: String,
    val aliases: List<String>,
    val name: String,
    val phone: String? = null,
    /** テスト用送信画面での宛先名 */
    val testMessengerName: String? = null,
    val note: String = "",
) {
    /** 話すときの呼び方 (娘 → 娘さん) */
    val spoken: String get() = if (key.endsWith("さん") || key.endsWith("ちゃん") || key.endsWith("くん")) key else when (key) {
        "娘", "息子", "孫", "妻", "夫" -> key + "さん"
        else -> key
    }

    fun toArg(): ArgValue {
        val f = linkedMapOf("label" to spoken, "name" to name, "key" to key)
        phone?.let { f["phone"] = it.filter { c -> c.isDigit() || c == '+' }; f["phone_display"] = it; f["phone_tail"] = it.filter { c -> c.isDigit() }.takeLast(4) }
        testMessengerName?.let { f["test_name"] = it }
        return ArgValue(ArgType.CONTACT, "$spoken（$name）", f)
    }

    fun toJson() = JSONObject().put("key", key).put("aliases", JSONArray(aliases)).put("name", name).put("phone", phone ?: JSONObject.NULL)
        .put("test_messenger_name", testMessengerName ?: JSONObject.NULL).put("note", note)
    companion object {
        fun from(j: JSONObject) = PersonEntry(j.getString("key"), j.optJSONArray("aliases").strings(), j.optString("name"),
            j.optString("phone").nn(), j.optString("test_messenger_name").nn(), j.optString("note"))
    }
}

data class PlaceEntry(val key: String, val aliases: List<String>, val name: String, val address: String? = null, val phone: String? = null) {
    fun toArg(): ArgValue {
        val f = linkedMapOf("label" to key, "name" to name)
        address?.let { f["address"] = it }; phone?.let { f["phone"] = it.filter { c -> c.isDigit() || c == '+' }; f["phone_display"] = it }
        return ArgValue(ArgType.PLACE, "$key（$name）", f)
    }
    fun toJson() = JSONObject().put("key", key).put("aliases", JSONArray(aliases)).put("name", name)
        .put("address", address ?: JSONObject.NULL).put("phone", phone ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = PlaceEntry(j.getString("key"), j.optJSONArray("aliases").strings(), j.optString("name"), j.optString("address").nn(), j.optString("phone").nn())
    }
}

/** 「いつもの」: 呼び名と部品と引数の組。 */
data class UsualEntry(val key: String, val partId: String, val args: Map<String, String>) {
    fun toJson() = JSONObject().put("key", key).put("part", partId).put("args", JSONObject(args as Map<*, *>))
    companion object {
        fun from(j: JSONObject): UsualEntry {
            val a = j.optJSONObject("args") ?: JSONObject()
            return UsualEntry(j.getString("key"), j.getString("part"), a.keys().asSequence().associateWith { a.getString(it) })
        }
    }
}

data class Profile(
    val userName: String = "",
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val people: List<PersonEntry> = emptyList(),
    val places: List<PlaceEntry> = emptyList(),
    val usual: List<UsualEntry> = emptyList(),
) {
    fun toJson() = JSONObject().put("user_name", userName).put("assistant_name", assistantName)
        .put("people", JSONArray(people.map { it.toJson() })).put("places", JSONArray(places.map { it.toJson() }))
        .put("usual", JSONArray(usual.map { it.toJson() }))

    companion object {
        fun from(j: JSONObject) = Profile(
            j.optString("user_name"), j.optString("assistant_name", DEFAULT_ASSISTANT_NAME).ifBlank { DEFAULT_ASSISTANT_NAME },
            j.optJSONArray("people").objects().map { PersonEntry.from(it) },
            j.optJSONArray("places").objects().map { PlaceEntry.from(it) },
            j.optJSONArray("usual").objects().map { UsualEntry.from(it) },
        )

        /** 導入直後の見本 (テスト用の宛先だけ。実在の人の情報は入れない) */
        fun sample() = Profile(
            people = listOf(
                PersonEntry("娘", listOf("娘", "むすめ", "はなこ", "花子"), "テスト 花子", phone = "000-0000-0001", testMessengerName = "花子（テスト）", note = "テスト用の宛先"),
                PersonEntry("息子", listOf("息子", "むすこ", "たろう", "太郎"), "テスト 太郎", phone = "000-0000-0002", testMessengerName = "太郎（テスト）", note = "テスト用の宛先"),
            ),
            places = listOf(PlaceEntry("いつもの病院", listOf("病院", "いつもの病院", "かかりつけ"), "テスト病院", phone = "000-0000-0100")),
        )
    }
}

/** 端末の連絡先 (Android の ContactsProvider など) を探す口。試験では偽物を使う。 */
fun interface ContactSource {
    fun search(query: String): List<PersonEntry>
}

sealed class Resolution {
    data class Found(val value: ArgValue) : Resolution()
    /** 曖昧。勝手に選ばず、聞き返す */
    data class Ambiguous(val options: List<ArgValue>) : Resolution()
    data object NotFound : Resolution()
}

/** 呼び方 → 人・場所。曖昧なら Ambiguous を返す (勝手に選ばない)。 */
class ProfileResolver(private val profile: () -> Profile, private val contacts: ContactSource? = null) {

}

/** アシスタントの呼び名 (ENVOY MCP は呼びかけを使わないので、表示だけ) */
const val DEFAULT_ASSISTANT_NAME = "リゲル"
