package com.example.navi_app.navigation.voice

/**
 * Dictation updates the draft; a confirmed final result may be handed to the caller's submit flow.
 * The coordinator owns neither networking nor navigation commands.
 * A request id isolates delayed permission/recognition callbacks after cancellation or retry.
 */
class VoiceInputCoordinator(
    private val port: VoiceInputPort,
    private val readInput: () -> String,
    private val onRecognized: (String) -> Unit = {},
    private val readHotwords: () -> List<String> = { emptyList() },
    private val publish: (VoiceInputState) -> Unit
) {
    var state = VoiceInputState()
        private set
    private var activeRequestId: String? = null
    private var originalText = ""
    private var recognizedText = ""
    private var clearedByUser = false
    private val recentProgramTexts = mutableListOf<String>()

    init { port.subscribe(::onEvent) }

    fun toggle() {
        when (state.phase) {
            VoiceInputPhase.IDLE -> start()
            VoiceInputPhase.LISTENING -> stop()
            else -> Unit
        }
    }

    /** Without an event source, ignore only a small window of known programmatic echoes. */
    fun editText(text: String) {
        if (!state.active || text == state.text) return
        // Empty text is a meaningful clear action, even when the recording began empty.
        if (text.isNotEmpty() && text in recentProgramTexts) return
        // Clear the old echo history after accepting an edit so a user's subsequent Undo
        // can restore a previous recognized value. Exact old-value edits remain ambiguous.
        recentProgramTexts.clear()
        clearedByUser = text.isEmpty()
        update(state.copy(text = text, manuallyEdited = true, error = ""), programmatic = false)
    }

    /**
     * Sending untouched dictation finishes capture and waits for the corrected final result.
     * Deliberately edited/typed text can be submitted immediately. A null return means either
     * waiting for recognition or no sendable active attempt; final results use onRecognized.
     */
    fun takeForSubmit(text: String = readInput()): String? {
        val requestId = activeRequestId ?: return null
        if (!state.active || text.isBlank()) return null
        val typed = state.manuallyEdited || (text != state.text && text !in recentProgramTexts)
        if (!typed && state.phase != VoiceInputPhase.STARTING) {
            if (state.phase == VoiceInputPhase.LISTENING) stop()
            return null
        }
        activeRequestId = null
        try { port.cancel(requestId) } catch (_: Exception) { /* The retired attempt cannot publish further input. */ }
        update(state.copy(phase = VoiceInputPhase.IDLE, text = text, error = "",
            manuallyEdited = state.manuallyEdited || text != state.text))
        return if (state.phase == VoiceInputPhase.IDLE && state.requestId == requestId) text.trim() else null
    }

    private fun start() {
        originalText = readInput()
        recognizedText = ""
        clearedByUser = false
        recentProgramTexts.clear()
        val requestId = "voice-${++nextRequestId}"
        activeRequestId = requestId
        update(VoiceInputState(VoiceInputPhase.STARTING, originalText, requestId = requestId))
        try {
            port.start(requestId, readHotwords().map { it.trim() }.filter { it.length in 2..20 }.distinct().take(200))
        } catch (error: Exception) {
            fail(requestId, error.message ?: "无法启动语音输入，请重试")
        }
    }

    private fun stop() {
        val requestId = activeRequestId ?: return
        // A second tap stops capture but retains the request until final recognition arrives.
        update(state.copy(phase = VoiceInputPhase.FINISHING))
        try {
            port.stop(requestId)
        } catch (error: Exception) {
            fail(requestId, error.message ?: "无法结束语音输入，请重试")
        }
    }

    fun onEvent(event: VoiceInputEvent) {
        if (state.phase == VoiceInputPhase.DISPOSED || event.requestId != activeRequestId) return
        if (event.text.isNotBlank()) {
            val nextSpeech = event.text.trim()
            val nextText = when {
                clearedByUser -> ""
                state.manuallyEdited -> VoiceTextMerge.merge(recognizedText, state.text, nextSpeech)
                else -> nextSpeech
            }
            recognizedText = nextSpeech
            // Error/final handling below publishes once, with the merged draft.
            state = state.copy(text = nextText)
        }
        if (event.error.isNotBlank()) {
            fail(event.requestId, event.error)
            return
        }
        if (event.phase == VoiceInputPhase.IDLE && !event.isFinal) {
            // Host back/background events can cancel natively before shared page teardown.
            // They must discard partial dictation exactly like the visible Cancel action.
            activeRequestId = null
            update(state.copy(phase = VoiceInputPhase.IDLE, text = cancellationText(), error = ""))
            return
        }
        if (event.isFinal) {
            val finalText = event.text.trim()
            val submittedText = if (state.manuallyEdited) state.text.trim() else finalText
            activeRequestId = null
            update(state.copy(phase = VoiceInputPhase.IDLE,
                text = visibleText(),
                error = if (!state.manuallyEdited && recognizedText.isBlank()) "未识别到语音，请再试一次" else ""))
            // Publish the unlocked input first, so the caller can reuse its normal guarded
            // submit action. Reentrant disposal/retry must not submit this retired attempt.
            // A partial fallback can remain editable, but only explicit final text is sent.
            if (finalText.isNotEmpty() && submittedText.isNotEmpty() && state.phase == VoiceInputPhase.IDLE &&
                state.requestId == event.requestId) onRecognized(submittedText)
            return
        }
        // In-flight start/listening events cannot reopen a microphone already being stopped.
        val phase = when {
            state.phase == VoiceInputPhase.FINISHING -> VoiceInputPhase.FINISHING
            state.phase == VoiceInputPhase.LISTENING && event.phase == VoiceInputPhase.STARTING -> state.phase
            else -> event.phase
        }
        update(state.copy(phase = phase, text = visibleText()))
    }

    /** Discard dictation on cancel while preserving any deliberate edits made during recording. */
    fun cancel() {
        if (state.phase == VoiceInputPhase.DISPOSED) return
        val requestId = activeRequestId ?: return
        activeRequestId = null
        update(state.copy(phase = VoiceInputPhase.IDLE, text = cancellationText(), error = ""))
        // Invalidate before cleanup: synchronous native completion must not replace the draft.
        try { port.cancel(requestId) } catch (_: Exception) { /* Native teardown also runs on unsubscribe. */ }
    }

    fun destroy() {
        if (state.phase == VoiceInputPhase.DISPOSED) return
        cancel()
        update(state.copy(phase = VoiceInputPhase.DISPOSED, error = ""))
        try { port.unsubscribe() } catch (_: Exception) { /* No callback may revive a disposed page. */ }
    }

    private fun fail(requestId: String, error: String) {
        if (activeRequestId != requestId) return
        activeRequestId = null
        update(state.copy(phase = VoiceInputPhase.IDLE,
            text = visibleText(), error = error.ifBlank { "语音输入失败，请重试" }))
        try { port.cancel(requestId) } catch (_: Exception) { /* Request is already invalidated. */ }
    }

    private fun update(next: VoiceInputState, programmatic: Boolean = true) {
        state = next
        if (programmatic && next.active && next.text !in recentProgramTexts) {
            recentProgramTexts += next.text
            if (recentProgramTexts.size > 4) recentProgramTexts.removeAt(0)
        }
        publish(next)
    }

    private fun visibleText() = if (state.manuallyEdited) state.text else recognizedText.ifBlank { originalText }
    private fun cancellationText() = if (state.manuallyEdited) state.text else originalText

    companion object { private var nextRequestId = 0L }
}
