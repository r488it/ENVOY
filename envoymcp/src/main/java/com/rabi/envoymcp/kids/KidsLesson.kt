package com.rabi.envoymcp.kids

import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.random.Random

/**
 * お勉強 (まなび) の進み方。お勉強の画面 (KidsActivity) と、まなびの道具 (KidsTools) が分け合う。
 * - ひとりで: 画面で題を選び、5 もんずつ出す。リゲルが問題を読み上げる (端末の中の読み上げ。外には何も送らない)。
 *   正しく選んだら星とリゲルのうれしい顔 (腕につながっていて手が空いていれば、腕も同じ動き)。まちがえたら「もういちど やってみよう」
 * - AI の先生: 道具が問題を画面に出し、子どもが押すのを待って結果を返す。読み上げない (話すのは AI)
 * - 時間: 子どもモードの間、お勉強の画面が出ている長さを 1 日ごとに数え、決めた長さで「きょうは ここまで」にする
 * - 終わったら、したことに「まなび（かず）を 5 もん した（4 もん せいかい）」と残す (子どもの声・名前・選んだ中身は残さない)
 * 中の状態はメインスレッドだけで触る (道具からは withContext(Main) で呼ぶ)
 */
class KidsLesson(private val g: McpGraph) {
    /** 画面の場面: 題を選ぶ・AI の先生を待つ・問題・おしまい・時間になった */
    enum class Mode { PICK, WAIT, ASK, END, TIME_UP }

    /** 画面に出すもの */
    data class View(
        val mode: Mode = Mode.PICK,
        /** AI の先生が進めている (読み上げない・戻るで抜けない) */
        val ai: Boolean = false,
        val question: Question? = null,
        /** まちがえて押した選択肢 (うすくする) */
        val wrong: Set<Int> = emptySet(),
        /** 正しく押した選択肢 */
        val right: Int? = null,
        /** 大きく出す一言 (すごい！・もういちど やってみよう など) */
        val message: String = "",
        val stars: Int = 0,
        val size: Int = KidsSession.DEFAULT_SIZE,
        val summary: Summary? = null,
        /** 星が飛ぶ合図 (正しく選んだ・ほめたときに増える) */
        val cheerSeq: Int = 0,
        /** 読み上げる言葉と、その合図 (ひとりでお勉強するときだけ。増えたら読む) */
        val speech: String = "",
        val speechSeq: Int = 0,
    )

    /** ここまでの進み (AI の先生に返す) */
    data class Progress(val stars: Int, val asked: Int, val size: Int, val level: Int, val minutesLeft: Int)

    /** AI の先生の道具の結果 */
    sealed interface AiResult {
        /** きょうのお勉強の時間を使い切った */
        data object TimeUp : AiResult
        /** おうちの人が選んでいない題 */
        data class NotEnabled(val enabled: List<Topic>) : AiResult
        data class Started(val topic: Topic, val level: Int, val progress: Progress) : AiResult
        /** 問題を出した (待たずに戻る) */
        data class Shown(val question: Question, val progress: Progress) : AiResult
        /** 子どもが選んだ */
        data class Picked(val question: Question, val index: Int, val correct: Boolean, val firstTry: Boolean, val progress: Progress) : AiResult
        /** 待っても選ばなかった */
        data class NoAnswer(val question: Question, val progress: Progress) : AiResult
        data class Ended(val summary: Summary) : AiResult
    }

    /** 子どもが選んだことを、待っている道具に渡す */
    private sealed interface Reply {
        data class Tap(val question: Question, val index: Int, val correct: Boolean, val firstTry: Boolean) : Reply
        data object TimeUp : Reply
        /** 次の問題・おしまい・子どもモードを切ったので、待つのをやめた */
        data object Gone : Reply
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
    private var waiter: CompletableDeferred<Reply>? = null
    /** AI の先生が待っていない間に、子どもが選んだもの (次に待ったとき、すぐ返す) */
    private var pending: Reply.Tap? = null
    private var nextJob: Job? = null
    private val random = Random.Default
    private val timer by lazy { KidsTimer(g.settings.kidsUsedMs, g.settings.kidsUsedDay) }

    // ---------------------------------------------------------------- 子どもモードと時間

    /**
     * 子どもモードを入れる・切る (切るときは、呼ぶ側が先にご本人か確かめる)。入れたら、きょうの時間を 0 から数え直す。
     * 切ったら、今の回をしまって、待っている道具をやめさせる。who は、したことに残す名前
     */
    fun setKidMode(on: Boolean, who: String) {
        if (g.settings.kidsMode == on) return
        g.settings.kidsMode = on
        if (on) {
            timer.reset(now(), today())
            save()
        } else {
            finish()
            release(Reply.Gone)
            cancelNext()
            aiLed = false
            _view.value = View(cheerSeq = _view.value.cheerSeq, speechSeq = _view.value.speechSeq)
        }
        g.activity.add(who, "kids_mode", if (on) "子どもモードを入れた" else "子どもモードを切った", ActivityLog.KIND_ACT, ActivityLog.OK)
        RabiLog.i(TAG, "kid mode ${if (on) "on" else "off"}")
    }

    /** きょうのお勉強の時間を使い切ったか (子どもモードの間だけ数える) */
    fun over(): Boolean = g.settings.kidsMode && timer.over(limitMs(), now(), today())

    /** のこりの分 (切り上げ) と、きょうお勉強した分 (切り捨て) */
    fun minutesLeft(): Int = ((timer.left(limitMs(), now(), today()) + MINUTE_MS - 1) / MINUTE_MS).toInt()
    fun minutesUsed(): Int = (timer.used(now(), today()) / MINUTE_MS).toInt()

    /** お勉強の画面が出た: 見ている長さを数え始める */
    fun shown() { if (g.settings.kidsMode) timer.start(now(), today()) }

    /** お勉強の画面が隠れた: 数えるのをやめて残す */
    fun hidden() {
        timer.stop(now(), today())
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
        KidsActivity.open(g.context)
    }

    /** 題を選ぶところにする (途中の回は、したことに残してしまう) */
    fun pick() {
        cancelNext()
        if (over()) { timeUp(); return }
        finish()
        release(Reply.Gone)
        aiLed = false
        show(View(mode = Mode.PICK, message = KidsTeacher.PICK), speak = KidsTeacher.PICK)
    }

    /** ひとりで: 題を選んだ。5 もんの回を始める */
    fun startAlone(t: Topic) {
        if (over()) { timeUp(); return }
        aiLed = false
        teacher = ActivityLog.RIGEL
        begin(t, null)
        askNext(null, null)
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
        if (o.correct) {
            val cheer = KidsTeacher.cheer(random)
            // 画面のリゲルと、腕 (つながっていて手が空いているときだけ。つながっていなければ何もしない)
            g.express(Expression.HAPPY)
            show(v.copy(right = i, wrong = s.wrong, message = cheer, stars = s.stars), speak = cheer, cheer = true)
            if (!aiLed) nextJob = g.scope.launch {
                delay(NEXT_MS)
                if (session !== s) return@launch
                if (s.done) end() else askNext(null, null)
            }
        } else {
            show(v.copy(wrong = s.wrong, message = KidsTeacher.RETRY), speak = KidsTeacher.RETRY)
        }
        RabiLog.i(TAG, "tap ${if (o.correct) "right" else "wrong"}${if (o.levelUp) " (level up)" else if (o.levelDown) " (level down)" else ""}")
        if (aiLed) reply(Reply.Tap(q, i, o.correct, o.firstTry))
    }

    // ---------------------------------------------------------------- AI の先生

    /** AI の先生: 呼んだエージェントの名前 (お勉強を、したことに残すときに使う) */
    fun noteTeacher(name: String) { teacher = name }

    /** AI の先生: お勉強を始める。子どもモードがオフならオンにし、お勉強の画面を開く */
    suspend fun startAi(t: Topic, level: Int?): AiResult = withContext(Dispatchers.Main) {
        if (!g.settings.kidsMode) setKidMode(true, teacher)
        if (t !in g.settings.kidsTopics) return@withContext AiResult.NotEnabled(enabled())
        if (over()) { timeUp(); KidsActivity.open(g.context); return@withContext AiResult.TimeUp }
        aiLed = true
        begin(t, level)
        show(View(mode = Mode.WAIT, message = KidsTeacher.WAIT, size = session?.size ?: KidsSession.DEFAULT_SIZE))
        KidsActivity.open(g.context)
        AiResult.Started(t, session?.levelOf(t) ?: 1, progress())
    }

    /**
     * AI の先生: 問題を画面に出し、wait なら子どもが選ぶのを待つ (WAIT_MS まで)。
     * same なら新しい問題を作らず、今の問題のまま待つ (まちがえたあと・問題を読んでから待つとき)。
     * まだ始めていなければ始める (子どもモードがオフならオンにする)
     */
    suspend fun askAi(t: Topic?, level: Int?, same: Boolean, wait: Boolean): AiResult = withContext(Dispatchers.Main) {
        if (t != null && t !in g.settings.kidsTopics) return@withContext AiResult.NotEnabled(enabled())
        if (!g.settings.kidsMode) setKidMode(true, teacher)
        if (over()) { timeUp(); KidsActivity.open(g.context); return@withContext AiResult.TimeUp }
        val v = _view.value
        if (!aiLed || session == null || v.mode == Mode.PICK || v.mode == Mode.END || v.mode == Mode.TIME_UP) {
            aiLed = true
            begin(t ?: session?.topic?.takeIf { it in g.settings.kidsTopics } ?: enabled().first(), level)
        }
        val s = session ?: return@withContext AiResult.NotEnabled(enabled())
        val cur = s.current
        // 同じ問題のまま待つのは、まだ正しく選んでいないとき (選んだのに道具がまだ聞いていなければ、その答えを返す)
        val keep = same && cur != null && v.mode == Mode.ASK && (!s.solved || pending?.question == cur)
        val q = if (keep && cur != null) cur else askNext(t, level)
        KidsActivity.open(g.context)
        if (!wait) return@withContext AiResult.Shown(q, progress())
        pending?.let { p ->
            pending = null
            if (p.question == q) return@withContext AiResult.Picked(q, p.index, p.correct, p.firstTry, progress())
        }
        release(Reply.Gone)
        val w = CompletableDeferred<Reply>()
        waiter = w
        val r = withTimeoutOrNull(WAIT_MS) { w.await() }
        if (waiter === w) waiter = null
        when (r) {
            is Reply.Tap -> AiResult.Picked(r.question, r.index, r.correct, r.firstTry, progress())
            Reply.TimeUp -> AiResult.TimeUp
            Reply.Gone, null -> AiResult.NoAnswer(q, progress())
        }
    }

    /** AI の先生: もっとほめる (リゲルのうれしい顔・腕の動き・画面の星) */
    suspend fun cheerAi(): Unit = withContext(Dispatchers.Main) {
        g.express(Expression.HAPPY)
        show(_view.value.copy(message = KidsTeacher.cheer(random)), cheer = true)
    }

    /** AI の先生: おしまいにして、まとめを返す (始めていなければ、0 のまとめ) */
    suspend fun endAi(): AiResult = withContext(Dispatchers.Main) {
        val s = session ?: return@withContext AiResult.Ended(Summary(emptyList(), 0, 0, 0))
        if (_view.value.mode != Mode.END) end()
        AiResult.Ended(s.summary())
    }

    // ---------------------------------------------------------------- 中のこと

    /** 新しい回を始める (前の回は、したことに残してしまう)。level があれば、その題の難しさにする */
    private fun begin(t: Topic, level: Int?) {
        finish()
        release(Reply.Gone)
        cancelNext()
        pending = null
        val levels = Topic.entries.associateWith { g.settings.kidsLevel(it) }.toMutableMap()
        if (level != null) levels[t] = level
        session = KidsSession(t, levels, random = random)
        logged = false
    }

    /** 次の問題を出す */
    private fun askNext(t: Topic?, level: Int?): Question {
        val s = session ?: KidsSession(t ?: enabled().first(), random = random).also { session = it; logged = false }
        val q = s.next(t ?: s.topic, level)
        saveLevels(s)
        pending = null
        show(View(mode = Mode.ASK, question = q, stars = s.stars, size = s.size), speak = q.speech)
        return q
    }

    /** ひと区切りおしまい: 星を見せて、したことに残す */
    private fun end() {
        cancelNext()
        val s = session ?: return
        val sum = s.summary()
        finish()
        release(Reply.Gone)
        // うなずくように笑う (腕につながっていれば、腕も)
        g.express(Expression.LAUGH)
        show(View(mode = Mode.END, summary = sum, stars = sum.stars, size = s.size, message = KidsTeacher.END), speak = KidsTeacher.END, cheer = true)
    }

    /** 時間になった: やさしく止める (待っている道具には「時間」と返す) */
    private fun timeUp() {
        cancelNext()
        finish()
        release(Reply.TimeUp)
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
        g.activity.add(teacher, TOOL, sum.sentence, ActivityLog.KIND_ACT, ActivityLog.OK)
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

    private fun reply(r: Reply.Tap) {
        val w = waiter
        if (w != null && w.isActive) { waiter = null; w.complete(r) } else pending = r
    }

    private fun release(r: Reply) {
        val w = waiter ?: return
        waiter = null
        w.complete(r)
    }

    private fun cancelNext() { nextJob?.cancel(); nextJob = null }

    private fun saveLevels(s: KidsSession) = s.levels().forEach { (t, lv) -> g.settings.setKidsLevel(t, lv) }

    private fun enabled(): List<Topic> = Topic.entries.filter { it in g.settings.kidsTopics }.ifEmpty { Topic.entries }

    private fun progress(): Progress {
        val s = session
        return Progress(s?.stars ?: 0, s?.asked ?: 0, s?.size ?: KidsSession.DEFAULT_SIZE, s?.levelOf(s.topic) ?: 1, minutesLeft())
    }

    private fun save() {
        g.settings.kidsUsedMs = timer.stored()
        g.settings.kidsUsedDay = timer.day
    }

    private fun limitMs(): Long = g.settings.kidsLimitMin.coerceAtLeast(1) * MINUTE_MS
    private fun now() = System.currentTimeMillis()
    private fun today() = LocalDate.now().toString()

    companion object {
        private const val TAG = "kids"
        /** したことに残すときの道具の名前 */
        const val TOOL = "kids_lesson"
        /** AI の先生が、子どもが選ぶのを待つ長さ */
        const val WAIT_MS = 30_000L
        /** 正しく選んでから、次の問題を出すまで (ほめる言葉を聞く間) */
        private const val NEXT_MS = 2_200L
        private const val MINUTE_MS = 60_000L
        /** 時間になったら止める場面 (おしまいの画面はそのまま) */
        private val ACTIVE = setOf(Mode.PICK, Mode.WAIT, Mode.ASK)
    }
}
