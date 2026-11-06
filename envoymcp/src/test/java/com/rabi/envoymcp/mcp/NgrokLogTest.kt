package com.rabi.envoymcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外からつなぐ中継 (ngrok) の行の読み取り・伏せ方と、設定の形 */
class NgrokLogTest {
    // 作りものの認証トークン (本物ではない。ngrok のトークンと同じ形)
    private val fake = "2abcdefghijklmnopqrstuvwxyzA_abcdefghijklmnopqrstu"

    @Test fun statusLinesAreRead() {
        assertEquals(NgrokLog.Event.Ready("https://example-name.ngrok-free.app"), NgrokLog.event("READY https://example-name.ngrok-free.app"))
        assertEquals(NgrokLog.Event.Forwarded(200), NgrokLog.event("REQ POST /mcp 200 35ms"))
        assertEquals(NgrokLog.Event.Forwarded(502), NgrokLog.event("REQ GET /.well-known/oauth-protected-resource 502 3ms"))
        assertEquals(NgrokLog.Event.NoUpstream, NgrokLog.event("NOUPSTREAM dial tcp 127.0.0.1:8765: connect: connection refused"))
        // ngrok-go の注意や知らない行は、状態を変えない
        assertNull(NgrokLog.event("LOG level=WARN msg=\"agent version is deprecated\""))
        assertNull(NgrokLog.event("READYX"))
        assertNull(NgrokLog.event(""))
    }

    @Test fun reasonsArePlainWords() {
        // 中継が実際に出す行 (認証トークンが違う)
        val bad = "ERROR ERR_NGROK_107 authentication failed: The authtoken you specified is properly formed, but it is invalid."
        assertEquals(NgrokLog.Event.Failed("ngrok の認証トークンが違います"), NgrokLog.event(bad))
        assertEquals("ngrok の認証トークンが違います", NgrokLog.reason("ERR_NGROK_105 authentication failed: does not look like a proper ngrok authtoken"))
        assertEquals("ngrok の認証トークンがありません", NgrokLog.reason("NO_TOKEN"))
        assertEquals("ドメインの形が違います", NgrokLog.reason("BAD_DOMAIN"))
        assertTrue(NgrokLog.reason("ERR_NGROK_108 authentication failed: Your account is limited to 1 simultaneous ngrok agent sessions.").contains("ほかの所でも動いています"))
        assertTrue(NgrokLog.reason("ERR_NGROK_334 The endpoint 'https://x.ngrok-free.app' is already online.").contains("もう使われています"))
        assertEquals("ngrok から断られました (ERR_NGROK_999)", NgrokLog.reason("ERR_NGROK_999 something new"))
        val net = NgrokLog.event("RETRY failed to dial ngrok server with address \"connect.ngrok-agent.com:443\": dial tcp: lookup connect.ngrok-agent.com: no such host")
        assertEquals(NgrokLog.Event.Retry("ngrok につながりません (ネットを確かめてください)"), net)
        assertEquals("中継がうまくいっていません", NgrokLog.reason("something else"))
    }

    @Test fun tokensAreHidden() {
        // 保存したトークンそのもの (ngrok のエラーは、入れたトークンを文の中に出すことがある)
        val r = NgrokLog.redact("ERROR ERR_NGROK_105 authentication failed. Your authtoken: $fake", fake)
        assertFalse(r.contains("abcdefghijklmnop"))
        assertTrue(r.contains("Your authtoken: …"))
        // 保存したものと違っても、トークンの形なら伏せる
        assertFalse(NgrokLog.redact("token 1abcdefghijklmnopqrstuvwxyzB_ABCDEFGHIJKLMN").contains("ABCDEFGHIJKLMN"))
        // 合言葉 (Bearer) と、長くてでたらめな文字の並び
        val h = NgrokLog.redact("LOG header=\"Authorization: Bearer s3cretTOKENvalue\" key=Zq7TAbCdEfGhJkMnPqRsTuVwXyZ23456789abcdefgh")
        assertFalse(h.contains("s3cretTOKENvalue"))
        assertFalse(h.contains("Zq7TAbCd"))
        assertTrue(h.contains("Bearer …"))
        // ふつうの行はそのまま
        assertEquals("READY https://example-name.ngrok-free.app", NgrokLog.redact("READY https://example-name.ngrok-free.app", fake))
        assertEquals(300, NgrokLog.redact("x ".repeat(1000)).length)
    }

    @Test fun connectorUrl() {
        assertEquals("https://example-name.ngrok-free.app/mcp", NgrokSetup.connectorUrl("example-name.ngrok-free.app"))
        assertEquals("https://example-name.ngrok-free.app/mcp", NgrokSetup.connectorUrl(" https://Example-Name.ngrok-free.app/ "))
        assertNull(NgrokSetup.connectorUrl(""))
        assertNull(NgrokSetup.connectorUrl("not a domain"))
    }

    @Test fun domainIsCleaned() {
        assertEquals("example-name.ngrok-free.app", NgrokSetup.domainOf("https://example-name.ngrok-free.app/mcp"))
        assertEquals("example-name.ngrok.app", NgrokSetup.domainOf("http://EXAMPLE-NAME.ngrok.app?x=1"))
        assertNull(NgrokSetup.domainOf("localhost"))
        assertNull(NgrokSetup.domainOf("-x.ngrok.app"))
        assertNull(NgrokSetup.domainOf("x..app"))
    }

    @Test fun tokenFromClipboard() {
        assertEquals(fake, NgrokSetup.tokenFrom("  $fake\n"))
        // ngrok のページのコマンドをそのまま写したとき
        assertEquals(fake, NgrokSetup.tokenFrom("ngrok config add-authtoken $fake"))
        assertNull(NgrokSetup.tokenProblem(fake))
        assertTrue(NgrokSetup.tokenProblem("")!!.contains("空"))
        assertTrue(NgrokSetup.tokenProblem("short")!!.contains("形ではありません"))
        assertTrue(NgrokSetup.tokenProblem("2abcdefghijklmnopqrst uvwxyz")!!.contains("形ではありません"))
        // 画面に出すのは最後の 4 文字だけ
        assertEquals("…rstu", NgrokSetup.tail(fake))
        assertFalse(NgrokSetup.tail(fake).contains("abcdefghijklmnopqrstuvwxyz"))
    }
}
