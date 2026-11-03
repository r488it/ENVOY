package com.rabi.launcher.conversation

/** アバターの表情 */
enum class Mood { IDLE, LISTENING, THINKING, SPEAKING, ACTING, DONE, CONFUSED, APPROVAL }

/**
 * 一度だけ見せる感情の表情。状態 (Mood) の上に重ね、決まった長さで元に戻る。
 * id は道具や記録で使う名前
 */
enum class Expression(val id: String, val label: String, val holdMs: Long) {
    HAPPY("happy", "うれしい", 2_200),
    LAUGH("laugh", "笑う", 2_400),
    SURPRISED("surprised", "驚き", 1_400),
    SAD("sad", "悲しい", 2_600),
    SHY("shy", "照れ", 2_400),
    WINK("wink", "ウインク", 900);

    companion object {
        fun byId(id: String?): Expression? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * アバターと操作中のオーバーレイに出す状態。ENVOY MCP では、ChatGPT が道具を呼んでいる間だけ動く
 * (聞き取り・返事は ChatGPT のアプリが受け持つ)
 */
data class EngineState(
    val mood: Mood = Mood.IDLE,
    val caption: String = "",
    val wakeSeq: Int = 0,
    val stepSeq: Int = 0,
    val stepKind: String = "",
    val doneSeq: Int = 0,
    /** 一度だけ見せる感情の表情と、その番号 (見せるたびに増える) */
    val expression: Expression? = null,
    val expressionSeq: Int = 0,
    /** 0 でなければ、表情をこの長さで戻す (呼び名で起きたとき。0 なら表情ごとの長さ・話し始めるまで) */
    val expressionHoldMs: Long = 0L,
)
