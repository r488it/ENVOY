package com.rabi.envoymcp.ui.settings

import com.rabi.envoymcp.VoiceAgent

/** かんたんセットアップの 1 つの手順の、今の様子 */
enum class StepState(val label: String) {
    DONE("できています"),
    NOT_YET("まだです"),
    /** 自動では確かめきれない (AI の会社のページで登録するなど)。家族の方が、使ってみて確かめる */
    FAMILY("家族の方が確かめてください"),
}

/**
 * かんたんセットアップの手順。この並びの順に案内する。
 * optional は「あとで」で飛ばせる手順、family は、つながったかを家族の方が確かめる手順
 */
enum class SetupStep(val title: String, val why: String, val optional: Boolean = false, val family: Boolean = false) {
    HOME("ENVOY MCP をホームアプリにする",
        "ホームボタンを押すと、いつもリゲルの画面に戻るようにします。使う方は、リゲルを押すだけで AI と話せます。"),
    ACCESSIBILITY("ユーザー補助（ENVOY MCP の操作）をオンにする",
        "AI に頼まれたとき、ENVOY MCP がスマホの画面を読んだり、ボタンを押したりするのに使います。"),
    OVERLAY("他のアプリの上に表示を許可する",
        "AI がスマホを動かしている間、画面のふちを光らせ、リゲルを出してお知らせします。リゲルを押すと、いつでも止められます。"),
    NOTIFICATIONS("通知へのアクセスを許可する",
        "かかってきた電話の相手や、流れている曲の名前を読むのに使います。"),
    PERMISSIONS("電話の許可（連絡先・電話をかける）",
        "「〇〇さんに電話して」と頼んだとき、連絡先から番号を探して電話をかけるのに使います。かける前には、かならず本人の承認があります。"),
    SCREEN_LOCK("画面ロック（指紋・PIN）を用意する",
        "電話をかける・送るなど、取り消せない操作の前に、指紋や PIN で本人か確かめます。画面ロックが無いと確かめられないので、その操作ができません。"),
    SERVER("MCP サーバーをオンにする",
        "AI がこのスマホの道具を使えるように、スマホの中で MCP サーバーを動かします。動いている間は、通知に出ます。"),
    CHOOSE_AI("話し相手の AI を選ぶ",
        "リゲルを押したときに開いて、声でお話しする AI のアプリを選びます。選んだアプリが、このスマホに入っている必要があります。"),
    CONNECT_AI("AI とつなぐ",
        "選んだ AI から、このスマホの道具を使えるように登録します。登録は AI の会社のページでするので、つながったかは、家族の方が使ってみて確かめてください。",
        family = true),
    ARM("AgentARM（腕）とつなぐ",
        "スマホを載せる腕があれば、つないでおくと、「ねえ、リゲル」と呼んだ方を向いたり、音楽に合わせて踊ったりします。腕が無ければ「あとで」を押してください。",
        optional = true),
}

/** 手順の様子を決めるための、今の様子 (端末から読んだもの。準備の状態と同じ所から読む) */
data class SetupFacts(
    val home: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val phone: Boolean = false,
    /** 画面ロック (指紋・PIN など) があり、本人確認ができる */
    val screenLock: Boolean = false,
    val serverOn: Boolean = false,
    val serverRunning: Boolean = false,
    /** 話し相手の AI */
    val ai: VoiceAgent = VoiceAgent.CHATGPT,
    /** 話し相手の AI のアプリが入っている */
    val aiInstalled: Boolean = false,
    /** 話し相手の AI からの入口: true はつながっている、false はオフかうまくいっていない、null は分からない (動き始め・ほかの機械で動かしている) */
    val aiLink: Boolean? = null,
    /** 話し相手の AI が、一度でもこのスマホの MCP サーバーにつながった (道具の一覧を読んだ・道具を呼んだ) */
    val aiUsed: Boolean = false,
    val armOn: Boolean = false,
    /** 腕に要る許可 (Bluetooth・カメラ) がそろっている */
    val armPermissions: Boolean = false,
    val armReady: Boolean = false,
)

/** かんたんセットアップの決まり: 手順ごとの様子、渡せるか、次に見る手順。端末に触れない (試験する) */
object SetupGuide {
    const val TITLE = "かんたんセットアップ（家族の方へ）"
    const val LEAD = "スマホを渡す前に、順番に設定します"
    const val DONE_TITLE = "できました。スマホを渡してください"
    const val HINT = "使う人は『ねえ、リゲル』と話しかけるか、ホームのリゲルを押すだけです"

    val steps: List<SetupStep> = SetupStep.entries

    fun state(step: SetupStep, f: SetupFacts): StepState = when (step) {
        SetupStep.HOME -> done(f.home)
        SetupStep.ACCESSIBILITY -> done(f.accessibility)
        SetupStep.OVERLAY -> done(f.overlay)
        SetupStep.NOTIFICATIONS -> done(f.notifications)
        SetupStep.PERMISSIONS -> done(f.phone)
        SetupStep.SCREEN_LOCK -> done(f.screenLock)
        SetupStep.SERVER -> done(f.serverOn && f.serverRunning)
        SetupStep.CHOOSE_AI -> done(f.aiInstalled)
        // サーバーが止まっている・入口がオフかうまくいっていないときは、まだ。AI から一度つながっていれば、できている。
        // それ以外は自動で確かめきれないので、家族の方が確かめる
        SetupStep.CONNECT_AI -> when {
            !(f.serverOn && f.serverRunning) || f.aiLink == false -> StepState.NOT_YET
            f.aiUsed -> StepState.DONE
            else -> StepState.FAMILY
        }
        SetupStep.ARM -> done(f.armOn && f.armPermissions && f.armReady)
    }

    fun states(f: SetupFacts): Map<SetupStep, StepState> = steps.associateWith { state(it, f) }

    /** 渡す前に済ませる手順 (任意のもの以外) が、すべてできているか */
    fun ready(states: Map<SetupStep, StepState>): Boolean = steps.filter { !it.optional }.all { states[it] == StepState.DONE }

    /** 渡す前に済ませる手順のうち、できている数と、全部の数 */
    fun progress(states: Map<SetupStep, StepState>): Pair<Int, Int> {
        val required = steps.filter { !it.optional }
        return required.count { states[it] == StepState.DONE } to required.size
    }

    /**
     * after より後 (after は含まない。null なら最初から) で、まだできていない最初の手順。無ければ null。
     * 家族の方が確かめる手順も、まだできていないものに数える。skipped は「あとで」にした手順で、飛ばす
     */
    fun nextNotDone(states: Map<SetupStep, StepState>, after: SetupStep? = null, skipped: Set<SetupStep> = emptySet()): SetupStep? {
        val from = after?.let { it.ordinal + 1 } ?: 0
        return steps.drop(from).firstOrNull { states[it] != StepState.DONE && it !in skipped }
    }

    /** 開いたときに出す手順: 渡す前に済ませる手順がそろっていれば null (終わりの画面)。そろっていなければ、まだできていない最初の手順 */
    fun start(states: Map<SetupStep, StepState>, skipped: Set<SetupStep> = emptySet()): SetupStep? =
        if (ready(states)) null else nextNotDone(states, null, skipped)

    /** 設定の検索で、かんたんセットアップを出すための言葉 (入口の名前・説明と、手順の名前) */
    val searchWords: List<String> = listOf(TITLE, LEAD, DONE_TITLE) + steps.map { it.title }

    /** 探している言葉 (SettingsSearch.norm で正規化したもの) が名前に入っている、最初の手順 (無ければ null) */
    fun matching(normQuery: String): SetupStep? =
        if (normQuery.isEmpty()) null else steps.firstOrNull { SettingsSearch.norm(it.title).contains(normQuery) }

    private fun done(ok: Boolean) = if (ok) StepState.DONE else StepState.NOT_YET
}
