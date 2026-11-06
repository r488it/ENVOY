package com.rabi.envoymcp.core

import org.json.JSONObject

/**
 * このアプリの出どころとライセンス。設定の「ライセンス」、MCP の最初の返事 (initialize の _meta)、
 * 外からつなぐときの公開の説明 (oauth-protected-resource) に出す。
 * - ライセンスは、配るときに Required Notice の行を残すことを求めている (LICENSE.md)
 * - ID は、この作品だけの番号。写されたものを見分けるのに使う
 */
object Provenance {
    const val NOTICE = "Required Notice: Copyright 2026 taku_sid (https://github.com/r488it/ENVOY)"
    const val LICENSE = "PolyForm-Noncommercial-1.0.0"
    const val LICENSE_URL = "https://polyformproject.org/licenses/noncommercial/1.0.0"
    const val SOURCE = "https://github.com/r488it/ENVOY"
    const val ID = "ENVOY-TS-50B857-3EC0"

    /** 外に出す印 (ライセンス・著作権の表示・出どころ・番号) */
    fun meta(): JSONObject = JSONObject()
        .put("license", LICENSE)
        .put("license_url", LICENSE_URL)
        .put("notice", NOTICE)
        .put("source", SOURCE)
        .put("id", ID)
}
