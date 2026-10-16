package com.example.navi_app.navigation.agent

import com.example.navi_app.navigation.protocol.NavigationResponse
import com.example.navi_app.navigation.protocol.NavigationTask
import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.domain.NavigationTextIntent
import kotlin.random.Random

/**
 * MapTemplate-compatible Agent session. It only translates user intent and responses;
 * route planning remains owned by NavigationPort/HarmonyNavigationSession.
 */
class NavigationAgentRepository(
    private val transport: NavigationAgentTransport,
    private val onResponse: (NavigationResponse) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var sequence: Long = 0
    private val requestPrefix: String
    private var sessionId: String = ""
    private var pendingRequestId: String? = null
    private var lastError: String? = null
    private var disposed = false
    private var generation: Long = 0
    private var lastResponseRequestId: String? = null
    private var lastTraceId: String? = null

    init {
        // A random per-instance prefix prevents collisions after a page/session recreation.
        requestPrefix = "navi-${Random.nextLong().toString(16)}"
    }

    fun snapshot() = NavigationAgentSnapshot(sessionId, pendingRequestId, lastError, disposed,
        lastResponseRequestId, lastTraceId)

    fun submitText(text: String, travelMode: String? = null, location: GeoPoint? = null,
        existingTask: NavigationTask? = null) {
        require(text.isNotBlank()) { "请输入导航内容" }
        if (existingTask != null) {
            require(existingTask.stops.isNotEmpty() &&
                existingTask.stops.count { it.role.equals("destination", true) } == 1 &&
                existingTask.stops.all { !it.needsConfirmation && it.point?.isValid() == true }) {
                "当前导航任务缺少有效地点，请重新输入完整目的地"
            }
        }
        val mode = NavigationTextIntent.resolveTravelMode(text, travelMode ?: existingTask?.travelMode)
        send(NavigationAgentRequest(NavigationAgentRequestType.TEXT, text = text.trim(),
            travelMode = mode, location = location,
            kind = if (existingTask == null) "new_intent" else "modify_task",
            existingTask = existingTask?.copy(travelMode = mode ?: existingTask.travelMode)))
    }

    fun selectCandidate(groupId: String, candidateId: String, location: GeoPoint? = null,
        travelMode: String? = null) {
        require(groupId.isNotBlank() && candidateId.isNotBlank()) { "候选地点标识缺失" }
        require(sessionId.isNotBlank()) { "导航会话已失效，请重新搜索" }
        require(pendingRequestId == null) { "上一条导航请求仍在处理中，请稍候" }
        send(NavigationAgentRequest(NavigationAgentRequestType.CANDIDATE_SELECTION,
            sessionId = sessionId, groupId = groupId, candidateId = candidateId,
            kind = "disambiguation_choice", location = location, travelMode = travelMode))
    }

    fun submitFeedback(
        accepted: Boolean,
        comment: String? = null,
        sessionId: String? = null,
        originalRequestId: String? = null,
        traceId: String? = null,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        val actualSession = sessionId?.ifBlank { null } ?: this.sessionId.ifBlank { null }
        val actualRequest = originalRequestId?.ifBlank { null } ?: lastResponseRequestId
        val actualTrace = traceId?.ifBlank { null } ?: lastTraceId
        require(!actualSession.isNullOrBlank()) { "导航会话已失效，无法提交反馈" }
        require(!actualRequest.isNullOrBlank()) { "缺少原始导航请求标识，无法提交反馈" }
        require(!actualTrace.isNullOrBlank()) { "缺少原始导航追踪标识，无法提交反馈" }
        require(pendingRequestId == null) { "上一条导航请求仍在处理中，请稍候" }
        send(NavigationAgentRequest(NavigationAgentRequestType.FEEDBACK,
            sessionId = actualSession, feedbackRequestId = actualRequest,
            traceId = actualTrace,
            accepted = accepted, comment = comment?.trim()?.ifBlank { null }),
            onSuccess, onFailure, publishResponse = false)
    }

    /** Legacy rating API retained for callers that still use MapTemplate's 1..5 rating. */
    fun submitFeedback(rating: Int, comment: String? = null) {
        require(rating in 1..5) { "评分必须在 1 到 5 之间" }
        submitFeedback(rating >= 4, comment)
    }

    fun useSession(newSessionId: String) {
        require(newSessionId.isNotBlank()) { "导航会话标识不能为空" }
        check(!disposed) { "导航会话已关闭" }
        cancelPending()
        generation++
        sessionId = newSessionId
        lastResponseRequestId = null
        lastTraceId = null
        lastError = null
    }

    fun cancelPending() {
        pendingRequestId?.let { transport.cancel(it) }
        pendingRequestId = null
    }

    fun closeSession() {
        if (disposed) return
        cancelPending()
        // The transport can hold an authenticated socket even before a server session id exists.
        transport.close()
        disposed = true
        generation++
        sessionId = ""
        lastResponseRequestId = null
        lastTraceId = null
    }

    /** Clears auth/session context after logout while keeping this repository reusable. */
    fun resetSession() {
        cancelPending()
        transport.close()
        generation++
        sessionId = ""
        lastResponseRequestId = null
        lastTraceId = null
        lastError = null
        disposed = false
    }

    private fun send(
        base: NavigationAgentRequest,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {},
        publishResponse: Boolean = true
    ) {
        check(!disposed) { "导航会话已关闭" }
        cancelPending()
        val requestGeneration = generation
        val request = base.copy(
            sessionId = base.sessionId ?: sessionId.ifBlank { null },
            requestId = base.requestId ?: "$requestPrefix-${++sequence}",
            traceId = base.traceId ?: "$requestPrefix-trace-${sequence}"
        )
        pendingRequestId = request.requestId
        lastError = null
        transport.send(request, { response ->
            // A cancelled request may still deliver a late callback from HTTP/WebSocket.
            if (requestGeneration != generation || pendingRequestId != request.requestId) return@send
            val responseRequestId = response.requestId
            if (publishResponse && responseRequestId.isNotBlank() && responseRequestId != request.requestId) return@send
            if (response.sessionId.isNotBlank()) sessionId = response.sessionId
            // Feedback acknowledgements must never replace the navigation context used by
            // a later feedback operation. A trace is valid only when it came from a response.
            if (publishResponse) {
                lastResponseRequestId = response.requestId.ifBlank { request.requestId }
                lastTraceId = response.traceId.ifBlank { null }
            }
            if (pendingRequestId == request.requestId) pendingRequestId = null
            if (publishResponse) onResponse(response)
            onSuccess()
        }, { error ->
            if (requestGeneration == generation && pendingRequestId == request.requestId) {
                pendingRequestId = null
                lastError = error
                // The gateway validates the request id against the channel
                // binding. If a reconnect or account switch left the native
                // socket bound to another session, drop the stale shared id
                // so the next intent uses the channel's current session.
                if (error.contains("session_id does not match bound channel session", ignoreCase = true) ||
                    error.contains("bound channel session", ignoreCase = true)) {
                    sessionId = ""
                    lastResponseRequestId = null
                    lastTraceId = null
                }
                onError(error)
                onFailure(error)
            }
        })
    }
}
