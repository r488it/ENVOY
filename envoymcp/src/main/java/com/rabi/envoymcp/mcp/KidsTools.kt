package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.kids.KidsLesson
import com.rabi.envoymcp.kids.Topic
import org.json.JSONArray
import org.json.JSONObject

/**
 * まなび (子どものお勉強) の道具。AI の先生が、お勉強の画面に問題を出し、子どもが押すのを待つ。
 * 画面の操作はしないので、端末の操作の接続も、止められるリゲルも要らない。ENVOY は読み上げない (話すのは AI)
 */
internal class KidsTools(st: ToolState) : ToolGroup(st) {
    suspend fun start(a: JSONObject): ToolResult {
        val t = Topic.byId(a.optString("topic")) ?: return failed(TOPIC_HINT)
        return result(g.kids.startAi(t, level(a)))
    }

    suspend fun question(a: JSONObject): ToolResult {
        val raw = a.optString("topic").trim()
        val t = if (raw.isEmpty()) null else Topic.byId(raw) ?: return failed(TOPIC_HINT)
        return result(g.kids.askAi(t, level(a), a.optBoolean("same", false), a.optBoolean("wait", true)))
    }

    suspend fun cheer(): ToolResult {
        g.kids.cheerAi()
        return ok("リゲルがうれしい顔をして、画面に星を出しました (腕につながっていて手が空いていれば、腕もうれしい動きをします)。")
    }

    suspend fun end(): ToolResult = result(g.kids.endAi())

    /** 難しさ (無い・1 より小さければ null: 今の難しさのまま) */
    private fun level(a: JSONObject): Int? = if (a.has("level") && !a.isNull("level")) a.optInt("level", 0).takeIf { it > 0 } else null

    private fun result(r: KidsLesson.AiResult): ToolResult = when (r) {
        KidsLesson.AiResult.TimeUp -> ok("お勉強の時間になりました (おうちの人が決めた、きょうの時間)。画面に「きょうは ここまで」を出しました。" +
            "子どもに「きょうは ここまで。また あそぼうね」とやさしく伝えてから、end_voice_chat で会話を終えてください。", JSONObject().put("time_up", true))
        is KidsLesson.AiResult.NotEnabled -> failed("その題は、おうちの人が選んでいません。選べるのは ${r.enabled.joinToString("・") { "${it.id} (${it.label})" }} です。",
            JSONObject().put("topics", JSONArray(r.enabled.map { it.id })))
        is KidsLesson.AiResult.Started -> ok("お勉強の画面を開きました (題: ${r.topic.label}、難しさ ${r.level})。" +
            "子どもに短くやさしく声をかけてから、kids_question で問題を出してください。",
            progress(JSONObject().put("topic", r.topic.id), r.progress))
        is KidsLesson.AiResult.Shown -> ok("問題を画面に出しました。問題をやさしく読んでから、kids_question を same=true で呼び、子どもが選ぶのを待ってください。",
            progress(r.question.toJson(), r.progress))
        is KidsLesson.AiResult.Picked -> {
            val chosen = r.question.choices[r.index].label
            val o = progress(r.question.toJson().put("chosen", chosen).put("correct", r.correct).put("first_try", r.firstTry), r.progress)
            if (r.correct) ok("子どもが「$chosen」を選びました。せいかいです${if (r.firstTry) "" else " (もういちどで、できました)"}。画面に星を出しました。たくさんほめてください。", o)
            else ok("子どもが「$chosen」を選びました。ちがいます (正しい答えは「${r.question.answerLabel}」)。" +
                "しからずに「もういちど やってみよう」とやさしく言い、kids_question を same=true で呼んで、同じ問題をもう一度待ってください。", o)
        }
        is KidsLesson.AiResult.NoAnswer -> ok("${KidsLesson.WAIT_MS / 1000} 秒待ちましたが、子どもはまだ選んでいません。" +
            "やさしく声をかけて (問題を読み直すなど)、kids_question を same=true で呼んで、もう一度待ってください。",
            progress(r.question.toJson().put("chosen", JSONObject.NULL).put("correct", JSONObject.NULL), r.progress))
        is KidsLesson.AiResult.Ended -> ok("お勉強をおしまいにして、画面に星の数と「きょうも がんばったね」を出しました " +
            "(問題 ${r.summary.questions} もん・1 回でせいかい ${r.summary.correct} もん・星 ${r.summary.stars} こ)。星の数を伝えて、がんばったことをほめてください。",
            r.summary.toJson())
    }

    /** 進み (星・出した問題の数・ひと区切りの数・難しさ・のこりの分) を足す */
    private fun progress(o: JSONObject, p: KidsLesson.Progress): JSONObject =
        o.put("stars", p.stars).put("asked", p.asked).put("round_size", p.size).put("level", p.level).put("minutes_left", p.minutesLeft)

    companion object {
        private const val TOPIC_HINT = "topic は kazu (かず)・hiragana (ひらがな)・tokei (とけい) のどれかです。"
    }
}
