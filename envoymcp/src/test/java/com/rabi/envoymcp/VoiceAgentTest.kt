package com.rabi.envoymcp

import org.junit.Assert.assertEquals
import org.junit.Test

/** 話し相手の AI の設定: 最初は ChatGPT、知らない値も ChatGPT */
class VoiceAgentTest {
    @Test fun defaultsToChatGpt() {
        assertEquals(VoiceAgent.CHATGPT, VoiceAgent.from(null))
        assertEquals(VoiceAgent.CHATGPT, VoiceAgent.from(""))
        assertEquals(VoiceAgent.CHATGPT, VoiceAgent.from("gemini"))
        assertEquals(VoiceAgent.CHATGPT, VoiceAgent.from("chatgpt"))
        assertEquals(VoiceAgent.CLAUDE, VoiceAgent.from("claude"))
    }

    @Test fun savedIdsAndLabels() {
        assertEquals(listOf("chatgpt", "claude"), VoiceAgent.entries.map { it.id })
        assertEquals(listOf("ChatGPT のアプリ", "Claude のアプリ"), VoiceAgent.entries.map { it.label })
    }
}
