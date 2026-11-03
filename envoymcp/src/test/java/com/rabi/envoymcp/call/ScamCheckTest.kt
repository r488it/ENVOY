package com.rabi.envoymcp.call

import com.rabi.envoymcp.call.ScamCheck.Level
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 詐欺の疑いの見方 (やり取りは作り話。名前・番号は実在しない) */
class ScamCheckTest {
    private fun check(vararg peer: String) = ScamCheck.checkTexts(peer.toList())

    @Test fun fakePoliceIsHigh() {
        val r = check(
            "もしもし、こちら警視庁の生活安全課の者です。",
            "あなたの銀行口座が、詐欺グループの犯罪に使われていることが分かりました。",
            "このままだと、あなたも逮捕されるおそれがあります。",
            "確認のため、キャッシュカードの暗証番号を教えてください。",
            "捜査中ですので、ご家族には言わないでください。今日中に手続きが必要です。",
        )
        assertEquals(Level.HIGH, r.level)
        assertEquals(listOf("お金の話", "大事な番号を聞く", "急がせる", "警察などを名乗る", "おどす", "秘密にさせる"), r.reasons)
    }

    @Test fun refundAtAtmIsHigh() {
        val r = check(
            "こちら市役所の保険年金課です。",
            "医療費の還付金が三万円ございまして、そのご案内です。",
            "手続きの期限が本日までとなっております。",
            "お近くのATMに行っていただいて、着いたらこちらにお電話ください。操作をご案内します。",
        )
        assertEquals(Level.HIGH, r.level)
        assertEquals(listOf("お金の話", "急がせる", "警察などを名乗る"), r.reasons)
    }

    @Test fun oreOreIsHigh() {
        val r = check(
            "もしもし、オレだけど。",
            "携帯をなくしちゃって、番号が変わったんだ。",
            "実は会社のお金を使い込んじゃって、今日中に返さないといけないんだ。",
            "悪いけど現金を用意してくれないかな。会社の人が取りに行くから。",
            "このことは父さんには内緒にしてね。",
        )
        assertEquals(Level.HIGH, r.level)
        assertEquals(listOf("お金の話", "急がせる", "秘密にさせる", "身内を名乗る"), r.reasons)
    }

    @Test fun automatedUnpaidNoticeIsHigh() {
        val r = check("こちらは料金センターの自動音声です。", "未納の料金がございます。詳しくは１番を押してください。")
        assertEquals(Level.HIGH, r.level)
        assertEquals(listOf("お金の話", "自動音声"), r.reasons)
    }

    @Test fun clinicAppointmentIsNone() {
        // マイナンバーカード (を持ってきて) は、ふつうの話なので数えない
        val r = check(
            "もしもし、駅前クリニックの受付です。",
            "明日の午前十時からのご予約の確認でお電話しました。",
            "ご来院の際は、マイナ保険証かマイナンバーカードをお持ちください。",
            "ご都合が悪くなった場合は、お電話でご連絡ください。",
            "それでは、お待ちしております。",
        )
        assertEquals(Level.NONE, r.level)
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun friendCallIsNone() {
        val r = check(
            "もしもし、久しぶり！元気にしてた？",
            "今度の日曜日、みんなでお昼ごはんを食べに行こうって話になってさ。",
            "駅前に新しいお蕎麦屋さんができたんだって。駅からまっすぐ行ったところ。",
            "よかったら一緒にどうかなと思って。また連絡するね。",
        )
        assertEquals(Level.NONE, r.level)
    }

    @Test fun deliveryNoticeIsAtMostCaution() {
        val r = check(
            "お世話になっております、宅配便のドライバーです。",
            "お荷物をお届けに伺ったのですが、ご不在でしたのでお電話しました。",
            "今日の夕方か、明日の午前中でしたら再配達できますが、いかがでしょうか。",
            "お荷物の保管期限は一週間です。",
        )
        assertNotEquals(Level.HIGH, r.level)
        assertFalse("お金の話" in r.reasons)
    }

    @Test fun onlyPeerWordsCount() {
        // リゲルがお金や番号の話を断っても、数えない
        val lines = listOf(
            CallLine("rigel", "AI アシスタントのリゲルです。お金の振り込みや暗証番号のお話は、今すぐにはお受けできません。", true, 1L),
            CallLine("peer", "そうですか、分かりました。また改めます。", true, 2L),
        )
        assertEquals(Level.NONE, ScamCheck.check(lines).level)
    }

    @Test fun kanaWidthAndSpacesAreNormalized() {
        // カタカナ・半角カナ・全角数字・途中の空白でも当たる
        assertEquals(listOf("お金の話", "急がせる", "警察などを名乗る"), check("ケイサツ の 者です", "ｹﾞﾝｷﾝを ｲﾏｽｸﾞ 用意して").reasons)
        assertEquals(listOf("自動音声"), check("ご用件の方は ２ を おしてください").reasons)
        assertEquals(listOf("大事な番号を聞く"), check("ｷｬｯｼｭ・カードを確認します").reasons)
    }

    @Test fun ordinaryPhrasesAreNotCounted() {
        assertEquals(Level.NONE, check("折り返しの電話番号をおしえてください").level)         // 「番号をおしえて」は「押して」ではない
        assertEquals(Level.NONE, check("この道をまっすぐ行ってください").level)
        assertEquals(Level.NONE, check("ご家族に話して、決めてくださいね").level)
        assertEquals(listOf("警察などを名乗る"), check("裁判所の者です").reasons)     // 「裁判所」を「裁判」 (おどす) とは数えない
    }

    @Test fun levels() {
        // 種類が 1〜2 つで、お金・番号と、急がせる・名乗るなどの組み合わせでなければ「気をつけて」
        assertEquals(Level.CAUTION, check("警察の者です。至急お知らせしたいことがあります").level)
        assertEquals(Level.CAUTION, check("口座の番号を確認させてください").level)
        // 3 つ以上なら、お金の話が無くても「高い」
        assertEquals(Level.HIGH, check("警察です。あなたは事件に関わっています。至急こちらに来てください").level)
        assertEquals(Level.NONE, check().level)
    }

    @Test fun wording() {
        assertEquals("詐欺の疑い: 高い（お金の話・急がせる）", ScamCheck.label(Level.HIGH, listOf("お金の話", "急がせる")))
        assertEquals("詐欺の疑い: 気をつけて（おどす）。", ScamCheck.sentence(Level.CAUTION, listOf("おどす")))
        assertEquals("", ScamCheck.sentence(Level.NONE, emptyList()))
        // 言い切らない (「詐欺の疑い」とだけ言う)
        Level.entries.filter { it != Level.NONE }.forEach { assertTrue(ScamCheck.title(it).startsWith("詐欺の疑い")) }
    }

    @Test fun callInfoKeepsRisk() {
        val c = CallInfo("id", "in", "phone", "登録されていない番号（末尾 0000）", startedAt = 1_000L)
            .withText("peer", "税務署の者です。", true, 2_000L).withText("peer", "還付金があります。", true, 3_000L).withRisk()
        assertEquals(Level.HIGH, c.risk)
        assertEquals(listOf("お金の話", "警察などを名乗る"), c.riskReasons)
        // 記録 (JSON) に残り、読み戻せる
        val back = CallInfo.of(JSONObject(c.toJson().toString()))
        assertEquals(Level.HIGH, back.risk)
        assertEquals(c.riskReasons, back.riskReasons)
        // risk の無い記録も読める (なし)
        val old = CallInfo.of(JSONObject().put("id", "old").put("dir", "in").put("name", "佐藤さん").put("started_at", 1_000L))
        assertEquals(Level.NONE, old.risk)
        assertTrue(old.riskReasons.isEmpty())
    }
}
