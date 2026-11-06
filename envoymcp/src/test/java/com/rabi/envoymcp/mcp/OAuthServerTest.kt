package com.rabi.envoymcp.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder

/** 外 (Claude のコネクター) からつなぐときの OAuth と、外の名前の Host の振り分け */
class OAuthServerTest {
    private class Tools : ToolHost {
        val called = ArrayList<String>()
        override fun list(): JSONArray = JSONArray().put(JSONObject().put("name", "get_state").put("annotations", JSONObject().put("readOnlyHint", true)))
        override suspend fun call(name: String, args: JSONObject, agent: Agent?): ToolResult? {
            if (name != "get_state") return null
            called += name
            return ToolResult("ok")
        }
    }

    private var clock = 1_000_000_000L
    private var domain = DOMAIN
    private var answer: suspend () -> Boolean = { true }
    private val store = OAuthServer.Memory()
    private val agents = Agents()
    private val activity = ActivityLog(now = { clock })
    private val log = ToolLog()
    private val tools = Tools()
    private val oauth = OAuthServer({ domain }, agents, store, approve = { answer() }, activity = activity, now = { clock })
    private val server = McpServer(tools, port = { 0 }, token = { MAIN }, log = log, agents = agents, now = { clock }, oauth = oauth)

    // ---------------------------------------------------------------- 依頼を作る

    private fun req(method: String, path: String, host: String = DOMAIN, body: String = "", headers: Map<String, String> = emptyMap()): HttpRequest {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            if (host.isNotEmpty()) append("Host: $host\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
        }
        return Http.read(ByteArrayInputStream((head + body).toByteArray()), 1 shl 20)!!
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun formOf(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
    private fun json(r: HttpResponse) = JSONObject(String(r.body))
    private fun header(r: HttpResponse, name: String) = r.extra.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Location の URL のクエリ */
    private fun query(r: HttpResponse): Map<String, String> = OAuthServer.form(URI(header(r, "Location")!!).rawQuery)!!

    private suspend fun register(redirect: String = CALLBACK, extra: JSONObject.() -> Unit = {}): HttpResponse =
        server.respond(req("POST", "/register", body = JSONObject().put("redirect_uris", JSONArray().put(redirect)).put("client_name", "Claude").apply(extra).toString(),
            headers = mapOf("Content-Type" to "application/json")))

    private suspend fun clientId(): String = json(register()).getString("client_id").also { clock += 10_000 }

    private fun authorizePath(clientId: String, state: String = "st-1", challenge: String = CHALLENGE, wait: Boolean = true, vararg more: Pair<String, String>): String {
        val p = listOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to CALLBACK, "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "resource" to "https://$DOMAIN/mcp") + more + (if (wait) listOf("envoy_wait" to "1") else emptyList())
        return "/authorize?" + formOf(*p.toTypedArray())
    }

    private suspend fun authorize(clientId: String, state: String = "st-1"): HttpResponse = server.respond(req("GET", authorizePath(clientId, state)))

    private suspend fun code(clientId: String): String {
        val r = authorize(clientId)
        assertEquals(302, r.status)
        return query(r).getValue("code")
    }

    private suspend fun token(vararg p: Pair<String, String>): HttpResponse =
        server.respond(req("POST", "/token", body = formOf(*p), headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")))

    private suspend fun exchange(clientId: String, code: String, verifier: String = VERIFIER, redirect: String = CALLBACK) =
        token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to redirect, "code_verifier" to verifier)

    private suspend fun mcp(bearer: String?, path: String = "/mcp", host: String = DOMAIN): HttpResponse {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list").toString()
        return server.respond(req("POST", path, host, body, if (bearer != null) mapOf("Authorization" to "Bearer $bearer") else emptyMap()))
    }

    /** 登録 → 認可 → 引き換え (アクセスの合言葉と更新用の合言葉) */
    private suspend fun connect(): Triple<String, String, String> {
        val id = clientId()
        val r = json(exchange(id, code(id)))
        return Triple(id, r.getString("access_token"), r.getString("refresh_token"))
    }

    // ---------------------------------------------------------------- Host の振り分け

    @Test fun hostRouting() = runTest {
        // 外の名前 (大文字小文字・ポートは見ない) では OAuth の入口がある
        assertEquals(200, server.respond(req("GET", "/.well-known/oauth-protected-resource")).status)
        assertEquals(200, server.respond(req("GET", "/.well-known/oauth-authorization-server", host = "XXX.Ngrok-Free.App:443")).status)
        // スマホの中 (127.0.0.1・localhost) では OAuth の入口は無い
        for (p in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server", "/authorize", "/token", "/register")) {
            assertEquals(p, 404, server.respond(req("GET", p, host = "127.0.0.1:8765")).status)
            assertEquals(p, 404, server.respond(req("POST", p, host = "localhost")).status)
        }
        // 外の名前でないもの・名前の一部だけ合うものは断る
        assertEquals(403, server.respond(req("GET", "/.well-known/oauth-protected-resource", host = "evil.example")).status)
        assertEquals(403, server.respond(req("GET", "/.well-known/oauth-protected-resource", host = "a.$DOMAIN")).status)
        // 外の名前が空なら、外の名前の Host も断る
        domain = ""
        assertEquals(403, server.respond(req("GET", "/.well-known/oauth-protected-resource")).status)
        // 外の名前に localhost・127.0.0.1 を入れても、スマホの中の扱いは変わらない
        for (d in listOf("localhost", "127.0.0.1")) {
            domain = d
            assertEquals(404, server.respond(req("GET", "/.well-known/oauth-protected-resource", host = d)).status)
            assertEquals(200, mcp(null, path = "/mcp/$MAIN", host = d).status)
        }
        // 「https://」や末尾の「/」が付いていても同じ名前
        domain = "https://$DOMAIN/"
        assertEquals(200, server.respond(req("GET", "/.well-known/oauth-protected-resource")).status)
    }

    /** スマホの中の扱いは、外の名前があっても同じ (URL の合言葉・Bearer・Origin を断る) */
    @Test fun localBehaviorUnchanged() = runTest {
        assertEquals(200, mcp(null, path = "/mcp/$MAIN", host = "127.0.0.1:8765").status)
        assertEquals(200, mcp(MAIN, host = "localhost").status)
        assertEquals(403, mcp(null, host = "127.0.0.1").status)
        assertEquals(403, mcp("wrong", host = "127.0.0.1").status)
        val origin = server.respond(req("POST", "/mcp/$MAIN", "127.0.0.1", "{}", mapOf("Origin" to "https://evil.example")))
        assertEquals(403, origin.status)
        assertEquals(404, server.respond(req("GET", "/", host = "127.0.0.1")).status)
    }

    // ---------------------------------------------------------------- 見つけ方

    @Test fun discoveryJson() = runTest {
        for (p in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            val r = server.respond(req("GET", p))
            assertEquals("application/json", r.contentType)
            val o = json(r)
            assertEquals("https://$DOMAIN/mcp", o.getString("resource"))
            assertEquals(listOf("https://$DOMAIN"), o.getJSONArray("authorization_servers").let { a -> (0 until a.length()).map { a.getString(it) } })
        }
        val m = json(server.respond(req("GET", "/.well-known/oauth-authorization-server")))
        assertEquals("https://$DOMAIN", m.getString("issuer"))
        assertEquals("https://$DOMAIN/authorize", m.getString("authorization_endpoint"))
        assertEquals("https://$DOMAIN/token", m.getString("token_endpoint"))
        assertEquals("https://$DOMAIN/register", m.getString("registration_endpoint"))
        fun list(k: String) = m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("code"), list("response_types_supported"))
        assertEquals(listOf("authorization_code", "refresh_token"), list("grant_types_supported"))
        assertEquals(listOf("S256"), list("code_challenge_methods_supported"))
        assertEquals(listOf("none"), list("token_endpoint_auth_methods_supported"))
        assertEquals(405, server.respond(req("POST", "/.well-known/oauth-authorization-server")).status)
        assertEquals(404, server.respond(req("GET", "/.well-known/openid-configuration")).status)
    }

    @Test fun unauthenticatedMcpGets401() = runTest {
        val r = mcp(null)
        assertEquals(401, r.status)
        assertEquals("Bearer resource_metadata=\"https://$DOMAIN/.well-known/oauth-protected-resource\"", header(r, "WWW-Authenticate"))
        val wrong = mcp("wrong")
        assertEquals(401, wrong.status)
        assertTrue(header(wrong, "WWW-Authenticate")!!.startsWith("Bearer error=\"invalid_token\""))
        // ブラウザから (Origin あり) の /mcp は断る
        assertEquals(403, server.respond(req("POST", "/mcp", body = "{}", headers = mapOf("Origin" to "https://claude.ai"))).status)
        assertTrue(tools.called.isEmpty())
    }

    // ---------------------------------------------------------------- 登録

    @Test fun registerAcceptsOnlyClaudeCallbacks() = runTest {
        for (ok in OAuthServer.REDIRECTS) {
            // 秘密を使う方式を頼まれても、秘密は出さず none にする
            val r = register(ok) { put("token_endpoint_auth_method", "client_secret_post") }
            assertEquals(201, r.status)
            val o = json(r)
            assertTrue(o.getString("client_id").isNotEmpty())
            assertEquals("none", o.getString("token_endpoint_auth_method"))
            assertFalse(o.has("client_secret"))
            clock += 10_000
        }
        val bad = listOf("https://evil.example/api/mcp/auth_callback", "http://claude.ai/api/mcp/auth_callback", "https://claude.ai/api/mcp/auth_callback/",
            "https://claude.ai/api/mcp/auth_callback?x=1", "https://claude.ai.evil.example/api/mcp/auth_callback", "https://CLAUDE.AI/api/mcp/auth_callback")
        for (b in bad) {
            val r = register(b)
            assertEquals(b, 400, r.status)
            assertEquals("invalid_redirect_uri", json(r).getString("error"))
            clock += 10_000
        }
        // 正しいものと正しくないものが混ざっている・空・無い
        assertEquals(400, server.respond(req("POST", "/register", body = JSONObject().put("redirect_uris", JSONArray().put(CALLBACK).put("https://evil.example/cb")).toString())).status)
        clock += 10_000
        assertEquals(400, server.respond(req("POST", "/register", body = JSONObject().put("redirect_uris", JSONArray()).toString())).status)
        clock += 10_000
        assertEquals(400, server.respond(req("POST", "/register", body = "{}")).status)
        clock += 10_000
        assertEquals("invalid_client_metadata", json(server.respond(req("POST", "/register", body = "not json"))).getString("error"))
        clock += 10_000
        assertEquals(400, register { put("grant_types", JSONArray().put("client_credentials")) }.status)
    }

    @Test fun registerCapsClientsAndRate() = runTest {
        val first = clientId()
        repeat(OAuthServer.MAX_CLIENTS + 5) { clientId() }
        assertEquals(OAuthServer.MAX_CLIENTS, JSONObject(store.read()!!).getJSONArray("clients").length())
        // 古いものから忘れる (忘れた呼び手では認可できない)
        val r = server.respond(req("GET", authorizePath(first)))
        assertEquals(400, r.status)
        assertNull(header(r, "Location"))
        // 1 分に 10 回まで
        clock += 60_000
        repeat(10) { assertEquals(201, register().status) }
        assertEquals(429, register().status)
        clock += 60_000
        assertEquals(201, register().status)
    }

    // ---------------------------------------------------------------- 認可と引き換え

    @Test fun pkceVerify() {
        assertTrue(OAuthServer.pkceOk(VERIFIER, CHALLENGE))
        assertFalse(OAuthServer.pkceOk(VERIFIER + "x", CHALLENGE))
        assertFalse(OAuthServer.pkceOk(VERIFIER, CHALLENGE.dropLast(1) + "A"))
        // 短い・使えない文字のベリファイアは合わない
        assertFalse(OAuthServer.pkceOk("short", CHALLENGE))
        assertFalse(OAuthServer.pkceOk(VERIFIER.replace('_', '/'), CHALLENGE))
        // チャレンジそのもの (plain) では合わない
        assertFalse(OAuthServer.pkceOk(CHALLENGE, CHALLENGE))
    }

    @Test fun wrongVerifierIsRefusedAndBurnsCode() = runTest {
        val id = clientId()
        val c = code(id)
        val r = exchange(id, c, verifier = VERIFIER.reversed())
        assertEquals(400, r.status)
        assertEquals("invalid_grant", json(r).getString("error"))
        // 一度失敗したコードは、正しいベリファイアでも使えない
        assertEquals("invalid_grant", json(exchange(id, c)).getString("error"))
        assertEquals(1, agents.list.value.size)
    }

    @Test fun codeIsSingleUseAndExpires() = runTest {
        val id = clientId()
        val c = code(id)
        assertEquals(200, exchange(id, c).status)
        assertEquals("invalid_grant", json(exchange(id, c)).getString("error"))
        // 60 秒で切れる
        val late = code(id)
        clock += OAuthServer.CODE_MS
        assertEquals("invalid_grant", json(exchange(id, late)).getString("error"))
        // 別の呼び手・別の戻り先では使えない (使おうとしたコードは消える)
        val other = clientId()
        val c2 = code(id)
        assertEquals("invalid_grant", json(exchange(other, c2)).getString("error"))
        assertEquals("invalid_grant", json(exchange(id, c2)).getString("error"))
        // スマホに確認を出す回数の上限 (10 分に 3 回) を越えないよう、時間を進める
        clock += OAuthServer.ASK_WINDOW_MS
        val c3 = code(id)
        assertEquals("invalid_grant", json(exchange(id, c3, redirect = "https://claude.com/api/mcp/auth_callback")).getString("error"))
        // 足したエージェントは最初の 1 つだけ
        assertEquals(2, agents.list.value.size)
    }

    @Test fun fullFlowAddsAgentAndServesMcp() = runTest {
        val id = clientId()
        // 1 段目: ブラウザに「スマホで許可してください」を出し、同じ頼みを開き直させる (まだ本人に聞かない)
        var asked = 0
        answer = { asked++; true }
        val first = server.respond(req("GET", authorizePath(id, wait = false)))
        assertEquals(200, first.status)
        val html = String(first.body)
        assertTrue(html.contains(OAuthServer.WAIT_TEXT))
        assertTrue(html.contains("envoy_wait=1"))
        assertFalse(html.contains("<script"))
        assertEquals(0, asked)
        // 2 段目: 許可されたらコードと state を付けて戻す
        val r = authorize(id, "state with spaces&x=1")
        assertEquals(302, r.status)
        assertTrue(header(r, "Location")!!.startsWith("$CALLBACK?"))
        assertEquals("state with spaces&x=1", query(r)["state"])
        assertEquals(1, asked)
        val t = exchange(id, query(r).getValue("code"))
        assertEquals(200, t.status)
        val o = json(t)
        assertEquals("Bearer", o.getString("token_type"))
        val access = o.getString("access_token")
        val refresh = o.getString("refresh_token")
        // エージェントを既定の権限 (読むだけ・1 週間) で足し、その合言葉を渡す
        val a = agents.list.value.single { !it.main }
        assertEquals(OAuthServer.AGENT_NAME, a.name)
        assertEquals(access, a.token)
        assertTrue(a.readOnly)
        assertEquals(clock + Agents.WEEK_MS, a.expiresAt)
        // 外からの /mcp は、その合言葉 (Bearer) で使える
        val m = mcp(access)
        assertEquals(200, m.status)
        assertEquals("get_state", json(m).getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
        // したことに残す (秘密は入れない)
        val e = activity.entries.value.first()
        assertEquals(ActivitySentence.connector(true), e.sentence)
        assertEquals(true, e.approved)
        assertTrue(activity.entries.value.none { it.sentence.contains(access) || it.sentence.contains(refresh) })
        // 置き場所には、合言葉も更新用の合言葉もそのままでは置かない
        val saved = store.read()!!
        assertFalse(saved.contains(access))
        assertFalse(saved.contains(refresh))
        assertTrue(log.entries.value.none { it.detail.contains(access) || it.detail.contains(refresh) })
    }

    @Test fun refreshRotatesAndDeletingAgentRevokes() = runTest {
        val (id, access, refresh) = connect()
        val r = json(token("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id))
        assertEquals(access, r.getString("access_token"))
        val next = r.getString("refresh_token")
        assertNotEquals(refresh, next)
        // 使った更新用の合言葉は、もう使えない。別の呼び手でも使えない
        assertEquals("invalid_grant", json(token("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id)).getString("error"))
        assertEquals("invalid_grant", json(token("grant_type" to "refresh_token", "refresh_token" to next, "client_id" to "c-other")).getString("error"))
        // 設定でエージェントを消すと、外から使えず、更新も断る
        agents.remove(agents.list.value.single { !it.main }.id)
        assertEquals(401, mcp(access).status)
        assertEquals("invalid_grant", json(token("grant_type" to "refresh_token", "refresh_token" to next, "client_id" to id)).getString("error"))
        assertEquals("unsupported_grant_type", json(token("grant_type" to "password")).getString("error"))
    }

    /** 止めた・期限切れのエージェントは、外からでも 403 (合言葉は合っている) */
    @Test fun stoppedOrExpiredAgentIsRefusedOutside() = runTest {
        val (_, access, _) = connect()
        val a = agents.list.value.single { !it.main }
        agents.update(a.copy(enabled = false))
        assertEquals(403, mcp(access).status)
        agents.update(a.copy(enabled = true))
        clock = a.expiresAt
        assertEquals(403, mcp(access).status)
    }

    @Test fun pathTokenAndOtherTokensRefusedOutside() = runTest {
        // URL の合言葉 (本体・足したエージェント) は外からは断る
        val local = agents.add(clock)
        assertEquals(403, mcp(null, path = "/mcp/$MAIN").status)
        assertEquals(403, mcp(null, path = "/mcp/${local.token}").status)
        // 本体の合言葉・設定で足したエージェントの合言葉は、Bearer でも外からは使えない
        assertEquals(401, mcp(MAIN).status)
        assertEquals(401, mcp(local.token).status)
        // OAuth で渡した合言葉でも、URL に入れたものは断る
        val (_, access, _) = connect()
        assertEquals(403, mcp(null, path = "/mcp/$access").status)
        assertEquals(200, mcp(access).status)
        assertTrue(log.entries.value.none { it.detail.contains(MAIN) || it.detail.contains(local.token) || it.detail.contains(access) })
        assertEquals(listOf<String>(), tools.called)
    }

    @Test fun authorizeValidation() = runTest {
        val id = clientId()
        // 知らない呼び手・登録していない戻り先: 戻さず、ブラウザに一言
        for (p in listOf(authorizePath("c-unknown"), authorizePath(id).replace(enc(CALLBACK), enc("https://claude.com/api/mcp/auth_callback")))) {
            val r = server.respond(req("GET", p))
            assertEquals(400, r.status)
            assertNull(header(r, "Location"))
            assertTrue(String(r.body).contains(OAuthServer.BAD_TEXT))
        }
        // PKCE が無い・plain・response_type が違う・resource が違う: error を付けて戻す
        fun err(r: HttpResponse) = query(r)["error"]
        assertEquals("invalid_request", err(server.respond(req("GET", authorizePath(id, challenge = "")))))
        assertEquals("invalid_request", err(server.respond(req("GET", authorizePath(id).replace("S256", "plain")))))
        assertEquals("unsupported_response_type", err(server.respond(req("GET", authorizePath(id).replace("response_type=code", "response_type=token")))))
        assertEquals("invalid_target", err(server.respond(req("GET", authorizePath(id).replace(enc("https://$DOMAIN/mcp"), enc("https://evil.example/mcp"))))))
        assertEquals("invalid_request", err(server.respond(req("GET", authorizePath(id, state = "")))))
        // 同じ名前が 2 回ある頼みは受けない
        assertEquals(400, server.respond(req("GET", authorizePath(id) + "&client_id=$id")).status)
        assertEquals(1, agents.list.value.size)
    }

    @Test fun deniedOrTimedOutIsAccessDenied() = runTest {
        val id = clientId()
        answer = { false }
        val r = authorize(id)
        assertEquals("access_denied", query(r)["error"])
        assertEquals("st-1", query(r)["state"])
        assertNull(query(r)["code"])
        assertEquals(ActivityLog.REFUSED, activity.entries.value.first().outcome)
        // 本人が何もしなければ、時間切れで access_denied
        answer = { awaitCancellation() }
        assertEquals("access_denied", query(authorize(id))["error"])
        assertEquals(ActivitySentence.connector(false, "時間切れ"), activity.entries.value.first().sentence)
        assertEquals(1, agents.list.value.size)
    }

    @Test fun onePendingAtATimeAndRateLimit() = runTest {
        val id = clientId()
        val gate = CompletableDeferred<Boolean>()
        var asked = 0
        answer = { asked++; gate.await() }
        val first = async { authorize(id, "s-1") }
        runCurrent()
        assertEquals(1, asked)
        // 待っている間の、別の頼みは断る (スマホに 2 つ目の確認を出さない)
        val other = authorize(id, "s-2")
        assertEquals(429, other.status)
        assertTrue(String(other.body).contains(OAuthServer.BUSY_TEXT))
        // 同じ頼みが届き直したら (ブラウザの読み直し)、同じ答えを待つ
        val again = async { authorize(id, "s-1") }
        val again2 = async { authorize(id, "s-1") }
        runCurrent()
        assertEquals(1, asked)
        // いっしょに待たせるのは 2 つまで (それより多ければ断る)
        assertEquals(429, authorize(id, "s-1").status)
        gate.complete(true)
        val a = first.await()
        val b = again.await()
        assertEquals(query(a)["code"], query(b)["code"])
        assertEquals(query(a)["code"], query(again2.await())["code"])
        assertNotNull(query(a)["code"])
        // 10 分に 3 回まで
        answer = { false }
        repeat(2) { assertEquals(302, authorize(id, "s-x$it").status) }
        val limited = authorize(id, "s-y")
        assertEquals(429, limited.status)
        assertTrue(String(limited.body).contains(OAuthServer.LATER_TEXT))
        clock += OAuthServer.ASK_WINDOW_MS
        assertEquals(302, authorize(id, "s-z").status)
    }

    companion object {
        private const val DOMAIN = "xxx.ngrok-free.app"
        private const val MAIN = "mainTOKEN234"
        private const val CALLBACK = "https://claude.ai/api/mcp/auth_callback"
        /** PKCE の例 (チャレンジは Python の hashlib と base64 で別に求めたもの) */
        private const val VERIFIER = "envoy-test-verifier_0123456789.abcdefghijklmn~XYZ"
        private const val CHALLENGE = "AoclJTFbr3N9JjEfvz7PZf7i_6xOvKX0y4AJ1-y_dPg"
    }
}
