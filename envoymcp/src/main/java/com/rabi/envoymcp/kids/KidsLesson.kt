package com.rabi.envoymcp.kids

import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

/** お勉強が使う、まわりのもの (本物は McpGraph の設定・したこと・リゲル・お勉強の画面。試験では偽物) */
interface KidsHost {
    /** 子どもモードか */
    var kidMode: Boolean
    /** おうちの人が選んだ題 */
    val topics: Set<Topic>
    /** 1 日にお勉強できる時間 (分) */
    val limitMin: Int
    /** 題ごとの難しさ (読む・残す) */
    fun level(t: Topic): Int
    fun saveLevel(t: Topic, level: Int)
    /** きょうお勉強した長さ (ミリ秒) と、その日 */
    var usedMs: Long
    var usedDay: String
    /** したことに残す */
    fun log(who: String, tool: String, sentence: String)
    /** リゲルの表情 (腕につながっていて手が空いていれば、腕も同じような動き) */
    fun express(e: Expression)
    /** お勉強の画面を開く (前に出す) */
    fun open()
    fun now(): Long
    /** 今日を表す文字 (年月日) */
    fun today(): String
}

/**
 * お勉強 (まなび) の進み方。お勉強の画面 (KidsActivity) と、まなびの道具 (KidsTools) が分け合う。
 * - ひとりで: 画面で題を選び、5 もんずつ出す。リゲルが問題を読み上げる (端末の中の読み上げ。外には何も送らない)。
 *   正しく押したら星とリゲルのうれしい顔 (腕も)。少ししてから次の問題。まちがえたら「もういちど やってみよう」
 * - AI の先生: 道具はどれも子どもを待たずにすぐ返る (待っている間は AI が話せないため)。子どもは声でも画面を押しても答えられる。
 *   声の答えは AI が kids_answer(answer) で渡し、ENVOY が今の問題と比べる (KidsAnswer)。画面で押した答えは、次の kids_answer で伝える。
 *   正しければ、次の問題を決めて同じ結果で返す (1 問に 1 回)。画面は星を少し見せてから次の問題にする
 * - 時間: 子どもモードの間、お勉強の画面が出ている長さを 1 日ごとに数え、決めた長さで「きょうは ここまで」にする
 * - 終わったら、したことに「まなび（かず）を 5 もん した（4 もん せいかい）」と残す (子どもの声・名前・答えた中身は残さない)
 * 中の状態はメインスレッドだけで触る (道具からは withContext(Main) で呼ぶ)
 */
class KidsLesson(private val host: KidsHost, private val scope: CoroutineScope, private val random: Random = Random.Default) {
    /** 画面の場面: 題を選ぶ・問題・おしまい・時間になった */
    enum class Mode { PICK, ASK, END, TIME_UP }

    /** 画面に出すもの */
    data class View(
        val mode: Mode = Mode.PICK,
        /** AI の先生が進めている (読み上げない・戻るで抜けない・固定しない) */
        val ai: Boolean = false,
        val question: Question? = null,
        /** まちがえて押した選択肢 (うすくする) */
        val wrong: Set<Int> = emptySet(),
        /** 正しく答えた選択肢 */
        val right: Int? = null,
        /** 大きく出す一言 (すごい！・もういちど やってみよう など) */
        val message: String = "",
        val stars: Int = 0,
        val size: Int = KidsSession.DEFAULT_SIZE,
        val summary: Summary? = null,
        /** 星が飛ぶ合図 (正しく答えた・ほめたときに増える) */
        val cheerSeq: Int = 0,
        /** 読み上げる言葉と、その合図 (ひとりでお勉強するときだけ。増えたら読む) */
        val speech: String = "",
        val speechSeq: Int = 0,
    )

    /** ここまでの進み (AI の先生に返す) */
    data class Progress(val stars: Int, val asked: Int, val size: Int, val level: Int, val minutesLeft: Int)

    /** 子どもの 1 回の答え (byVoice: 声で答えた。そうでなければ画面で押した)。chosen は選んだもの・言ったものの言い方 */
    data class Answer(val question: Question, val chosen: String, val correct: Boolean, val firstTry: Boolean, val byVoice: Boolean)

    /** AI の先生の道具の結果 (どれも待たずに返す) */
    sealed interface AiResult {
        /** きょうのお勉強の時間を使い切った */
        data object TimeUp : AiResult
        /** おうちの人が選んでいない題 */
        data class NotEnabled(val enabled: List<Topic>) : AiResult
        /** まだ始めていない */
        data object NotStarted : AiResult
        /** 題を選ぶボタンを出した (子どもは声か画面で選ぶ) */
        data class Picking(val topics: List<Topic>) : AiResult
        /** AI が題を決めて始めた: 最初の問題を画面に出した */
        data class Started(val topic: Topic, val question: Question, val progress: Progress) : AiResult
        /**
         * 答えを確かめた。picked は前に確かめてから子どもが選んで始めた題 (画面か声)。answers は前に確かめてから今までの答え (古い順)。
         * unclear は答えとして分からなかった声の答え、skipped は画面で先に答えていたので比べなかった声の答え。
         * question は今の問題 (正しければ次の問題、まちがい・まだならそのまま)。summary はひと区切り終わったとき (question は null)。
         * hint はいちばん新しい答えがまちがいのとき
         */
        data class Checked(
            val picked: Topic?, val answers: List<Answer>, val unclear: String?, val skipped: String?,
            val question: Question?, val summary: Summary?, val hint: String?, val progress: Progress,
        ) : AiResult
        data class Ended(val summary: Summary) : AiResult
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private var session: KidsSession? = null
    /** 今の回を、したことに残したか */
    private var logged = true
    /** AI の先生が進めているか */
    private var aiLed = false
    /** したことに残すときの名前 (ひとりならリゲル、AI の先生ならそのエージェント) */
    @Volatile private var teacher: String = ActivityLog.RIGEL
    /** AI の先生にまだ伝えていない、画面で押した答え */
    private val reports = ArrayList<Answer>()
    /** 子どもが画面で選んだ題 (AI の先生にまだ伝えていない) */
    private var pickedByChild: Topic? = null
    /** 正しく答えたあと、星を見せてから出す画面 (次の問題・おしまい) と、それを出す仕事 */
    private var pendingShow: (() -> Unit)? = null
    private var nextJob: Job? = null
    private val timer by lazy { KidsTimer(host.usedMs, host.usedDay) }

    // ---------------------------------------------------------------- 子どもモードと時間

    /**
     * 子どもモードを入れる・切る (切るときは、呼ぶ側が先にご本人か確かめる)。入れたら、きょうの時間を 0 から数え直す。
     * 切ったら、今の回をしまう。who は、したことに残す名前
     */
    fun setKidMode(on: Boolean, who: String) {
        if (host.kidMode == on) return
        host.kidMode = on
        if (on) {
            timer.reset(host.now(), host.today())
            save()
        } else {
            dropPending()
            finish()
            reports.clear()
            pickedByChild = null
            aiLed = false
            session = null
            _view.value = View(cheerSeq = _view.value.cheerSeq, speechSeq = _view.value.speechSeq)
        }
        host.log(who, "kids_mode", if (on) "子どもモードを入れた" else "子どもモードを切った")
        RabiLog.i(TAG, "kid mode ${if (on) "on" else "off"}")
    }

    /** きょうのお勉強の時間を使い切ったか (子どもモードの間だけ数える) */
    fun over(): Boolean = host.kidMode && timer.over(limitMs(), host.now(), host.today())

    /** のこりの分 (切り上げ) と、きょうお勉強した分 (切り捨て) */
    fun minutesLeft(): Int = ((timer.left(limitMs(), host.now(), host.today()) + MINUTE_MS - 1) / MINUTE_MS).toInt()
    fun minutesUsed(): Int = (timer.used(host.now(), host.today()) / MINUTE_MS).toInt()

    /** お勉強の画面が出た: 見ている長さを数え始める */
    fun shown() { if (host.kidMode) timer.start(host.now(), host.today()) }

    /** お勉強の画面が隠れた: 数えるのをやめて残す */
    fun hidden() {
        timer.stop(host.now(), host.today())
        save()
    }

    /** 画面が出ている間、1 秒ごと: 時間になったら、やさしく止める */
    fun tick() { if (over() && _view.value.mode in ACTIVE) timeUp() }

    // ---------------------------------------------------------------- ひとりで

    /** お勉強の画面を開く (ひとりで)。途中の問題があればそこから。無ければ題を選ぶところから */
    fun openAlone() {
        val v = _view.value
        when {
            over() -> timeUp()
            v.ai || v.mode != Mode.ASK || session?.current == null -> pick()
        }
        host.open()
    }

    /** 題を選ぶところにする (ひとりで。途中の回は、したことに残してしまう) */
    fun pick() {
        dropPending()
        if (over()) { timeUp(); return }
        finish()
        reports.clear()
        pickedByChild = null
        aiLed = false
        show(View(mode = Mode.PICK, message = KidsTeacher.PICK), speak = KidsTeacher.PICK)
    }

    /** 題を選ぶ画面で、子どもが題を押した: その題で最初の問題を出す (AI の先生なら、次の道具で先生に伝える) */
    fun pickTopic(t: Topic) {
        if (_view.value.mode != Mode.PICK) return
        if (over()) { timeUp(); return }
        if (t !in host.topics) return
        begin(t, null)
        askNext(null, null)
        if (aiLed) pickedByChild = t
    }

    /** 子どもが選択肢を押した (画面から) */
    fun tap(i: Int) {
        val v = _view.value
        val s = session ?: return
        val q = s.current ?: return
        if (v.mode != Mode.ASK || v.question != q) return
        if (over()) { timeUp(); return }
        val o = s.answer(i) ?: return
        saveLevels(s)
        if (aiLed) reports += Answer(q, q.choices[i].label, o.correct, o.firstTry, byVoice = false)
        if (o.correct) advance(i, if (aiLed) AI_NEXT_MS else NEXT_MS)
        else show(v.copy(wrong = s.wrong, message = KidsTeacher.RETRY), speak = KidsTeacher.RETRY)
        RabiLog.i(TAG, "tap ${if (o.correct) "right" else "wrong"}${if (o.levelUp) " (level up)" else if (o.levelDown) " (level down)" else ""}")
    }

    // ---------------------------------------------------------------- AI の先生 (どれも待たずに返す)

    /** AI の先生: 呼んだエージェントの名前 (お勉強を、したことに残すときに使う) */
    fun noteTeacher(name: String) { teacher = name }

    /**
     * AI の先生: 始める。子どもモードがオフならオンにし、お勉強の画面を開く。
     * topic が無ければ、題を選ぶボタンを出して返す (子どもは声か画面で選ぶ)。子どもがもう画面で選んでいれば、それを返す。
     * topic があれば、その題で最初の問題を画面に出して返す
     */
    fun startAi(t: Topic?, level: Int?): AiResult {
        if (!host.kidMode) setKidMode(true, teacher)
        if (t != null && t !in host.topics) return AiResult.NotEnabled(enabled())
        if (over()) { timeUp(); host.open(); return AiResult.TimeUp }
        flush()
        host.open()
        if (t == null) {
            if (aiLed && pickedByChild != null) return check("")
            dropPending()
            finish()
            session = null
            reports.clear()
            aiLed = true
            show(View(mode = Mode.PICK, message = KidsTeacher.PICK))
            return AiResult.Picking(enabled())
        }
        aiLed = true
        begin(t, level)
        val q = askNext(null, null)
        return AiResult.Started(t, q, progress())
    }

    /**
     * AI の先生: 答えを確かめる (待たない)。said があれば、子どもが声で言った答えとして今の問題と比べる
     * (題を選ぶところなら、言った題で始める)。無ければ、前に確かめてから画面で押したもの (と今の問題) を返す。まだなら、今の問題のまま
     */
    fun answerAi(said: String?): AiResult {
        if (over()) { timeUp(); return AiResult.TimeUp }
        flush()
        return check(said?.trim().orEmpty())
    }

    /** AI の先生: もっとほめる (リゲルのうれしい顔・腕の動き・画面の星) */
    fun cheerAi() {
        host.express(Expression.HAPPY)
        show(_view.value.copy(message = KidsTeacher.cheer(random)), cheer = true)
    }

    /** AI の先生: おしまいにして、まとめを返す (始めていなければ、0 のまとめ) */
    fun endAi(): AiResult {
        flush()
        reports.clear()
        pickedByChild = null
        val s = session ?: return AiResult.Ended(Summary(emptyList(), 0, 0, 0))
        end()
        return AiResult.Ended(s.summary())
    }

    // ---------------------------------------------------------------- 中のこと

    /** 今の様子を確かめて返す。words は声の答え (空なら無し) */
    private fun check(words: String): AiResult {
        val v = _view.value
        val picked = pickedByChild
        pickedByChild = null
        // 題を選ぶところ: 声で言った題で始める
        if (aiLed && v.mode == Mode.PICK) {
            val t = if (words.isEmpty()) null else KidsAnswer.topic(words)
            if (t == null) return AiResult.Picking(enabled())
            if (t !in host.topics) return AiResult.NotEnabled(enabled())
            begin(t, null)
            val q = askNext(null, null)
            return AiResult.Checked(t, emptyList(), null, null, q, null, null, progress())
        }
        val s = session
        if (!aiLed || s == null || v.mode == Mode.TIME_UP) return AiResult.NotStarted
        val answers = ArrayList(reports)
        reports.clear()
        var unclear: String? = null
        var skipped: String? = null
        if (words.isNotEmpty()) {
            val q = s.current
            when {
                // 画面で先に答えた・題の名前を言った・もう終わった: 声の答えは今の問題のものではないので、比べない
                answers.any { it.correct } || (picked != null && KidsAnswer.topic(words) != null) || q == null || s.solved -> skipped = words
                else -> when (val h = KidsAnswer.judge(q, words)) {
                    is KidsAnswer.Heard.Choice -> {
                        val o = s.answer(h.index)
                        val correct = h.index == q.answer
                        answers += Answer(q, q.choices[h.index].label, correct, o?.firstTry == true, byVoice = true)
                        saveLevels(s)
                        if (correct) advance(h.index, AI_NEXT_MS)
                        else show(_view.value.copy(wrong = s.wrong, message = KidsTeacher.RETRY))
                    }
                    is KidsAnswer.Heard.Other -> {
                        s.miss()
                        saveLevels(s)
                        answers += Answer(q, h.value, correct = false, firstTry = false, byVoice = true)
                        show(_view.value.copy(message = KidsTeacher.RETRY))
                    }
                    KidsAnswer.Heard.Unclear -> unclear = words
                }
            }
        }
        val summary = if (s.done) s.summary() else null
        val now = if (summary != null) null else s.current
        val last = answers.lastOrNull()
        val hint = if (last != null && !last.correct && now != null) KidsTeacher.hint(now) else null
        return AiResult.Checked(picked, answers, unclear, skipped, now, summary, hint, progress())
    }

    /** 新しい回を始める (前の回は、したことに残してしまう)。level があれば、その題の難しさにする */
    private fun begin(t: Topic, level: Int?) {
        dropPending()
        finish()
        reports.clear()
        pickedByChild = null
        val levels = Topic.entries.associateWith { host.level(it) }.toMutableMap()
        if (level != null) levels[t] = level
        session = KidsSession(t, levels, random = random)
        logged = false
        if (!aiLed) teacher = ActivityLog.RIGEL
    }

    /** 次の問題を出す */
    private fun askNext(t: Topic?, level: Int?): Question {
        val s = session ?: KidsSession(t ?: enabled().first(), random = random).also { session = it; logged = false }
        val q = s.next(t ?: s.topic, level)
        saveLevels(s)
        showQuestion(q, s)
        return q
    }

    private fun showQuestion(q: Question, s: KidsSession) =
        show(View(mode = Mode.ASK, question = q, stars = s.stars, size = s.size), speak = q.speech)

    /**
     * 正しく答えた: 星・うれしい顔 (腕も)・ほめる言葉を出し、次の問題 (5 もん目ならおしまい) をここで決めて、
     * delayMs のあとに画面に出す (星を見せる間。AI の先生が先に確かめたら、すぐ出す: flush)
     */
    private fun advance(right: Int, delayMs: Long) {
        val s = session ?: return
        val cheer = KidsTeacher.cheer(random)
        host.express(Expression.HAPPY)
        show(_view.value.copy(right = right, wrong = s.wrong, message = cheer, stars = s.stars), speak = cheer, cheer = true)
        dropPending()
        pendingShow = if (s.done) {
            finish()
            val sum = s.summary()
            ({
                // うなずくように笑う (腕につながっていれば、腕も)
                host.express(Expression.LAUGH)
                show(View(mode = Mode.END, summary = sum, stars = sum.stars, size = s.size, message = KidsTeacher.END), speak = KidsTeacher.END)
            })
        } else {
            val q = s.next()
            saveLevels(s)
            ({ showQuestion(q, s) })
        }
        nextJob = scope.launch {
            delay(delayMs)
            nextJob = null
            showPending()
        }
    }

    /** 星を見せている途中の画面を、すぐ出す */
    private fun flush() {
        nextJob?.cancel()
        nextJob = null
        showPending()
    }

    private fun showPending() {
        val f = pendingShow ?: return
        pendingShow = null
        f()
    }

    /** 出す予定の画面をやめる (新しく始める・時間になった・子どもモードを切った) */
    private fun dropPending() {
        nextJob?.cancel()
        nextJob = null
        pendingShow = null
    }

    /** おしまい (AI の先生の kids_end): 星を見せて、したことに残す */
    private fun end() {
        if (_view.value.mode == Mode.END) return
        val s = session ?: return
        val sum = s.summary()
        finish()
        host.express(Expression.LAUGH)
        show(View(mode = Mode.END, summary = sum, stars = sum.stars, size = s.size, message = KidsTeacher.END), speak = KidsTeacher.END, cheer = true)
    }

    /** 時間になった: やさしく止める */
    private fun timeUp() {
        dropPending()
        finish()
        if (_view.value.mode == Mode.TIME_UP) return
        RabiLog.i(TAG, "time up")
        show(View(mode = Mode.TIME_UP, message = KidsTeacher.TIME_UP), speak = KidsTeacher.TIME_UP_SPEECH)
    }

    /** 今の回をしまう: まだ残していなければ、したことに残す (1 もんも出していなければ残さない) */
    private fun finish() {
        val s = session ?: return
        if (logged) return
        logged = true
        val sum = s.summary()
        if (sum.questions == 0) return
        host.log(teacher, TOOL, sum.sentence)
        RabiLog.i(TAG, "lesson ${sum.topics.joinToString(",") { it.id }} q=${sum.questions} c=${sum.correct}")
    }

    /** 画面を変える。speak は読み上げる言葉 (AI の先生のときは読まない)。cheer なら星を飛ばす */
    private fun show(v: View, speak: String = "", cheer: Boolean = false) {
        val cur = _view.value
        val say = !aiLed && speak.isNotBlank()
        _view.value = v.copy(
            ai = aiLed,
            cheerSeq = cur.cheerSeq + if (cheer) 1 else 0,
            speech = if (say) speak else cur.speech,
            speechSeq = cur.speechSeq + if (say) 1 else 0,
        )
    }

    private fun saveLevels(s: KidsSession) = s.levels().forEach { (t, lv) -> host.saveLevel(t, lv) }

    private fun enabled(): List<Topic> = Topic.entries.filter { it in host.topics }.ifEmpty { Topic.entries }

    private fun progress(): Progress {
        val s = session
        return Progress(s?.stars ?: 0, s?.asked ?: 0, s?.size ?: KidsSession.DEFAULT_SIZE, s?.levelOf(s.topic) ?: 1, minutesLeft())
    }

    private fun save() {
        host.usedMs = timer.stored()
        host.usedDay = timer.day
    }

    private fun limitMs(): Long = host.limitMin.coerceAtLeast(1) * MINUTE_MS

    /** 本物のまわり (McpGraph: 設定・したこと・リゲルの表情・お勉強の画面) */
    class GraphHost(private val g: McpGraph) : KidsHost {
        override var kidMode: Boolean
            get() = g.settings.kidsMode
            set(v) { g.settings.kidsMode = v }
        override val topics: Set<Topic> get() = g.settings.kidsTopics
        override val limitMin: Int get() = g.settings.kidsLimitMin
        override fun level(t: Topic): Int = g.settings.kidsLevel(t)
        override fun saveLevel(t: Topic, level: Int) = g.settings.setKidsLevel(t, level)
        override var usedMs: Long
            get() = g.settings.kidsUsedMs
            set(v) { g.settings.kidsUsedMs = v }
        override var usedDay: String
            get() = g.settings.kidsUsedDay
            set(v) { g.settings.kidsUsedDay = v }
        override fun log(who: String, tool: String, sentence: String) = g.activity.add(who, tool, sentence, ActivityLog.KIND_ACT, ActivityLog.OK)
        override fun express(e: Expression) = g.express(e)
        override fun open() = KidsActivity.open(g.context)
        override fun now(): Long = System.currentTimeMillis()
        override fun today(): String = LocalDate.now().toString()
    }

    companion object {
        private const val TAG = "kids"
        /** したことに残すときの道具の名前 */
        const val TOOL = "kids_lesson"
        /** 正しく答えてから、次の問題を出すまで: ひとりのとき (ほめる言葉を聞く間) と、AI の先生のとき (星を見せる間) */
        const val NEXT_MS = 2_200L
        const val AI_NEXT_MS = 1_000L
        private const val MINUTE_MS = 60_000L
        /** 時間になったら止める場面 (おしまいの画面はそのまま) */
        private val ACTIVE = setOf(Mode.PICK, Mode.ASK)
    }
}
