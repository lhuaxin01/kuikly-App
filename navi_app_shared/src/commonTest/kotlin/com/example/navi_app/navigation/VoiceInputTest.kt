package com.example.navi_app.navigation

import com.example.navi_app.navigation.voice.*
import com.example.navi_app.navigation.agent.NavigationAgentRepository
import com.example.navi_app.navigation.agent.NavigationAgentRequest
import com.example.navi_app.navigation.agent.NavigationAgentTransport
import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.*
import com.example.navi_app.navigation.port.NavigationEnvironment
import com.example.navi_app.navigation.port.NavigationPort
import com.example.navi_app.navigation.presentation.NavigationStore
import com.example.navi_app.navigation.protocol.NavigationResponse
import com.example.navi_app.navigation.protocol.NavigationStop
import com.example.navi_app.navigation.protocol.NavigationTask
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputTest {
    @Test fun dictationKeepsDraftUntilSpeechArrivesAndPublishesTheRecognizedText() {
        val fixture = Fixture("  原目的地  ")
        fixture.coordinator.toggle()
        assertEquals("  原目的地  ", fixture.input)
        assertFalse(fixture.state.recording)
        assertFalse(fixture.state.canToggle)
        fixture.emit(VoiceInputPhase.LISTENING, "先去图书馆")
        assertTrue(fixture.state.recording)
        assertTrue(fixture.state.canToggle)
        assertEquals("先去图书馆", fixture.input)
        fixture.emit(VoiceInputPhase.IDLE, "先去图书馆，再去博物馆", final = true)
        assertEquals("先去图书馆，再去博物馆", fixture.input)
        assertFalse(fixture.state.active)
        assertEquals("", fixture.state.error)
        assertEquals(1, fixture.port.starts.size)
        assertTrue(fixture.port.stops.isEmpty())
    }

    @Test fun secondTapStopsCaptureButWaitsForFinalRecognition() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去")
        fixture.coordinator.toggle()
        assertTrue(fixture.state.transcribing)
        assertTrue(fixture.state.active)
        assertFalse(fixture.state.canToggle)
        fixture.coordinator.toggle()
        assertEquals(listOf(fixture.requestId), fixture.port.stops)
        fixture.emit(VoiceInputPhase.LISTENING, "去西湖")
        assertEquals(VoiceInputPhase.FINISHING, fixture.state.phase)
        fixture.emit(VoiceInputPhase.IDLE, "去西湖景区", final = true)
        assertEquals("去西湖景区", fixture.input)
        assertTrue(fixture.state.canToggle)
    }

    @Test fun preparingIsNotRecordingAndCannotAccidentallyStopBeforeReady() {
        val fixture = Fixture("杭州站")
        fixture.coordinator.toggle()
        fixture.coordinator.toggle()
        assertEquals(VoiceInputPhase.STARTING, fixture.state.phase)
        assertFalse(fixture.state.recording)
        assertFalse(fixture.state.canToggle)
        assertTrue(fixture.state.statusText.contains("准备"))
        assertTrue(fixture.port.stops.isEmpty())
        fixture.emit(VoiceInputPhase.STARTING)
        fixture.emit(VoiceInputPhase.LISTENING)
        assertTrue(fixture.state.recording)
        fixture.coordinator.toggle()
        assertEquals(VoiceInputPhase.FINISHING, fixture.state.phase)
        fixture.emit(VoiceInputPhase.IDLE)
        assertEquals("杭州站", fixture.input)
        assertFalse(fixture.state.active)
    }

    @Test fun emptyRecognitionDoesNotEraseExistingInput() {
        val fixture = Fixture("  西湖  ")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, " ")
        fixture.emit(VoiceInputPhase.IDLE, "\n", final = true)
        assertEquals("  西湖  ", fixture.input)
        assertTrue(fixture.state.error.contains("未识别"))
        assertTrue(fixture.state.canToggle)
    }

    @Test fun finalWithoutDuplicateTextRetainsRecognizedPartialForEditing() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去西湖，再去杭州东站")
        fixture.emit(VoiceInputPhase.IDLE, final = true)
        assertEquals("先去西湖，再去杭州东站", fixture.input)
        assertEquals("", fixture.state.error)
    }

    @Test fun cancelRestoresDraftAndLateCompletionCannotChangeIt() {
        val fixture = Fixture("原输入")
        fixture.coordinator.toggle()
        val cancelled = fixture.requestId
        fixture.emit(VoiceInputPhase.LISTENING, "未确认的新输入")
        fixture.coordinator.cancel()
        assertEquals("原输入", fixture.input)
        assertEquals(listOf(cancelled), fixture.port.cancels)
        fixture.emit(VoiceInputPhase.IDLE, "迟到的最终文本", final = true, requestId = cancelled)
        assertEquals("原输入", fixture.input)
        assertEquals("", fixture.state.error)
    }

    @Test fun permissionFailureIsActionableAndRetryRejectsPreviousResults() {
        val fixture = Fixture("保留的输入")
        fixture.coordinator.toggle()
        val failed = fixture.requestId
        fixture.emit(VoiceInputPhase.IDLE, error = "请在设置中允许麦克风权限")
        assertEquals("保留的输入", fixture.input)
        assertTrue(fixture.state.error.contains("麦克风"))
        fixture.coordinator.toggle()
        assertNotEquals(failed, fixture.requestId)
        assertEquals("", fixture.state.error)
        fixture.emit(VoiceInputPhase.IDLE, "上一轮结果", final = true, requestId = failed)
        assertTrue(fixture.state.active)
        assertEquals("保留的输入", fixture.input)
        fixture.emit(VoiceInputPhase.IDLE, "这次去公园", final = true)
        assertEquals("这次去公园", fixture.input)
    }

    @Test fun nativeHostCancellationRestoresDraftAndDiscardsPartialDictation() {
        val fixture = Fixture("  预先输入的目的地  ")
        fixture.coordinator.toggle()
        val cancelled = fixture.requestId
        fixture.emit(VoiceInputPhase.LISTENING, "准备放弃的目的地")
        // Hardware back/background cancellation arrives as idle without a final result.
        fixture.emit(VoiceInputPhase.IDLE, "准备放弃的目的地", final = false)
        assertEquals("  预先输入的目的地  ", fixture.input)
        assertEquals("", fixture.state.error)
        assertFalse(fixture.state.active)
        assertTrue(fixture.state.canToggle)
        fixture.emit(VoiceInputPhase.IDLE, "取消后迟到的结果", final = true, requestId = cancelled)
        assertEquals("  预先输入的目的地  ", fixture.input)
        assertTrue(fixture.port.cancels.isEmpty(), "native cancellation already released the attempt")
    }

    @Test fun nativeCancellationBeforeSpeechDoesNotReportAnEmptyRecognitionError() {
        val fixture = Fixture("原输入")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, final = false)
        assertEquals("原输入", fixture.input)
        assertEquals("", fixture.state.error)
        assertFalse(fixture.state.active)
        fixture.coordinator.toggle()
        assertEquals(VoiceInputPhase.STARTING, fixture.state.phase)
        fixture.emit(VoiceInputPhase.IDLE, error = "麦克风权限已被撤销")
        assertEquals("麦克风权限已被撤销", fixture.state.error,
            "an explicit failure remains actionable even though its final flag is false")
    }

    @Test fun recognitionFailureKeepsPartialTextAvailableForManualCorrection() {
        val fixture = Fixture("原输入")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去车站")
        fixture.emit(VoiceInputPhase.IDLE, error = "语音识别超时，请重试")
        assertEquals("先去车站", fixture.input)
        assertFalse(fixture.state.active)
        assertTrue(fixture.state.error.contains("超时"))
        assertEquals(1, fixture.port.cancels.size)
    }

    @Test fun completedDictationCannotOverwriteManualCorrectionsWithDuplicateFinal() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, "东站", final = true)
        fixture.input = "杭州东站"
        fixture.emit(VoiceInputPhase.IDLE, "旧的东站", final = true)
        assertEquals("杭州东站", fixture.input)
    }

    @Test fun cancelDuringFinishingPreventsLateFinalFromReplacingAnotherSessionDraft() {
        val fixture = Fixture("当前草稿")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING)
        fixture.coordinator.toggle()
        val cancelled = fixture.requestId
        fixture.coordinator.cancel()
        fixture.input = "其他会话的草稿"
        fixture.emit(VoiceInputPhase.IDLE, "前一个会话结果", final = true, requestId = cancelled)
        assertEquals("其他会话的草稿", fixture.input)
    }

    @Test fun lifecycleDisposalCancelsUnsubscribesAndRejectsCallbacks() {
        val fixture = Fixture("原输入")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "部分内容")
        fixture.coordinator.destroy()
        fixture.coordinator.destroy()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, "迟到内容", final = true)
        assertEquals("原输入", fixture.input)
        assertEquals(VoiceInputPhase.DISPOSED, fixture.state.phase)
        assertFalse(fixture.state.active)
        assertFalse(fixture.state.canToggle)
        assertEquals(1, fixture.port.cancels.size)
        assertEquals(1, fixture.port.unsubscribes)
        assertEquals(1, fixture.port.starts.size)
    }

    @Test fun commandFailureCannotLeaveRecordingStateStuck() {
        val fixture = Fixture("西湖")
        fixture.port.startFailure = IllegalStateException("语音引擎不可用")
        fixture.coordinator.toggle()
        assertFalse(fixture.state.active)
        assertEquals("西湖", fixture.input)
        assertEquals("语音引擎不可用", fixture.state.error)
        fixture.port.startFailure = null
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING)
        fixture.port.stopFailure = IllegalStateException("录音中断")
        fixture.coordinator.toggle()
        assertFalse(fixture.state.active)
        assertEquals("录音中断", fixture.state.error)
    }

    @Test fun cleanupCanSynchronouslyCompleteWithoutRevivingCancelledInput() {
        val fixture = Fixture("原输入")
        fixture.coordinator.toggle()
        fixture.port.onCancel = { id ->
            fixture.emit(VoiceInputPhase.IDLE, "取消时的最终结果", final = true, requestId = id)
        }
        fixture.coordinator.cancel()
        assertEquals("原输入", fixture.input)
        assertFalse(fixture.state.active)
    }

    @Test fun synchronousStopCompletionDoesNotGetOverwrittenByFinishingState() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING)
        fixture.port.onStop = { id ->
            fixture.emit(VoiceInputPhase.IDLE, "去图书馆", final = true, requestId = id)
        }
        fixture.coordinator.toggle()
        assertEquals(VoiceInputPhase.IDLE, fixture.state.phase)
        assertEquals("去图书馆", fixture.input)
    }

    @Test fun eventCodecRequiresKnownStateAndKeepsAttemptIdentity() {
        val event = VoiceInputEvent.decode(JSONObject().put("requestId", "voice-12")
            .put("state", "idle").put("text", "先去公园，再去车站").put("isFinal", true))!!
        assertEquals("voice-12", event.requestId)
        assertEquals(VoiceInputPhase.IDLE, event.phase)
        assertEquals("先去公园，再去车站", event.text)
        assertTrue(event.isFinal)
        assertNull(VoiceInputEvent.decode(JSONObject().put("state", "unexpected")))
    }

    @Test fun successfulFinalInvokesCompletionAfterPublishingUnlockedInputExactlyOnce() {
        val fixture = Fixture("原输入")
        val completions = mutableListOf<String>()
        fixture.onRecognized = { text ->
            assertEquals(VoiceInputPhase.IDLE, fixture.state.phase)
            assertFalse(fixture.state.active)
            assertEquals(text, fixture.input)
            completions += text
        }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去")
        assertTrue(completions.isEmpty())
        fixture.coordinator.toggle()
        assertTrue(completions.isEmpty())
        fixture.emit(VoiceInputPhase.IDLE, "  先去公园，再去车站  ", final = true)
        fixture.emit(VoiceInputPhase.IDLE, "重复返回的最终结果", final = true)
        assertEquals(listOf("先去公园，再去车站"), completions)
        assertEquals("先去公园，再去车站", fixture.input)
    }

    @Test fun cancelledFailedAndEmptyAttemptsNeverInvokeCompletion() {
        val fixture = Fixture("原输入")
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "取消前的半句")
        fixture.coordinator.cancel()
        fixture.emit(VoiceInputPhase.IDLE, "取消后的结果", final = true)
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, "原生取消前的半句", final = false)
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, "错误结果不可发送", final = true, error = "识别服务出错")
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, " ", final = true)
        assertTrue(completions.isEmpty())
        assertFalse(fixture.state.active)
    }

    @Test fun emptyFinalMayPreservePartialButCannotSendItAsAConfirmedResult() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "只有临时识别文本")
        fixture.emit(VoiceInputPhase.IDLE, final = true)
        assertEquals("只有临时识别文本", fixture.input)
        assertTrue(completions.isEmpty())
    }

    @Test fun completionMayReenterRecordingWithoutLettingThePreviousFinalReplaceIt() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { text ->
            completions += text
            fixture.coordinator.toggle()
        }
        fixture.coordinator.toggle()
        val firstRequest = fixture.requestId
        fixture.emit(VoiceInputPhase.IDLE, "先去公园", final = true)
        assertNotEquals(firstRequest, fixture.requestId)
        assertEquals(VoiceInputPhase.STARTING, fixture.state.phase)
        fixture.emit(VoiceInputPhase.IDLE, "前一请求重复结果", final = true, requestId = firstRequest)
        assertEquals(listOf("先去公园"), completions)
        assertEquals(VoiceInputPhase.STARTING, fixture.state.phase)
        fixture.onRecognized = { completions += it }
        fixture.emit(VoiceInputPhase.IDLE, "再去车站", final = true)
        assertEquals(listOf("先去公园", "再去车站"), completions)
    }

    @Test fun finalEventReenteredByStateObserverDoesNotDuplicateCompletion() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.onPublish = { next ->
            if (next.phase == VoiceInputPhase.IDLE) {
                fixture.emit(VoiceInputPhase.IDLE, "同步重入结果", final = true)
            }
        }
        fixture.emit(VoiceInputPhase.IDLE, "去图书馆", final = true)
        assertEquals(listOf("去图书馆"), completions)
        assertEquals("去图书馆", fixture.input)
    }

    @Test fun disposalWhilePublishingFinalPreventsCompletionForTheClosedPage() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.onPublish = { next ->
            if (next.phase == VoiceInputPhase.IDLE) fixture.coordinator.destroy()
        }
        fixture.emit(VoiceInputPhase.IDLE, "去图书馆", final = true)
        assertEquals(VoiceInputPhase.DISPOSED, fixture.state.phase)
        assertEquals("去图书馆", fixture.input)
        assertTrue(completions.isEmpty())
    }

    @Test fun manualCorrectionsAreProtectedWhileLaterSpeechContinuesFillingTheDraft() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去东站")
        fixture.coordinator.editText("先去西站")
        assertTrue(fixture.state.manuallyEdited)
        assertTrue(fixture.state.recording)
        fixture.emit(VoiceInputPhase.LISTENING, "先去东站，再去公园")
        assertEquals("先去西站，再去公园", fixture.input)
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.IDLE, "先去东站，再去公园东门", final = true)
        assertEquals("先去西站，再去公园东门", fixture.input)
        assertEquals(listOf("先去西站，再去公园东门"), completions)
    }

    @Test fun matchingNativeTextEchoDoesNotBlockFollowingRecognitionUpdates() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去")
        fixture.coordinator.editText("先去")
        assertFalse(fixture.state.manuallyEdited)
        fixture.emit(VoiceInputPhase.LISTENING, "先去图书馆")
        assertEquals("先去图书馆", fixture.input)
        fixture.emit(VoiceInputPhase.IDLE, "先去图书馆，再去公园", final = true)
        assertEquals("先去图书馆，再去公园", fixture.input)
    }

    @Test fun clearingDraftDuringRecordingIsPreservedAndCannotAutoSendRecognizedText() {
        val fixture = Fixture("原目的地")
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "识别中的目的地")
        fixture.coordinator.editText("")
        fixture.emit(VoiceInputPhase.LISTENING, "仍在识别的目的地")
        fixture.emit(VoiceInputPhase.IDLE, "最终识别的目的地", final = true)
        assertEquals("", fixture.input)
        assertTrue(fixture.state.manuallyEdited)
        assertTrue(completions.isEmpty())
    }

    @Test fun editsDuringFinishingAreUsedWhenTheFinalResultArrives() {
        val fixture = Fixture()
        val completions = mutableListOf<String>()
        fixture.onRecognized = { completions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去车站")
        fixture.coordinator.toggle()
        fixture.coordinator.editText("去车站南广场")
        assertTrue(fixture.state.transcribing)
        fixture.emit(VoiceInputPhase.IDLE, "去车站北广场", final = true)
        assertEquals("去车站南广场", fixture.input)
        assertEquals(listOf("去车站南广场"), completions)
    }

    @Test fun manualSendConsumesRecognitionImmediatelyAndRejectsLateFinalAndDuplicateSend() {
        val fixture = Fixture("原输入")
        val autoCompletions = mutableListOf<String>()
        fixture.onRecognized = { autoCompletions += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "识别中的文本")
        fixture.coordinator.editText("  手动确认的目的地  ")
        fixture.port.onCancel = { id ->
            fixture.emit(VoiceInputPhase.IDLE, "同步取消时的最终文本", final = true, requestId = id)
        }
        val text = fixture.coordinator.takeForSubmit()
        assertEquals("手动确认的目的地", text)
        assertEquals("  手动确认的目的地  ", fixture.input)
        assertFalse(fixture.state.active)
        assertEquals(listOf(fixture.requestId), fixture.port.cancels)
        assertTrue(fixture.port.stops.isEmpty(), "manual Send cancels immediately instead of waiting for recognition")
        assertNull(fixture.coordinator.takeForSubmit())
        fixture.emit(VoiceInputPhase.IDLE, "又一个迟到的最终结果", final = true)
        assertEquals("  手动确认的目的地  ", fixture.input)
        assertTrue(autoCompletions.isEmpty())
    }

    @Test fun manualSendDuringPermissionOrFinalWaitUsesCurrentTextAndClosesThatAttempt() {
        for (finishing in listOf(false, true)) {
            val fixture = Fixture("原输入")
            val completions = mutableListOf<String>()
            fixture.onRecognized = { completions += it }
            fixture.coordinator.toggle()
            if (finishing) {
                fixture.emit(VoiceInputPhase.LISTENING, "半句识别")
                fixture.coordinator.toggle()
            }
            assertEquals("此刻手工输入", fixture.coordinator.takeForSubmit("此刻手工输入"))
            fixture.emit(VoiceInputPhase.LISTENING, "启动迟到的音频")
            fixture.emit(VoiceInputPhase.IDLE, "启动迟到的结果", final = true)
            assertEquals("此刻手工输入", fixture.input)
            assertFalse(fixture.state.active)
            assertTrue(completions.isEmpty())
        }
    }

    @Test fun blankManualSendKeepsCaptureAvailableWhileDisposedAttemptCannotSend() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        assertNull(fixture.coordinator.takeForSubmit(" "))
        assertEquals(VoiceInputPhase.STARTING, fixture.state.phase)
        assertTrue(fixture.port.cancels.isEmpty())
        fixture.coordinator.destroy()
        assertNull(fixture.coordinator.takeForSubmit("关闭后的文本"))
        assertEquals(VoiceInputPhase.DISPOSED, fixture.state.phase)
    }

    @Test fun cancellationAndRecognitionFailureNeverDiscardManualEditsOrSendThem() {
        for (termination in listOf("cancel", "nativeCancel", "error")) {
            val fixture = Fixture("原输入")
            val completions = mutableListOf<String>()
            fixture.onRecognized = { completions += it }
            fixture.coordinator.toggle()
            fixture.emit(VoiceInputPhase.LISTENING, "语音临时文本")
            fixture.coordinator.editText("已手动修改的文本")
            when (termination) {
                "cancel" -> fixture.coordinator.cancel()
                "nativeCancel" -> fixture.emit(VoiceInputPhase.IDLE, "最后一段语音", final = false)
                else -> fixture.emit(VoiceInputPhase.IDLE, "最后一段语音", error = "识别中断")
            }
            assertEquals("已手动修改的文本", fixture.input)
            assertFalse(fixture.state.active)
            assertTrue(completions.isEmpty())
            assertEquals(if (termination == "error") "识别中断" else "", fixture.state.error)
        }
    }

    @Test fun aNewRecordingCanReplaceThePreviousAttemptManualDraft() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.coordinator.editText("上一次手工文字")
        fixture.coordinator.cancel()
        fixture.coordinator.toggle()
        assertFalse(fixture.state.manuallyEdited)
        fixture.emit(VoiceInputPhase.LISTENING, "新的目的地")
        assertEquals("新的目的地", fixture.input)
    }

    @Test fun sendingUneditedSpeechWaitsForCorrectedFinalAndDoesNotCancelCapture() {
        val fixture = Fixture()
        val completed = mutableListOf<String>()
        fixture.onRecognized = { completed += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去故工")
        assertNull(fixture.coordinator.takeForSubmit())
        assertTrue(fixture.state.transcribing)
        assertEquals(listOf(fixture.requestId), fixture.port.stops)
        assertTrue(fixture.port.cancels.isEmpty())
        repeat(3) { assertNull(fixture.coordinator.takeForSubmit()) }
        assertEquals(1, fixture.port.stops.size)
        assertTrue(completed.isEmpty())
        fixture.emit(VoiceInputPhase.IDLE, "去故宫博物院", final = true)
        fixture.emit(VoiceInputPhase.IDLE, "重复的最终结果", final = true)
        assertEquals(listOf("去故宫博物院"), completed)
        assertEquals("去故宫博物院", fixture.input)
    }

    @Test fun failedFinalAfterSendKeepsPartialAsDraftWithoutSubmitting() {
        val fixture = Fixture()
        val completed = mutableListOf<String>()
        fixture.onRecognized = { completed += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去故工")
        assertNull(fixture.coordinator.takeForSubmit())
        fixture.emit(VoiceInputPhase.IDLE, error = "识别响应超时")
        assertEquals("去故工", fixture.input)
        assertFalse(fixture.state.active)
        assertEquals("识别响应超时", fixture.state.error)
        assertTrue(completed.isEmpty())
    }

    @Test fun sendingExistingTypedDraftDuringPreparationDoesNotPretendItWasRecognized() {
        val fixture = Fixture("已输入的目的地")
        val completed = mutableListOf<String>()
        fixture.onRecognized = { completed += it }
        fixture.coordinator.toggle()
        assertEquals("已输入的目的地", fixture.coordinator.takeForSubmit())
        assertFalse(fixture.state.active)
        assertEquals(listOf(fixture.requestId), fixture.port.cancels)
        fixture.emit(VoiceInputPhase.IDLE, "迟到的语音", final = true)
        assertEquals("已输入的目的地", fixture.input)
        assertTrue(completed.isEmpty())
    }

    @Test fun delayedPreviousProgramTextCannotSuppressTheCorrectedFinal() {
        val fixture = Fixture()
        val completed = mutableListOf<String>()
        fixture.onRecognized = { completed += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去故")
        fixture.emit(VoiceInputPhase.LISTENING, "去故工")
        fixture.coordinator.editText("去故")
        assertFalse(fixture.state.manuallyEdited)
        assertEquals("去故工", fixture.input)
        fixture.emit(VoiceInputPhase.IDLE, "去故宫", final = true)
        assertEquals(listOf("去故宫"), completed)
    }

    @Test fun undoAfterAnActualEditCanRestoreThePreviousRecognizedText() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去东站")
        fixture.coordinator.editText("去西站")
        fixture.coordinator.editText("去东站")
        assertEquals("去东站", fixture.input)
        fixture.emit(VoiceInputPhase.IDLE, "去东站南广场", final = true)
        assertEquals("去东站南广场", fixture.input)
    }

    @Test fun clearingAfterStartingEmptyIsNotMistakenForAnInitialProgramEcho() {
        val fixture = Fixture()
        val completed = mutableListOf<String>()
        fixture.onRecognized = { completed += it }
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "去东站")
        fixture.coordinator.editText("")
        fixture.emit(VoiceInputPhase.IDLE, "去东站南广场", final = true)
        assertEquals("", fixture.input)
        assertTrue(completed.isEmpty())
    }

    @Test fun finalRecognitionCorrectsUneditedRegionWithoutOverwritingUserCorrection() {
        assertEquals("先去西站，再去故宫", VoiceTextMerge.merge(
            "先去东站，再去故工", "先去西站，再去故工", "先去东站，再去故宫"))
    }

    @Test fun deletingOneClauseKeepsItDeletedWhileNewSpeechIsAppended() {
        val fixture = Fixture()
        fixture.coordinator.toggle()
        fixture.emit(VoiceInputPhase.LISTENING, "先去东站，再去公园")
        fixture.coordinator.editText("先去东站")
        fixture.emit(VoiceInputPhase.IDLE, "先去东站，再去公园，最后去车站", final = true)
        assertEquals("先去东站，最后去车站", fixture.input)
    }

    @Test fun matchingTypedAndRecognizedContinuationIsNotDuplicated() {
        assertEquals("去车站南门，再去公园", VoiceTextMerge.merge(
            "去车站", "去车站南门", "去车站南门，再去公园"))
        assertEquals("去车站南广场", VoiceTextMerge.merge(
            "去车站", "去车站南广场", "去车站北广场"))
    }

    @Test fun longPastedRewriteUsesBoundedConservativeMerge() {
        val speech = "东".repeat(20000)
        val edited = "用户输入".repeat(5000)
        assertEquals(edited, VoiceTextMerge.merge(speech, edited, "西".repeat(20000)))
    }

    @Test fun conflictingPoiCompletionsKeepTypedPlaceAndAppendOnlyTheNextClause() {
        assertEquals("去车站南广场，然后去公园", VoiceTextMerge.merge(
            "去车站", "去车站南广场", "去车站北广场，然后去公园"))
        assertEquals("去南广场", VoiceTextMerge.merge("去广场", "去南广场", "去北广场"))
    }

    @Test fun conflictingAsrAbbreviationCannotDeleteCharactersFromTheEditedPoi() {
        assertEquals("去清华大学，然后去车站", VoiceTextMerge.merge(
            "去北京大学", "去清华大学", "去北大，然后去车站"))
        assertEquals("去公园南门，然后去车站", VoiceTextMerge.merge(
            "去公元", "去公园南门", "去公园，然后去车站"))
    }

    @Test fun distinctLaterPlacesWithSameLastCharacterAreStillAppended() {
        assertEquals("去西站，再去火车站", VoiceTextMerge.merge(
            "去车站", "去西站", "去车站，再去火车站"))
        assertEquals("去南广场然后去北广场", VoiceTextMerge.merge(
            "去广场", "去南广场", "去广场然后去北广场"))
    }

    @Test fun eachRecordingPassesTrimmedDistinctCurrentHotwords() {
        val port = FakePort()
        var hotwords = listOf(" 西湖 ", "", "站", "湖".repeat(21), "西湖", "杭州东站")
        val coordinator = VoiceInputCoordinator(port, { "" }, readHotwords = { hotwords }) {}
        coordinator.toggle()
        assertEquals(listOf("西湖", "杭州东站"), port.hotwords.single())
        coordinator.cancel()
        hotwords = listOf("灵隐寺")
        coordinator.toggle()
        assertEquals(listOf("灵隐寺"), port.hotwords.last())
    }

    @Test fun finalDictationSubmitsThroughAgentAndKeepsFullRequestAfterMultiStopPlanning() {
        val voicePort = FakePort()
        val navigationPort = FakeNavigationPort()
        val store = NavigationStore(navigationPort) {}
        val origin = GeoPoint(39.9, 116.3)
        val destination = Place("车站", GeoPoint(39.93, 116.33))
        store.configure(NavigationEnvironment(destination, true, "test-app-id"))
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = origin))
        store.setInputText("原输入")
        val requests = mutableListOf<NavigationAgentRequest>()
        var reply: (NavigationResponse) -> Unit = {}
        val transport = object : NavigationAgentTransport {
            override fun send(request: NavigationAgentRequest, onResponse: (NavigationResponse) -> Unit,
                onError: (String) -> Unit) {
                requests += request
                reply = onResponse
            }
            override fun cancel(requestId: String) {}
            override fun close() {}
        }
        val agent = NavigationAgentRepository(transport, { response ->
            store.acceptResponse(response)
            store.setInputSubmitting(false)
        }, store::setInputError)
        val coordinator = VoiceInputCoordinator(
            port = voicePort,
            readInput = { store.state.shell.input.text },
            onRecognized = { text ->
                assertTrue(store.state.canSubmit, "the voice lock must be cleared before normal submission")
                assertEquals(text, store.state.shell.input.text)
                store.beginDestinationRequest()
                store.setInputSubmitting(true)
                agent.submitText(text, store.state.draft.travelMode.wireValue, store.state.runtime.location)
            },
            publish = { voice ->
                store.updateShell { shell -> shell.copy(input = shell.input.copy(text = voice.text,
                    voiceActive = voice.active, micListening = voice.recording, error = voice.error)) }
            }
        )
        coordinator.toggle()
        val requestId = voicePort.starts.single()
        voicePort.event(VoiceInputEvent(requestId, VoiceInputPhase.LISTENING, "先去公园"))
        assertFalse(store.state.canSubmit)
        assertTrue(requests.isEmpty())
        coordinator.toggle()
        assertTrue(requests.isEmpty())
        val text = "先去公园，再去图书馆，最后去车站"
        voicePort.event(VoiceInputEvent(requestId, VoiceInputPhase.IDLE, text, isFinal = true))
        assertEquals(text, requests.single().text)
        assertEquals(text, store.state.shell.input.text)
        assertFalse(store.state.shell.input.voiceActive)
        assertTrue(store.state.busy)
        assertFalse(store.state.canSubmit, "the request lock takes over after dictation finishes")

        val stops = listOf(
            NavigationStop("waypoint", "公园", GeoPoint(39.91, 116.31)),
            NavigationStop("waypoint", "图书馆", GeoPoint(39.92, 116.32)),
            NavigationStop("destination", destination.name, destination.point)
        )
        reply(NavigationResponse("ok", "navigation_task", true, false, NavigationTask(stops),
            requestId = requests.single().requestId!!))
        assertEquals(text, store.state.shell.input.text)
        assertEquals(listOf("公园", "图书馆"), store.state.draft.waypoints.map { it.name })
        assertEquals(destination, store.state.draft.destination)
        assertEquals(store.state.draft, navigationPort.plans.single())
        assertEquals(0, navigationPort.starts)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = origin,
            route = RouteSummary(1, 3000.0, 900.0), routeRevision = store.state.draftRevision))
        assertTrue(store.state.canSubmit)
        assertEquals(text, store.state.shell.input.text)
        voicePort.event(VoiceInputEvent(requestId, VoiceInputPhase.IDLE, "重复的语音回调", isFinal = true))
        assertEquals(1, requests.size)
        assertEquals(text, store.state.shell.input.text)
    }

    private class Fixture(initial: String = "") {
        val port = FakePort()
        var input = initial
        var state = VoiceInputState()
        var onRecognized: (String) -> Unit = {}
        var onPublish: (VoiceInputState) -> Unit = {}
        val coordinator = VoiceInputCoordinator(port, { input }, { onRecognized(it) }) { next ->
            state = next
            input = next.text
            onPublish(next)
        }
        val requestId: String get() = port.starts.last()
        fun emit(phase: VoiceInputPhase, text: String = "", final: Boolean = false,
            error: String = "", requestId: String = this.requestId) {
            port.event(VoiceInputEvent(requestId, phase, text, final, error))
        }
    }

    private class FakePort : VoiceInputPort {
        var event: (VoiceInputEvent) -> Unit = {}
        val starts = mutableListOf<String>()
        val hotwords = mutableListOf<List<String>>()
        val stops = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        var unsubscribes = 0
        var startFailure: Exception? = null
        var stopFailure: Exception? = null
        var onCancel: (String) -> Unit = {}
        var onStop: (String) -> Unit = {}
        override fun subscribe(onEvent: (VoiceInputEvent) -> Unit) { event = onEvent }
        override fun start(requestId: String, hotwords: List<String>) {
            starts += requestId
            this.hotwords += hotwords
            startFailure?.let { throw it }
        }
        override fun stop(requestId: String) {
            stops += requestId
            stopFailure?.let { throw it }
            onStop(requestId)
        }
        override fun cancel(requestId: String) { cancels += requestId; onCancel(requestId) }
        override fun unsubscribe() { unsubscribes++ }
    }

    private class FakeNavigationPort : NavigationPort {
        val plans = mutableListOf<NavigationDraft>()
        var starts = 0
        override fun open() {}
        override fun locate() {}
        override fun plan(draft: NavigationDraft, revision: Int) { plans += draft }
        override fun start(simulation: Boolean, revision: Int) { starts++ }
        override fun stop() {}
        override fun exitGuidance() {}
        override fun clearRoute() {}
        override fun overview() {}
        override fun follow() {}
    }
}
