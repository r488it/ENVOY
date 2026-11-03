package com.rabi.envoymcp.media

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.services.semanticretrieval.UniversalOnDeviceEmbedder
import com.rabi.envoymcp.McpGraph
import com.rabi.launcher.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * スマホの写真を言葉で探す (ChatGPT の音声モードで「〇〇の画像を探して X にポストして」「LINE で誰々に送って」など)。
 * - モデルは EmbeddingGemma 2 (740M。画像・文字を同じ空間のベクトルにする)。Pixel 10 (Tensor G5) は TPU 版、Tensor G6 はその版、
 *   ほかの端末は GPU・CPU の版。Hugging Face (litert-community/embeddinggemma-2-740m-litert-lm、Apache 2.0) から落とす
 * - 動かすのは Google AI Edge Gallery の UniversalOnDeviceEmbedder (Apache 2.0。そのまま取り込んだもの)。写真は長い辺を 1024 px にし、
 *   Edge Gallery と同じく TGA にして渡す。設定も同じ (文字は 256 トークンまで・画像は 70 トークン)
 * - 索引: 写真ごとに 768 次元のベクトルを、アプリの中のファイルに足していく (新しい写真から)。消えた写真は、索引を作るときに除く。
 *   探すときは、言葉のベクトルとの近さ (コサイン) の順に返す
 * - 写真も索引もスマホの中だけ。ChatGPT に返すのは、写真の番号・撮った日時・近さだけ (写真そのものは返さない)
 */
class PhotoSearch(private val g: McpGraph) {
    /** 端末に合うモデルのファイル (名前・大きさ・使う演算器) */
    data class Spec(val file: String, val size: Long, val accelerator: Accelerator, val label: String)

    data class Status(
        val modelReady: Boolean = false,
        val downloading: Boolean = false,
        val downloaded: Long = 0L,
        val indexing: Boolean = false,
        val indexed: Int = 0,
        val total: Int = -1,
        /** 最後に 1 枚にかかった時間 (ミリ秒。TPU で動いているかの目安) */
        val msPerPhoto: Long = 0L,
        val note: String = "",
    )

    data class Hit(val id: Long, val uri: Uri, val takenAt: Long, val score: Float, val folder: String, val mime: String)

    val spec: Spec = specFor(Build.SOC_MODEL)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val modelDir get() = File(g.context.getExternalFilesDir(null), "models").apply { mkdirs() }
    val modelFile: File get() = File(modelDir, spec.file)
    fun modelReady(): Boolean = modelFile.exists() && modelFile.length() == spec.size

    fun hasPermission(): Boolean = g.context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private val embedLock = Mutex()
    private var embedder: UniversalOnDeviceEmbedder? = null
    private var closeJob: Job? = null
    private var indexJob: Job? = null
    private var downloadJob: Job? = null
    private val index = PhotoIndex(File(g.context.filesDir, "photo_index.bin"), DIM, spec.size)

    init { refreshStatus() }

    fun refreshStatus() {
        _status.update { it.copy(modelReady = modelReady(), indexed = index.size) }
    }

    // ---------------------------------------------------------------- モデル

    /** モデルを Hugging Face から落とす (途中から続けられる)。終わったら索引を作り始める */
    fun download() {
        if (downloadJob?.isActive == true || modelReady()) return
        downloadJob = g.scope.launch(Dispatchers.IO) {
            val part = File(modelDir, spec.file + ".part")
            _status.update { it.copy(downloading = true, downloaded = part.length(), note = "") }
            try {
                val have = part.length()
                val c = URL(URL_BASE + spec.file).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code != 200 && code != 206) error("HTTP $code")
                val append = code == 206
                var got = if (append) have else 0L
                c.inputStream.use { ins ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (isActive) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (got - last > (4 shl 20)) { last = got; _status.update { it.copy(downloaded = got) } }
                        }
                    }
                }
                if (part.length() != spec.size) error("大きさが違います (${part.length()} / ${spec.size})")
                part.renameTo(modelFile)
                RabiLog.i("photos", "model downloaded (${spec.file})")
                _status.update { it.copy(downloading = false, modelReady = true, note = "モデルを入れました") }
                startIndexing()
            } catch (e: Exception) {
                RabiLog.w("photos", "download failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                _status.update { it.copy(downloading = false, note = "モデルを落とせませんでした (${e.javaClass.simpleName}。もう一度押すと続きから)") }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun deleteModel() {
        g.scope.launch(Dispatchers.IO) {
            embedLock.withLock { embedder?.close(); embedder = null }
            modelFile.delete(); File(modelDir, spec.file + ".part").delete()
            refreshStatus()
        }
    }

    /** 埋め込みを作るもの (はじめて使うときに作り、しばらく使わなければ閉じる。モデルは大きいので開いたままにしない) */
    private suspend fun <T> withEmbedder(block: (UniversalOnDeviceEmbedder) -> T): T? = embedLock.withLock {
        if (!modelReady()) return@withLock null
        closeJob?.cancel()
        val e = embedder ?: UniversalOnDeviceEmbedder(
            modelFile.path, spec.accelerator, g.context,
            maxInputSequenceLength = MAX_TEXT_TOKENS, visionTokenBudget = VISION_TOKENS, visionAccelerator = spec.accelerator,
        ).also { val t0 = System.currentTimeMillis(); it.initialize(); embedder = it; RabiLog.i("photos", "embedder ready (${spec.label}, ${System.currentTimeMillis() - t0} ms)") }
        val r = block(e)
        closeJob = g.scope.launch { delay(IDLE_CLOSE_MS); embedLock.withLock { embedder?.close(); embedder = null; RabiLog.i("photos", "embedder closed (idle)") } }
        r
    }

    // ---------------------------------------------------------------- 索引

    /** 写真の一覧 (新しい順。番号・撮った時刻・フォルダ・種類) */
    private data class Photo(val id: Long, val takenAt: Long, val folder: String, val mime: String)

    private fun listPhotos(): List<Photo> {
        val cr = g.context.contentResolver
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val out = ArrayList<Photo>()
        runCatching {
            cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    out += Photo(c.getLong(0), taken, c.getString(3).orEmpty(), c.getString(4) ?: "image/jpeg")
                }
            }
        }.onFailure { RabiLog.w("photos", "list failed: ${it.javaClass.simpleName}") }
        return out
    }

    /** 索引をまだ作っていない写真の索引を、裏で作る (もう作っていれば何もしない) */
    fun startIndexing() {
        if (indexJob?.isActive == true || !modelReady() || !hasPermission()) return
        indexJob = g.scope.launch(Dispatchers.Default) { indexNew(Long.MAX_VALUE) }
    }

    fun stopIndexing() { indexJob?.cancel() }

    /** 索引を作る (新しい写真から)。budgetMs を過ぎたら、続きは裏で作る。消えた写真は索引から除く */
    private suspend fun indexNew(budgetMs: Long) {
        val photos = listPhotos()
        val alive = photos.mapTo(HashSet()) { it.id }
        index.retain(alive)
        val todo = photos.filter { !index.has(it.id) }
        _status.update { it.copy(indexing = todo.isNotEmpty(), total = photos.size, indexed = index.size) }
        if (todo.isEmpty()) return
        RabiLog.i("photos", "indexing ${todo.size} photos")
        val t0 = System.currentTimeMillis()
        val power = g.context.getSystemService(PowerManager::class.java)
        try {
            for (p in todo) {
                if (System.currentTimeMillis() - t0 > budgetMs) break
                // 熱いときは休む (TPU でも、続けて回すと温まる)
                while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                    _status.update { it.copy(note = "スマホが熱いので、索引を作るのを休んでいます") }
                    delay(HOT_WAIT_MS)
                }
                val t1 = System.currentTimeMillis()
                val vec = embedPhoto(p.id) ?: continue
                index.add(p.id, p.takenAt, vec)
                _status.update { it.copy(indexed = index.size, msPerPhoto = System.currentTimeMillis() - t1, note = "") }
            }
        } finally {
            index.flush()
            _status.update { it.copy(indexing = false, indexed = index.size) }
            RabiLog.i("photos", "indexed ${index.size}/${photos.size}")
        }
    }

    /** 写真 1 枚の埋め込み (長い辺 1024 px にして、TGA で渡す。Edge Gallery と同じ) */
    private suspend fun embedPhoto(id: Long): FloatArray? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(g.context.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val s = TARGET_PX.toFloat() / maxOf(w, h)
                if (s < 1f) d.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null.also { RabiLog.w("photos", "decode failed") }
        val tga = ImageUtils.encodeTga(bmp.copy(Bitmap.Config.ARGB_8888, false))
        return withEmbedder { it.generateImageEmbedding(tga) }?.let(::normalize)
    }

    // ---------------------------------------------------------------- 探す

    /**
     * 言葉で探す。まだ索引の無い新しい写真があれば、先に (INDEX_BUDGET_MS まで) 索引を作る。after・before は撮った時刻 (ミリ秒) の範囲。
     * 近い順に limit 枚 (消えた写真は除く)
     */
    suspend fun search(query: String, limit: Int, after: Long? = null, before: Long? = null): List<Hit> = withContext(Dispatchers.Default) {
        if (indexJob?.isActive != true) indexNew(INDEX_BUDGET_MS) else delay(0)
        if (indexJob?.isActive != true && _status.value.total > index.size) startIndexing()
        val q = withEmbedder { it.generateTextEmbedding(query) }?.let(::normalize) ?: return@withContext emptyList()
        val photos = listPhotos().associateBy { it.id }
        index.top(q, limit * 3) { id, at -> photos.containsKey(id) && (after == null || at >= after) && (before == null || at < before) }
            .take(limit)
            .map { (id, score) ->
                val p = photos.getValue(id)
                Hit(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), p.takenAt, score, p.folder, p.mime)
            }
    }

    /** 番号の写真 (共有するとき)。無ければ null */
    fun photo(id: Long): Hit? = listPhotos().firstOrNull { it.id == id }?.let {
        Hit(it.id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id), it.takenAt, 0f, it.folder, it.mime)
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    companion object {
        const val DIM = 768
        private const val URL_BASE = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/"
        private const val MAX_TEXT_TOKENS = 256
        private const val VISION_TOKENS = 70
        private const val TARGET_PX = 1024
        /** 探すときに、先に索引を作る最長 (残りは裏で) */
        private const val INDEX_BUDGET_MS = 20_000L
        /** 使わなくなってからモデルを閉じるまで */
        private const val IDLE_CLOSE_MS = 5 * 60_000L
        private const val HOT_WAIT_MS = 30_000L

        /** 端末のチップに合うモデル (Edge Gallery の一覧と同じ。Tensor G5・G6 は TPU 版) */
        fun specFor(soc: String?): Spec = when {
            soc?.lowercase()?.contains("tensor g5") == true ->
                Spec("embeddinggemma-2-740m_Google_Tensor_G5.litertlm", 657_656_500L, Accelerator.TPU, "Tensor G5 の TPU")
            soc?.lowercase()?.contains("tensor g6") == true ->
                Spec("embeddinggemma-2-740m_Google_Tensor_G6.litertlm", 615_713_460L, Accelerator.TPU, "Tensor G6 の TPU")
            else -> Spec("embeddinggemma-2-740m.litertlm", 484_622_336L, Accelerator.GPU, "GPU")
        }
    }
}

/**
 * 写真の索引 (番号・撮った時刻・ベクトル) をファイルに持つ。頭に印・次元・モデルの大きさ (モデルが変わったら作り直す)。
 * 足すときはファイルの後ろに書き足し、消えた写真を除くときは書き直す
 */
class PhotoIndex(private val file: File, private val dim: Int, private val model: Long) {
    private val ids = ArrayList<Long>()
    private val dates = ArrayList<Long>()
    private val vecs = ArrayList<FloatArray>()
    private val known = HashSet<Long>()
    private var pending = ArrayList<Int>()

    val size get() = synchronized(this) { ids.size }

    init { load() }

    @Synchronized fun has(id: Long) = id in known

    @Synchronized fun add(id: Long, takenAt: Long, v: FloatArray) {
        if (v.size != dim || id in known) return
        ids += id; dates += takenAt; vecs += v; known += id
        pending += ids.size - 1
        if (pending.size >= FLUSH_EVERY) flush()
    }

    /** 書き足していないものをファイルに書く */
    @Synchronized fun flush() {
        if (pending.isEmpty()) return
        val fresh = !file.exists() || file.length() == 0L
        DataOutputStream(FileOutputStream(file, !fresh).buffered()).use { o ->
            if (fresh) { o.writeInt(MAGIC); o.writeInt(dim); o.writeLong(model) }
            for (i in if (fresh) ids.indices.toList() else pending) {
                o.writeLong(ids[i]); o.writeLong(dates[i]); for (x in vecs[i]) o.writeFloat(x)
            }
        }
        pending = ArrayList()
    }

    /** 今ある写真だけを残す (消えた写真を除く) */
    @Synchronized fun retain(alive: Set<Long>) {
        if (ids.all { it in alive }) return
        val keep = ids.indices.filter { ids[it] in alive }
        val ni = keep.map { ids[it] }; val nd = keep.map { dates[it] }; val nv = keep.map { vecs[it] }
        ids.clear(); dates.clear(); vecs.clear(); known.clear()
        ids += ni; dates += nd; vecs += nv; known += ni
        file.delete(); pending = ArrayList(ids.indices.toList()); flush()
    }

    /** q に近い順 (内積。どちらも長さ 1 にしてある) に n 件。ok で絞る */
    @Synchronized fun top(q: FloatArray, n: Int, ok: (Long, Long) -> Boolean): List<Pair<Long, Float>> {
        val scored = ArrayList<Pair<Long, Float>>(ids.size)
        for (i in ids.indices) {
            if (!ok(ids[i], dates[i])) continue
            val v = vecs[i]; var s = 0f
            for (k in 0 until dim) s += v[k] * q[k]
            scored += ids[i] to s
        }
        return scored.sortedByDescending { it.second }.take(n)
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != dim || inp.readLong() != model) { file.delete(); return }
                val rec = 16L + dim * 4L
                val count = ((file.length() - 16) / rec).toInt()
                repeat(count) {
                    val id = inp.readLong(); val at = inp.readLong()
                    val v = FloatArray(dim) { inp.readFloat() }
                    if (id !in known) { ids += id; dates += at; vecs += v; known += id }
                }
            }
        }.onFailure { RabiLog.w("photos", "index load failed: ${it.javaClass.simpleName}"); ids.clear(); dates.clear(); vecs.clear(); known.clear(); file.delete() }
    }

    private companion object {
        const val MAGIC = 0x45504931     // "EPI1"
        const val FLUSH_EVERY = 20
    }
}
