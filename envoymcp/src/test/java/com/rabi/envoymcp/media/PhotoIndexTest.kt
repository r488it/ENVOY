package com.rabi.envoymcp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class PhotoIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun unit(vararg x: Float): FloatArray {
        val v = FloatArray(4) { if (it < x.size) x[it] else 0f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(4) { v[it] / n }
    }

    @Test fun nearestFirstAndFilter() {
        val ix = PhotoIndex(tmp.newFile("i.bin").also { it.delete() }, 4, 100L)
        ix.add(1, 1_000, unit(1f, 0f))
        ix.add(2, 2_000, unit(0f, 1f))
        ix.add(3, 3_000, unit(1f, 1f))
        val top = ix.top(unit(1f, 0.1f), 3) { _, _ -> true }
        assertEquals(listOf(1L, 3L, 2L), top.map { it.first })
        assertEquals(listOf(3L, 2L), ix.top(unit(1f, 0.1f), 3) { _, at -> at >= 2_000 }.map { it.first })   // 撮った時刻で絞る
    }

    @Test fun keepsAcrossReloadAndAppends() {
        val f = tmp.newFile("i.bin").also { it.delete() }
        PhotoIndex(f, 4, 100L).apply { add(1, 1, unit(1f)); add(2, 2, unit(0f, 1f)); flush() }
        PhotoIndex(f, 4, 100L).apply { assertEquals(2, size); add(3, 3, unit(0f, 0f, 1f)); add(1, 1, unit(1f)); flush() }   // 同じ写真は足さない
        val ix = PhotoIndex(f, 4, 100L)
        assertEquals(3, ix.size)
        assertTrue(ix.has(3))
        assertEquals(3L, ix.top(unit(0f, 0f, 1f), 1) { _, _ -> true }.single().first)
    }

    @Test fun removesGonePhotosAndResetsForAnotherModel() {
        val f = tmp.newFile("i.bin").also { it.delete() }
        PhotoIndex(f, 4, 100L).apply { add(1, 1, unit(1f)); add(2, 2, unit(0f, 1f)); add(3, 3, unit(0f, 0f, 1f)); flush(); retain(setOf(1L, 3L)) }
        val ix = PhotoIndex(f, 4, 100L)
        assertEquals(2, ix.size)
        assertFalse(ix.has(2))
        assertEquals(0, PhotoIndex(f, 4, 200L).size)                 // モデルが変わった: 作り直す
    }
}
