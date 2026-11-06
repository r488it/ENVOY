package com.rabi.envoymcp.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.runtime.Composable
import com.rabi.envoymcp.BuildConfig
import com.rabi.envoymcp.core.Provenance

/** ライセンス: 著作権の表示 (Required Notice)・使ってよい範囲・同梱しているほかの人のコード・この作品の番号 */
@Composable
internal fun LicenseCard() {
    Card("ライセンス", "PolyForm Noncommercial 1.0.0", icon = Icons.Rounded.Gavel) {
        Mono(Provenance.NOTICE)
        Note("非商用 (個人での利用・学習・研究、学校や公的な機関などの非営利の団体での利用) なら、自由に使えます。" +
            "製品やサービスに組み込むなど、商用で使うときは、作者の許可が要ります。")
        Line("正式な条件", Provenance.LICENSE_URL)
        Line("ソース", Provenance.SOURCE)
        Note("同梱している、ほかの人のコードは、それぞれのライセンスに従います (Google AI Edge Gallery の一部: Apache 2.0、" +
            "tunnel-client: Apache 2.0、ngrok-go: MIT ほか)。")
        Line("版", BuildConfig.VERSION_NAME)
        Line("作品の番号", Provenance.ID)
    }
}
