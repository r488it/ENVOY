package com.rabi.envoymcp.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.rabi.launcher.core.RabiLog
import com.rabi.launcher.llm.AiException
import com.rabi.launcher.llm.ChatGptAuth
import com.rabi.launcher.llm.OpenAiClient
import com.rabi.launcher.observe.Bounds
import com.rabi.launcher.observe.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 画面の画像を、ENVOY MCP の中で AI (Sign in with ChatGPT のモデル) に読ませる。
 * ChatGPT のアプリは、MCP の道具が返した画像をモデルに見せない (OpenAI の回答) ので、
 * ここで画像を文章 (画面の説明と、押せそうなものの名前・位置) にしてから ChatGPT に返す。
 * - 画像には部品の枠と番号を書き込み、部品の一覧と一緒に渡す (部品の一覧にあるものは番号で答えさせる。位置が正確になる)
 * - 部品の一覧に無いもの (アイコンだけのボタン・Web やゲームの画面・ユーザー補助に見えない部品) は位置で答えさせる
 * - 送るのは、本人が「画面の画像を ChatGPT に渡す」をオンにしているときだけ (呼ぶ側で確かめる)
 */
class ScreenVision(private val auth: ChatGptAuth, private val client: OpenAiClient = OpenAiClient()) {
    /** 押せそうなもの。bounds は画面のピクセル */
    data class Target(val visualId: String, val label: String, val kind: String, val elementId: Int?, val bounds: Bounds, val commit: Boolean)
    data class Result(val summary: String, val targets: List<Target>)

    class VisionException(message: String) : Exception(message)

    /** 考える深さ (reasoning.effort) を浅くして送るか (対応していないモデルなら、外して以後は付けない) */
    @Volatile private var lowEffort = true

    suspend fun look(o: Observation): Result {
        val png = o.screenshotPng ?: throw VisionException("画面の画像がありません")
        val model = auth.status.value.selectedModel ?: throw VisionException("ChatGPT のモデルが選ばれていません (ENVOY MCP の設定で選んでください)")
        val (img, iw, ih) = withContext(Dispatchers.Default) { mark(png, o) }
        val sw = o.screenW.takeIf { it > 0 } ?: iw
        val sh = o.screenH.takeIf { it > 0 } ?: ih
        val text = "画像の大きさ: ${iw}x$ih ピクセル\n部品の一覧 (番号 id、位置 b は画面のピクセル。画像には枠と番号を書き込んである):\n" + o.toModelJson(MAX_ELEMENTS)
        val user = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", text))
            .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP))))
        val body = JSONObject().put("model", model).put("instructions", INSTRUCTIONS).put("input", JSONArray().put(user))
            .put("store", false).put("stream", true)
        // 画面の説明と位置を答えるだけなので、考える深さは浅くてよい (深いと 1 回 15 秒ほどかかる)
        if (lowEffort) body.put("reasoning", JSONObject().put("effort", EFFORT))
        val token = auth.accessToken()
        val t0 = System.currentTimeMillis()
        val out = try {
            withContext(Dispatchers.IO) { client.respond(token, body, null).second }
        } catch (e: AiException) {
            // 考える深さの指定に対応していないモデル: 外して呼び直し、次からも付けない
            val m = e.message.orEmpty()
            if (!lowEffort || !(m.contains("reasoning", ignoreCase = true) || m.contains("effort", ignoreCase = true))) throw e
            lowEffort = false
            body.remove("reasoning")
            withContext(Dispatchers.IO) { client.respond(token, body, null).second }
        }
        RabiLog.i("vision", "model ${System.currentTimeMillis() - t0}ms (effort ${if (lowEffort) EFFORT else "none"})")
        return parseVision(out, iw, ih, sw, sh)
    }

    /** 画像に部品の枠と番号を書き込み、JPEG にする。(JPEG, 幅, 高さ) */
    private fun mark(png: ByteArray, o: Observation): Triple<ByteArray, Int, Int> {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw VisionException("画面の画像を読めません")
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val k = bmp.width / (o.screenW.takeIf { it > 0 } ?: bmp.width).toFloat()
        val c = Canvas(bmp)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(220, 255, 64, 160) }
        val tag = Paint().apply { color = Color.argb(220, 255, 64, 160) }
        val num = Paint().apply { color = Color.WHITE; textSize = 14f; isAntiAlias = true; isFakeBoldText = true }
        o.interesting().filter { it.clickable || it.editable || it.text.isNotBlank() || it.desc.isNotBlank() }.take(MAX_ELEMENTS).forEach { n ->
            val l = n.bounds.l * k; val t = n.bounds.t * k; val r = n.bounds.r * k; val b = n.bounds.b * k
            c.drawRect(l, t, r, b, box)
            val s = n.index.toString()
            val w = num.measureText(s) + 6f
            c.drawRect(l, t, l + w, t + 17f, tag)
            c.drawText(s, l + 3f, t + 14f, num)
        }
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return Triple(out, bmp.width, bmp.height)
    }

    companion object {
        private const val MAX_ELEMENTS = 80
        private const val EFFORT = "low"
        private const val MAX_TARGETS = 30
        private val INSTRUCTIONS = """
            あなたはスマホの画面を読む係です。画面の画像と、画面の部品の一覧を受け取ります。画像には、部品の枠と番号 (部品の一覧の id) を書き込んであります。
            次の形の JSON だけを返してください (前後に文章を付けない):
            {"summary": "画面の説明 (日本語・200 文字以内。どのアプリのどんな画面か、何が表示されているか、何ができるか)",
             "targets": [{"label": "押せるもの・入力欄の名前 (日本語。見た目から分かる名前)", "kind": "button|icon|switch|field|link|tab|item|other",
                          "element_id": 枠と番号がある部品ならその番号、無ければ null, "box": [左, 上, 右, 下] (画像のピクセル), "commit": 送信・投稿・購入・削除・発信・公開・確定の操作なら true}]}
            - targets には、押せそうなもの・入力欄を、画面の上から順に 30 個まで入れる。特に、名前の無いもの (アイコンだけのボタンなど) と、枠が無いのに画面に見えるもの (Web やゲームの中のボタンなど) は必ず入れる
            - box は、そのもの自体の外枠。枠と番号がある部品は、その枠に合わせる
            - スイッチ (オン・オフ) は kind を switch にし、label にオンかオフかも書く
            - 画面に無いものを作らない。分からないものは入れない
        """.trimIndent()
    }
}

/** モデルの答え (JSON) を読み、位置を画面のピクセルに直す */
internal fun parseVision(out: String, iw: Int, ih: Int, sw: Int, sh: Int): ScreenVision.Result {
    val start = out.indexOf('{'); val end = out.lastIndexOf('}')
    if (start < 0 || end <= start) throw ScreenVision.VisionException("画面の説明を読み取れませんでした")
    val j = runCatching { JSONObject(out.substring(start, end + 1)) }.getOrElse { throw ScreenVision.VisionException("画面の説明を読み取れませんでした") }
    val fx = sw / iw.toFloat(); val fy = sh / ih.toFloat()
    val arr = j.optJSONArray("targets") ?: JSONArray()
    val targets = (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        val b = t.optJSONArray("box") ?: return@mapNotNull null
        if (b.length() != 4) return@mapNotNull null
        val l = (b.optDouble(0) * fx).toInt().coerceIn(0, sw); val tp = (b.optDouble(1) * fy).toInt().coerceIn(0, sh)
        val r = (b.optDouble(2) * fx).toInt().coerceIn(0, sw); val bt = (b.optDouble(3) * fy).toInt().coerceIn(0, sh)
        if (r <= l || bt <= tp) return@mapNotNull null
        ScreenVision.Target("v${i + 1}", t.optString("label").take(60), t.optString("kind", "other"),
            t.optInt("element_id", -1).takeIf { t.has("element_id") && !t.isNull("element_id") && it >= 0 },
            Bounds(l, tp, r, bt), t.optBoolean("commit", false))
    }.take(30)
    return ScreenVision.Result(j.optString("summary").take(400), targets)
}
