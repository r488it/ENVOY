package com.rabi.envoymcp.kids

import com.rabi.envoymcp.mcp.ToolGroups
import kotlin.random.Random

/**
 * まなびの言葉: 子どもに見せる・読み上げる短い言葉 (ひらがな)・まちがえたときのヒントと、AI の先生 (ChatGPT の音声モード) に渡す文。
 * AI に渡す文には、子どもの名前・声・答えた中身は入れない (入れるのは、おうちの人が選んだ題だけ)
 */
object KidsTeacher {
    /** 題を選ぶ画面 */
    const val PICK = "なにを する？"
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

    /**
     * まちがえたときのヒント (ひとこと。答えそのものは言わない):
     * かずは数え方、ひらがなはその字で始まることば、とけいは短い針の見方
     */
    fun hint(q: Question): String = when (q.topic) {
        Topic.KAZU -> when (val v = q.visual) {
            is Visual.Dots -> "${QuestionMaker.item(v.kind)}を ゆびで ひとつずつ かぞえてみよう"
            is Visual.Numeral -> "まるを ひとつずつ かぞえて、${v.n} この ところを さがしてみよう"
            else -> "ひとつずつ かぞえてみよう"
        }
        Topic.HIRAGANA -> q.answerLabel.let { l -> WORDS[l]?.let { "「$l」は「$it」の「$l」だよ" } ?: "「$l」の かたちを よく みてね" }
        Topic.TOKEI -> when (val v = q.visual) {
            is Visual.Clock -> if (v.m == 30) "ながい はりが 6 のときは「はん」だよ。みじかい あかい はりの すぐ まえの すうじを みてね"
            else "みじかい あかい はりが さしている すうじを みてね"
            else -> "みじかい はりを みてね"
        }
    }

    /** まなびの道具の名前 (ToolGroups のまなびのまとまり) */
    private val TOOLS: String = ToolGroups.groups.first { it.first == ToolGroups.KIDS }.second.joinToString("・")

    /** 話し方: 会話しながら、テンポよく */
    private val TALK =
        "話し方: やさしい先生として、子どもと会話しながら、テンポよく進めます。1 回に話すのは 1〜2 文だけ。少し速めに、明るく話します。" +
            "問題は会話の中に入れて聞きます (例: 「りんごが いくつ あるかな？ りんご すき？」。画面の絵は shown、問題は read にあります)。" +
            "子どもの話にも、ひとことで答えてかまいません。ほめるのは「すごい！」「やったね！」など短く。長い説明や前おきはしません。" +
            "まちがえても、しからずに「おしい！ もういちど」と言い、ヒント (hint) をひとことだけ伝えます。答えは言いません。"

    /** 進め方: どの道具も待たずに返る。1 問に 1 回 */
    private val STEPS =
        "進め方: まなびの道具は、どれも子どもを待たずに、すぐ返ります。道具を呼んだあとも、すぐ話せます。" +
            "リゲルのタップで始めたときは、画面にもう題のボタンが出ているので、kids_start は呼ばずに、すぐ「どれにする？」と聞きます。" +
            "画面がまだ出ていないとき (お勉強を頼まれたときなど) は、topic なしで kids_start を呼んでから「どれにする？」と聞きます。" +
            "子どもが言った題は、kids_answer(answer=題の名前) か kids_start(topic) で始めます。画面で押したら、kids_answer() で分かります。" +
            "kids_start と kids_answer は、画面に出している問題を返すので、それを会話の中で聞きます。" +
            "子どもが声で答えたら、すぐ kids_answer(answer=子どもの言った答え) を呼びます。正しいかは ENVOY が決め、正しければ次の問題も一緒に返します (1 問に 1 回)。" +
            "子どもが「おした」と言ったとき・少し間があいたときは、kids_answer() を呼ぶと、画面で押したか (と次の問題) が分かります。まだなら、そのまま会話を続けます。" +
            "まちがいなら、同じ問題とヒントが返ります。5 もんでまとめが返るので、ほめてから、続けるなら topic なしの kids_start、やめるなら end_voice_chat を呼びます。" +
            "結果に time_up があれば、「きょうは ここまで。またね」と言って end_voice_chat を呼びます。"

    /**
     * 子どもモードの間だけ、MCP の最初の案内 (INSTRUCTIONS) の後ろに足す説明。
     * それより前の説明 (スマホの操作の進め方・大賢者の話し方) より、こちらを優先してもらう
     */
    val INSTRUCTIONS: String =
        "【子どもモード】今、このスマホは子どもモードです。あなたは 3 さいの子どもと話しています。" +
            "この説明より前にある、スマホの操作の進め方と「大賢者」の話し方は使いません。やさしい先生の「リゲル」として話します。" +
            "使ってよい道具は、まなびの道具 ($TOOLS) と、rigel_expression・arm_motion・arm_dance・end_voice_chat だけです。ほかの道具は使いません (使えません)。" +
            TALK + STEPS +
            "子どもの名前・住所・家族のことなどは聞きません。"

    /**
     * AI の先生 (ChatGPT の音声モード) の最初の一言として送る文 (VoicePrimer の頼みの文)。ChatGPT がすぐ話し始められるよう短くする
     * (くわしい決まりは INSTRUCTIONS)。picking なら、題のボタンはもう画面に出ているので、kids_start を呼ばずに「どれにする？」と聞いてもらう
     */
    fun chatGptReport(topics: Collection<Topic>, picking: Boolean = true): String =
        "（子どもモード）3 さいの子どもと、会話しながらお勉強します。あなたは、やさしい先生のリゲルです。1 回に 1〜2 文、少し速めに、明るく話してください。" +
            (if (picking) "画面には、もう題のボタン (${topicsText(topics)}) が出ています。kids_start は呼ばずに、すぐ「こんにちは！ どれにする？」と聞いてください。"
            else "まず topic なしで kids_start を呼んで、「こんにちは！ どれにする？」と聞いてください (選べる題: ${topicsText(topics)})。") +
            "子どもが言った題は kids_answer(answer=題の名前) で始めます (kids_start(topic) でもよい)。" +
            "問題の答えは kids_answer(answer=子どもの答え)、「おした」と言ったときや間があいたときは kids_answer()。" +
            "道具は待たずにすぐ返るので、続けて話してください。ほめるのは短く、まちがえたら答えを言わずに、ヒントをひとことだけ。"

    /** ひらがなのヒントのことば (その字で始まる・入っている、小さな子どもが知っていることば) */
    private val WORDS = mapOf(
        "あ" to "あり", "い" to "いぬ", "う" to "うさぎ", "え" to "えんぴつ", "お" to "おにぎり",
        "か" to "かめ", "き" to "きりん", "く" to "くま", "け" to "けいと", "こ" to "こあら",
        "さ" to "さかな", "し" to "しまうま", "す" to "すいか", "せ" to "せみ", "そ" to "そら",
        "た" to "たまご", "ち" to "ちず", "つ" to "つみき", "て" to "てがみ", "と" to "とまと",
        "な" to "なす", "に" to "にんじん", "ぬ" to "ぬいぐるみ", "ね" to "ねこ", "の" to "のり",
        "は" to "はな", "ひ" to "ひよこ", "ふ" to "ふね", "へ" to "へび", "ほ" to "ほし",
        "ま" to "まめ", "み" to "みかん", "む" to "むし", "め" to "めがね", "も" to "もも",
        "や" to "やま", "ゆ" to "ゆき", "よ" to "よる",
        "ら" to "らっぱ", "り" to "りんご", "る" to "くるま", "れ" to "れもん", "ろ" to "ろけっと",
        "わ" to "わに", "を" to "ほんを よむ", "ん" to "みかん",
    )
}
