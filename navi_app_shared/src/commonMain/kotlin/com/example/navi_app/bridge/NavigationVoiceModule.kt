package com.example.navi_app.bridge

import com.example.navi_app.navigation.voice.VoiceInputEvent
import com.example.navi_app.navigation.voice.VoiceInputPhase
import com.example.navi_app.navigation.voice.VoiceInputPort
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** JSON bridge only; native Harmony code owns permission, audio and speech SDK resources. */
class NavigationVoiceModule : Module(), VoiceInputPort {
    override fun moduleName() = MODULE_NAME
    private var subscription: CallbackRef? = null
    private var onEvent: (VoiceInputEvent) -> Unit = {}

    override fun subscribe(onEvent: (VoiceInputEvent) -> Unit) {
        unsubscribe()
        this.onEvent = onEvent
        subscription = toNative(true, "subscribe", null, { event ->
            if (event != null) VoiceInputEvent.decode(event)?.let { this.onEvent(it) }
        }, false).callbackRef
    }

    override fun start(requestId: String, hotwords: List<String>) = command("start", requestId,
        JSONObject().put("requestId", requestId).put("hotwords", JSONArray().apply {
            hotwords.forEach { put(it) }
        }))
    override fun stop(requestId: String) = command("stop", requestId)
    override fun cancel(requestId: String) = command("cancel", requestId)

    override fun unsubscribe() {
        onEvent = {}
        if (subscription != null) {
            toNative(false, "unsubscribe", null, null, false)
            subscription?.let { removeCallback(it) }
            subscription = null
        }
    }

    private fun command(name: String, requestId: String,
        params: JSONObject = JSONObject().put("requestId", requestId)) {
        toNative(false, name, params.toString(), { result ->
            if (result == null || !result.optBoolean("ok")) {
                onEvent(VoiceInputEvent(requestId, VoiceInputPhase.IDLE,
                    error = result?.optString("message")?.ifBlank { "语音输入操作失败，请重试" }
                        ?: "语音输入没有返回结果，请重试"))
            }
        }, false)
    }

    companion object { const val MODULE_NAME = "KRNavigationVoiceModule" }
}
