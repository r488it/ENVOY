package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.kids.KidsAnswer
import com.rabi.envoymcp.kids.KidsLesson
import com.rabi.envoymcp.kids.KidsTeacher
import com.rabi.envoymcp.kids.Question
import com.rabi.envoymcp.kids.Topic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * まなび (子どものお勉強) の道具。AI の先生 (ChatGPT の音声モード) が、子どもと会話しながら使う。
 * どれも子どもを待たずに、すぐ返る (待っている間は AI が話せないため)。画面の操作はしないので、端末の操作の接続も、止められるリゲルも要らない。
 * ENVOY は読み上げない (話すのは AI)。子どもの声の答えは比べるだけで、記録に残さない
 */
internal class KidsTools(st: ToolState) : ToolGroup(st) {
    suspend fun start(a: JSONObject): ToolResult {
        val raw = a.optString("topic").trim()
        val t = if (raw.isEmpty()) null else Topic.byId(raw) ?: KidsAnswer.topic(raw) ?: return failed(TOPIC_HINT)
        return result(main { g.kids.startAi(t, level(a)) })
    }

    suspend fun answer(a: JSONObject): ToolResult = result(main { g.kids.answerAi(a.optString("answer").takeIf { it.isNotBlank() }) })

    suspend fun cheer(): ToolResult {
        main { g.kids.cheerAi() }
        return ok("リゲルがうれしい顔をして、画面に星を出しました (腕につながっていて手が空いていれば、腕もうれしい動きをします)。")
    }

    /** おしまい: おわりの画面を出し、重ねる窓なら少し見せてから消す (ChatGPT の音声モードの画面に戻る) */
    suspend fun end(): ToolResult = result(main { g.kids.endAi().also { g.kidsOverlay.hideAfter() } })

    /** お勉強の中の状態は、メインスレッドだけで触る */
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /** 難しさ (無い・1 より小さければ null: 前の続きの難しさ) */
    private fun level(a: JSONObject): Int? = if (a.has("level") && !a.isNull("level")) a.optInt("level", 0).takeIf { it > 0 } else null

    private fun result(r: KidsLesson.AiResult): ToolResult = when (r) {
        KidsLesson.AiResult.TimeUp -> ok("お勉強の時間になりました (おうちの人が決めた、きょうの時間)。画面に「きょうは ここまで」を出しました。" +
            "「きょうは ここまで。またね」とやさしく言って、end_voice_chat で会話を終えてください。", JSONObject().put("status", "time_up").put("time_up", true))
        is KidsLesson.AiResult.NotEnabled -> failed("その題は、おうちの人が選んでいません。選べるのは ${r.enabled.joinToString("・") { "${it.label} (${it.id})" }} です。",
            JSONObject().put("status", "not_enabled").put("topics", JSONArray(r.enabled.map { it.id })))
        KidsLesson.AiResult.NotStarted -> failed("まだお勉強を始めていません。kids_start で始めてください (topic なしなら、子どもが題を選びます)。",
            JSONObject().put("status", "not_started"))
        is KidsLesson.AiResult.Picking -> ok("画面に題のボタン (${KidsTeacher.topicsText(r.topics)}) を出しました。「どれにする？」と聞いてね。" +
            "子どもが声で言ったら kids_start(topic) を、画面で押したら kids_answer() で分かります。",
            JSONObject().put("status", "picking").put("topics", JSONArray(r.topics.map { it.id })))
        is KidsLesson.AiResult.Started -> ok("「${r.topic.label}」を始めて、最初の問題を画面に出しました: 「${r.question.speech}」${shown(r.question)}。" +
            "会話しながら聞いてね。声で答えたら kids_answer(answer=…)、画面で押したら kids_answer() で分かります。",
            progress(JSONObject().put("status", "started").put("topic", r.topic.id).put("question", r.question.toJson()), r.progress))
        is KidsLesson.AiResult.Checked -> checked(r)
        is KidsLesson.AiResult.Ended -> ok("おしまいにして、画面に星と「きょうも がんばったね」を出しました " +
            "(問題 ${r.summary.questions} もん・1 回でせいかい ${r.summary.correct} もん・星 ${r.summary.stars} こ)。ひとことでほめてね。",
            r.summary.toJson().put("status", "ended"))
    }

    /** 答えを確かめた結果の文 (短く。次に話すことと、次に呼ぶ道具) */
    private fun checked(r: KidsLesson.AiResult.Checked): ToolResult {
        val parts = ArrayList<String>()
        val last = r.answers.lastOrNull()
        val q = r.question
        r.picked?.let { parts += "子どもが「${it.label}」を選んだので、始めました。" }
        if (r.answers.size > 1) parts += "子どもの答え (古い順): " + r.answers.joinToString("・") { "${how(it)}「${it.chosen}」${if (it.correct) "せいかい" else "ちがう"}" } + "。"
        r.skipped?.let { parts += "声の答え「$it」は、画面で先に答えていたので、数えていません。" }
        val status: String
        when {
            r.summary != null -> {
                status = "done"
                parts += "せいかい！ ${r.summary.questions} もん おわりました (星 ${r.summary.stars} こ)。画面に星と「きょうも がんばったね」を出しました。" +
                    "短くほめてね。続けるなら topic なしの kids_start、やめるなら end_voice_chat。"
            }
            q == null -> {
                status = "not_started"
                parts += "今は問題がありません。kids_start で始めてください。"
            }
            last != null && last.correct -> {
                status = "correct"
                parts += "せいかい！ (${how(last)}「${last.chosen}」)。次の問題を画面に出しました: 「${q.speech}」${shown(q)}。ひとことほめて、会話しながら次の問題を聞いてね。"
            }
            last != null -> {
                status = "wrong"
                parts += "ざんねん、ちがいました (${how(last)}「${last.chosen}」。正しい答えは「${q.answerLabel}」ですが、言わないでね)。" +
                    "ヒント: ${r.hint}。「おしい！ もういちど」と言って、ヒントをひとこと伝えてね。問題はそのまま: 「${q.speech}」"
            }
            r.unclear != null -> {
                status = "unclear"
                parts += "「${r.unclear}」は、答えとして分かりませんでした。問題はそのまま: 「${q.speech}」。もういちど聞いてね (画面を押してもよい)。"
            }
            r.picked != null -> {
                status = "started"
                parts += "最初の問題: 「${q.speech}」${shown(q)}。会話しながら聞いてね。"
            }
            else -> {
                status = "waiting"
                parts += "まだ答えていません。問題はそのまま: 「${q.speech}」${shown(q)}。会話を続けて、答えを待ってね。"
            }
        }
        val o = JSONObject().put("status", status)
        r.picked?.let { o.put("picked", it.id) }
        o.put("answers", JSONArray(r.answers.map {
            JSONObject().put("chosen", it.chosen).put("correct", it.correct).put("first_try", it.firstTry).put("by", if (it.byVoice) "voice" else "screen")
        }))
        if (last != null) o.put("correct", last.correct).put("chosen", last.chosen)
        r.hint?.let { o.put("hint", it) }
        if (r.summary != null) o.put("done", true).put("summary", r.summary.toJson()) else q?.let { o.put("question", it.toJson()) }
        return ok(parts.joinToString(""), progress(o, r.progress))
    }

    private fun how(a: KidsLesson.Answer) = if (a.byVoice) "声で" else "画面で"

    /** 画面の絵の言い方 (会話に使える。例: (画面: りんごが 3 こ)) */
    private fun shown(q: Question) = if (q.shown.isEmpty()) "" else " (画面: ${q.shown})"

    /** 進み (星・出した問題の数・ひと区切りの数・難しさ・のこりの分) を足す */
    private fun progress(o: JSONObject, p: KidsLesson.Progress): JSONObject =
        o.put("stars", p.stars).put("asked", p.asked).put("round_size", p.size).put("level", p.level).put("minutes_left", p.minutesLeft)

    companion object {
        private const val TOPIC_HINT = "topic は kazu (かず)・hiragana (ひらがな)・tokei (とけい) のどれかです (省略すると、子どもが画面で選びます)。"
    }
}
