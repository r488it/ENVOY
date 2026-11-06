package com.rabi.envoymcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** アプリの中の著作権の表示は、LICENSE.md・LICENSE.ja.md の 1 行目と同じ */
class ProvenanceTest {
    private fun firstLine(name: String): String {
        val f = listOf(File("../$name"), File(name)).first { it.exists() }
        return f.readLines().first()
    }

    @Test fun noticeMatchesLicenseFiles() {
        assertEquals(firstLine("LICENSE.md"), Provenance.NOTICE)
        assertEquals(firstLine("LICENSE.ja.md"), Provenance.NOTICE)
        assertTrue(Provenance.NOTICE.startsWith("Required Notice: "))
    }
}
