package com.example.navi_app.navigation.protocol

import com.example.navi_app.navigation.model.GeoPoint
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Wire names match navi-claw and MapTemplate; malformed/missing coordinates remain invalid. */
object NavigationResponseCodec {
    fun decode(json: JSONObject): NavigationResponse {
        val groups = json.optJSONArray("candidate_groups")
        val candidateGroups = (0 until (groups?.length() ?: 0)).mapNotNull { index ->
            decodeCandidateGroup(groups?.optJSONObject(index))
        }
        val error = json.optJSONObject("error")
        return NavigationResponse(
            status = json.optString("status"),
            responseType = if (json.has("response_type")) json.optString("response_type") else null,
            taskComplete = if (json.has("task_complete")) json.opt("task_complete") as? Boolean ?: false else null,
            feedbackRequired = json.optBoolean("feedback_required"),
            task = decodeTask(json.optJSONObject("task")),
            reply = json.optString("reply"),
            question = json.optString("question"),
            errorMessage = error?.optString("message"),
            candidateGroups = candidateGroups,
            sessionId = json.optString("session_id"),
            requestId = json.optString("request_id"),
            traceId = json.optString("trace_id"),
            type = json.optString("type", "navigation"),
            partialTask = decodeTask(json.optJSONObject("partial_task")),
            errorCode = error?.optString("code")
        )
    }

    private fun decodeTask(taskJson: JSONObject?): NavigationTask? {
        if (taskJson == null) return null
        val stops = taskJson.optJSONArray("stops")
        val decodedStops = (0 until (stops?.length() ?: 0)).map { index ->
            val stop = stops?.optJSONObject(index)
            val poi = stop?.optJSONObject("poi")
            NavigationStop(
                role = stop?.optString("role") ?: "",
                name = poi?.optString("name")?.ifBlank { stop?.optString("text") ?: "" }
                    ?: stop?.optString("text") ?: "",
                point = point(poi?.optJSONObject("location")),
                needsConfirmation = stop?.optBoolean("needs_confirmation") ?: false,
                poiId = poi?.optString("poi_id") ?: "",
                address = poi?.optString("address") ?: ""
            )
        }
        return NavigationTask(
            stops = decodedStops,
            travelMode = taskJson.optString("travel_mode", "drive"),
            taskTitle = taskJson.optString("task_title"),
            notes = taskJson.optString("notes")
        )
    }

    private fun decodeCandidateGroup(group: JSONObject?): CandidateGroup? {
        if (group == null) return null
        val items = group.optJSONArray("items")
        val candidates = (0 until (items?.length() ?: 0)).mapNotNull { index ->
            val item = items?.optJSONObject(index) ?: return@mapNotNull null
            val poi = item.optJSONObject("poi")
            val reasons = item.optJSONArray("reasons")
            Candidate(
                id = item.optString("id"),
                name = poi?.optString("name") ?: "",
                address = poi?.optString("address") ?: "",
                point = point(poi?.optJSONObject("location")),
                score = (item.opt("score") as? Number)?.toDouble(),
                reasons = (0 until (reasons?.length() ?: 0)).mapNotNull { reasonIndex ->
                    reasons?.optString(reasonIndex)?.takeIf { it.isNotBlank() }
                },
                poiId = poi?.optString("poi_id") ?: ""
            )
        }
        return CandidateGroup(
            id = group.optString("group_id"),
            stopIndex = group.optInt("for_stop_index", -1),
            candidates = candidates,
            hint = group.optString("hint")
        )
    }

    private fun point(json: JSONObject?): GeoPoint? = json?.let {
        GeoPoint(it.optDouble("lat", Double.NaN), it.optDouble("lng", Double.NaN))
    }
}
