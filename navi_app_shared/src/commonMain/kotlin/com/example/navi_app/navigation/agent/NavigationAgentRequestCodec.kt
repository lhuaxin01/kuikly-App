package com.example.navi_app.navigation.agent

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.example.navi_app.navigation.protocol.NavigationTask

/** Encodes the unchanged navi-claw wire contract at one boundary. */
object NavigationAgentRequestCodec {
    fun encode(request: NavigationAgentRequest): JSONObject = JSONObject().apply {
        put("type", request.type.wireValue)
        request.kind?.let { put("kind", it) }
        request.text?.let { put("text", it) }
        request.sessionId?.let { put("session_id", it) }
        request.requestId?.let { put("request_id", it) }
        request.traceId?.let { put("trace_id", it) }
        request.feedbackRequestId?.let { put("feedback_request_id", it) }
        request.accepted?.let { put("accepted", it) }
        request.travelMode?.let { put("travel_mode", it) }
        request.location?.let {
            put("location", JSONObject().apply {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
            })
        }
        request.groupId?.let { put("group_id", it) }
        request.candidateId?.let { put("candidate_id", it) }
        request.rating?.let { put("rating", it) }
        request.comment?.let { put("comment", it) }
        request.existingTask?.let { put("existing_task", encodeTask(it)) }
    }

    private fun encodeTask(task: NavigationTask): JSONObject = JSONObject().apply {
        put("travel_mode", task.travelMode)
        if (task.taskTitle.isNotBlank()) put("task_title", task.taskTitle)
        if (task.notes.isNotBlank()) put("notes", task.notes)
        put("stops", JSONArray().apply {
            task.stops.forEach { stop ->
                put(JSONObject().apply {
                    put("role", stop.role)
                    put("text", stop.name)
                    put("needs_confirmation", stop.needsConfirmation)
                    put("poi", JSONObject().apply {
                        put("name", stop.name)
                        if (stop.poiId.isNotBlank()) put("poi_id", stop.poiId)
                        if (stop.address.isNotBlank()) put("address", stop.address)
                        stop.point?.let { point ->
                            put("location", JSONObject().apply {
                                put("lat", point.latitude)
                                put("lng", point.longitude)
                            })
                        }
                    })
                })
            }
        })
    }
}
