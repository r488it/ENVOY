package com.rabi.launcher.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

/**
 * ChatGPT 接続状態。「ログイン」と「ChatGPT プランの利用許可」と「実際に接続できたか」を分けて持つ。
 * 接続済みと表示するのは verified（このトークンでモデル一覧の取得に成功）の時だけ。
 */
data class AiStatus(
    val signedIn: Boolean = false,
    val email: String? = null,
    val planAllowed: Boolean = false,
    val verified: Boolean = false,
    val verifiedAt: Long = 0,
    val busy: String? = null,
    val error: String? = null,
    val needsReauth: Boolean = false,
    val usageLimited: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val selectedModel: String? = null,
) {
    val label: String
        get() = when {
            busy != null -> busy
            !signedIn && needsReauth -> "ChatGPT 再ログインが必要"
            !signedIn -> "ChatGPT 未接続"
            !planAllowed -> "ログイン済み・プラン利用は未許可"
            usageLimited -> "ChatGPT 利用枠の上限"
            verified -> "ChatGPT 接続済み"
            else -> "ログイン済み・接続未確認"
        }
    val connected get() = signedIn && planAllowed && verified && !usageLimited

    /** 画面表示用。アカウント名（メールアドレス）は平文で出さない。 */
    val maskedAccount: String?
        get() = email?.let { mask(it) }

    companion object {
        fun mask(email: String): String {
            val local = email.substringBefore('@'); val domain = email.substringAfter('@', "")
            val l = if (local.length <= 2) "•".repeat(local.length) else local.first() + "•".repeat(minOf(6, local.length - 2)) + local.last()
            val d = domain.substringBefore('.').let { if (it.isEmpty()) "" else it.first() + "•••" }
            val tld = domain.substringAfter('.', "")
            return if (domain.isEmpty()) l else "$l@$d" + if (tld.isNotEmpty()) ".$tld" else ""
        }
    }
}

/**
 * Sign in with ChatGPT（オープンソースアプリ向けのプラン利用）。
 * https://developers.openai.com/siwc/token-sharing-open-source/sign-in に従う:
 * - 初回は client_id=dynamic_agent_client で動的登録し、コールバックで受け取った client_id を保存して再利用
 * - ext_agent_host_id（端末ごとに固定の urn:uuid）を送る
 * - PKCE(S256) / state / nonce は毎回新規
 * - リダイレクトは http://127.0.0.1:<port>/auth/callback（アプリ内の小さな HTTP サーバーで受ける）
 * - ID トークンは JWKS で署名・iss・aud・exp・nonce を検証
 * - chatgpt.tokens.use.direct が付与されていなければプラン利用は無効のまま
 */
class ChatGptAuth(private val context: Context) {
    companion object {
        private const val TAG = "ChatGptAuth"
        private const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
        private const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
        private const val DISCOVERY = "https://auth.openai.com/.well-known/openid-configuration"
        private const val ISSUER = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private const val AGENT_NAME = "Rabi Launcher"
        const val RETURN_URI = "rabi://auth-done"
        private val TERMINAL_REFRESH = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SecureStore(context, "chatgpt_credentials")
    private val prefs = context.getSharedPreferences("chatgpt", Context.MODE_PRIVATE)
    private val api = OpenAiClient()
    private val refreshLock = Mutex()
    private val _status = MutableStateFlow(AiStatus())
    val status: StateFlow<AiStatus> = _status
    @Volatile private var server: ServerSocket? = null

    /** 端末（エージェントホスト）ごとに固定の識別子。認証情報ではない。 */
    private val hostId: String
        get() = prefs.getString("host_id", null) ?: ("urn:uuid:" + UUID.randomUUID()).also { prefs.edit().putString("host_id", it).apply() }

    private data class Creds(
        val email: String?, val subject: String, val clientId: String, val idToken: String,
        val accessToken: String?, val refreshToken: String?, val expiresAt: Long, val scopes: List<String>, val savedAt: Long,
    ) {
        fun toJson() = JSONObject().put("email", email).put("issuer", ISSUER).put("subject", subject).put("client_id", clientId)
            .put("id_token", idToken).put("access_token", accessToken).put("refresh_token", refreshToken)
            .put("expires_at", expiresAt).put("scopes", JSONArray(scopes)).put("saved_at", savedAt).toString()

        companion object {
            fun from(s: String): Creds? = runCatching {
                val j = JSONObject(s)
                val sc = j.optJSONArray("scopes") ?: JSONArray()
                Creds(
                    j.optString("email").takeIf { it.isNotEmpty() && it != "null" }, j.getString("subject"), j.getString("client_id"),
                    j.getString("id_token"), j.optString("access_token").takeIf { it.isNotEmpty() && it != "null" },
                    j.optString("refresh_token").takeIf { it.isNotEmpty() && it != "null" }, j.optLong("expires_at"),
                    (0 until sc.length()).map { sc.getString(it) }, j.optLong("saved_at"),
                )
            }.getOrNull()
        }
    }

    private fun creds(): Creds? = store.read()?.let { Creds.from(it) }

    // ================================================================ 状態

    /** 起動時: 保存済みの認証情報から状態を復元し、実際に接続できるか確認する。 */
    fun restore() {
        val c = creds()
        _status.value = AiStatus(
            signedIn = c?.accessToken != null,
            email = c?.email,
            planAllowed = c?.scopes?.contains(PLAN_SCOPE) == true,
            needsReauth = c != null && c.accessToken == null,
            selectedModel = prefs.getString("model", null),
        )
        if (c?.accessToken != null && c.scopes.contains(PLAN_SCOPE)) scope.launch { verify() }
    }

    fun selectModel(slug: String) {
        prefs.edit().putString("model", slug).apply()
        _status.update { it.copy(selectedModel = slug) }
    }

    /** モデル一覧を取得できたら「接続済み」。存在を確認していないモデル名は使わない。 */
    suspend fun verify(): Boolean {
        _status.update { it.copy(busy = "接続を確認中…", error = null) }
        return try {
            val token = accessToken()
            val manual = prefs.getStringSet("manual_models", emptySet())!!
            val listed = api.listModels(token)
            val models = listed + manual.filter { m -> listed.none { it.slug == m } }.sorted().map { ModelInfo(it, "$it（手動・確認済み）") }
            val saved = prefs.getString("model", null)
            val selected = saved?.takeIf { s -> models.any { it.slug == s } } ?: models.firstOrNull()?.slug
            if (selected != null && selected != saved) prefs.edit().putString("model", selected).apply()
            _status.update { it.copy(busy = null, verified = models.isNotEmpty(), verifiedAt = System.currentTimeMillis(), models = models, selectedModel = selected, usageLimited = false, error = if (models.isEmpty()) "利用できるモデルがありません" else null) }
            models.isNotEmpty()
        } catch (e: AiException) {
            onApiError(e); false
        }
    }

    /** API 呼び出しで起きたエラーを状態に反映する。 */
    fun onApiError(e: AiException) {
        Log.w(TAG, "api error kind=${e.kind} status=${e.httpStatus} code=${e.code} request=${e.requestId}")
        _status.update {
            when (e.kind) {
                AiErrorKind.AUTH -> it.copy(busy = null, verified = false, error = e.userMessage)
                AiErrorKind.USAGE_LIMIT -> it.copy(busy = null, usageLimited = true, error = e.userMessage)
                AiErrorKind.NOT_ELIGIBLE -> it.copy(busy = null, verified = false, error = e.userMessage)
                else -> it.copy(busy = null, error = e.userMessage)
            }
        }
    }

    // ================================================================ トークン

    /** 有効なアクセストークン。期限が近ければ更新する（同時更新しないよう直列化）。 */
    suspend fun accessToken(): String = refreshLock.withLock {
        val c = creds() ?: throw AiException(AiErrorKind.AUTH, message = "not signed in")
        val token = c.accessToken ?: throw AiException(AiErrorKind.AUTH, message = "needs sign-in")
        if (!c.scopes.contains(PLAN_SCOPE)) throw AiException(AiErrorKind.NOT_ELIGIBLE, code = "scope_missing", message = "plan scope not granted")
        if (System.currentTimeMillis() < c.expiresAt - 5 * 60_000) return token
        refresh(c)
    }

    private fun refresh(c: Creds): String {
        val rt = c.refreshToken ?: throw AiException(AiErrorKind.AUTH, message = "no refresh token")
        val (status, body) = postForm(TOKEN, mapOf("grant_type" to "refresh_token", "client_id" to c.clientId, "refresh_token" to rt, "resource" to RESOURCE))
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (status != 200 || json == null) {
            val code = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: json?.optJSONObject("error")?.optString("code")
            if (code != null && code in TERMINAL_REFRESH) {
                // 使えないトークンは消し、保存済みの client_id で再ログインしてもらう
                store.write(c.copy(accessToken = null, refreshToken = null, expiresAt = 0).toJson())
                _status.update { it.copy(signedIn = false, verified = false, needsReauth = true, error = "ChatGPT の再ログインが必要です") }
                throw AiException(AiErrorKind.AUTH, status, code, message = "refresh failed: $code")
            }
            if (status == 0) throw AiException(AiErrorKind.NETWORK, message = "refresh network error")
            throw AiException(if (status >= 500) AiErrorKind.UNAVAILABLE else AiErrorKind.OTHER, status, code, message = "refresh failed")
        }
        val scopes = json.optString("scope").split(' ').filter { it.isNotEmpty() }.ifEmpty { c.scopes }
        val next = c.copy(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: rt,
            idToken = json.optString("id_token").takeIf { it.isNotEmpty() } ?: c.idToken,
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000,
            scopes = scopes,
            savedAt = System.currentTimeMillis(),
        )
        // アクセストークン・期限・スコープ・ローテーションされた更新トークンをまとめて置き換える
        store.write(next.toJson())
        _status.update { it.copy(planAllowed = scopes.contains(PLAN_SCOPE)) }
        return next.accessToken!!
    }

    // ================================================================ ログイン

    /**
     * ブラウザで認可画面を開く。完了はアプリ内のループバックサーバーで受け取る。
     * @param reconsent プラン利用を改めて許可してもらう場合（一度拒否した後など）
     */
    fun signIn(reconsent: Boolean = false) {
        if (server != null) return
        val saved = creds()
        val state = random(32); val nonce = random(32); val verifier = random(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val socket = try {
            ServerSocket(1455, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))   // ポートだけは変えてよい
        }
        server = socket
        val redirect = "http://127.0.0.1:${socket.localPort}/auth/callback"
        val clientId = saved?.clientId ?: prefs.getString("issued_client_id", null) ?: "dynamic_agent_client"
        val params = linkedMapOf(
            "client_id" to clientId,
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirect,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
        )
        if (clientId == "dynamic_agent_client") params["agent_name_hint"] = AGENT_NAME
        if (saved != null) {
            params["id_token_hint"] = saved.idToken
            saved.email?.let { params["login_hint"] = it }
        }
        if (reconsent) params["prompt"] = "consent"
        val url = AUTHORIZE + "?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        _status.update { it.copy(busy = "ブラウザでログイン中…", error = null) }

        scope.launch {
            val q = try {
                socket.soTimeout = 5 * 60_000
                awaitCallback(socket)
            } catch (e: SocketTimeoutException) {
                _status.update { it.copy(busy = null, error = "ログインがタイムアウトしました") }; null
            } catch (e: Exception) {
                // cancelSignIn() でソケットを閉じた場合など
                _status.update { it.copy(busy = null) }; null
            } finally {
                runCatching { socket.close() }
                server = null
            }
            if (q != null) {
                // 新規登録で発行された client_id は、交換に失敗しても次回の認可で再利用する
                if (clientId == "dynamic_agent_client" && q["state"] == state) q["client_id"]?.takeIf { it.startsWith("oaiapp") || it.isNotEmpty() && it != "dynamic_agent_client" }
                    ?.let { prefs.edit().putString("issued_client_id", it).apply() }
                // コールバック受信時はブラウザが前面で、このアプリは通信できないことがある（UnknownHost）。
                // トークン交換はアプリが前面に戻ってから行う。
                synchronized(this@ChatGptAuth) {
                    pending = {
                        try {
                            handleCallback(q, state, nonce, verifier, redirect, clientId, saved)
                        } catch (e: AiException) {
                            _status.update { it.copy(busy = null, error = e.message) }
                        } catch (e: Exception) {
                            Log.w(TAG, "sign-in failed: ${e.javaClass.simpleName}")
                            _status.update { it.copy(busy = null, error = "ログインに失敗しました") }
                        }
                    }
                }
                _status.update { it.copy(busy = "アプリに戻ると接続を完了します…") }
                if (foreground) runPending()
            }
        }
        // 認可 URL には id_token_hint が含まれるのでログに出さない
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Volatile private var foreground = false
    private var pending: (() -> Unit)? = null

    /** SettingsActivity の onResume / onPause から呼ぶ。前面に戻ったら保留中のトークン交換を行う。 */
    fun setForeground(fg: Boolean) { foreground = fg; if (fg) runPending() }

    private fun runPending() {
        val task = synchronized(this) { pending.also { pending = null } } ?: return
        scope.launch { task() }
    }

    fun cancelSignIn() {
        runCatching { server?.close() }
        server = null
        _status.update { it.copy(busy = null) }
    }

    /** /auth/callback へのリクエストを 1 件受け取り、クエリを返す。 */
    private fun awaitCallback(socket: ServerSocket): Map<String, String> {
        while (true) {
            socket.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@use
                val path = requestLine.split(' ').getOrNull(1) ?: return@use
                val ok = path.startsWith("/auth/callback")
                val html = if (ok) """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
                    <title>Rabi</title></head><body style="font-family:sans-serif;padding:32px;background:#fff;color:#111">
                    <h2>ログイン処理が完了しました</h2><p>アプリに戻ってください。</p>
                    <p><a href="$RETURN_URI" style="display:inline-block;padding:14px 22px;background:#111;color:#c6f432;text-decoration:none;border-radius:10px">アプリに戻る</a></p>
                    <script>setTimeout(function(){location.href='$RETURN_URI'},300)</script></body></html>""" else "Not found"
                val bytes = html.toByteArray()
                val out = s.getOutputStream()
                out.write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes); out.flush()
                if (ok) {
                    val query = path.substringAfter('?', "")
                    return query.split('&').filter { it.contains('=') }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                }
            }
        }
    }

    private fun handleCallback(q: Map<String, String>, state: String, nonce: String, verifier: String, redirect: String, requestedClientId: String, saved: Creds?) {
        Log.i(TAG, "callback received params=${q.keys.sorted()}")
        if (q["state"] != state) throw AiException(AiErrorKind.AUTH, message = "state が一致しません")
        q["error"]?.let { err ->
            if (err == "access_denied") throw AiException(AiErrorKind.AUTH, code = err, message = "許可がキャンセルされました")
            throw AiException(AiErrorKind.AUTH, code = err, message = "ログインエラー: $err")
        }
        val code = q["code"] ?: throw AiException(AiErrorKind.AUTH, message = "認可コードがありません")
        val clientId = if (requestedClientId == "dynamic_agent_client") {
            // 新規登録ではコールバックで発行された client_id を使う。無ければ登録未完了
            q["client_id"]?.takeIf { it.isNotEmpty() && it != "dynamic_agent_client" } ?: throw AiException(AiErrorKind.AUTH, message = "クライアント登録が完了しませんでした")
        } else {
            val cb = q["client_id"]
            if (cb != null && cb != requestedClientId) throw AiException(AiErrorKind.AUTH, message = "別のクライアントの応答のため破棄しました")
            requestedClientId
        }
        _status.update { it.copy(busy = "トークンを確認中…") }
        val form = mapOf(
            "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
            "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE,
        )
        var (status, body) = postForm(TOKEN, form)
        var tries = 1
        while (status == 0 && tries < 4) { Thread.sleep(1500L * tries); tries++; postForm(TOKEN, form).let { status = it.first; body = it.second } }
        if (status == 0) throw AiException(AiErrorKind.NETWORK, message = "通信できなかったためトークン交換ができませんでした。通信状況を確認して、もう一度ログインしてください")
        if (status != 200) {
            // エラー応答にはトークンは含まれないので、原因調査のため要点だけ記録・表示する
            val detail = describeError(body)
            Log.w(TAG, "token exchange failed status=$status redirectPort=${redirect.substringAfter("127.0.0.1:").substringBefore('/')} newRegistration=${requestedClientId == "dynamic_agent_client"} $detail")
            throw AiException(AiErrorKind.AUTH, status, null, message = "トークン交換に失敗しました（HTTP $status ${detail.take(160)}）")
        }
        val t = JSONObject(body)
        val idToken = t.getString("id_token")
        val claims = validateIdToken(idToken, clientId, nonce)
        val subject = claims.getString("sub")
        if (saved != null && saved.subject != subject) throw AiException(AiErrorKind.AUTH, message = "別のアカウントでログインされたため破棄しました")
        val scopes = t.optString("scope").split(' ').filter { it.isNotEmpty() }
        val c = Creds(
            email = claims.optString("email").takeIf { it.isNotEmpty() } ?: saved?.email,
            subject = subject, clientId = clientId, idToken = idToken,
            accessToken = t.getString("access_token"), refreshToken = t.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000,
            scopes = scopes, savedAt = System.currentTimeMillis(),
        )
        store.write(c.toJson())
        val plan = scopes.contains(PLAN_SCOPE)
        _status.update { it.copy(busy = null, signedIn = true, email = c.email, planAllowed = plan, needsReauth = false, verified = false, error = if (plan) null else "ChatGPT プランの利用が許可されていません") }
        // ID トークンが有効でもプラン利用は別。許可されていれば実際に接続を確認する
        if (plan) scope.launch { verify() }
    }

    /** ID トークンの署名（JWKS, RS256）と iss / aud / exp / nonce を検証する。 */
    private fun validateIdToken(jwt: String, clientId: String, nonce: String): JSONObject {
        val parts = jwt.split('.')
        if (parts.size != 3) throw AiException(AiErrorKind.AUTH, message = "ID トークンの形式が不正です")
        val header = JSONObject(String(b64urlDecode(parts[0])))
        val claims = JSONObject(String(b64urlDecode(parts[1])))
        if (header.optString("alg") != "RS256") throw AiException(AiErrorKind.AUTH, message = "未対応の署名方式です")
        val discovery = JSONObject(get(DISCOVERY))
        prefs.edit().putString("revocation_endpoint", discovery.optString("revocation_endpoint")).apply()
        val keys = JSONObject(get(discovery.getString("jwks_uri"))).getJSONArray("keys")
        val kid = header.optString("kid")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { it.optString("kid") == kid }
            ?: throw AiException(AiErrorKind.AUTH, message = "署名鍵が見つかりません")
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, b64urlDecode(jwk.getString("n"))), BigInteger(1, b64urlDecode(jwk.getString("e"))),
        ))
        val sig = Signature.getInstance("SHA256withRSA").apply { initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray()) }
        if (!sig.verify(b64urlDecode(parts[2]))) throw AiException(AiErrorKind.AUTH, message = "ID トークンの署名が不正です")
        if (claims.optString("iss") != discovery.optString("issuer", ISSUER)) throw AiException(AiErrorKind.AUTH, message = "発行元が一致しません")
        val aud = claims.opt("aud")
        val audOk = when (aud) { is String -> aud == clientId; is JSONArray -> (0 until aud.length()).any { aud.getString(it) == clientId }; else -> false }
        if (!audOk) throw AiException(AiErrorKind.AUTH, message = "対象クライアントが一致しません")
        if (claims.optLong("exp") * 1000 < System.currentTimeMillis() - 60_000) throw AiException(AiErrorKind.AUTH, message = "ID トークンの期限が切れています")
        if (claims.optString("nonce") != nonce) throw AiException(AiErrorKind.AUTH, message = "nonce が一致しません")
        return claims
    }

    /** ログアウト: 更新トークンを失効させてからローカルのトークンを消す。client_id とホスト ID は残す。 */
    fun signOut(onDone: (remoteRevoked: Boolean) -> Unit) {
        scope.launch {
            val c = creds()
            var revoked = false
            if (c?.refreshToken != null) {
                val endpoint = prefs.getString("revocation_endpoint", null)
                    ?: runCatching { JSONObject(get(DISCOVERY)).optString("revocation_endpoint") }.getOrNull()
                if (!endpoint.isNullOrEmpty()) {
                    val (status, _) = postForm(endpoint, mapOf("token" to c.refreshToken, "token_type_hint" to "refresh_token", "client_id" to c.clientId))
                    revoked = status == 200
                }
            } else revoked = true
            if (c != null) store.write(c.copy(accessToken = null, refreshToken = null, expiresAt = 0, scopes = emptyList()).toJson())
            _status.value = AiStatus(selectedModel = prefs.getString("model", null))
            launch(Dispatchers.Main) { onDone(revoked) }
        }
    }

    // ================================================================ HTTP

    private fun postForm(url: String, form: Map<String, String>): Pair<Int, String> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()) }
        val status = c.responseCode
        val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        status to body
    } catch (e: IOException) {
        Log.w(TAG, "POST failed: ${e.javaClass.simpleName} ${e.message?.take(120)}")
        0 to ""
    }

    private fun get(url: String): String = try {
        (URL(url).openConnection() as HttpURLConnection).run { connectTimeout = 15_000; readTimeout = 15_000; inputStream.bufferedReader().use { it.readText() } }
    } catch (e: IOException) {
        throw AiException(AiErrorKind.NETWORK, message = "通信できませんでした")
    }

    /** OAuth エラー応答の要点（error / error_description / code / message）。 */
    private fun describeError(body: String): String {
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(160).replace(Regex("\\s+"), " ")
        val e = j.opt("error")
        return when (e) {
            is JSONObject -> listOfNotNull(e.optString("code").takeIf { it.isNotEmpty() }, e.optString("message").takeIf { it.isNotEmpty() }).joinToString(": ")
            is String -> listOfNotNull(e, j.optString("error_description").takeIf { it.isNotEmpty() }).joinToString(": ")
            else -> j.optString("detail").ifEmpty { body.take(160) }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun random(n: Int): String = ByteArray(n).also { SecureRandom().nextBytes(it) }.let { b64url(it) }
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun b64urlDecode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
