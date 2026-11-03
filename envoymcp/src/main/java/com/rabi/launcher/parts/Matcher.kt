package com.rabi.launcher.parts

import com.rabi.launcher.core.Text
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode

/** 要素の指定 (Selector) と画面の確認 (Expect) を、いまの画面に当てはめる。 */
object Matcher {

    sealed class Found {
        data class One(val node: UiNode) : Found()
        /** 同じくらい当てはまる要素が複数 (確定操作では止める) */
        data class Many(val nodes: List<UiNode>) : Found()
        data object None : Found()
        /** テンプレートの値が足りない */
        data class BadTemplate(val what: String) : Found()
    }

    fun find(obs: Observation, sel: Selector, vars: Map<String, String>): Found {
        // 手がかりの無い指定 (名前の無いアイコンを押した記録など) は、どれにも当てはめない (画面の最初の部品を押してしまうため)
        if (sel.isEmpty) return Found.None
        val texts = sel.text.map { Text.fill(it, vars) ?: return Found.BadTemplate(it) }
        val descs = sel.desc.map { Text.fill(it, vars) ?: return Found.BadTemplate(it) }
        val ids = sel.id.map { Text.fill(it, vars) ?: return Found.BadTemplate(it) }
        fun eq(a: String, b: String) = if (sel.exact) Text.norm(a) == Text.norm(b) else Text.norm(a).let { n -> val m = Text.norm(b); m.isNotEmpty() && n.contains(m) }
        val scored = obs.nodes.asSequence().filter { it.bounds.area > 0 && it.enabled }.mapNotNull { n ->
            if (sel.editable != null && n.editable != sel.editable) return@mapNotNull null
            if (sel.cls != null && !n.cls.endsWith(sel.cls)) return@mapNotNull null
            var s = 0
            var identity = false
            if (ids.isNotEmpty() && ids.any { n.resId == it || n.resId.endsWith(":id/$it") || n.shortId == it }) { s += 3; identity = true }
            if (texts.isNotEmpty() && texts.any { eq(n.text, it) }) { s += 2; identity = true }
            if (descs.isNotEmpty() && descs.any { eq(n.desc, it) }) { s += 2; identity = true }
            // 文字の指定だけで、要素側は content-desc に同じ文言がある場合も拾う
            if (!identity && texts.isNotEmpty() && descs.isEmpty() && texts.any { eq(n.desc, it) }) { s += 1; identity = true }
            if (!identity && ids.isEmpty() && texts.isEmpty() && descs.isEmpty()) identity = true   // cls / editable だけの指定
            if (!identity) return@mapNotNull null
            if (n.clickable) s += 1
            n to s
        }.toList()
        if (scored.isEmpty()) return Found.None
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        // 文字の指定があるのに、文字は合わず ID だけが合う要素が複数 → 同じ ID の別の行の可能性が高い
        if ((texts.isNotEmpty() || descs.isNotEmpty()) && top.size > 1 &&
            top.none { n -> texts.any { eq(n.text, it) || eq(n.desc, it) } || descs.any { eq(n.desc, it) } }) return Found.None
        // 入れ子 (親子) で同じものを指している場合は、内側を代表にする
        val distinct = top.filter { a -> top.none { b -> b !== a && b.depth > a.depth && a.bounds.contains(b.bounds.cx, b.bounds.cy) } }
        return if (distinct.size == 1) Found.One(distinct[0]) else Found.Many(distinct)
    }

    data class ExpectResult(val ok: Boolean, val why: String)

    fun check(obs: Observation, e: Expect, vars: Map<String, String>): ExpectResult {
        if (!obs.ok) return ExpectResult(false, "画面を取れない: ${obs.error}")
        e.pkg?.let { p ->
            val want = Text.fill(p, vars) ?: return ExpectResult(false, "package テンプレート不足")
            if (obs.pkg != want) return ExpectResult(false, "前面アプリが ${obs.pkg} (期待 $want)")
        }
        e.activity?.let { a -> if (obs.activity?.contains(a) != true) return ExpectResult(false, "画面 ${obs.activity} (期待 $a)") }
        val any = e.anyText.map { Text.fill(it, vars) ?: return ExpectResult(false, "文字テンプレート不足: $it") }
        if (any.isNotEmpty() && any.none { obs.hasText(it) }) return ExpectResult(false, "どれも見つからない: ${any.joinToString("/")}")
        for (t in e.allText) {
            val s = Text.fill(t, vars) ?: return ExpectResult(false, "文字テンプレート不足: $t")
            if (!obs.hasText(s)) return ExpectResult(false, "見つからない: $s")
        }
        for (t in e.noneText) {
            val s = Text.fill(t, vars) ?: continue
            if (obs.hasText(s)) return ExpectResult(false, "出てはいけない文字: $s")
        }
        e.selector?.let { sel ->
            when (val f = find(obs, sel, vars)) {
                is Found.None -> return ExpectResult(false, "要素が無い: $sel")
                is Found.BadTemplate -> return ExpectResult(false, "テンプレート不足: ${f.what}")
                else -> {}
            }
        }
        return ExpectResult(true, "ok")
    }
}
