package com.rabi.envoymcp.mcp

import android.content.Context
import androidx.core.content.edit
import com.rabi.envoymcp.McpSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * エージェント: ENVOY MCP を呼ぶ相手。合言葉 (token) で見分ける。
 * - 本体の ChatGPT (id = main): 合言葉は設定の合言葉 (McpSettings.token。トンネルが Authorization で渡す)。ここには持たない。消せない
 * - 足したエージェント: 自分の合言葉を持つ (同じスマホの中や、adb forward でつないだ PC のエージェント向け)
 */
data class Agent(
    val id: String,
    val name: String,
    /** 合言葉 (足したエージェントだけ。本体は空) */
    val token: String = "",
    /** オフなら、合言葉が合っていても断る */
    val enabled: Boolean = true,
    /** 読むだけの道具 (readOnly) だけ使える */
    val readOnly: Boolean = false,
    /** 使ってよい道具のまとまり (ToolGroups の名前)。null はすべて (どのまとまりにも入らない「その他」の道具も) */
    val groups: Set<String>? = null,
    /** 許したまとまりの中で、1 つずつ止めた道具の名前 */
    val denied: Set<String> = emptySet(),
    /** この時刻から使えない。0 は期限なし */
    val expiresAt: Long = 0L,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    val main get() = id == MAIN_ID

    /** 合言葉は出さない (記録に残さないように) */
    override fun toString() = "Agent($id, $name)"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("read_only", readOnly)
        .put("expires_at", expiresAt).put("created_at", createdAt).put("last_used_at", lastUsedAt)
        .apply {
            if (!main) put("token", token)
            if (groups != null) put("groups", JSONArray(groups.toList()))
            if (denied.isNotEmpty()) put("denied", JSONArray(denied.toList()))
        }

    companion object {
        const val MAIN_ID = "main"
        const val MAIN_NAME = "ChatGPT"
        /** 本体の ChatGPT (すべての道具・期限なし) */
        val MAIN = Agent(MAIN_ID, MAIN_NAME)

        fun fromJson(o: JSONObject): Agent? = runCatching {
            Agent(
                id = o.getString("id"), name = o.optString("name").ifBlank { "エージェント" }, token = o.optString("token"),
                enabled = o.optBoolean("enabled", true), readOnly = o.optBoolean("read_only", false),
                groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
                denied = o.optJSONArray("denied")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                expiresAt = o.optLong("expires_at"), createdAt = o.optLong("created_at"), lastUsedAt = o.optLong("last_used_at"),
            )
        }.getOrNull()
    }
}

/** エージェントが道具を使ってよいかの決まり (端末に触れない。試験する) */
object AgentPolicy {
    enum class Status { OK, OFF, EXPIRED }

    /** 今つなげるか。開発モード (unlimited) では期限を見ない (止めているものは断る) */
    fun status(a: Agent, now: Long, unlimited: Boolean = false): Status = when {
        !a.enabled -> Status.OFF
        !unlimited && a.expiresAt > 0 && now >= a.expiresAt -> Status.EXPIRED
        else -> Status.OK
    }

    /** すべてのまとまりを使える (どのまとまりにも入らない「その他」の道具も使える) */
    fun allGroups(a: Agent): Boolean = a.groups == null || ToolGroups.groups.all { it.first in a.groups }

    /**
     * この道具を使ってよいか (tool は道具の名前、readOnly はその道具が読むだけか)。
     * 止めている・期限切れ → だめ。子どもモード (kidMode) の間は、子どもモードで使える道具 (KIDS_TOOLS) だけ (開発モードでも)。
     * 開発モードなら、それ以外は全部よい。読むだけのエージェントは読むだけの道具だけ。
     * まとまりを絞っていれば、そのまとまりの道具だけ (「その他」の道具は、すべてのまとまりを許したときだけ)。1 つずつ止めた道具はだめ
     */
    fun allows(a: Agent, tool: String, readOnly: Boolean, now: Long, unlimited: Boolean = false, kidMode: Boolean = false): Boolean {
        if (status(a, now, unlimited) != Status.OK) return false
        if (kidMode && !kidAllows(tool)) return false
        if (unlimited) return true
        if (a.readOnly && !readOnly) return false
        if (tool in a.denied) return false
        if (allGroups(a)) return true
        val group = ToolGroups.of(tool)
        return group != ToolGroups.OTHER && a.groups?.contains(group) == true
    }

    /** 許されていない道具を頼まれたときの返事 (道具の失敗として返す。頼んだ AI が読んで、利用者に伝えられる) */
    fun refusal(a: Agent): ToolResult =
        failed("この道具は、スマホの ENVOY MCP の設定で、このエージェント (${a.name}) に許されていません。", JSONObject().put("permitted", false))

    /**
     * 子どもモードの間に使える道具: まなびのまとまりの道具と、表情・腕の動き・踊り・会話を終える道具。
     * 買う・電話する・共有する・ほかのアプリを開く・ほかのアプリを押す道具は入れない
     */
    val KIDS_TOOLS: Set<String> by lazy {
        (ToolGroups.groups.first { it.first == ToolGroups.KIDS }.second + listOf("rigel_expression", "arm_motion", "arm_dance", "end_voice_chat")).toSet()
    }

    /** 子どもモードの間に使える道具か */
    fun kidAllows(tool: String): Boolean = tool in KIDS_TOOLS

    /** 子どもモードの間に、ほかの道具を頼まれたときの返事 (道具の失敗として返す。AI が読んで、子どもとのお勉強を続けられる) */
    fun kidRefusal(): ToolResult =
        failed("今は子どもモードです。子どもモードの間は、まなびの道具 (kids_start・kids_question・kids_cheer・kids_end) と、" +
            "rigel_expression・arm_motion・arm_dance・end_voice_chat だけ使えます。この道具は使えません。子どもとのお勉強を続けてください。",
            JSONObject().put("permitted", false).put("kid_mode", true))

    /** 権限の一行のまとめ:「すべて」「読むだけ」「読むだけ・画面とアプリだけ」 */
    fun summary(a: Agent): String {
        val parts = ArrayList<String>()
        if (a.readOnly) parts += "読むだけ"
        if (!allGroups(a)) {
            val gs = ToolGroups.groups.map { it.first }.filter { a.groups?.contains(it) == true }
            parts += when {
                gs.isEmpty() -> "使える道具なし"
                gs.size <= 3 -> gs.joinToString("と") { short(it) } + "だけ"
                else -> "${gs.size} つのまとまりだけ"
            }
        }
        if (a.denied.isNotEmpty()) parts += "${a.denied.size} 個を止めている"
        return parts.joinToString("・").ifEmpty { "すべて" }
    }

    /** まとまりの様子: すべてオン・一部だけオン・オフ */
    enum class GroupState { ON, PART, OFF }

    fun groupState(a: Agent, group: String): GroupState {
        if (a.groups?.contains(group) == false) return GroupState.OFF
        val tools = ToolGroups.groups.firstOrNull { it.first == group }?.second.orEmpty()
        return if (tools.any { it in a.denied }) GroupState.PART else GroupState.ON
    }

    /** 道具 1 つが、まとまりと除外の上でオンか (読むだけかどうかは見ない) */
    fun toolOn(a: Agent, tool: String): Boolean {
        if (tool in a.denied) return false
        val group = ToolGroups.of(tool)
        return if (group == ToolGroups.OTHER) allGroups(a) else a.groups?.contains(group) ?: true
    }

    /** まとまりを足す・外す (中の道具の除外は消す。全部そろったら null = すべて。あとで増えたまとまりも使える) */
    fun withGroup(a: Agent, group: String, on: Boolean): Agent {
        val all = ToolGroups.groups.map { it.first }
        val cur = a.groups ?: all.toSet()
        val next = if (on) cur + group else cur - group
        val inGroup = ToolGroups.groups.firstOrNull { it.first == group }?.second.orEmpty().toSet()
        return a.copy(groups = if (all.all { it in next }) null else next, denied = a.denied - inGroup)
    }

    /**
     * 道具を 1 つずつオン・オフする。オフのまとまりの道具をオンにしたら、まとまりをオンにして、ほかの道具は止める。
     * まとまりの道具が全部オフになったら、まとまりごとオフにする
     */
    fun withTool(a: Agent, tool: String, on: Boolean): Agent {
        val group = ToolGroups.of(tool)
        if (group == ToolGroups.OTHER) return a.copy(denied = if (on) a.denied - tool else a.denied + tool)
        val inGroup = ToolGroups.groups.first { it.first == group }.second
        val groupOn = a.groups?.contains(group) ?: true
        return when {
            on && groupOn -> a.copy(denied = a.denied - tool)
            on -> withGroup(a, group, true).let { it.copy(denied = it.denied + (inGroup - tool)) }
            !groupOn -> a
            else -> (a.denied + tool).let { d -> if (inGroup.all { it in d }) withGroup(a, group, false) else a.copy(denied = d) }
        }
    }

    /** まとまりの短い呼び名 (一行のまとめに使う) */
    fun short(group: String): String = SHORT[group] ?: group.substringBefore(" (")

    private val SHORT = mapOf(
        "様子・画面" to "画面", "部品 (覚えた手順)" to "部品", "音声モード・表情・お知らせ" to "音声", "AgentARM (スマホを載せる腕)" to "腕",
    )
}

/**
 * エージェントの一覧 (この端末の中だけ。本体の ChatGPT が先頭、あとは足した順)。
 * 足したエージェントの合言葉はここに置く (本体の合言葉は McpSettings)
 */
class Agents(private val store: Store = Memory()) {
    /** 置き場所 (本物は SharedPreferences。試験ではメモリ) */
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    class Memory(private var text: String? = null) : Store {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    /** アプリ専用の SharedPreferences に JSON で置く */
    class Prefs(context: Context) : Store {
        private val p = context.getSharedPreferences("envoymcp_agents", Context.MODE_PRIVATE)
        override fun read(): String? = p.getString("agents", null)
        override fun write(text: String) = p.edit { putString("agents", text) }
    }

    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Agent>> = _list

    fun get(id: String): Agent? = _list.value.firstOrNull { it.id == id }

    /** 足す。最初は控えめにする (読むだけ・すべてのまとまり・1 週間) */
    @Synchronized fun add(now: Long = System.currentTimeMillis()): Agent {
        val a = Agent(id = "a-" + UUID.randomUUID().toString().take(8), name = NEW_NAME, token = McpSettings.randomToken(),
            readOnly = true, groups = null, expiresAt = now + WEEK_MS, createdAt = now)
        save(_list.value + a)
        return a
    }

    /** 名前・オンオフ・権限・期限を変える (合言葉・作った時刻・使った時刻は、ここでは変えない) */
    @Synchronized fun update(a: Agent) {
        save(_list.value.map { if (it.id == a.id) a.copy(token = it.token, createdAt = it.createdAt, lastUsedAt = it.lastUsedAt) else it })
    }

    /** 消す (本体の ChatGPT は消せない) */
    @Synchronized fun remove(id: String) {
        if (id == Agent.MAIN_ID) return
        save(_list.value.filterNot { it.id == id })
    }

    /** 使った時刻を残す (書き込みを減らすため、前に残してから TOUCH_MS たっていなければ残さない) */
    @Synchronized fun touch(id: String, now: Long) {
        val a = get(id) ?: return
        if (now - a.lastUsedAt < TOUCH_MS) return
        save(_list.value.map { if (it.id == id) it.copy(lastUsedAt = now) else it })
    }

    private fun load(): List<Agent> {
        val stored = runCatching {
            val arr = JSONArray(store.read() ?: "[]")
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Agent::fromJson) }
        }.getOrDefault(emptyList())
        val main = stored.firstOrNull { it.main }?.copy(token = "") ?: Agent.MAIN
        return listOf(main) + stored.filter { !it.main && it.token.isNotEmpty() }
    }

    private fun save(list: List<Agent>) {
        _list.value = list
        runCatching { store.write(JSONArray(list.map { it.toJson() }).toString()) }
    }

    companion object {
        const val NEW_NAME = "新しいエージェント"
        const val HOUR_MS = 60 * 60_000L
        const val WEEK_MS = 7 * 24 * HOUR_MS
        /** 使った時刻を残す間隔 */
        private const val TOUCH_MS = 60_000L
    }
}
