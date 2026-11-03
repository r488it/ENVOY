package com.rabi.envoymcp.arm

import com.rabi.envoymcp.arm.ArmController.Key
import com.rabi.envoymcp.core.Expression

/** リゲルの表情に合わせた腕の動き */
internal object ArmGestures {
    /**
     * 表情ごとの腕の動き (始めた向きからの差。tilt は上が正、turn・roll は左右) と、くり返す回数。
     * 長さは画面の表情 (出る・続く・戻る) とほぼ同じにしてある。
     * 速さの上限 (1 秒に約 28°) に収まる長さにしてある
     * - うれしい: 少し上を向いて弾み、左右に首をかしげて揺れる (ルンルン)。約 3.5 秒
     * - 笑う: 少し上を向き、「は・は・は」と小刻みにうなずく。2 回。約 3.2 秒
     * - 驚き: のけぞって (上を向いて) しばらく止まり、ゆっくり戻る。約 2.7 秒
     * - 悲しい: ゆっくりうなだれて (下を向いて) しばらくそのまま、ゆっくり戻る。約 3.7 秒
     * - 照れ: 横下へ顔をそらし、もじもじと首をかしげ、戻る。約 3.2 秒
     * - ウインク: 首をかしげて止まり、戻る。約 1.9 秒
     */
    fun of(e: Expression): Pair<List<Key>, Int> = when (e) {
        Expression.HAPPY -> listOf(
            Key(0.0, 5.0, 0.0, 360), Key(0.0, 4.0, 6.0, 480), Key(0.0, 4.0, -6.0, 840), Key(0.0, 4.0, 6.0, 840), Key(0.0, 4.0, 0.0, 420), Key(0.0, 0.0, 0.0, 600)) to 1
        Expression.LAUGH -> listOf(
            Key(0.0, 5.0, 0.0, 360), Key(0.0, 2.0, 0.0, 220), Key(0.0, 5.0, 0.0, 220), Key(0.0, 2.0, 0.0, 220), Key(0.0, 5.0, 0.0, 220), Key(0.0, 0.0, 0.0, 360)) to 2
        Expression.SURPRISED -> listOf(
            Key(0.0, 8.0, 0.0, 560), Key(0.0, 8.0, 0.0, 1000), Key(0.0, 0.0, 0.0, 1100)) to 1
        Expression.SAD -> listOf(
            Key(0.0, -10.0, 0.0, 1300), Key(0.0, -10.0, 0.0, 1200), Key(0.0, 0.0, 0.0, 1200)) to 1
        Expression.SHY -> listOf(
            Key(10.0, -5.0, 0.0, 720), Key(10.0, -5.0, 4.0, 300), Key(10.0, -5.0, -4.0, 580), Key(10.0, -5.0, 4.0, 580), Key(10.0, -5.0, 0.0, 300), Key(0.0, 0.0, 0.0, 760)) to 1
        Expression.WINK -> listOf(
            Key(0.0, 0.0, 8.0, 560), Key(0.0, 0.0, 8.0, 700), Key(0.0, 0.0, 0.0, 620)) to 1
    }
}
