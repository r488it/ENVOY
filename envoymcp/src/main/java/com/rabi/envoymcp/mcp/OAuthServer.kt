package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.core.Provenance
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 外 (Claude のコネクター) からつなぐときの OAuth 2.1 (MCP の認可の決まり)。外の名前 (ngrok の決まったドメイン) で届いた依頼だけに答える
 * (McpServer が振り分ける。スマホの中の 127.0.0.1 では、ここの入口はどれも無い)。
 * - 見つけ方: 守る先の説明 (RFC 9728) と、認可サーバーの説明 (RFC 8414)
 * - 登録 (RFC 7591): 秘密を持たない呼び手だけ。戻り先は Claude の決まった 2 つだけ。覚えておく数に上限がある
 * - 認可: 本人がスマホで指紋などで許可したときだけ、1 回だけ使える 60 秒のコードを出す (PKCE の S256 が要る)。
 *   許可を待つ頼みは 1 つずつで、回数にも上限がある (知らない人がスマホに何度も確認を出せないように)
 * - 引き換え: コードと引き換えに、エージェント「Claude（コネクター）」を足し (権限は Agents.add の控えめな既定)、その合言葉を渡す。
 *   更新用の合言葉 (refresh token) は SHA-256 だけを残し、使うたびに新しくする
 * - 外から使えるのは、ここで渡した合言葉 (grants に残したエージェント) だけ。設定でエージェントを消すと、外からも使えず、更新も断る
 * コード・合言葉・更新用の合言葉は記録に残さない。比べるときは時間が一定の比べ方 (MessageDigest.isEqual) をする
 */
class OAuthServer(
    /** 外の名前 (例: xxx.ngrok-free.app)。空・名前として正しくないときは、外からの依頼を受けない */
    private val domain: () -> String,
    private val agents: Agents,
    private val store: Store = Memory(),
    /** 本人にスマホで許可を求める (許可したら true)。時間切れ (APPROVE_MS) はこちらで見る */
    private val approve: suspend () -> Boolean = { false },
    /** したこと (つないだ・許可しなかった) を残す先。秘密は入れない */
    private val activity: ActivityLog = ActivityLog(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 置き場所 (本物はアプリ専用の SharedPreferences。試験ではメモリ)。秘密そのものは置かない (ハッシュだけ) */
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    class Memory(private var text: String? = null) : Store {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    class Prefs(context: Context) : Store {
        private val p = context.getSharedPreferences("envoymcp_oauth", Context.MODE_PRIVATE)
        override fun read(): String? = p.getString("oauth", null)
        override fun write(text: String) = p.edit { putString("oauth", text) }
    }

    /** 登録した呼び手 (client_id と、許した戻り先) */
    private class Client(val id: String, val redirects: List<String>, val createdAt: Long)

    /** 外から使ってよいエージェント。更新用の合言葉と、渡した合言葉は SHA-256 (16 進) だけ */
    private class Grant(val agentId: String, val clientId: String, val refreshHash: String, val tokenHash: String, val createdAt: Long)

    /** 出したコード (メモリだけ。SHA-256 だけを持つ) */
    private class Code(val hash: String, val clientId: String, val redirect: String, val challenge: String, val issuedAt: Long)

    /**
     * 本人の許可を待っている頼み。同じ頼みが届き直したら (ブラウザの読み直しなど)、同じ答えを待つ
     * (いっしょに待つのは MAX_FOLLOWERS まで。サーバーの接続の口をふさがないように)
     */
    private class Ask(val key: String, val code: CompletableDeferred<String?>) {
        var followers = 0
    }

    private var clients: List<Client>
    private var grants: List<Grant>
    private val codes = ArrayList<Code>()
    private var asking: Ask? = null
    private val askLimit = Limit(MAX_ASKS, ASK_WINDOW_MS)
    private val registerLimit = Limit(MAX_REGISTERS, REGISTER_WINDOW_MS)
    private val random = SecureRandom()

    init {
        val o = runCatching { JSONObject(store.read() ?: "{}") }.getOrDefault(JSONObject())
        clients = o.optJSONArray("clients").objects().mapNotNull { c ->
            runCatching { Client(c.getString("id"), c.getJSONArray("redirect_uris").strings().filter { it in REDIRECTS }, c.optLong("created_at")) }.getOrNull()
        }.filter { it.redirects.isNotEmpty() }
        grants = o.optJSONArray("grants").objects().mapNotNull { g ->
            runCatching { Grant(g.getString("agent"), g.getString("client"), g.getString("refresh"), g.getString("token"), g.optLong("created_at")) }.getOrNull()
        }
    }

    /** 外の名前 (小文字・ポートなし)。ドメインとして正しいときだけ (127.0.0.1・localhost などは外の名前にしない) */
    fun host(): String? {
        val d = domain().trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        return d.takeIf { HOST.matches(it) }
    }

    /** 外の名前で届いた OAuth の入口に答える (OAuth の入口でなければ null) */
    internal suspend fun handle(req: HttpRequest, path: String): HttpResponse? {
        val base = "https://" + (host() ?: return null)
        return when (path) {
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp" ->
                if (req.method == "GET") HttpResponse.json(200, resourceMetadata(base)) else notAllowed("GET")
            "/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server/mcp" ->
                if (req.method == "GET") HttpResponse.json(200, serverMetadata(base)) else notAllowed("GET")
            "/register" -> if (req.method == "POST") register(req) else notAllowed("POST")
            "/authorize" -> if (req.method == "GET") authorize(req, base) else notAllowed("GET")
            "/token" -> if (req.method == "POST") token(req, base) else notAllowed("POST")
            else -> null
        }
    }

    /** 合言葉なし・違う合言葉で /mcp に来たときの 401 (どこで合言葉をもらえるかを WWW-Authenticate で知らせる) */
    internal fun unauthorized(invalid: Boolean = false): HttpResponse {
        val base = "https://" + host().orEmpty()
        val head = if (invalid) "Bearer error=\"invalid_token\", " else "Bearer "
        return HttpResponse(401, "Unauthorized", "text/plain; charset=utf-8", "unauthorized".toByteArray(),
            extra = listOf("WWW-Authenticate" to head + "resource_metadata=\"$base/.well-known/oauth-protected-resource\""))
    }

    /** 外から届いた Bearer の合言葉に合うエージェント (ここで渡した合言葉で、今もそのエージェントの合言葉のものだけ) */
    @Synchronized fun agentFor(bearer: String): Agent? {
        if (bearer.isEmpty()) return null
        val list = agents.list.value
        var found: Agent? = null
        // 見つかっても最後まで比べる (かかる時間で合言葉を当てられないように)
        for (g in grants) {
            val a = list.firstOrNull { it.id == g.agentId && !it.main && it.token.isNotEmpty() } ?: continue
            if (same(a.token, bearer) && same(sha(a.token), g.tokenHash)) found = a
        }
        return found
    }

    // ---------------------------------------------------------------- 見つけ方

    private fun resourceMetadata(base: String) = JSONObject()
        .put("resource", "$base/mcp")
        .put("authorization_servers", JSONArray().put(base))
        .put("bearer_methods_supported", JSONArray().put("header"))
        .put("resource_name", "ENVOY MCP")
        .put("envoy_provenance", Provenance.meta())

    private fun serverMetadata(base: String) = JSONObject()
        .put("issuer", base)
        .put("authorization_endpoint", "$base/authorize")
        .put("token_endpoint", "$base/token")
        .put("registration_endpoint", "$base/register")
        .put("response_types_supported", JSONArray().put("code"))
        .put("grant_types_supported", JSONArray().put("authorization_code").put("refresh_token"))
        .put("code_challenge_methods_supported", JSONArray().put("S256"))
        .put("token_endpoint_auth_methods_supported", JSONArray().put("none"))

    // ---------------------------------------------------------------- 登録 (RFC 7591)

    /**
     * 呼び手を登録する。戻り先は Claude の決まった 2 つだけ。秘密は出さない (token_endpoint_auth_method は none に決める)。
     * 呼び手が付けた名前は覚えず、スマホの確認にも出さない (知らない人が好きな名前を見せられないように)
     */
    private fun register(req: HttpRequest): HttpResponse {
        if (!synchronized(this) { registerLimit.take(now()) }) return oauthError(429, "invalid_client_metadata", "too many registrations")
        val o = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
            ?: return oauthError(400, "invalid_client_metadata", "body must be a JSON object")
        val uris = o.optJSONArray("redirect_uris")?.let { a -> (0 until a.length()).map { a.opt(it) as? String } }
        if (uris.isNullOrEmpty() || uris.size > REDIRECTS.size || uris.any { it == null || it !in REDIRECTS }) {
            return oauthError(400, "invalid_redirect_uri", "only the Claude callback is allowed")
        }
        val grantTypes = o.optJSONArray("grant_types")?.strings()
        if (grantTypes != null && (grantTypes.isEmpty() || grantTypes.any { it !in GRANT_TYPES })) {
            return oauthError(400, "invalid_client_metadata", "unsupported grant_types")
        }
        val responseTypes = o.optJSONArray("response_types")?.strings()
        if (responseTypes != null && responseTypes != listOf("code")) return oauthError(400, "invalid_client_metadata", "unsupported response_types")
        val c = Client("c-" + randomString(18), uris.filterNotNull().distinct(), now())
        synchronized(this) {
            // 上限を超えたら、外から使っていない古いものから忘れる (それでも多ければ古いものから)
            val used = grants.map { it.clientId }.toSet()
            var next = clients + c
            while (next.size > MAX_CLIENTS) {
                val drop = next.filter { it !== c }.minWith(compareBy<Client>({ it.id in used }, { it.createdAt }))
                next = next - drop
            }
            clients = next
            save()
        }
        val res = JSONObject().put("client_id", c.id).put("client_id_issued_at", c.createdAt / 1000)
            .put("redirect_uris", JSONArray(c.redirects)).put("grant_types", JSONArray(GRANT_TYPES))
            .put("response_types", JSONArray().put("code")).put("token_endpoint_auth_method", "none")
        return HttpResponse.json(201, res)
    }

    // ---------------------------------------------------------------- 認可

    /**
     * 認可。頼みを確かめてから、2 段で進める:
     * 1. ブラウザに「スマホで許可してください」を出し、同じ頼み (envoy_wait 付き) を開き直させる
     * 2. 開き直した頼みは、本人がスマホで許可するまで (APPROVE_MS まで) 返事を待たせ、許可したらコードを付けて Claude へ戻す (302)。
     *    許可しなかった・時間切れなら access_denied で戻す
     * 呼び手・戻り先が正しくないときは、戻さずにブラウザに一言だけ出す (知らない先へ飛ばさないように)
     */
    private suspend fun authorize(req: HttpRequest, base: String): HttpResponse {
        val q = form(req.path.substringAfter('?', "")) ?: return page(400, BAD_TEXT)
        val client = q["client_id"]?.let { id -> synchronized(this) { clients.firstOrNull { it.id == id } } } ?: return page(400, BAD_TEXT)
        val redirect = q["redirect_uri"]?.takeIf { it in client.redirects } ?: return page(400, BAD_TEXT)
        val state = q["state"]
        if (state != null && state.length > MAX_STATE) return page(400, BAD_TEXT)
        if (state.isNullOrEmpty()) return back(redirect, null, "error" to "invalid_request")
        fun fail(error: String) = back(redirect, state, "error" to error)
        if (q["response_type"] != "code") return fail("unsupported_response_type")
        val challenge = q["code_challenge"]
        if (q["code_challenge_method"] != "S256" || challenge == null || !CHALLENGE.matches(challenge)) return fail("invalid_request")
        if (!resourceOk(q["resource"], base)) return fail("invalid_target")

        if (q["envoy_wait"] == null) return waitPage(q)
        val key = sha(listOf(client.id, redirect, state, challenge).joinToString("\n"))
        val ask: Ask
        val mine: Boolean
        synchronized(this) {
            val cur = asking
            when {
                cur != null && cur.key == key && cur.followers < MAX_FOLLOWERS -> { cur.followers++; ask = cur; mine = false }
                cur != null -> return page(429, BUSY_TEXT)
                !askLimit.take(now()) -> return page(429, LATER_TEXT)
                else -> { ask = Ask(key, CompletableDeferred()); asking = ask; mine = true }
            }
        }
        val code = if (mine) decide(ask, client, redirect, challenge)
        else try { withTimeoutOrNull(APPROVE_MS) { ask.code.await() } } finally { synchronized(this) { ask.followers-- } }
        return if (code != null) back(redirect, state, "code" to code) else fail("access_denied")
    }

    /** 本人に許可を求め、許可されたらコードを出す (待っている同じ頼みにも同じコードを渡す)。許可されなければ null */
    private suspend fun decide(ask: Ask, client: Client, redirect: String, challenge: String): String? {
        try {
            val ok = withTimeoutOrNull(APPROVE_MS) { approve() }
            if (ok != true) {
                activity.add(ActivityLog.USER, TOOL, ActivitySentence.connector(false, if (ok == null) "時間切れ" else "許可しなかった"),
                    ActivityLog.KIND_ACT, ActivityLog.REFUSED, approved = false)
                ask.code.complete(null)
                return null
            }
            val code = randomString(32)
            synchronized(this) {
                codes.removeAll { now() - it.issuedAt >= CODE_MS }
                codes += Code(sha(code), client.id, redirect, challenge, now())
            }
            ask.code.complete(code)
            return code
        } finally {
            ask.code.complete(null)
            synchronized(this) { if (asking === ask) asking = null }
        }
    }

    // ---------------------------------------------------------------- 引き換え

    private fun token(req: HttpRequest, base: String): HttpResponse {
        val f = form(String(req.body, Charsets.UTF_8)) ?: return oauthError(400, "invalid_request", "bad form")
        return when (f["grant_type"]) {
            "authorization_code" -> exchange(f, base)
            "refresh_token" -> refresh(f)
            null -> oauthError(400, "invalid_request", "grant_type is required")
            else -> oauthError(400, "unsupported_grant_type", null)
        }
    }

    /**
     * コードを合言葉に引き換える。コードは見つかった時点で消す (1 回だけ。失敗しても使えなくなる)。
     * 60 秒以内・同じ呼び手・同じ戻り先・PKCE の S256 が合うときだけ、エージェントを足して、その合言葉と更新用の合言葉を渡す
     */
    @Synchronized private fun exchange(f: Map<String, String>, base: String): HttpResponse {
        val given = f["code"] ?: return oauthError(400, "invalid_request", "code is required")
        val clientId = f["client_id"] ?: return oauthError(400, "invalid_request", "client_id is required")
        val h = sha(given)
        var c: Code? = null
        for (x in codes) if (same(x.hash, h)) c = x
        if (c == null) return oauthError(400, "invalid_grant", null)
        codes.remove(c)
        if (now() - c.issuedAt >= CODE_MS) return oauthError(400, "invalid_grant", "code expired")
        if (!same(clientId, c.clientId) || f["redirect_uri"] != c.redirect) return oauthError(400, "invalid_grant", null)
        if (!pkceOk(f["code_verifier"].orEmpty(), c.challenge)) return oauthError(400, "invalid_grant", "PKCE verification failed")
        if (!resourceOk(f["resource"], base)) return oauthError(400, "invalid_target", null)

        val a = agents.add(now()).copy(name = AGENT_NAME)
        agents.update(a)
        val refresh = randomString(32)
        grants = live() + Grant(a.id, c.clientId, sha(refresh), sha(a.token), now())
        save()
        activity.add(ActivityLog.USER, TOOL, ActivitySentence.connector(true), ActivityLog.KIND_ACT, ActivityLog.OK, approved = true)
        return tokens(a.token, refresh)
    }

    /**
     * 更新用の合言葉で、エージェントの今の合言葉を渡す (更新用の合言葉は新しくし、前のものは使えなくする)。
     * エージェントが消えた・合言葉が変わったときは invalid_grant (外からはもう使えない)
     */
    @Synchronized private fun refresh(f: Map<String, String>): HttpResponse {
        val given = f["refresh_token"] ?: return oauthError(400, "invalid_request", "refresh_token is required")
        val clientId = f["client_id"] ?: return oauthError(400, "invalid_request", "client_id is required")
        val h = sha(given)
        var g: Grant? = null
        for (x in grants) if (same(x.refreshHash, h)) g = x
        if (g == null || !same(clientId, g.clientId)) return oauthError(400, "invalid_grant", null)
        val a = agents.get(g.agentId)
        if (a == null || a.main || a.token.isEmpty() || !same(sha(a.token), g.tokenHash)) {
            grants = grants.filter { it !== g }
            save()
            return oauthError(400, "invalid_grant", null)
        }
        val next = randomString(32)
        grants = live().map { if (it === g) Grant(it.agentId, it.clientId, sha(next), it.tokenHash, it.createdAt) else it }
        save()
        return tokens(a.token, next)
    }

    private fun tokens(access: String, refresh: String) = HttpResponse.json(200,
        JSONObject().put("access_token", access).put("token_type", "Bearer").put("refresh_token", refresh))

    /** 消えていないエージェントの分だけ (設定で消したエージェントの分は忘れる) */
    private fun live(): List<Grant> {
        val ids = agents.list.value.map { it.id }.toSet()
        return grants.filter { it.agentId in ids }
    }

    // ---------------------------------------------------------------- 小さな道具

    /** resource (RFC 8707) は、付いていれば、この MCP サーバー (https://<外の名前>/mcp) のときだけ */
    private fun resourceOk(r: String?, base: String) = r == null || r.trimEnd('/') in setOf(base, "$base/mcp")

    private fun waitPage(q: Map<String, String>): HttpResponse {
        // URLEncoder の結果は英数字と . - * _ + % だけなので、HTML の属性にそのまま入れられる
        val url = "/authorize?" + (q + ("envoy_wait" to "1")).entries.joinToString("&amp;") { "${enc(it.key)}=${enc(it.value)}" }
        return page(200, WAIT_TEXT, "<meta http-equiv=\"refresh\" content=\"1;url=$url\">")
    }

    /** Claude の戻り先へ返す (戻り先は登録で確かめた決まったものだけ。値は URL の形に直すので、ヘッダーに改行などは入らない) */
    private fun back(redirect: String, state: String?, vararg params: Pair<String, String>): HttpResponse {
        val all = params.toList() + listOfNotNull(state?.let { "state" to it })
        val url = redirect + (if ('?' in redirect) "&" else "?") + all.joinToString("&") { "${it.first}=${enc(it.second)}" }
        return HttpResponse(302, "Found", "text/html; charset=utf-8", html(BACK_TEXT, "").toByteArray(), extra = SAFE_HEADERS + ("Location" to url))
    }

    /** ブラウザに出す短い一言 (中身は決まった文だけ。頼みの値は入れない) */
    private fun page(status: Int, text: String, head: String = "") =
        HttpResponse(status, HttpResponse.reason(status), "text/html; charset=utf-8", html(text, head).toByteArray(), extra = SAFE_HEADERS)

    private fun html(text: String, head: String) =
        "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"referrer\" content=\"no-referrer\">$head<title>ENVOY MCP</title></head><body><h1>$text</h1></body></html>"

    private fun notAllowed(allow: String) = HttpResponse(405, "Method Not Allowed", extra = listOf("Allow" to allow))

    private fun oauthError(status: Int, error: String, description: String?) =
        HttpResponse.json(status, JSONObject().put("error", error).apply { if (description != null) put("error_description", description) })

    private fun save() {
        val o = JSONObject()
            .put("clients", JSONArray(clients.map { JSONObject().put("id", it.id).put("redirect_uris", JSONArray(it.redirects)).put("created_at", it.createdAt) }))
            .put("grants", JSONArray(grants.map {
                JSONObject().put("agent", it.agentId).put("client", it.clientId).put("refresh", it.refreshHash).put("token", it.tokenHash).put("created_at", it.createdAt)
            }))
        runCatching { store.write(o.toString()) }
    }

    private fun randomString(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** 決まった長さの間に、決まった回数まで (それより多ければ断る) */
    private class Limit(private val max: Int, private val windowMs: Long) {
        private val times = ArrayDeque<Long>()
        fun take(now: Long): Boolean {
            while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(now)
            return true
        }
    }

    companion object {
        /** 足すエージェントの名前 */
        const val AGENT_NAME = "Claude（コネクター）"
        /** したことの記録に使う印 */
        const val TOOL = "connect_claude"
        /** 登録で許す戻り先 (Claude のコネクターの戻り先だけ。完全に一致するものだけ) */
        val REDIRECTS = setOf("https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback")
        private val GRANT_TYPES = listOf("authorization_code", "refresh_token")
        /** 覚えておく呼び手の数 */
        const val MAX_CLIENTS = 20
        /** 登録の回数の上限 (1 分に 10 回まで) */
        private const val MAX_REGISTERS = 10
        private const val REGISTER_WINDOW_MS = 60_000L
        /** スマホに確認を出す回数の上限 (10 分に 3 回まで) */
        const val MAX_ASKS = 3
        const val ASK_WINDOW_MS = 10 * 60_000L
        /** 本人の許可を待つ長さと、コードの期限 */
        const val APPROVE_MS = 120_000L
        /** 同じ頼みが届き直したときに、いっしょに待たせる数 */
        private const val MAX_FOLLOWERS = 2
        const val CODE_MS = 60_000L
        private const val MAX_STATE = 512

        const val WAIT_TEXT = "スマホで許可してください"
        const val BUSY_TEXT = "ほかのつなぐ頼みを待っています。しばらくしてから、もう一度つないでください"
        const val LATER_TEXT = "つなぐ頼みが続いています。しばらくしてから、もう一度つないでください"
        const val BAD_TEXT = "つなぐ頼みが正しくありません。Claude から、もう一度つないでください"
        private const val BACK_TEXT = "Claude に戻ります"

        /** ブラウザに出す文の守り (外のものを読み込まない・枠に入れさせない・行き先に元の URL を渡さない) */
        private val SAFE_HEADERS = listOf(
            "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )

        /** ドメイン (英字の最上位を持つもの。IP アドレスと localhost は入らない) */
        private val HOST = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
        /** PKCE: S256 のチャレンジ (SHA-256 の base64url で 43 文字) と、ベリファイア (RFC 7636 の 43〜128 文字) */
        private val CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")
        private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

        /** PKCE の S256: base64url(SHA-256(ベリファイア)) がチャレンジと同じか (時間が一定の比べ方) */
        internal fun pkceOk(verifier: String, challenge: String): Boolean {
            if (!VERIFIER.matches(verifier)) return false
            val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return same(Base64.getUrlEncoder().withoutPadding().encodeToString(d), challenge)
        }

        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun sha(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** a=1&b=2 の形 (クエリ・フォーム) を読む。同じ名前が 2 回ある・形が壊れているときは null */
        internal fun form(s: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            for (part in s.split('&')) {
                if (part.isEmpty()) continue
                val k = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrNull() ?: return null
                val v = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return null
                if (out.put(k, v) != null) return null
            }
            return out
        }

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
    }
}
