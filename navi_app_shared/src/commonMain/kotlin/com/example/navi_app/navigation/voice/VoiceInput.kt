package com.example.navi_app.navigation.voice

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

enum class VoiceInputPhase { IDLE, STARTING, LISTENING, FINISHING, DISPOSED }

data class VoiceInputState(
    val phase: VoiceInputPhase = VoiceInputPhase.IDLE,
    val text: String = "",
    val error: String = "",
    val requestId: String = "",
    /** User-edited regions are protected while unrelated recognition updates may continue. */
    val manuallyEdited: Boolean = false
) {
    val active: Boolean get() = phase == VoiceInputPhase.STARTING ||
        phase == VoiceInputPhase.LISTENING || phase == VoiceInputPhase.FINISHING
    val recording: Boolean get() = phase == VoiceInputPhase.LISTENING
    val transcribing: Boolean get() = phase == VoiceInputPhase.FINISHING
    val canToggle: Boolean get() = phase == VoiceInputPhase.IDLE || recording
    val statusText: String get() = when (phase) {
        VoiceInputPhase.STARTING -> "正在准备语音识别，请稍候…"
        VoiceInputPhase.LISTENING -> "正在聆听，点击麦克风结束"
        VoiceInputPhase.FINISHING -> "正在识别…"
        else -> ""
    }
}

/** The platform publishes cumulative text, never separate SDK sentence fragments. */
data class VoiceInputEvent(
    val requestId: String,
    val phase: VoiceInputPhase,
    val text: String = "",
    val isFinal: Boolean = false,
    val error: String = ""
) {
    companion object {
        fun decode(json: JSONObject): VoiceInputEvent? {
            val phase = when (json.optString("state")) {
                "idle" -> VoiceInputPhase.IDLE
                "starting" -> VoiceInputPhase.STARTING
                "listening" -> VoiceInputPhase.LISTENING
                "finishing" -> VoiceInputPhase.FINISHING
                else -> return null
            }
            return VoiceInputEvent(json.optString("requestId"), phase, json.optString("text"),
                json.optBoolean("isFinal"), json.optString("error"))
        }
    }
}

/** Microphone permission, audio capture, recognition and timeouts belong to the native layer. */
interface VoiceInputPort {
    fun subscribe(onEvent: (VoiceInputEvent) -> Unit)
    fun start(requestId: String, hotwords: List<String> = emptyList())
    fun stop(requestId: String)
    fun cancel(requestId: String)
    fun unsubscribe()
}
