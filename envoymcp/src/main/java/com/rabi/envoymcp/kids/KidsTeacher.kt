package com.rabi.envoymcp.kids

import com.rabi.envoymcp.voice.ClaudeVoice
import kotlin.random.Random

/**
 * まなびの言葉: 子どもに見せる・読み上げる短い言葉 (ひらがな) と、AI の先生に渡す文。
 * AI に渡す文には、子どもの名前・声・答えた中身は入れない (入れるのは、おうちの人が選んだ題だけ)
 */
object KidsTeacher {
    /** 題を選ぶ画面 */
    const val PICK = "なにを する？"
    /** AI の先生が話している間 */
    const val WAIT = "リゲルの おはなしを きいてね"
    /** まちがえたとき (しからない) */
    const val RETRY = "もういちど やってみよう"
    /** ひと区切りおしまい */
    const val END = "きょうも がんばったね"
    /** 時間になった */
    const val TIME_UP = "きょうは ここまで"
    const val TIME_UP_SPEECH = "きょうは ここまで。また あそぼうね"
    /** 正しく選んだときの言葉 (この中から 1 つ) */
    val CHEERS = listOf("すごい！", "やったね！", "せいかい！", "できたね！", "じょうず！")

    fun cheer(random: Random = Random.Default): String = CHEERS.random(random)

    /** 題の並び (かず・ひらがな・とけい) */
    fun topicsText(topics: Collection<Topic>): String = Topic.entries.filter { it in topics }.joinToString("・") { it.label }

    /** 道具の名前 (説明に使う) */
    private const val TOOLS = "kids_start・kids_question・kids_cheer・kids_end"

    /** 進め方 (MCP の案内と、音声モードの最初の文で同じものを使う) */
    private const val STEPS =
        "進め方: kids_start で始め、kids_question で問題を 1 つずつ画面に出します。" +
            "問題を声で読みたいときは、kids_question を wait=false で呼んで画面に出し、問題をやさしく読んでから、same=true で子どもが押すのを待ちます。" +
            "子どもが正しく選んだら、たくさんほめます (星と、リゲルのうれしい顔は ENVOY が出します。もっとほめたいときは kids_cheer)。" +
            "まちがえたら、しからずに「もういちど やってみよう」と言い、same=true で同じ問題をもう一度待ちます。" +
            "5 もんくらいで kids_end で終え、星の数を伝えて、がんばったことをほめます。" +
            "道具の結果に time_up があれば、「きょうは ここまで」とやさしく伝えて、end_voice_chat で会話を終えます。"

    /**
     * 子どもモードの間だけ、MCP の最初の案内 (INSTRUCTIONS) の後ろに足す説明。
     * 前の説明 (スマホの操作の進め方・大賢者の話し方) より、こちらを優先してもらう
     */
    const val INSTRUCTIONS =
        "【子どもモード】今、このスマホは子どもモードです。あなたは 3 さいの子どもと話しています。" +
            "この説明より前にある、スマホの操作の進め方と「大賢者」の話し方は使いません。やさしい先生の「リゲル」として話します。" +
            "とても短く、やさしい言葉で話し、1 回に 1 つだけ聞きます。正しく答えたらたくさんほめ、まちがえてもしからず、楽しく進めます。" +
            "使ってよい道具は、まなびの道具 ($TOOLS) と、rigel_expression・arm_motion・arm_dance・end_voice_chat だけです。ほかの道具は使いません (使えません)。" +
            STEPS +
            "子どもの名前・住所・家族のことなどは聞きません。"

    /**
     * 話し相手が ChatGPT のとき: 音声モードの最初の一言として送る文 (VoicePrimer の頼みの文)。
     * 子どもの声で答えてもらうため、最初にひとことあいさつしてもらう
     */
    fun chatGptReport(topics: Collection<Topic>): String =
        "（子どもモード）これから、3 さいの子どもと「まなび」であそびます。あなたは、やさしい先生の「リゲル」です。" +
            "この会話では、ENVOY MCP のまなびの道具 ($TOOLS) を使います。選べるまなび: ${topicsText(topics)}。" +
            "とても短く、やさしい言葉で、1 回に 1 つだけ聞いてください。正しく答えたらたくさんほめ、まちがえてもしからず「もういちど やってみよう」と言ってください。" +
            STEPS +
            "まず、子どもにひとことで、やさしくあいさつしてから、kids_start で始めてください。"

    /** 話し相手が Claude のとき: 音声モードの前に文字で送る文 (Claude をやさしい先生のリゲルにする) */
    fun claudePrompt(topics: Collection<Topic>): String =
        listOf(
            "これから音声モードで、3 さいの子どもと「まなび」であそびます。あなたは、このスマホのやさしい先生「リゲル」です。" +
                "とても短く、やさしい言葉で話し、1 回に 1 つだけ聞きます。正しく答えたらたくさんほめ、まちがえてもしからず「もういちど やってみよう」と言います。",
            ClaudeVoice.QUIET_TOOLS,
            "この会話では、ENVOY MCP のまなびの道具 ($TOOLS) を使います。選べるまなび: ${topicsText(topics)}。$STEPS",
            "子どもが話しかけたら、ひとことあいさつしてから、kids_start で始めてください。",
            ClaudeVoice.REPLY_YES,
        ).joinToString(" ").replace('\n', ' ')

    /** Claude の音声モードを押したあとに出す一言 (Claude は話しかけるまで待つので) */
    const val CLAUDE_HINT = "「こんにちは」って はなしかけてね"
}
