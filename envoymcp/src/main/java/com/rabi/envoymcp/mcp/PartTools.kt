package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.parts.ArgSpec
import com.rabi.envoymcp.parts.ArgType
import com.rabi.envoymcp.parts.ArgValue
import com.rabi.envoymcp.parts.Part
import com.rabi.envoymcp.parts.PartMeta
import com.rabi.envoymcp.parts.RunListener
import com.rabi.envoymcp.parts.RunOutcome
import com.rabi.envoymcp.parts.Step
import com.rabi.envoymcp.parts.TraceCompiler
import org.json.JSONArray
import org.json.JSONObject

/** 部品の道具: 一覧・動かす・直前の操作を部品として登録・覚えた部品を消す */
internal class PartTools(st: ToolState) : ToolGroup(st) {
    fun listParts(): ToolResult {
        val arr = JSONArray(g.parts.usable().map { p ->
            JSONObject().put("part_id", p.id).put("name", p.name).put("description", p.description)
                .put("args", JSONArray(p.args.map { JSONObject().put("name", it.name).put("type", it.type.name.lowercase()).put("required", it.required).put("description", it.description) }))
                .put("needs_approval", p.hasCommit).put("learned", p.source == "learned")
        })
        val o = JSONObject().put("parts", arr)
        return ToolResult(o.toString(), o)
    }

    suspend fun runPart(a: JSONObject): ToolResult {
        val id = a.optString("part_id")
        val part = g.parts.usable().firstOrNull { it.id == id } ?: return failed("その部品はありません (list_parts で確かめてください)。")
        val given = a.optJSONObject("args") ?: JSONObject()
        val args = HashMap<String, ArgValue>()
        for (spec in part.args) {
            val raw = given.optString(spec.name).trim()
            if (raw.isEmpty()) { if (spec.required) return failed("引数「${spec.name}」がありません。") else continue }
            args[spec.name] = when (spec.type) {
                ArgType.APP -> {
                    val (pkg, why) = resolveApp(raw)
                    pkg ?: return failed(why)
                    val label = g.appLabel(pkg) ?: pkg
                    ArgValue(ArgType.APP, label, mapOf("package" to pkg, "label" to label))
                }
                ArgType.CONTACT, ArgType.PLACE -> return failed("連絡先・場所の引数は、ENVOY MCP ではまだ使えません。")
                ArgType.TEXT -> ArgValue.text(raw)
                else -> ArgValue(spec.type, raw, mapOf("value" to raw, "label" to raw))
            }
        }
        val listener = object : RunListener {
            override fun onStep(index: Int, step: Step) { g.beginWork("${part.name} (${index + 1}/${part.steps.size})", true) }
        }
        val out = g.runner.run(part, args, listener)
        val after = runCatching { g.device.observe() }.getOrNull()
        val s = after?.let { summary(it) } ?: JSONObject()
        return when (out) {
            is RunOutcome.Success -> { g.parts.recordSuccess(part); ok("「${part.name}」が終わり、画面で確かめました。", s) }
            is RunOutcome.Escalate -> { g.parts.recordEscalation(part, out.reason); failed("「${part.name}」の ${out.stepIndex + 1} 番目の手順で止めました: ${out.reason}。ここからは read_screen と個別の道具で続けられます。", s) }
            is RunOutcome.Rejected -> failed("利用者が承認しなかったので、確定はしていません (${out.reason})。", s)
            is RunOutcome.ContentChanged -> failed("確定はしていません: ${out.reason}。", s)
            is RunOutcome.Unknown -> unknown("${out.reason}。二重にならないよう、同じ部品を繰り返さないでください。", s)
            is RunOutcome.Failed -> { g.parts.recordFailure(part, out.reason); failed("「${part.name}」はできませんでした: ${out.reason}。", s) }
        }
    }

    fun savePart(a: JSONObject): ToolResult {
        val steps = synchronized(trace) { trace.toList() }
        if (steps.none { it.ok && it.op != "wait" }) return failed("部品にできる操作の記録がありません (前回の登録より後に、操作をしていません)。")
        val argMap = a.optJSONObject("args") ?: JSONObject()
        val args = argMap.keys().asSequence().map { lit -> lit to ArgSpec(argMap.getString(lit), ArgType.TEXT) }.toList()
        val name = a.optString("name").trim().take(40)
        val examples = a.optJSONArray("examples")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        val meta = PartMeta(null, name, a.optString("description").trim(), examples, args)
        val draft = TraceCompiler.compile(name, steps, meta) ?: return failed("この操作の記録は部品にできません (名前も ID も無い部品を押した手が含まれている など)。")
        val p: Part = g.parts.saveLearned(draft)
        synchronized(trace) { trace.clear() }
        val o = JSONObject().put("part_id", p.id).put("name", p.name).put("steps", p.steps.size).put("needs_approval", p.hasCommit)
        return ok("部品「${p.name}」を登録しました (手順 ${p.steps.size} 個)。run_part で使えます。", o)
    }

    fun deletePart(a: JSONObject): ToolResult {
        val id = a.optString("part_id")
        val p = g.parts.all().firstOrNull { it.id == id } ?: return failed("その部品はありません。")
        if (p.source != "learned") return failed("最初から入っている部品は消せません。")
        g.parts.deleteLearned(id)
        return ok("部品「${p.name}」を消しました。", JSONObject().put("part_id", id))
    }
}
