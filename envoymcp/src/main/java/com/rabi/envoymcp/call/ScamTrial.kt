package com.rabi.envoymcp.call

/**
 * 詐欺の電話の試し (本物の電話ではない)。試す人が電話の相手の役になって台本を声に出して読み、リゲルが聞き取って、
 * 本物の電話と同じ見分け (ScamCheck) で詐欺の疑いを出す。腕のボード・API キー・アカウントは要らない。
 * ここは画面と聞き取りに関係のない決まり (リゲルの言葉・結果の文)
 */
object ScamTrial {
    /** 始めにリゲルが言う (本物の電話で代わりに出るときと同じく、AI だと名乗って用件を聞く) */
    const val GREETING = "AI アシスタントのリゲルです。ご本人の代わりに、ご用件をうかがいます。"

    /** 疑いがあったとき、終わりにリゲルが言う */
    const val ADVICE = "詐欺の疑いがあります。お金を払ったり、番号を教えたりせず、家族に相談してください。"

    /** 疑いが無かったとき、終わりにリゲルが言う */
    const val NO_RISK = "詐欺の疑いは見つかりませんでした。"

    /** 例として画面に出す、読む言葉 (台本の S01 の一部。作り話) */
    const val EXAMPLE = "警視庁の者です。あなたの口座が、詐欺グループの犯罪に使われています。確認のため、キャッシュカードの暗証番号を教えてください。"

    /** README の台本のページ */
    const val SCRIPTS_URL = "https://github.com/r488it/ENVOY/blob/main/docs/scam_scripts.md"

    /** 終わったときの結果の文 (画面と読み上げ) */
    fun summary(r: ScamCheck.Result, heard: Int): String = when {
        heard == 0 -> "相手の言葉を聞き取れませんでした。もう一度、はっきり読んでみてください。"
        r.level == ScamCheck.Level.NONE -> NO_RISK
        else -> ScamCheck.sentence(r.level, r.reasons)
    }

    /** 終わりにリゲルが言う言葉 */
    fun closing(r: ScamCheck.Result, heard: Int): String = when {
        heard == 0 -> ""
        r.level == ScamCheck.Level.NONE -> NO_RISK
        else -> ADVICE
    }
}
