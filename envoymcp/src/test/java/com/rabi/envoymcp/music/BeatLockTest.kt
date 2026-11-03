package com.rabi.envoymcp.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BeatLockTest {
    private fun beat(bpm: Double, conf: Double) = BeatTracker.Beat(bpm, 0, conf, 0.5)

    @Test fun needsClearBeatToStart() {
        val l = BeatLock()
        assertNull(l.update(beat(120.0, 0.2), 0.6, true, 0))
        assertNull(l.update(beat(120.0, 0.4), 0.1, true, 250))      // 音が小さい
        assertNotNull(l.update(beat(120.0, 0.4), 0.6, true, 500))
    }

    @Test fun keepsBeatWhileMusicPlays() {
        // 確かさが上下しても、はっきりしない状態が続いても、音が鳴っている間は止めない (腕は踊り続ける)
        val l = BeatLock()
        var t = 0L
        assertNotNull(l.update(beat(120.0, 0.3), 0.6, true, t))
        for (i in 0 until 240) {                                    // 60 秒、弱い見積もり・見積もり無し・小さい音が続いても
            t += 250
            val raw = if (i % 3 == 0) null else beat(if (i % 2 == 0) 80.0 else 121.0, 0.05)
            assertNotNull("$i", l.update(raw, 0.02, true, t))
        }
        assertEquals(120.0, l.current!!.bpm, 0.01)
    }

    @Test fun stopsOnlyWhenSilent() {
        val l = BeatLock()
        assertNotNull(l.update(beat(120.0, 0.4), 0.6, true, 0))
        assertNull(l.update(null, 0.0, false, 250))
        assertEquals("音が鳴っていません", l.lostWhy)
    }

    @Test fun switchesToConsistentNewTempo() {
        val l = BeatLock(switchMs = 3_000)
        assertNotNull(l.update(beat(100.0, 0.4), 0.6, true, 0))
        var t = 0L
        while (t < 2_500) { t += 250; l.update(beat(130.0, 0.15), 0.6, true, t) }
        assertEquals(100.0, l.current!!.bpm, 0.01)                  // まだ替えない
        while (t < 4_000) { t += 250; l.update(beat(130.0, 0.15), 0.6, true, t) }
        assertEquals(130.0, l.current!!.bpm, 0.01)                  // 3 秒そろったので替える
        l.update(beat(90.0, 0.3), 0.6, true, t + 250)               // はっきりした見積もりは、すぐ取り直す
        assertEquals(90.0, l.current!!.bpm, 0.01)
    }

    @Test fun foldsDoubledTempo() {
        val l = BeatLock()
        l.update(beat(120.0, 0.4), 0.6, true, 0)
        assertEquals(121.0, l.update(beat(60.5, 0.4), 0.6, true, 250)!!.bpm, 0.01)    // 半分に跳んだ: 倍にそろえる
        assertEquals(119.0, l.update(beat(238.0, 0.2), 0.6, true, 500)!!.bpm, 0.01)   // 倍に跳んだ: 半分にそろえる
    }
}
