package com.rabi.envoymcp.observe

import com.rabi.envoymcp.core.Text
import org.json.JSONArray
import org.json.JSONObject

data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val w get() = r - l
    val h get() = b - t
    val area get() = maxOf(0, w) * maxOf(0, h)
    fun contains(x: Int, y: Int) = x in l until r && y in t until b
    override fun toString() = "[$l,$t][$r,$b]"
}

/** 画面上の 1 要素 (uiautomator dump の node)。 */
data class UiNode(
    val index: Int,
    val text: String,
    val desc: String,
    val resId: String,
    val cls: String,
    val pkg: String,
    val bounds: Bounds,
    val clickable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val password: Boolean,
    val hint: String,
    val depth: Int,
) {
    val editable get() = cls.endsWith("EditText") || cls.endsWith("AutoCompleteTextView")
    /** 人が読めるラベル (text → content-desc → hint の順) */
    val label get() = text.ifBlank { desc.ifBlank { hint } }
    val shortId get() = resId.substringAfter(":id/", resId)
}

/** ある時点の画面と端末の状態。 */
data class Observation(
    val pkg: String?,
    val activity: String?,
    val nodes: List<UiNode>,
    val screenW: Int,
    val screenH: Int,
    /** 画面の画像 (縮小済み。形式は screenshotMime) */
    val screenshotPng: ByteArray? = null,
    val at: Long = System.currentTimeMillis(),
    val error: String? = null,
    val screenshotMime: String = "image/png",
) {
    val ok get() = error == null
    val texts: List<String> get() = nodes.flatMap { listOf(it.text, it.desc) }.filter { it.isNotBlank() }

    fun hasText(t: String): Boolean {
        val n = Text.norm(t)
        if (n.isEmpty()) return false
        return texts.any { Text.norm(it).contains(n) }
    }

    /** 画面の同一性を比べるための要約 (前面アプリ + 主な文字)。 */
    fun signature(): String = (pkg ?: "?") + "|" + (activity ?: "") + "|" + texts.take(40).joinToString("·") { it.take(24) }

    /** モデルに渡す短い画面説明 (要素に番号を振る)。 */
    fun toModelJson(maxNodes: Int = 80): JSONObject {
        val arr = JSONArray()
        interesting().take(maxNodes).forEach { n ->
            val o = JSONObject().put("id", n.index)
            if (n.text.isNotBlank()) o.put("text", n.text.take(80))
            if (n.desc.isNotBlank()) o.put("desc", n.desc.take(80))
            if (n.hint.isNotBlank() && n.text.isBlank()) o.put("hint", n.hint.take(40))
            if (n.shortId.isNotBlank()) o.put("rid", n.shortId)
            o.put("cls", n.cls.substringAfterLast('.'))
            if (n.clickable) o.put("click", true)
            if (n.editable) o.put("edit", true)
            if (n.scrollable) o.put("scroll", true)
            if (n.checked) o.put("checked", true)
            if (n.password) o.put("password", true)
            o.put("b", n.bounds.toString())
            arr.put(o)
        }
        return JSONObject().put("app", pkg ?: JSONObject.NULL).put("activity", activity ?: JSONObject.NULL)
            .put("screen", "${screenW}x$screenH").put("elements", arr)
    }

    /** 文字・説明・操作できるものだけ (飾りのレイアウトは省く)。 */
    fun interesting(): List<UiNode> = nodes.filter {
        it.bounds.area > 0 && (it.text.isNotBlank() || it.desc.isNotBlank() || it.clickable || it.editable || it.scrollable)
    }

    fun nodeById(index: Int) = nodes.firstOrNull { it.index == index }

    /** 座標にある、いちばん小さい要素。 */
    fun nodeAt(x: Int, y: Int): UiNode? = nodes.filter { it.bounds.contains(x, y) && it.bounds.area > 0 }
        .minByOrNull { it.bounds.area }
}

/** uiautomator dump の XML を読む (属性はエスケープ済みの 1 行 XML)。 */
object UiDumpParser {
    private val nodeRe = Regex("<node\\s([^>]*?)(/?)>|</node>")
    private val attrRe = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")
    private val boundsRe = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun parse(xml: String): List<UiNode> {
        val out = ArrayList<UiNode>()
        var depth = 0
        var idx = 0
        for (m in nodeRe.findAll(xml)) {
            if (m.value == "</node>") { depth = maxOf(0, depth - 1); continue }
            val attrs = HashMap<String, String>()
            for (a in attrRe.findAll(m.groupValues[1])) attrs[a.groupValues[1]] = unescape(a.groupValues[2])
            val b = boundsRe.find(attrs["bounds"] ?: "")?.groupValues?.drop(1)?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
            out += UiNode(
                index = idx++,
                text = attrs["text"] ?: "",
                desc = attrs["content-desc"] ?: "",
                resId = attrs["resource-id"] ?: "",
                cls = attrs["class"] ?: "",
                pkg = attrs["package"] ?: "",
                bounds = Bounds(b[0], b[1], b[2], b[3]),
                clickable = attrs["clickable"] == "true",
                enabled = attrs["enabled"] != "false",
                focused = attrs["focused"] == "true",
                scrollable = attrs["scrollable"] == "true",
                checked = attrs["checked"] == "true",
                password = attrs["password"] == "true",
                hint = attrs["hint"] ?: "",
                depth = depth,
            )
            if (m.groupValues[2] != "/") depth++
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "\r").replace("&amp;", "&")
}
