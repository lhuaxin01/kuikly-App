package com.example.navi_app.navigation.presentation

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** The platform owns authenticated HTTP and channel.attach; shared code owns selection. */
interface SessionTransport {
    fun listSessions(callback: (JSONObject) -> Unit)
    fun loadSessionHistory(sessionId: String, callback: (JSONObject) -> Unit)
    fun attachSession(sessionId: String, callback: (JSONObject) -> Unit = {})
    fun createSession(title: String = "", callback: (JSONObject) -> Unit = {})
}

/**
 * A session is usable only after its channel.attach acknowledgement. History/list requests
 * may overlap, but a late response must never replace another account or conversation.
 */
class SessionCoordinator(
    private val transport: SessionTransport,
    private val readState: () -> SessionState,
    private val publish: (SessionState) -> Unit,
    private val useSession: (String) -> Unit,
    private val onAuthRequired: () -> Unit
) {
    private var epoch = 0
    private var listRequest = 0
    private var historyRequest = 0
    private var listLoading = false
    private var attaching = false

    fun refreshList() {
        val account = epoch
        val request = ++listRequest
        listLoading = true
        publish(readState().copy(loading = true, error = ""))
        transport.listSessions { result ->
            if (account != epoch || request != listRequest) return@listSessions
            listLoading = false
            if (!result.optBoolean("ok")) {
                fail(result, "会话列表加载失败")
                return@listSessions
            }
            val items = SessionPresentation.list(result)
            val current = readState()
            val active = items.firstOrNull { it.id == current.activeSessionId }
            publish(current.copy(
                items = items,
                activeSessionTitle = active?.title ?: current.activeSessionTitle,
                activeSessionUpdatedAt = active?.updatedAt ?: current.activeSessionUpdatedAt,
                loading = attaching,
                error = "",
                emptyMessage = if (items.isEmpty()) "暂无会话，创建会话后开始导航" else ""
            ))
        }
    }

    fun select(item: NavigationSessionItem) {
        if (attaching || item.id.isBlank()) return
        beginAttach()
        attach(item, epoch)
    }

    fun create(title: String) {
        if (attaching) return
        beginAttach()
        val account = epoch
        transport.createSession(title.trim()) { result ->
            if (account != epoch) return@createSession
            if (!result.optBoolean("ok")) {
                attaching = false
                fail(result, "创建会话失败")
                return@createSession
            }
            val response = result.optJSONObject("response") ?: result
            val session = response.optJSONObject("session") ?: response
            val id = session.optString("sessionId", session.optString("session_id")).trim()
            if (id.isEmpty()) {
                attaching = false
                fail(JSONObject(), "创建会话响应缺少会话编号")
                return@createSession
            }
            val item = NavigationSessionItem(
                id = id,
                title = session.optString("title").ifBlank { title.trim().ifBlank { "新会话" } },
                updatedAt = session.optString("updatedAt"),
                status = session.optString("status"),
                createdAt = session.optString("createdAt")
            )
            // The HTTP creation succeeded even if its later socket attachment fails.
            publish(readState().copy(items = listOf(item) + readState().items.filter { it.id != id }))
            // sessions.create is HTTP metadata creation; it does not change the socket binding.
            attach(item, account, created = true)
        }
    }

    fun refreshHistory() {
        if (attaching) return
        val sessionId = readState().activeSessionId
        if (sessionId.isBlank()) return
        val account = epoch
        val request = ++historyRequest
        publish(readState().copy(historyLoading = true, error = ""))
        transport.loadSessionHistory(sessionId) { result ->
            if (account != epoch || request != historyRequest ||
                readState().activeSessionId != sessionId) return@loadSessionHistory
            if (!result.optBoolean("ok")) {
                fail(result, "会话内容加载失败", historyFailed = true)
                return@loadSessionHistory
            }
            val response = result.optJSONObject("response") ?: result
            publish(readState().copy(
                history = SessionPresentation.history(response), historyLoading = false, error = ""
            ))
        }
    }

    /** Call before logout/account replacement or page disposal, even if a callback is pending. */
    fun invalidate() {
        epoch++
        listRequest++
        historyRequest++
        listLoading = false
        attaching = false
    }

    private fun beginAttach() {
        attaching = true
        historyRequest++
        // Preserve the current conversation if binding fails, but retire its pending history.
        publish(readState().copy(loading = true, historyLoading = false, error = ""))
    }

    private fun attach(item: NavigationSessionItem, account: Int, created: Boolean = false) {
        transport.attachSession(item.id) { result ->
            if (account != epoch) return@attachSession
            attaching = false
            if (!result.optBoolean("ok")) {
                fail(result, "会话绑定失败，请重试")
                return@attachSession
            }
            val response = result.optJSONObject("response") ?: result
            val attached = response.optJSONObject("session") ?: response
            val attachedId = attached.optString("sessionId", attached.optString("session_id", item.id))
            if (attachedId != item.id) {
                fail(JSONObject(), "服务返回的会话与所选会话不一致，请重新选择")
                return@attachSession
            }
            val current = readState()
            val selected = item.copy(title = attached.optString("title").ifBlank { item.title })
            publish(current.copy(
                activeSessionId = selected.id,
                activeSessionTitle = selected.title,
                activeSessionUpdatedAt = selected.updatedAt,
                items = if (current.items.any { it.id == selected.id }) {
                    current.items.map { if (it.id == selected.id) selected else it }
                } else listOf(selected) + current.items,
                history = emptyList(),
                newSessionTitle = if (created) "" else current.newSessionTitle,
                loading = listLoading,
                historyLoading = false,
                error = ""
            ))
            useSession(selected.id)
            refreshHistory()
            if (created) refreshList()
        }
    }

    private fun fail(result: JSONObject, fallback: String, historyFailed: Boolean = false) {
        val nested = result.optJSONObject("error")
        val code = result.optString("code").ifBlank { nested?.optString("code").orEmpty() }
        val message = result.optString("message").ifBlank {
            nested?.optString("message").orEmpty().ifBlank { fallback }
        }
        publish(readState().copy(
            loading = listLoading || attaching,
            historyLoading = if (historyFailed) false else readState().historyLoading,
            error = message
        ))
        if (code == "AUTH_REQUIRED" || code == "UNAUTHORIZED" || code == "FORBIDDEN") {
            invalidate()
            onAuthRequired()
        }
    }
}
