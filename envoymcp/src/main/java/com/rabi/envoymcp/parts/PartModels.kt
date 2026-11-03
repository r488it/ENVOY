package com.rabi.envoymcp.parts

import org.json.JSONArray
import org.json.JSONObject

/**
 * 操作部品。
 * - BASIC: アプリを問わない基本操作 (アプリを開く・戻る など)
 * - APP: 特定アプリの画面操作
 * - ERRAND: 用事を完了する部品 (他の部品を呼ぶこともある)
 *
 * 個人設定 (「娘」「いつもの病院」など) は部品に入れず、引数として PersonalProfile から解決する。
 * 手順は座標列ではなく、要素の文字・説明・ID で指定し、各操作のあとに画面を確かめる。
 */
data class Part(
    val id: String,
    val name: String,
    val kind: PartKind,
    val version: Int,
    val description: String,
    val examples: List<String>,
    val args: List<ArgSpec>,
    val app: AppSpec?,
    val conditions: Conditions,
    val preconditions: List<Precondition>,
    val steps: List<Step>,
    val success: Expect?,
    val commit: CommitSpec?,
    val verification: Verification,
    val source: String,
    val createdFrom: String? = null,
    /** 作業を始めるときの短い声かけ (テンプレート。例: 「{app.label}を開きますね」) */
    val sayStart: String? = null,
    /** 終わったときの声かけ (テンプレート。例: 「{app.label}を開きました」)。無ければ「できました」 */
    val sayDone: String? = null,
    /** 終わったらリゲルの画面に戻るか。null なら、確定のある用事 (送る・投稿する) だけ戻る */
    val backHome: Boolean? = null,
) {
    val hasCommit get() = commit != null || steps.any { it.commit }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1).put("id", id).put("name", name).put("kind", kind.name.lowercase()).put("version", version)
        .put("description", description).put("examples", JSONArray(examples))
        .put("args", JSONArray(args.map { it.toJson() })).put("app", app?.toJson() ?: JSONObject.NULL)
        .put("conditions", conditions.toJson()).put("preconditions", JSONArray(preconditions.map { it.toJson() }))
        .put("steps", JSONArray(steps.map { it.toJson() })).put("success", success?.toJson() ?: JSONObject.NULL)
        .put("commit", commit?.toJson() ?: JSONObject.NULL).put("verification", verification.toJson())
        .put("source", source).put("created_from", createdFrom ?: JSONObject.NULL).put("say_start", sayStart ?: JSONObject.NULL)
        .put("say_done", sayDone ?: JSONObject.NULL).apply { if (backHome != null) put("back_home", backHome) }

    companion object {
        fun from(j: JSONObject): Part = Part(
            id = j.getString("id"),
            name = j.getString("name"),
            kind = PartKind.valueOf(j.optString("kind", "app").uppercase()),
            version = j.optInt("version", 1),
            description = j.optString("description"),
            examples = j.optJSONArray("examples").strings(),
            args = j.optJSONArray("args").objects().map { ArgSpec.from(it) },
            app = j.optJSONObject("app")?.let { AppSpec.from(it) },
            conditions = j.optJSONObject("conditions")?.let { Conditions.from(it) } ?: Conditions(),
            preconditions = j.optJSONArray("preconditions").objects().map { Precondition.from(it) },
            steps = j.optJSONArray("steps").objects().map { Step.from(it) },
            success = j.optJSONObject("success")?.let { Expect.from(it) },
            commit = j.optJSONObject("commit")?.let { CommitSpec.from(it) },
            verification = j.optJSONObject("verification")?.let { Verification.from(it) } ?: Verification(),
            source = j.optString("source", "builtin"),
            createdFrom = j.optString("created_from").takeIf { it.isNotEmpty() && it != "null" },
            sayStart = j.optString("say_start").takeIf { it.isNotEmpty() && it != "null" },
            sayDone = j.optString("say_done").takeIf { it.isNotEmpty() && it != "null" },
            backHome = if (j.has("back_home") && !j.isNull("back_home")) j.optBoolean("back_home") else null,
        )
    }
}

enum class PartKind { BASIC, APP, ERRAND }

/** 検証状態。DRAFT は保存前、PROVISIONAL は暫定 (実行前に一言確認・逸れたら即停止)、VERIFIED は再利用で確かめ済み。 */
enum class VerifyStatus { DRAFT, PROVISIONAL, VERIFIED, DISABLED }

enum class ArgType { CONTACT, TEXT, APP, PLACE, CHOICE, NUMBER }

data class ArgSpec(
    val name: String,
    val type: ArgType,
    val required: Boolean = true,
    /** 足りないときに聞く質問 (一度に一つ) */
    val ask: String = "",
    val description: String = "",
    val options: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().put("name", name).put("type", type.name.lowercase()).put("required", required)
        .put("ask", ask).put("description", description).put("options", JSONArray(options))

    companion object {
        fun from(j: JSONObject) = ArgSpec(
            j.getString("name"), ArgType.valueOf(j.optString("type", "text").uppercase()), j.optBoolean("required", true),
            j.optString("ask"), j.optString("description"), j.optJSONArray("options").strings(),
        )
    }
}

data class AppSpec(val pkg: String?, val label: String, val alternatives: List<String> = emptyList()) {
    fun toJson() = JSONObject().put("package", pkg ?: JSONObject.NULL).put("label", label).put("alternatives", JSONArray(alternatives))
    companion object {
        fun from(j: JSONObject) = AppSpec(j.optString("package").takeIf { it.isNotEmpty() && it != "null" }, j.optString("label"), j.optJSONArray("alternatives").strings())
    }
}

data class Conditions(
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    /** 他アプリの操作や個人情報を使うのでロック解除が要る */
    val requiresUnlock: Boolean = true,
    /** ADB の接続が要る (Intent だけで済む部品でも、画面確認には要る) */
    val requiresAdb: Boolean = true,
) {
    fun toJson() = JSONObject().put("min_sdk", minSdk ?: JSONObject.NULL).put("max_sdk", maxSdk ?: JSONObject.NULL)
        .put("requires_unlock", requiresUnlock).put("requires_adb", requiresAdb)
    companion object {
        fun from(j: JSONObject) = Conditions(
            j.optInt("min_sdk", 0).takeIf { it > 0 }, j.optInt("max_sdk", 0).takeIf { it > 0 },
            j.optBoolean("requires_unlock", true), j.optBoolean("requires_adb", true),
        )
    }
}

/** 実行前の条件。type: app_installed / arg / profile / adb / unlocked */
data class Precondition(val type: String, val value: String = "") {
    fun toJson() = JSONObject().put("type", type).put("value", value)
    companion object { fun from(j: JSONObject) = Precondition(j.getString("type"), j.optString("value")) }
}

/** 要素の指定。文字・説明・リソース ID のいずれか (配列は「どれか」)。テンプレート {arg.field} 可。 */
data class Selector(
    val text: List<String> = emptyList(),
    val desc: List<String> = emptyList(),
    val id: List<String> = emptyList(),
    val cls: String? = null,
    val editable: Boolean? = null,
    /** true なら完全一致、false なら部分一致 */
    val exact: Boolean = false,
) {
    val isEmpty get() = text.isEmpty() && desc.isEmpty() && id.isEmpty() && cls == null && editable == null
    fun toJson() = JSONObject().put("text", JSONArray(text)).put("desc", JSONArray(desc)).put("id", JSONArray(id))
        .put("cls", cls ?: JSONObject.NULL).put("editable", editable ?: JSONObject.NULL).put("exact", exact)
    override fun toString() = listOfNotNull(
        text.takeIf { it.isNotEmpty() }?.let { "text=$it" }, desc.takeIf { it.isNotEmpty() }?.let { "desc=$it" },
        id.takeIf { it.isNotEmpty() }?.let { "id=$it" }, cls?.let { "cls=$it" }, editable?.let { "editable" },
    ).joinToString(" ")

    companion object {
        fun from(j: JSONObject?): Selector? = j?.let {
            Selector(
                it.optJSONArray("text").strings(), it.optJSONArray("desc").strings(), it.optJSONArray("id").strings(),
                it.optString("cls").takeIf { s -> s.isNotEmpty() && s != "null" },
                if (it.has("editable") && !it.isNull("editable")) it.optBoolean("editable") else null,
                it.optBoolean("exact", false),
            )
        }
    }
}

/** 画面の確認。どれも満たせば一致。timeoutMs まで待つ。 */
data class Expect(
    val pkg: String? = null,
    val activity: String? = null,
    val anyText: List<String> = emptyList(),
    val allText: List<String> = emptyList(),
    val noneText: List<String> = emptyList(),
    val selector: Selector? = null,
    val timeoutMs: Long = 4000,
) {
    fun toJson() = JSONObject().put("package", pkg ?: JSONObject.NULL).put("activity", activity ?: JSONObject.NULL)
        .put("any_text", JSONArray(anyText)).put("all_text", JSONArray(allText)).put("none_text", JSONArray(noneText))
        .put("selector", selector?.toJson() ?: JSONObject.NULL).put("timeout_ms", timeoutMs)
    companion object {
        fun from(j: JSONObject) = Expect(
            j.optString("package").takeIf { it.isNotEmpty() && it != "null" }, j.optString("activity").takeIf { it.isNotEmpty() && it != "null" },
            j.optJSONArray("any_text").strings(), j.optJSONArray("all_text").strings(), j.optJSONArray("none_text").strings(),
            Selector.from(j.optJSONObject("selector")), j.optLong("timeout_ms", 4000),
        )
    }
}

data class IntentSpec(
    val action: String? = null,
    val data: String? = null,
    val pkg: String? = null,
    val component: String? = null,
    val category: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    fun toJson() = JSONObject().put("action", action ?: JSONObject.NULL).put("data", data ?: JSONObject.NULL)
        .put("package", pkg ?: JSONObject.NULL).put("component", component ?: JSONObject.NULL)
        .put("category", category ?: JSONObject.NULL).put("extras", JSONObject(extras))
    companion object {
        fun from(j: JSONObject?) = j?.let {
            val ex = it.optJSONObject("extras")
            IntentSpec(
                it.optString("action").nn(), it.optString("data").nn(), it.optString("package").nn(),
                it.optString("component").nn(), it.optString("category").nn(),
                ex?.keys()?.asSequence()?.associateWith { k -> ex.getString(k) } ?: emptyMap(),
            )
        }
    }
}

/**
 * 手順の 1 段。op:
 * launch / shortcut / panel / intent / tap / type / swipe / scroll_to / back / home / wait / expect / branch / say / read
 * shortcut: アプリのショートカット (package と shortcut の ID) を開く。X の「ポスト」なら投稿の画面がすぐ開く
 * panel: 画面の上から引き出す画面を開く (text: quick_settings / notifications / close)
 * 操作のあとに expect があれば、その画面になるまで待ち、ならなければ止めて System 2 に渡す。
 */
data class Step(
    val id: String,
    val op: String,
    val selector: Selector? = null,
    val text: String? = null,
    val pkg: String? = null,
    val intent: IntentSpec? = null,
    val direction: String? = null,
    val ms: Long = 0,
    val expect: Expect? = null,
    /** 確定操作 (送信・投稿・購入・削除・発信)。承認なしには実行しない */
    val commit: Boolean = false,
    /** 見つからなければ飛ばしてよい (ダイアログを閉じる など) */
    val optional: Boolean = false,
    /** branch: expect が一致したら goto へ */
    val goto: String? = null,
    /** read: 読んだ文字を入れる変数名 */
    val into: String? = null,
    val note: String = "",
    /** shortcut: アプリのショートカットの ID */
    val shortcut: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("op", op).put("selector", selector?.toJson() ?: JSONObject.NULL)
        .put("text", text ?: JSONObject.NULL).put("package", pkg ?: JSONObject.NULL).put("intent", intent?.toJson() ?: JSONObject.NULL)
        .put("direction", direction ?: JSONObject.NULL).put("ms", ms).put("expect", expect?.toJson() ?: JSONObject.NULL)
        .put("commit", commit).put("optional", optional).put("goto", goto ?: JSONObject.NULL).put("into", into ?: JSONObject.NULL)
        .put("note", note).apply { if (shortcut != null) put("shortcut", shortcut) }

    companion object {
        fun from(j: JSONObject) = Step(
            j.optString("id").ifEmpty { j.getString("op") }, j.getString("op"), Selector.from(j.optJSONObject("selector")),
            j.optString("text").nn(), j.optString("package").nn(), IntentSpec.from(j.optJSONObject("intent")),
            j.optString("direction").nn(), j.optLong("ms", 0), j.optJSONObject("expect")?.let { Expect.from(it) },
            j.optBoolean("commit", false), j.optBoolean("optional", false), j.optString("goto").nn(), j.optString("into").nn(),
            j.optString("note"), j.optString("shortcut").nn(),
        )
    }
}

enum class CommitKind { SEND, POST, PURCHASE, DELETE, CALL, OTHER }

/** 確定の承認方法。固定ルールで決め、モデルの判断で省略しない。 */
enum class ApprovalLevel {
    /** 内容を大きく見せて BiometricPrompt (生体認証 or 端末の PIN 等) */
    BIOMETRIC,
    /** 内容を読み上げて「はい」で承認 (発信など、取り消しがきくもの。家族設定で BIOMETRIC に上げられる) */
    VOICE,
}

data class CommitSpec(
    val kind: CommitKind,
    val approval: ApprovalLevel,
    /** 承認画面と読み上げの文 */
    val summary: String,
    /** 承認画面に出す項目 (宛先・アカウント・公開範囲・内容・金額 など) */
    val fields: Map<String, String>,
    /** 確定の直前に画面に出ていなければならない文字 (宛先・本文 など)。承認後の内容変更の検出に使う */
    val screenChecks: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().put("kind", kind.name.lowercase()).put("approval", approval.name.lowercase())
        .put("summary", summary).put("fields", JSONObject(fields as Map<*, *>)).put("field_order", JSONArray(fields.keys.toList()))
        .put("screen_checks", JSONArray(screenChecks))
    companion object {
        fun from(j: JSONObject): CommitSpec {
            val f = j.optJSONObject("fields") ?: JSONObject()
            val fields = LinkedHashMap<String, String>()
            // org.json の keys() は順序を保たないことがあるので、"order" があればそれに従う
            val order = j.optJSONArray("field_order").strings().ifEmpty { f.keys().asSequence().toList() }
            for (k in order) if (f.has(k)) fields[k] = f.getString(k)
            return CommitSpec(
                CommitKind.valueOf(j.optString("kind", "other").uppercase()), ApprovalLevel.valueOf(j.optString("approval", "biometric").uppercase()),
                j.optString("summary"), fields, j.optJSONArray("screen_checks").strings(),
            )
        }
    }
}

data class Verification(
    val status: VerifyStatus = VerifyStatus.DRAFT,
    val successes: Int = 0,
    val failures: Int = 0,
    val escalations: Int = 0,
    val lastResult: String? = null,
    val updatedAt: Long = 0,
    val verifiedOn: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().put("status", status.name.lowercase()).put("successes", successes).put("failures", failures)
        .put("escalations", escalations).put("last_result", lastResult ?: JSONObject.NULL).put("updated_at", updatedAt)
        .put("verified_on", JSONArray(verifiedOn))
    companion object {
        fun from(j: JSONObject) = Verification(
            VerifyStatus.valueOf(j.optString("status", "draft").uppercase()), j.optInt("successes"), j.optInt("failures"),
            j.optInt("escalations"), j.optString("last_result").nn(), j.optLong("updated_at"), j.optJSONArray("verified_on").strings(),
        )
    }
}

internal fun String.nn(): String? = takeIf { it.isNotEmpty() && it != "null" }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
