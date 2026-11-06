package com.rabi.envoymcp.ui.settings

import com.rabi.envoymcp.VoiceAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** かんたんセットアップ: 手順の並び、手順ごとの様子、渡せるか、次に見る手順、探す言葉 */
class SetupGuideTest {
    /** 渡す前に済ませる手順が、すべてそろった様子 (腕は使わない) */
    private val allSet = SetupFacts(
        home = true, accessibility = true, overlay = true, notifications = true, phone = true, screenLock = true,
        serverOn = true, serverRunning = true, ai = VoiceAgent.CHATGPT, aiInstalled = true, aiLink = true, aiUsed = true,
    )

    @Test fun stepsAreInTheOrderToFollow() {
        assertEquals(
            listOf(SetupStep.HOME, SetupStep.ACCESSIBILITY, SetupStep.OVERLAY, SetupStep.NOTIFICATIONS, SetupStep.PERMISSIONS,
                SetupStep.SCREEN_LOCK, SetupStep.SERVER, SetupStep.CHOOSE_AI, SetupStep.CONNECT_AI, SetupStep.ARM),
            SetupGuide.steps,
        )
        // 飛ばせるのは腕だけ。家族が確かめるのは AI とつなぐ手順だけ
        assertEquals(listOf(SetupStep.ARM), SetupGuide.steps.filter { it.optional })
        assertEquals(listOf(SetupStep.CONNECT_AI), SetupGuide.steps.filter { it.family })
        // どの手順にも、名前と、なぜ要るかがある
        assertTrue(SetupGuide.steps.all { it.title.isNotBlank() && it.why.isNotBlank() })
    }

    @Test fun nothingIsDoneAtFirst() {
        val st = SetupGuide.states(SetupFacts())
        assertTrue(SetupGuide.steps.filter { it != SetupStep.CONNECT_AI }.all { st[it] == StepState.NOT_YET })
        // サーバーが止まっている間は、AI とつなぐ手順もまだ
        assertEquals(StepState.NOT_YET, st[SetupStep.CONNECT_AI])
        assertFalse(SetupGuide.ready(st))
        assertEquals(0 to 9, SetupGuide.progress(st))
    }

    @Test fun eachSimpleStepFollowsItsCheck() {
        val st = SetupGuide.states(SetupFacts(home = true, overlay = true, screenLock = true))
        assertEquals(StepState.DONE, st[SetupStep.HOME])
        assertEquals(StepState.NOT_YET, st[SetupStep.ACCESSIBILITY])
        assertEquals(StepState.DONE, st[SetupStep.OVERLAY])
        assertEquals(StepState.NOT_YET, st[SetupStep.NOTIFICATIONS])
        assertEquals(StepState.NOT_YET, st[SetupStep.PERMISSIONS])
        assertEquals(StepState.DONE, st[SetupStep.SCREEN_LOCK])
    }

    @Test fun serverMustBeOnAndRunning() {
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.SERVER, SetupFacts(serverOn = true, serverRunning = false)))
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.SERVER, SetupFacts(serverOn = false, serverRunning = true)))
        assertEquals(StepState.DONE, SetupGuide.state(SetupStep.SERVER, SetupFacts(serverOn = true, serverRunning = true)))
    }

    @Test fun chosenAiAppMustBeInstalled() {
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.CHOOSE_AI, allSet.copy(ai = VoiceAgent.CLAUDE, aiInstalled = false)))
        assertEquals(StepState.DONE, SetupGuide.state(SetupStep.CHOOSE_AI, allSet.copy(ai = VoiceAgent.CLAUDE, aiInstalled = true)))
    }

    @Test fun connectingTheAiIsCheckedByTheFamilyUntilItConnects() {
        // 入口が分からない (Mac で動かしている など) か、つながっているが、まだ AI から呼ばれていない: 家族が確かめる
        assertEquals(StepState.FAMILY, SetupGuide.state(SetupStep.CONNECT_AI, allSet.copy(aiLink = null, aiUsed = false)))
        assertEquals(StepState.FAMILY, SetupGuide.state(SetupStep.CONNECT_AI, allSet.copy(aiLink = true, aiUsed = false)))
        // AI から一度つながった: できている (入口が分からなくても)
        assertEquals(StepState.DONE, SetupGuide.state(SetupStep.CONNECT_AI, allSet.copy(aiLink = null, aiUsed = true)))
        // 入口がオフ・うまくいっていない、サーバーが止まっている: まだ (つながったことがあっても)
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.CONNECT_AI, allSet.copy(aiLink = false, aiUsed = true)))
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.CONNECT_AI, allSet.copy(serverRunning = false)))
    }

    @Test fun armNeedsSwitchPermissionsAndLink() {
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.ARM, SetupFacts(armOn = false, armPermissions = true, armReady = true)))
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.ARM, SetupFacts(armOn = true, armPermissions = false, armReady = true)))
        assertEquals(StepState.NOT_YET, SetupGuide.state(SetupStep.ARM, SetupFacts(armOn = true, armPermissions = true, armReady = false)))
        assertEquals(StepState.DONE, SetupGuide.state(SetupStep.ARM, SetupFacts(armOn = true, armPermissions = true, armReady = true)))
    }

    @Test fun readyIgnoresOptionalSteps() {
        val st = SetupGuide.states(allSet)
        assertEquals(StepState.NOT_YET, st[SetupStep.ARM])
        assertTrue(SetupGuide.ready(st))
        assertEquals(9 to 9, SetupGuide.progress(st))
        // 家族が確かめる手順が残っていれば、まだ渡せない
        val family = SetupGuide.states(allSet.copy(aiUsed = false))
        assertFalse(SetupGuide.ready(family))
        assertEquals(8 to 9, SetupGuide.progress(family))
    }

    @Test fun nextNotDoneSkipsDoneAndSkippedSteps() {
        val st = SetupGuide.states(allSet.copy(overlay = false, aiUsed = false))
        assertEquals(SetupStep.OVERLAY, SetupGuide.nextNotDone(st))
        // after の手順そのものは見ない
        assertEquals(SetupStep.CONNECT_AI, SetupGuide.nextNotDone(st, after = SetupStep.OVERLAY))
        assertEquals(SetupStep.ARM, SetupGuide.nextNotDone(st, after = SetupStep.CONNECT_AI))
        // 「あとで」にした手順は飛ばす
        assertNull(SetupGuide.nextNotDone(st, after = SetupStep.CONNECT_AI, skipped = setOf(SetupStep.ARM)))
        assertNull(SetupGuide.nextNotDone(st, after = SetupStep.ARM))
    }

    @Test fun startOpensTheFirstUnfinishedStepOrTheEnd() {
        assertEquals(SetupStep.HOME, SetupGuide.start(SetupGuide.states(SetupFacts())))
        assertEquals(SetupStep.SCREEN_LOCK, SetupGuide.start(SetupGuide.states(allSet.copy(screenLock = false))))
        // 渡す前の手順がそろっていれば、腕がまだでも終わりの画面 (null)
        assertNull(SetupGuide.start(SetupGuide.states(allSet)))
    }

    @Test fun searchFindsTheSetupAndItsSteps() {
        val search = SettingsSearch()
        search.setQuery("かんたん")
        assertTrue(search.itemHit(*SetupGuide.searchWords.toTypedArray()))
        search.setQuery("画面ろっく")
        assertTrue(search.itemHit(*SetupGuide.searchWords.toTypedArray()))
        assertEquals(SetupStep.SCREEN_LOCK, SetupGuide.matching(search.query))
        assertEquals(SetupStep.HOME, SetupGuide.matching(SettingsSearch.norm("ホームアプリ")))
        search.setQuery("録画")
        assertFalse(search.itemHit(*SetupGuide.searchWords.toTypedArray()))
        assertNull(SetupGuide.matching(search.query))
        assertNull(SetupGuide.matching(""))
    }
}
