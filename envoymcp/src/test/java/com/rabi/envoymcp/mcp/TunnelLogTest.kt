package com.rabi.envoymcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLogTest {
    // 実機の tunnel-client v0.0.16 が出す行 (識別子とキーは作りもの)
    private val badKey = "2000/01/01 09:15:53 WARN poll failed; backing off client_instance_id=0123456789ab tunnel_id=tunnel_0123 component=controlplane " +
        "error=\"controlplane client: unexpected status 401: invalid_api_key: Incorrect API key provided: sk-proj-********************abcd.\" status_code=401"

    @Test fun eventsAreRead() {
        assertEquals(TunnelLog.Event.BAD_KEY, TunnelLog.event(badKey))
        assertEquals(TunnelLog.Event.POLL_FAILED, TunnelLog.event("WARN poll failed; backing off error=\"dial tcp: lookup api.openai.com: no such host\""))
        assertEquals(TunnelLog.Event.POLLING, TunnelLog.event("INFO poller started client_instance_id=1"))
        assertEquals(TunnelLog.Event.MCP_READY, TunnelLog.event("INFO mcp session initialized"))
        assertEquals(TunnelLog.Event.MCP_FAILED, TunnelLog.event("ERROR failed to connect to mcp error=\"calling \\\"initialize\\\": Forbidden\""))
        assertEquals(TunnelLog.Event.FORWARDED, TunnelLog.event("INFO dispatcher forwarded command to MCP server"))
        // 実機で出る回復の行
        assertEquals(TunnelLog.Event.RECOVERED, TunnelLog.event("2000/01/01 09:34:54 INFO poller recovered; polling operational component=controlplane"))
        assertNull(TunnelLog.event("INFO harpoon enabled"))
    }

    @Test fun keysAndTokensAreHidden() {
        val r = TunnelLog.redact(badKey)
        assertFalse(r.contains("Zq7T"))
        assertTrue(r.contains("sk-…"))
        // 毎行同じ識別子は落とす
        assertFalse(r.contains("client_instance_id"))
        assertFalse(r.contains("tunnel_0123"))
        val full = "error=\"x\" key=sk-proj-AbCdEf123_-xyz header=\"Authorization: Bearer s3cretTOKEN\""
        val h = TunnelLog.redact(full)
        assertFalse(h.contains("AbCdEf"))
        assertFalse(h.contains("s3cretTOKEN"))
        assertTrue(h.contains("Bearer …"))
    }

    @Test fun longLinesAreCut() {
        assertEquals(300, TunnelLog.redact("x".repeat(1000)).length)
    }
}
