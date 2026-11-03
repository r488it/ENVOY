package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写真の道具: 言葉で探す (端末の中の EmbeddingGemma 2)・大きく見せる・アプリの共有に添えて開く */
internal class PhotoTools(st: ToolState, private val apps: AppTools) : ToolGroup(st) {
    /** 写真を言葉で探す (端末の中の EmbeddingGemma 2)。写真そのものは返さない */
    suspend fun searchPhotos(a: JSONObject): ToolResult {
        val q = a.optString("query").trim()
        if (q.isEmpty()) return failed("探す写真の説明 (query) を入れてください。")
        val ps = g.photos
        if (!ps.hasPermission()) return failed("写真へのアクセスが許可されていません。利用者に、スマホの ENVOY MCP の設定の「写真を探す」で許可してもらってください。")
        if (!ps.modelReady()) return failed("写真を探す AI (EmbeddingGemma 2) がまだ入っていません。利用者に、スマホの ENVOY MCP の設定の「写真を探す」でモデルを入れてもらってください (${ps.spec.size / 1_000_000} MB)。")
        fun day(k: String): Long? = a.optString(k).trim().takeIf { it.isNotEmpty() }?.let { runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).parse(it)?.time }.getOrNull() }
        val limit = a.optInt("limit", 5).coerceIn(1, 10)
        val hits = runCatching { ps.search(q, limit, day("taken_after"), day("taken_before")) }
            .getOrElse { return failed("写真を探せませんでした (${it.javaClass.simpleName})。") }
        val st = ps.status.value
        val idx = "索引 ${st.indexed}/${if (st.total >= 0) st.total else "?"} 枚" + if (st.indexing) " (残りは作っているところ)" else ""
        if (hits.isEmpty()) return ok("「$q」に近い写真は見つかりませんでした ($idx)。", JSONObject().put("photos", JSONArray()))
        val fmt = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN)
        val top = hits.first().score
        val arr = JSONArray()
        val lines = hits.mapIndexed { n, h ->
            val weak = h.score < PHOTO_WEAK || h.score < top - PHOTO_GAP
            arr.put(JSONObject().put("photo_id", h.id).put("taken", fmt.format(Date(h.takenAt))).put("folder", h.folder)
                .put("score", Math.round(h.score * 100) / 100.0).put("weak", weak))
            "${n + 1}. photo_id ${h.id}: ${fmt.format(Date(h.takenAt))} 撮影 (${h.folder.ifBlank { "フォルダ不明" }})・近さ ${"%.2f".format(h.score)}" + if (weak) " (違うかもしれない)" else ""
        }
        RabiLog.i("photos", "search: ${hits.size} hits (top ${"%.2f".format(top)})")
        g.photoStrip.show(q, hits)      // 画面の下に、同じ番号で並べる
        return ok("「$q」に近い写真 (近い順。$idx):\n" + lines.joinToString("\n") +
            "\n画面の下に、同じ番号で写真を並べました (写真の中身は返していません)。共有するときは share_photo に photo_id を渡してください。", JSONObject().put("photos", arr))
    }

    /** 見つけた写真を、画面いっぱいに大きく出す (ChatGPT には渡さない) */
    suspend fun showPhoto(a: JSONObject): ToolResult {
        val id = a.optLong("photo_id", -1L)
        if (id < 0) return failed("photo_id を入れてください (search_photos が返した番号)。")
        val p = withContext(Dispatchers.IO) { g.photos.photo(id) } ?: return failed("photo_id $id の写真が見つかりません (消えたか、番号が違います)。")
        val n = g.photoStrip.enlarge(p)
        val when_ = SimpleDateFormat("M月d日", Locale.JAPAN).format(Date(p.takenAt))
        return ok((if (n != null) "$n 番の写真" else "写真") + " ($when_ 撮影) を、スマホの画面に大きく出しました。利用者が画面を押すと、並べた写真に戻ります。")
    }

    /** 見つけた写真を、アプリの共有の画面に添えて開く */
    suspend fun sharePhoto(a: JSONObject): ToolResult {
        val id = a.optLong("photo_id", -1L)
        if (id < 0) return failed("photo_id を入れてください (search_photos が返した番号)。")
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val p = withContext(Dispatchers.IO) { g.photos.photo(id) } ?: return failed("photo_id $id の写真が見つかりません (消えたか、番号が違います)。")
        val when_ = SimpleDateFormat("M月d日", Locale.JAPAN).format(Date(p.takenAt))
        g.photoStrip.hide()             // 共有の画面を見せる (並べた写真を消す)
        return apps.shareImage(p.uri, p.mime, pkg, a.optString("text").trim(), a.optString("target", "post"), "写真 ($when_ 撮影) を添えて、")
    }

    companion object {
        /** これより近さが低い・いちばん近いものからこれ以上離れた写真は「違うかもしれない」(Edge Gallery の薄く出す基準と同じ) */
        private const val PHOTO_WEAK = 0.40f
        private const val PHOTO_GAP = 0.20f
    }
}
