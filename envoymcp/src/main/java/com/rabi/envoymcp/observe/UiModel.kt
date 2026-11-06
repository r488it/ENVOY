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

    /**
     * (x, y) を押したときに押される部品 (targets) と、その中身 (inner)。確定かどうかを、選んだ部品ではなく実際に押される部品で見るのに使う。
     * - 押される部品: その場所にある押せる部品のうち、いちばん内側のもの (重なっていれば、それぞれ)
     * - 中身: 押される部品の子孫。中の別の押せる部品 (その場所に無いので押されない) とその子孫・入力欄・画面に出ていないものは除く。
     *   押される部品が画面の半分より大きければ (画面全体の入れ物など)、その場所に重なる子孫だけ
     * - 押せる部品が無ければ、その場所に重なる部品 (画面の半分より大きいものは除く) を targets にする
     * 親子は、木を上から順にたどった並び (nodes) と深さ (depth) で見る
     */
    fun pressAt(x: Int, y: Int): Press {
        val at = nodes.indices.filter { nodes[it].bounds.area > 0 && nodes[it].bounds.contains(x, y) }
        val half = screenArea() / 2
        val clickable = at.filter { nodes[it].clickable }
        val receivers = clickable.filter { p -> val end = subtreeEnd(p); clickable.none { q -> q in (p + 1) until end } }
        if (receivers.isEmpty()) return Press(at.map { nodes[it] }.filter { it.bounds.area <= half }, emptyList())
        val inner = ArrayList<UiNode>()
        for (p in receivers) {
            val end = subtreeEnd(p)
            val big = nodes[p].bounds.area > half
            var q = p + 1
            while (q < end) {
                val n = nodes[q]
                if (n.clickable) { q = subtreeEnd(q); continue }
                if (n.bounds.area > 0 && !n.editable && (!big || n.bounds.contains(x, y))) inner += n
                q++
            }
        }
        return Press(receivers.map { nodes[it] }, inner)
    }

    /** nodes の p 番目の部品の子孫が並ぶ範囲の終わり (含まない) */
    private fun subtreeEnd(p: Int): Int {
        val d = nodes[p].depth
        var q = p + 1
        while (q < nodes.size && nodes[q].depth > d) q++
        return q
    }

    /** 画面の面積 (大きさが分からなければ、いちばん大きい部品の面積) */
    private fun screenArea(): Long =
        if (screenW > 0 && screenH > 0) screenW.toLong() * screenH else nodes.maxOfOrNull { it.bounds.area.toLong() } ?: 0L
}

/** ある位置を押したときに押される部品 (targets) と、その中にある部品 (inner。名前の手がかり) */
class Press(val targets: List<UiNode>, val inner: List<UiNode>)

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
