package com.example.navi_app.navigation.presentation

import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.protocol.NavigationResponse
import com.example.navi_app.navigation.protocol.NavigationTask
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.round

/** MapTemplate's conversation presentation, independent of HTTP and the native map SDK. */
object SessionPresentation {
    /** Live replies and reloaded history use the same labels, summaries and JSON filtering. */
    fun reply(response: NavigationResponse): String {
        val payload = JSONObject().put("reply", response.reply).put("question", response.question)
        response.task?.let { payload.put("task", taskJson(it)) }
        response.partialTask?.let { payload.put("partial_task", taskJson(it)) }
        response.errorMessage?.let { payload.put("error", JSONObject().put("message", it)) }
        payload.put("candidate_groups", JSONArray().also { groups ->
            response.candidateGroups.forEach { group ->
                groups.put(JSONObject().put("items", JSONArray().also { items ->
                    group.candidates.forEach { candidate ->
                        items.put(JSONObject().put("poi", poiJson(candidate.name, candidate.address, candidate.point)))
                    }
                }))
            }
        })
        return responseText(payload).ifBlank { if (response.isError()) "导航请求失败" else "" }
    }

    private fun taskJson(task: NavigationTask): JSONObject = JSONObject().put("stops", JSONArray().also { stops ->
        task.stops.forEach { stop ->
            stops.put(JSONObject().put("role", stop.role).put("poi", poiJson(stop.name, stop.address, stop.point)))
        }
    })

    private fun poiJson(name: String, address: String, point: GeoPoint?): JSONObject =
        JSONObject().put("name", name).put("address", address).also { poi ->
            if (point?.isValid() == true) poi.put("location", JSONObject().put("lat", point.latitude).put("lng", point.longitude))
        }

    /** The bridge wraps the sessions.list array in response, while history returns an object. */
    fun list(result: JSONObject): List<NavigationSessionItem> {
        val rows = result.optJSONArray("response") ?: result.optJSONArray("sessions")
            ?: result.optJSONArray("result") ?: result.optJSONObject("response")?.optJSONArray("sessions")
        return objects(rows).mapNotNull { row ->
            val id = value(row, "sessionId", "id")
            if (id.isBlank()) return@mapNotNull null
            NavigationSessionItem(
                id = id,
                title = value(row, "title").ifBlank { "未命名会话" },
                updatedAt = value(row, "updatedAt", "updated_at", "lastMessageAt", "last_message_at"),
                preview = displayText(value(row, "lastMessage", "last_message", "preview")),
                status = value(row, "status"),
                createdAt = value(row, "createdAt", "created_at"),
                lastMessageAt = value(row, "lastMessageAt", "last_message_at")
            )
        }.distinctBy { it.id }
    }

    /**
     * A navigation turn is recorded several times (wire request, model input, model output,
     * structured result and outbound result). Render it once as two human-readable bubbles.
     * The final channel result takes precedence over intermediate model/structured output.
     */
    fun history(response: JSONObject): List<NavigationMessage> {
        val events = response.optJSONArray("events") ?: response.optJSONObject("response")?.optJSONArray("events")
        val messages = mutableListOf<NavigationMessage>()
        var current: Turn? = null
        fun flush() {
            val turn = current ?: return
            if (turn.user.isNotBlank() || turn.replies.isNotEmpty()) {
                val key = turn.id.ifBlank { "turn-${messages.size / 2}" }
                messages += NavigationMessage("$key:user", "user", turn.user.ifBlank { "（无用户文本）" }, turn.createdAt)
                messages += NavigationMessage("$key:assistant", "assistant",
                    turn.replies.joinToString("\n\n").ifBlank { "（暂无助手回复）" }, turn.updatedAt)
            }
            current = null
        }
        for (event in objects(events)) {
            val type = value(event, "type")
            val payload = event.optJSONObject("payload")
            val timestamp = value(event, "createdAt", "created_at")
            val eventId = value(event, "id", "eventId")
            when (type) {
                "channel_inbound", "structured_request" -> {
                    val text = requestText(payload)
                    if (text.isBlank()) continue
                    val requestId = payload?.let { value(it, "request_id") }.orEmpty()
                    val previous = current
                    if (previous != null && (previous.replies.isNotEmpty() ||
                            (requestId.isNotBlank() && previous.requestId.isNotBlank() && requestId != previous.requestId))) flush()
                    val turn = current ?: Turn(eventId, timestamp, fromRequest = true).also { current = it }
                    if (turn.user.isBlank()) turn.user = text
                    if (requestId.isNotBlank()) turn.requestId = requestId
                    turn.updatedAt = timestamp
                }
                "user_message" -> {
                    val raw = value(event, "text", "content")
                    val text = userText(raw)
                    if (text.isBlank()) continue
                    val previous = current
                    if (previous != null && previous.fromRequest && previous.replies.isEmpty()) {
                        // Preserve the original request text over backend action markers.
                        if (!isAction(raw) || previous.user.isBlank()) previous.user = text
                        previous.fromRequest = false
                        previous.updatedAt = timestamp
                    } else {
                        flush()
                        current = Turn(eventId, timestamp).also { it.user = text }
                    }
                }
                "assistant_message", "structured_response", "channel_outbound" -> {
                    val text = if (type == "assistant_message") displayText(value(event, "text", "content"))
                        else payload?.let { responseText(it) }.orEmpty()
                    if (text.isBlank()) continue
                    val turn = current ?: Turn(eventId, timestamp).also { current = it }
                    val priority = when (type) { "channel_outbound" -> 3; "structured_response" -> 2; else -> 1 }
                    if (priority > turn.replyPriority) {
                        turn.replies.clear()
                        turn.replyPriority = priority
                    }
                    if (priority == turn.replyPriority && text !in turn.replies) turn.replies += text
                    turn.updatedAt = timestamp
                }
            }
        }
        flush()
        return messages
    }

    private class Turn(val id: String, val createdAt: String, var fromRequest: Boolean = false) {
        var user = ""
        var requestId = ""
        var updatedAt = createdAt
        var replyPriority = 0
        val replies = mutableListOf<String>()
    }

    private fun requestText(payload: JSONObject?): String {
        if (payload == null) return ""
        val text = userText(value(payload, "text"))
        if (text.isNotBlank()) return text
        return actionText(value(payload, "kind"))
    }

    private fun actionText(kind: String): String = when (kind) {
        "cancel" -> "取消当前导航任务"
        "new_intent" -> "发起导航请求"
        "disambiguation_choice" -> "我选择了候选地点"
        "modify_task" -> "修改导航任务"
        else -> ""
    }

    private fun isAction(text: String): Boolean = listOf("cancel", "new_intent", "disambiguation_choice", "modify_task")
        .any { text.startsWith("[$it]") }

    private fun userText(text: String): String {
        if (isAction(text)) return actionText(text.substringAfter('[').substringBefore(']'))
        return displayText(text)
    }

    private fun responseText(payload: JSONObject): String {
        // An error may include stale task state; prefer the actual failure to that stale route.
        val error = payload.optJSONObject("error")?.let { displayText(value(it, "message")) }.orEmpty()
        if (error.isNotBlank()) return error
        val route = taskStops(payload.optJSONObject("task"))
        if (route.isNotBlank()) return route
        val raw = place(payload.optJSONObject("raw"))
        if (raw.isNotBlank()) return raw
        val direct = place(payload)
        if (direct.isNotBlank()) return direct
        val candidateItems = objects(payload.optJSONArray("candidate_groups")).flatMap { objects(it.optJSONArray("items")) }
        val candidates = candidates(candidateItems.ifEmpty { objects(payload.optJSONArray("candidates")) })
        val question = humanText(value(payload, "question"))
        if (candidates.isNotBlank()) return listOf(question, candidates).filter { it.isNotBlank() }.joinToString("\n\n")
        val partial = taskStops(payload.optJSONObject("partial_task"))
        if (partial.isNotBlank()) return listOf(question, partial).filter { it.isNotBlank() }.joinToString("\n\n")
        return displayText(value(payload, "reply")).ifBlank { question }
            .ifBlank { displayText(value(payload, "outputText")) }
            .ifBlank { displayText(value(payload, "text", "content", "message")) }
    }

    private fun taskStops(task: JSONObject?): String = objects(task?.optJSONArray("stops"))
        .map { place(it) }.filter { it.isNotBlank() }.joinToString("\n\n")

    private fun candidates(items: List<JSONObject>): String {
        val places = items.mapIndexedNotNull { index, item ->
            place(item, "候选地点 ${index + 1}").takeIf { it.isNotBlank() }
        }
        return if (places.isEmpty()) "" else "候选地点：\n\n${places.joinToString("\n\n")}"
    }

    private fun place(source: JSONObject?, forcedLabel: String = ""): String {
        if (source == null) return ""
        val poi = source.optJSONObject("poi")
        if (poi == null && source.optJSONObject("location") == null && source.optJSONObject("point") == null &&
            value(source, "role", "address").isBlank()) return ""
        val name = humanText(poi?.let { value(it, "name") }.orEmpty().ifBlank { value(source, "name", "text") })
        if (name.isBlank()) return ""
        val label = forcedLabel.ifBlank {
            when (value(source, "role")) { "origin" -> "起点"; "waypoint" -> "途经点"; "destination" -> "目的地"; else -> "地点" }
        }
        val address = humanText(poi?.let { value(it, "address") }.orEmpty().ifBlank { value(source, "address") })
        val coordinates = listOf(poi?.optJSONObject("location"), source.optJSONObject("location"),
            source.optJSONObject("point"), poi, source).map { coordinates(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return buildString {
            append("$label：$name")
            if (address.isNotBlank()) append("\n位置：$address")
            else if (coordinates.isNotBlank()) append("\n位置：$coordinates")
            if (address.isNotBlank() && coordinates.isNotBlank()) append("\n坐标：$coordinates")
        }
    }

    private fun coordinates(source: JSONObject?): String {
        if (source == null) return ""
        val lat = number(source, "lat", "latitude", "y") ?: return ""
        val lng = number(source, "lng", "lon", "longitude", "x") ?: return ""
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return ""
        return "${decimal(lat)}, ${decimal(lng)}"
    }

    /** Common Kotlin equivalent of MapTemplate's six-decimal coordinate display. */
    private fun decimal(value: Double): String {
        val scaled = round(kotlin.math.abs(value) * 1_000_000).toLong()
        return (if (value < 0 && scaled != 0L) "-" else "") + (scaled / 1_000_000).toString() + "." +
            (scaled % 1_000_000).toString().padStart(6, '0')
    }

    private fun number(source: JSONObject, vararg keys: String): Double? = keys.asSequence().mapNotNull {
        when (val item = source.opt(it)) { is Number -> item.toDouble(); is String -> item.toDoubleOrNull(); else -> null }
    }.firstOrNull { it.isFinite() }

    private fun displayText(text: String): String {
        val clean = text.trim()
        if (!looksStructured(clean)) return clean
        val json = if (clean.startsWith("```")) clean.lines().drop(1).let { lines ->
            (if (lines.lastOrNull()?.trim()?.startsWith("```") == true) lines.dropLast(1) else lines).joinToString("\n").trim()
        } else clean
        return try {
            when { json.startsWith("{") -> responseText(JSONObject(json)); json.startsWith("[") -> candidates(objects(JSONArray(json))); else -> "" }
        } catch (_: Exception) { "" }
    }

    private fun humanText(text: String): String = text.trim().takeUnless { looksStructured(it) }.orEmpty()

    private fun looksStructured(text: String): Boolean = text.startsWith("{") || text.startsWith("[") ||
        text.startsWith("```") || listOf("raw", "poi", "response_type", "task", "candidate_groups").any { text.contains("\"$it\"") }

    private fun value(source: JSONObject, vararg keys: String): String = keys.asSequence()
        .map { (source.opt(it) as? String).orEmpty().trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0))
        .mapNotNull { array?.optJSONObject(it) }
}
