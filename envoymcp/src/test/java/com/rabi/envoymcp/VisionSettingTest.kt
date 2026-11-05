package com.rabi.envoymcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面の理解の設定: 読む AI は最初 ChatGPT、Claude のモデルは最初 Sonnet 5.5 */
class VisionSettingTest {
    @Test fun providerDefaultsToChatGpt() {
        assertEquals(VisionProvider.CHATGPT, VisionProvider.from(null))
        assertEquals(VisionProvider.CHATGPT, VisionProvider.from("unknown"))
        assertEquals(VisionProvider.CHATGPT, VisionProvider.from("chatgpt"))
        assertEquals(VisionProvider.CLAUDE, VisionProvider.from("claude"))
    }

    @Test fun claudeModelDefaultsToSonnet() {
        assertEquals(ClaudeModel.SONNET, ClaudeModel.from(null))
        assertEquals(ClaudeModel.SONNET, ClaudeModel.from("claude-unknown"))
        assertEquals(listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5-20251001"), ClaudeModel.entries.map { it.id })
        assertEquals(ClaudeModel.HAIKU, ClaudeModel.from("claude-haiku-4-5-20251001"))
        // 考える深さは Haiku 4.5 には付けない (付けると断られる)
        assertTrue(ClaudeModel.SONNET.effort && ClaudeModel.OPUS.effort)
        assertFalse(ClaudeModel.HAIKU.effort)
    }
}
