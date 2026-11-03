package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.core.Text
import java.text.Normalizer

/**
 * 「〇〇を開いて」のアプリの名前から、パッケージ名を選ぶ (Android に頼らない。テストできるように分けてある)。
 * いちばんよく合うものを選び、同じくらい合うものが複数あるときだけ「複数あります」にする。
 * Pixel では YouTube Music のアプリの名前が「YT Music」なので、名前の一部が合うもの (頼んだ言葉の中に「YouTube」が入っている)
 * だけで探すと「YouTube Music」で YouTube が当たる。そのため、パッケージ名の中の言葉 (com.google.android.apps.youtube.music) と、
 * よく使う呼び名も見る
 */
internal object AppMatcher {
    sealed class Result {
        data class Found(val pkg: String) : Result()
        object NotFound : Result()
        data class Many(val apps: List<Pair<String, String>>) : Result()
    }

    /** よく使う呼び名 (アプリの名前と違うもの) → パッケージ名 */
    private val ALIASES: Map<String, String> = mapOf(
        "YouTube Music" to "com.google.android.apps.youtube.music",
        "ユーチューブミュージック" to "com.google.android.apps.youtube.music",
        "YTミュージック" to "com.google.android.apps.youtube.music",
        "YouTubeミュージック" to "com.google.android.apps.youtube.music",
        "ユーチューブ" to "com.google.android.youtube",
        "スポティファイ" to "com.spotify.music",
    ).mapKeys { Text.norm(it.key) }

    /** アプリの名前のあとに付いていてもよい言葉 (「LINE アプリ」「Chrome ブラウザ」) */
    private val GENERIC: Set<String> = setOf("", "アプリ", "app", "アプリケーション", "application", "ブラウザ").map { Text.norm(it) }.toSet()

    /** apps: (パッケージ名, アプリの名前) */
    fun resolve(query: String, apps: List<Pair<String, String>>): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result.NotFound
        val scored = apps.map { it to score(q, it.first, it.second) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return Result.NotFound
        val top = scored.filter { it.second == best }.map { it.first }.distinctBy { it.first }
        return if (top.size == 1) Result.Found(top[0].first) else Result.Many(top)
    }

    private fun score(q: String, pkg: String, label: String): Int {
        val nq = Text.norm(q)
        val nl = Text.norm(label)
        return when {
            pkg == q -> 1000
            ALIASES[nq] == pkg -> 900
            nl == nq -> 800
            tokensIn(q, label, pkg) -> 600 + words(q).size
            nl.contains(nq) -> 400
            // 頼んだ言葉の中にアプリの名前がある (弱い。長い名前ほど優先)。残りが「アプリ」などのときだけ
            // (「Google フォト」でフォトが一覧に無いとき、Google を開かないように)
            nq.contains(nl) && nl.length >= 2 && nq.replace(nl, "").trim() in GENERIC -> 200 + nl.length
            else -> 0
        }
    }

    /** 頼んだ言葉の単語が、全部アプリの名前かパッケージ名の中にあるか */
    private fun tokensIn(q: String, label: String, pkg: String): Boolean {
        val qs = words(q)
        if (qs.isEmpty()) return false
        val have = words(label) + pkg.lowercase().split('.', '_')
        return qs.all { it in have }
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.toSet()
}
