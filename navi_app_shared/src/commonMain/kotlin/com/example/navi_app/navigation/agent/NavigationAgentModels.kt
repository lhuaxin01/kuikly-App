package com.example.navi_app.navigation.agent

import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.protocol.NavigationTask

/** Stable request names used by MapTemplate and navi-claw. */
enum class NavigationAgentRequestType(val wireValue: String) {
    TEXT("text"),
    CANDIDATE_SELECTION("candidate_selection"),
    FEEDBACK("feedback"),
    CANCEL("cancel")
}

data class NavigationAgentRequest(
    val type: NavigationAgentRequestType,
    val text: String? = null,
    val sessionId: String? = null,
    val requestId: String? = null,
    val traceId: String? = null,
    val feedbackRequestId: String? = null,
    val accepted: Boolean? = null,
    val travelMode: String? = null,
    val location: GeoPoint? = null,
    val groupId: String? = null,
    val candidateId: String? = null,
    val rating: Int? = null,
    val comment: String? = null,
    val kind: String? = null,
    val existingTask: NavigationTask? = null
)

data class NavigationAgentSnapshot(
    val sessionId: String = "",
    val pendingRequestId: String? = null,
    val lastError: String? = null,
    val closed: Boolean = false,
    val lastResponseRequestId: String? = null,
    val lastTraceId: String? = null
)

/** A transport owns HTTP/WebSocket details; it must not own NavigationStore or SDK state. */
interface NavigationAgentTransport {
    fun send(
        request: NavigationAgentRequest,
        onResponse: (com.example.navi_app.navigation.protocol.NavigationResponse) -> Unit,
        onError: (String) -> Unit
    )

    fun cancel(requestId: String)
    fun close()
}
