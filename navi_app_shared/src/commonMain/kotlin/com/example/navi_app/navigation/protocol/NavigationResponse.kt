package com.example.navi_app.navigation.protocol

import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.*

data class NavigationStop(
    val role: String,
    val name: String,
    val point: GeoPoint?,
    val needsConfirmation: Boolean = false,
    val poiId: String = "",
    val address: String = ""
)

data class NavigationTask(
    val stops: List<NavigationStop>,
    val travelMode: String = "drive",
    val taskTitle: String = "",
    val notes: String = ""
)

data class Candidate(
    val id: String,
    val name: String,
    val address: String,
    val point: GeoPoint?,
    val score: Double? = null,
    val reasons: List<String> = emptyList(),
    val poiId: String = ""
)

data class CandidateGroup(
    val id: String,
    val stopIndex: Int,
    val candidates: List<Candidate>,
    val hint: String = ""
)

/** Preserves MapTemplate's readiness and feedback rules, including older gateways without task_complete. */
data class NavigationResponse(
    val status: String,
    val responseType: String?,
    val taskComplete: Boolean?,
    val feedbackRequired: Boolean,
    val task: NavigationTask?,
    val reply: String = "",
    val question: String = "",
    val errorMessage: String? = null,
    val candidateGroups: List<CandidateGroup> = emptyList(),
    val sessionId: String = "",
    val requestId: String = "",
    val traceId: String = "",
    val type: String = "navigation",
    val partialTask: NavigationTask? = null,
    val errorCode: String? = null
) {
    fun isOkStatus() = status.equals("ok", true)
    fun isTask() = responseType.equals("navigation_task", true) || (responseType == null && status.equals("ready", true))
    fun isError() = errorMessage != null || status.equals("error", true) || responseType.equals("error", true)
    fun isDisambiguation() = responseType.equals("disambiguation", true) || status.equals("need_disambiguation", true)
    fun isChatReply() = responseType.equals("chat_reply", true)
    fun isUnsupported() = responseType.equals("unsupported", true)
    fun isTaskComplete() = taskComplete != false
    fun isIncompleteNavigationTask() = isTask() && !isReady()
    fun isReady(): Boolean = isTask() && !isError() && !isDisambiguation() && isTaskComplete() &&
        (isOkStatus() || status.equals("ready", true)) && task != null && task.stops.isNotEmpty() &&
        task.stops.all { !it.needsConfirmation && it.point?.isValid() == true } &&
        task.stops.count { it.role.equals("destination", true) } == 1

    fun requiresFeedback() = feedbackRequired && !isError() && !isIncompleteNavigationTask()
}

sealed class ResponseAction {
    data class ApplyDraft(val draft: NavigationDraft) : ResponseAction()
    data class Disambiguate(val question: String, val group: CandidateGroup) : ResponseAction()
    data class ShowMessage(val text: String) : ResponseAction()
}

/** Pure replacement for MapTemplate NavigationResponseCoordinator. Applying a task never waits for a rating. */
object NavigationResponseCoordinator {
    fun resolve(response: NavigationResponse): ResponseAction {
        if (response.isError()) return ResponseAction.ShowMessage(response.errorMessage ?: response.reply.ifBlank { "导航请求失败" })
        if (response.isDisambiguation()) {
            val group = response.candidateGroups.filter { it.candidates.isNotEmpty() }.minByOrNull { it.stopIndex }
            val stop = group?.let { (response.partialTask ?: response.task)?.stops?.getOrNull(it.stopIndex) }
            val label = when (stop?.role) {
                "origin" -> "起点"
                "waypoint" -> "途经点"
                else -> "目的地"
            }
            val prompt = "请选择$label" + (stop?.name?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            return if (group != null) ResponseAction.Disambiguate(
                listOf(prompt, response.question.ifBlank { group.hint }).filter { it.isNotBlank() }.distinct().joinToString("\n"), group)
            else ResponseAction.ShowMessage("请补充或确认地点")
        }
        if (!response.isReady()) return ResponseAction.ShowMessage(response.reply.ifBlank {
            response.question.ifBlank {
                when {
                    response.isTask() -> "导航任务尚未完整，请补充或确认地点"
                    response.isChatReply() -> ""
                    response.isUnsupported() -> "当前暂不支持此导航请求"
                    else -> "暂时无法执行此导航请求"
                }
            }
        })
        val task = response.task!!
        val mode = TravelMode.entries.firstOrNull { it.wireValue.equals(task.travelMode, true) }
            ?: return ResponseAction.ShowMessage("不支持的出行方式：${task.travelMode}")
        val originStops = task.stops.filter { it.role.equals("origin", true) }
        if (originStops.size > 1 || task.stops.any { it.role.lowercase() !in setOf("origin", "waypoint", "destination") }) {
            return ResponseAction.ShowMessage("导航任务地点类型无效")
        }
        fun NavigationStop.asPlace() = Place(name.ifBlank { "地图地点" }, point!!)
        return ResponseAction.ApplyDraft(NavigationDraft(
            destination = task.stops.single { it.role.equals("destination", true) }.asPlace(),
            waypoints = task.stops.filter { it.role.equals("waypoint", true) }.map { it.asPlace() },
            travelMode = mode,
            origin = originStops.firstOrNull()?.asPlace()
        ))
    }
}
