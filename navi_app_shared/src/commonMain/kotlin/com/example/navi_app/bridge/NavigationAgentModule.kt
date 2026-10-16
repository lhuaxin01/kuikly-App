package com.example.navi_app.bridge

import com.example.navi_app.navigation.agent.NavigationAgentRequest
import com.example.navi_app.navigation.agent.NavigationAgentRequestCodec
import com.example.navi_app.navigation.agent.NavigationAgentTransport
import com.example.navi_app.navigation.protocol.NavigationResponse
import com.example.navi_app.navigation.protocol.NavigationResponseCodec
import com.example.navi_app.navigation.presentation.SessionTransport
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * JSON-only boundary for navi-claw. The Harmony implementation owns the HTTP/WebSocket client;
 * this module deliberately does not know about a URL, credentials, or platform networking APIs.
 */
class NavigationAgentModule : Module(), NavigationAgentTransport, SessionTransport {
    override fun moduleName() = MODULE_NAME

    override fun send(request: NavigationAgentRequest, onResponse: (NavigationResponse) -> Unit,
        onError: (String) -> Unit) {
        toNative(false, "request", NavigationAgentRequestCodec.encode(request).toString(), { result ->
            if (result == null) {
                onError("导航服务没有返回结果")
            } else if (result.has("ok") && !result.optBoolean("ok")) {
                onError(result.optString("message", "导航服务请求失败"))
            } else {
                val response = result.optJSONObject("response") ?: result
                onResponse(NavigationResponseCodec.decode(response))
            }
        }, false)
    }

    override fun cancel(requestId: String) {
        command("cancel", JSONObject().put("request_id", requestId))
    }

    override fun close() {
        command("close")
    }

    /** Authentication is kept behind the same native boundary as the WebSocket transport. */
    fun authenticate(username: String, password: String, register: Boolean, displayName: String,
        callback: (JSONObject) -> Unit) {
        val payload = JSONObject()
            // Keep the HTTP contract identical to MapTemplate/AuthApiClient.
            .put("username", username.trim())
            .put("password", password)
            .put("register", register)
            .put("displayName", displayName.trim())
        toNative(false, "auth", payload.toString(), { result -> callback(result ?: JSONObject()) }, false)
    }

    fun restoreAuth(callback: (JSONObject) -> Unit) {
        toNative(false, "auth.restore", "{}", { result -> callback(result ?: JSONObject()) }, false)
    }

    /** Binds subsequent navigation requests to an existing navi-claw session. */
    override fun attachSession(sessionId: String, callback: (JSONObject) -> Unit) {
        val payload = JSONObject().put("sessionId", sessionId)
        toNative(false, "session.attach", payload.toString(), { result ->
            callback(result ?: JSONObject())
        }, false)
    }

    /** Creates a server-side navigation session and returns its id through the native bridge. */
    override fun createSession(title: String, callback: (JSONObject) -> Unit) {
        val payload = JSONObject()
        if (title.trim().isNotEmpty()) payload.put("title", title.trim())
        toNative(false, "session.create", payload.toString(), { result ->
            callback(result ?: JSONObject())
        }, false)
    }

    fun logout(callback: (JSONObject) -> Unit) {
        toNative(false, "logout", "{}", { result -> callback(result ?: JSONObject()) }, false)
    }

    override fun listSessions(callback: (JSONObject) -> Unit) {
        toNative(false, "sessions.list", "{}", { result -> callback(result ?: JSONObject()) }, false)
    }

    override fun loadSessionHistory(sessionId: String, callback: (JSONObject) -> Unit) {
        val payload = JSONObject().put("sessionId", sessionId).put("limit", 50)
        toNative(false, "sessions.history", payload.toString(), { result -> callback(result ?: JSONObject()) }, false)
    }

    private fun command(name: String, payload: JSONObject? = null) {
        toNative(false, name, payload?.toString(), null, false)
    }

    companion object { const val MODULE_NAME = "KRNavigationAgentModule" }
}
