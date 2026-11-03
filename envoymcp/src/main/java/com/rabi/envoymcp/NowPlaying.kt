package com.rabi.envoymcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.rabi.launcher.core.RabiLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.zip.CRC32

/**
 * 「通知へのアクセス」の受け口。
 * - Android は、他のアプリの再生 (MediaSession) を読めるのを、通知へのアクセスを許可したアプリだけにしているため、そのために置く
 * - かかってきた電話 (電話・LINE など) の通知から、どのアプリか・かけてきた人・「応答」を読む (IncomingCall)。
 *   それ以外の通知は読まない
 */
class RigelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, false) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (isCall(sbn)) onCallNotice?.invoke(sbn, true) }
    private fun isCall(sbn: StatusBarNotification) =
        sbn.notification.category == Notification.CATEGORY_CALL || sbn.notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) != 0
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** 着信の通知 (無ければ null) */
    fun incomingCall(): IncomingNotice? = runCatching { activeNotifications }.getOrNull().orEmpty()
        .firstNotNullOfOrNull { IncomingNotice.of(it) }

    companion object {
        @Volatile var instance: RigelNotificationListener? = null
        /** 通話の通知が出た・変わった (removed = false)、消えた (removed = true)。IncomingCall が受ける (メインスレッド) */
        @Volatile var onCallNotice: ((StatusBarNotification, Boolean) -> Unit)? = null
    }
}

/** 着信の通知: アプリ・かけてきた人の名前 (番号のこともある)・「応答」 (分からなければ null) */
data class IncomingNotice(val pkg: String, val name: String, val answer: PendingIntent?) {
    companion object {
        private val ANSWER_WORDS = listOf("応答", "Answer", "Accept")

        /** 着信の通知なら読む (通話の通知で、着信の種類か「応答」があるもの。発信中・通話中の通知は除く) */
        fun of(sbn: StatusBarNotification): IncomingNotice? {
            val n = sbn.notification
            val x = n.extras
            val callType = x.getInt(Notification.EXTRA_CALL_TYPE, 0)
            if (n.category != Notification.CATEGORY_CALL && callType == 0) return null
            if (callType != 0 && callType != Notification.CallStyle.CALL_TYPE_INCOMING) return null
            val answer = parcel(x, Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                ?: n.actions?.firstOrNull { a -> ANSWER_WORDS.any { w -> a.title?.toString()?.contains(w) == true } }?.actionIntent
            if (callType == 0 && answer == null) return null
            val person = parcel(x, Notification.EXTRA_CALL_PERSON, Person::class.java)
            val name = person?.name?.toString() ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            return IncomingNotice(sbn.packageName, name.trim(), answer)
        }

        private fun <T> parcel(b: Bundle, key: String, cls: Class<T>): T? =
            if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, cls)
            else @Suppress("DEPRECATION") cls.cast(b.getParcelable(key))
    }
}

/** 再生中の曲 (YouTube Music・Spotify など) */
/** durationMs: 曲の長さ (分からなければ 0。歌詞を探すのに使う)。paused: 一時停止・停止 (読み込み中や次の曲へ移る途中は false) */
data class Track(val pkg: String, val app: String, val title: String, val artist: String, val playing: Boolean, val album: String = "", val durationMs: Long = 0, val paused: Boolean = false) {
    /** 曲ごとの踊りの種 (曲名とアーティストから。同じ曲ならいつも同じ) */
    val seed: Int get() = CRC32().apply { update("$title|$artist".toByteArray()) }.value.toInt() and 0x7fffffff
    val key: String get() = "$title|$artist"
    val label: String get() = if (artist.isBlank()) title else "$title — $artist"

    fun toJson(): JSONObject = JSONObject().put("app", app).put("title", title).put("artist", artist).put("playing", playing)
}

/**
 * 再生中の曲を見張る (本人が「通知へのアクセス」を許可したときだけ。曲名・アーティスト・再生か止まっているか)。
 * 音楽の画面に曲名を出し、AgentARM が曲ごとの踊りを選ぶのに使う。メインスレッドで使う
 */
class NowPlaying(private val context: Context) {
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    private val component = ComponentName(context, RigelNotificationListener::class.java)
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var started = false
    /** 選んでいる曲の、最後に届いた再生の様子 (再生位置を出すのに使う。毎フレーム聞きに行かないよう、ここに持つ) */
    @Volatile private var playback: PlaybackState? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = update()
    }

    /** 通知へのアクセスが許可されているか */
    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 見張りを始める (許可が無ければ何もしない。許可が変わったら呼び直す) */
    fun refresh() {
        if (!hasAccess()) { stop(); return }
        if (started) return
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            watch(msm.getActiveSessions(component))
            started = true
            RabiLog.i("music", "now playing: watching")
        }.onFailure { RabiLog.w("music", "cannot watch sessions: ${it.javaClass.simpleName}") }
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        started = false
        playback = null
        _track.value = null
    }

    /**
     * 今の再生位置 (曲の頭からのミリ秒。歌詞を合わせるのに使う)。分からなければ null。
     * 再生中は、最後に届いた位置に、そこからの経過 (再生の速さを掛けて) を足す
     */
    fun positionMs(): Long? {
        val s = playback ?: return null
        if (s.position < 0) return null
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    private fun watch(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        list.forEach { runCatching { it.registerCallback(callback, handler) } }
        update()
    }

    /** 再生中のものを選ぶ (無ければ、曲名のある最初のもの) */
    private fun update() {
        val withTitle = controllers.mapNotNull { c ->
            trackOf(c)?.let { it to c }
        }
        val pick = withTitle.firstOrNull { it.first.playing } ?: withTitle.firstOrNull()
        playback = pick?.second?.playbackState
        val t = pick?.first
        if (t != _track.value) _track.value = t
    }

    /** 1 つの再生の、曲名・アーティストなど (曲名が無ければ null) */
    private fun trackOf(c: MediaController): Track? {
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isBlank()) return null
        val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        val state = c.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING
        val paused = state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR
        val app = runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() } }.getOrDefault(c.packageName)
        val album = m.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        return Track(c.packageName, app, title.take(120), artist.take(80), playing, album.take(120), dur, paused)
    }
}

/**
 * 曲ごとに覚えた踊り方 (本人や ChatGPT が「この曲は party で」と選んだもの)。この端末の中だけ
 */
class SongDances(context: Context) {
    data class Pref(val style: String, val amp: Double)

    private val p = context.getSharedPreferences("envoymcp_song_dances", Context.MODE_PRIVATE)

    fun get(t: Track?): Pref? {
        val s = t?.let { p.getString(it.key, null) } ?: return null
        return runCatching { JSONObject(s).let { Pref(it.getString("style"), it.getDouble("amp")) } }.getOrNull()
    }

    fun put(t: Track, pref: Pref) {
        p.edit().putString(t.key, JSONObject().put("style", pref.style).put("amp", pref.amp).toString()).apply()
    }

    fun count(): Int = p.all.size
}
