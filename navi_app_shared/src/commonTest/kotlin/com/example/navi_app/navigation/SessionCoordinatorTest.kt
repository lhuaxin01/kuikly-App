package com.example.navi_app.navigation

import com.example.navi_app.navigation.presentation.NavigationMessage
import com.example.navi_app.navigation.presentation.NavigationSessionItem
import com.example.navi_app.navigation.presentation.SessionCoordinator
import com.example.navi_app.navigation.presentation.SessionState
import com.example.navi_app.navigation.presentation.SessionTransport
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCoordinatorTest {
    @Test fun creationAttachesBeforeActivatingTheNewSession() {
        val fixture = Fixture()
        fixture.coordinator.create("  周末出行  ")
        assertEquals("周末出行", fixture.transport.creates.single().first)
        assertTrue(fixture.state.loading)
        fixture.transport.creates.single().second(successSession("new"))
        assertEquals("new", fixture.transport.attaches.single().first)
        assertEquals("old", fixture.state.activeSessionId)
        assertTrue(fixture.used.isEmpty())
        assertTrue(fixture.transport.histories.isEmpty())

        fixture.transport.attaches.single().second(successSession("new"))
        assertEquals(listOf("new"), fixture.used)
        assertEquals("new", fixture.state.activeSessionId)
        assertTrue(fixture.state.history.isEmpty())
        assertEquals("", fixture.state.newSessionTitle)
        assertEquals("new", fixture.transport.histories.single().first)
    }

    @Test fun failedAttachKeepsCurrentSessionAndNewlyCreatedSessionAvailableForRetry() {
        val fixture = Fixture()
        val original = fixture.state
        fixture.coordinator.create("新行程")
        fixture.transport.creates.single().second(successSession("new"))
        fixture.transport.attaches.single().second(failure("网络断开"))
        assertEquals(original.activeSessionId, fixture.state.activeSessionId)
        assertEquals(original.history, fixture.state.history)
        assertEquals("网络断开", fixture.state.error)
        assertEquals("new", fixture.state.items.first().id)
        assertFalse(fixture.state.loading)
        assertTrue(fixture.used.isEmpty())
    }

    @Test fun lateHistoryCannotOverwriteASelectedConversation() {
        val fixture = Fixture()
        fixture.coordinator.refreshHistory()
        val oldHistory = fixture.transport.histories.single().second
        fixture.coordinator.select(NavigationSessionItem("next", "下次出行"))
        fixture.transport.attaches.single().second(successSession("next"))
        fixture.transport.histories.last().second(history("新的内容"))
        val selectedHistory = fixture.state.history
        oldHistory(history("旧的内容"))
        assertEquals("next", fixture.state.activeSessionId)
        assertEquals(selectedHistory, fixture.state.history)
        assertEquals("新的内容", fixture.state.history.last().text)
        assertFalse(fixture.state.historyLoading)
    }

    @Test fun lateHistoryIsIgnoredWhenActiveSessionChangesOutsideCoordinator() {
        val fixture = Fixture()
        fixture.coordinator.refreshHistory()
        fixture.state = fixture.state.copy(activeSessionId = "changed", history = emptyList())
        fixture.transport.histories.single().second(history("旧内容"))
        assertTrue(fixture.state.history.isEmpty())
    }

    @Test fun refreshingHistoryDoesNotAttachAndOnlyNewestResponseWins() {
        val fixture = Fixture()
        fixture.coordinator.refreshHistory()
        fixture.coordinator.refreshHistory()
        fixture.transport.histories.last().second(history("最新内容"))
        fixture.transport.histories.first().second(history("较早内容"))
        assertTrue(fixture.transport.attaches.isEmpty())
        assertEquals("最新内容", fixture.state.history.last().text)
    }

    @Test fun invalidationRejectsLateHistoryListAndCreateResponses() {
        val fixture = Fixture()
        fixture.coordinator.refreshHistory()
        fixture.coordinator.refreshList()
        fixture.coordinator.create("待创建")
        fixture.coordinator.invalidate()
        fixture.state = SessionState()
        fixture.transport.histories.single().second(history("上一账号内容"))
        fixture.transport.lists.single()(sessionList("old"))
        fixture.transport.creates.single().second(successSession("late"))
        assertEquals(SessionState(), fixture.state)
        assertTrue(fixture.used.isEmpty())
        assertTrue(fixture.transport.attaches.isEmpty())
    }

    @Test fun invalidationRejectsLateAttachAcknowledgements() {
        val fixture = Fixture()
        fixture.coordinator.select(NavigationSessionItem("next", "下次出行"))
        fixture.coordinator.invalidate()
        fixture.state = SessionState()
        fixture.transport.attaches.single().second(successSession("next"))
        assertEquals(SessionState(), fixture.state)
        assertTrue(fixture.used.isEmpty())
        assertTrue(fixture.transport.histories.isEmpty())
    }

    @Test fun bindingOperationsAreSerializedToKeepSocketAndUiConsistent() {
        val fixture = Fixture()
        fixture.coordinator.select(NavigationSessionItem("next", "下次出行"))
        fixture.coordinator.select(NavigationSessionItem("other", "其他出行"))
        fixture.coordinator.create("其他出行")
        assertEquals(1, fixture.transport.attaches.size)
        assertTrue(fixture.transport.creates.isEmpty())
        fixture.transport.attaches.single().second(failure("绑定失败"))
        fixture.coordinator.select(NavigationSessionItem("other", "其他出行"))
        assertEquals(2, fixture.transport.attaches.size)
    }

    @Test fun newestListWinsAndDoesNotChangeTheActiveSession() {
        val fixture = Fixture()
        fixture.coordinator.refreshList()
        fixture.coordinator.refreshList()
        fixture.transport.lists.last()(sessionList("newest"))
        fixture.transport.lists.first()(sessionList("stale"))
        assertEquals("newest", fixture.state.items.single().id)
        assertEquals("old", fixture.state.activeSessionId)
        assertTrue(fixture.used.isEmpty())
        assertFalse(fixture.state.loading)
    }

    @Test fun expiredAuthenticationInvalidatesOutstandingCallbacks() {
        val fixture = Fixture()
        fixture.coordinator.refreshHistory()
        fixture.coordinator.refreshList()
        fixture.transport.lists.single()(failure("登录已过期", "AUTH_REQUIRED"))
        val stateAfterExpiry = fixture.state
        fixture.transport.histories.single().second(history("迟到消息"))
        assertEquals(1, fixture.authRequired)
        assertEquals(stateAfterExpiry, fixture.state)
    }

    @Test fun mismatchedAttachResponseCannotActivateTheWrongSession() {
        val fixture = Fixture()
        fixture.coordinator.select(NavigationSessionItem("next", "下次出行"))
        fixture.transport.attaches.single().second(successSession("wrong"))
        assertEquals("old", fixture.state.activeSessionId)
        assertTrue(fixture.state.error.contains("不一致"))
        assertTrue(fixture.used.isEmpty())
    }

    private class Fixture {
        val transport = FakeTransport()
        var state = SessionState(activeSessionId = "old", activeSessionTitle = "已有会话",
            history = listOf(NavigationMessage(role = "user", text = "保留内容")), newSessionTitle = "新行程")
        val used = mutableListOf<String>()
        var authRequired = 0
        val coordinator = SessionCoordinator(transport, { state }, { state = it }, { used += it }, { authRequired++ })
    }

    private class FakeTransport : SessionTransport {
        val lists = mutableListOf<(JSONObject) -> Unit>()
        val histories = mutableListOf<Pair<String, (JSONObject) -> Unit>>()
        val attaches = mutableListOf<Pair<String, (JSONObject) -> Unit>>()
        val creates = mutableListOf<Pair<String, (JSONObject) -> Unit>>()
        override fun listSessions(callback: (JSONObject) -> Unit) { lists += callback }
        override fun loadSessionHistory(sessionId: String, callback: (JSONObject) -> Unit) { histories += sessionId to callback }
        override fun attachSession(sessionId: String, callback: (JSONObject) -> Unit) { attaches += sessionId to callback }
        override fun createSession(title: String, callback: (JSONObject) -> Unit) { creates += title to callback }
    }

    private fun successSession(id: String) = JSONObject().put("ok", true).put("response",
        JSONObject().put("session", JSONObject().put("sessionId", id).put("title", "会话 $id")))

    private fun sessionList(id: String) = JSONObject().put("ok", true).put("response",
        JSONArray().put(JSONObject().put("sessionId", id).put("title", "会话 $id")))

    private fun history(text: String) = JSONObject().put("ok", true).put("response", JSONObject().put("events",
        JSONArray().put(JSONObject().put("type", "user_message").put("text", "导航请求"))
            .put(JSONObject().put("type", "assistant_message").put("text", text))))

    private fun failure(message: String, code: String = "SESSION_FAILED") = JSONObject()
        .put("ok", false).put("message", message).put("code", code)
}
