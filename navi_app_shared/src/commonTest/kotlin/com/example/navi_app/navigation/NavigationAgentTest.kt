package com.example.navi_app.navigation

import com.example.navi_app.navigation.agent.*
import com.example.navi_app.navigation.domain.NavigationTextIntent
import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.model.TravelMode
import com.example.navi_app.navigation.protocol.NavigationResponse
import com.example.navi_app.navigation.protocol.NavigationStop
import com.example.navi_app.navigation.protocol.NavigationTask
import kotlin.test.*

class NavigationAgentTest {
    @Test fun requestCodecKeepsMapTemplateWireNames() {
        val json = NavigationAgentRequestCodec.encode(NavigationAgentRequest(
            NavigationAgentRequestType.CANDIDATE_SELECTION,
            sessionId = "s1", requestId = "r1", groupId = "g1", candidateId = "p1"))
        assertEquals("candidate_selection", json.optString("type"))
        assertEquals("s1", json.optString("session_id"))
        assertEquals("r1", json.optString("request_id"))
        assertEquals("g1", json.optString("group_id"))
        assertEquals("p1", json.optString("candidate_id"))
    }

    @Test fun textTravelModeUsesLastAffirmativeModeWithoutGuessingDestination() {
        assertEquals(TravelMode.WALK, NavigationTextIntent.resolveTravelMode("开车去公园，改为步行", TravelMode.DRIVE))
        assertEquals(TravelMode.RIDE, NavigationTextIntent.resolveTravelMode("步行去学校，还是骑自行车去", TravelMode.DRIVE))
        assertEquals(TravelMode.WALK, NavigationTextIntent.resolveTravelMode("步行去北京大学，不要开车", TravelMode.DRIVE))
        assertEquals(TravelMode.DRIVE, NavigationTextIntent.resolveTravelMode("不要骑自行车去学校", TravelMode.DRIVE))
    }

    @Test fun poiNamesAndNoTravelExpressionKeepSelectedMode() {
        listOf("去步行街", "到汽车站", "去自行车店", "导航到驾驶学校", "先去医院再去学校").forEach { text ->
            assertEquals(TravelMode.RIDE, NavigationTextIntent.resolveTravelMode(text, TravelMode.RIDE), text)
        }
        assertEquals(TravelMode.WALK, NavigationTextIntent.resolveTravelMode("步行去汽车站", TravelMode.DRIVE))
        assertNull(NavigationTextIntent.resolveTravelMode("去北京大学", null as String?))
    }

    @Test fun newTextKeepsFullIntentAndDoesNotInventDestinationCoordinates() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        val location = GeoPoint(39.9, 116.4)
        repository.submitText("  步行从公司出发，先去医院再到学校  ", "drive", location)
        val request = transport.sent.single()
        assertEquals("new_intent", request.kind)
        assertEquals("walk", request.travelMode)
        assertEquals("步行从公司出发，先去医院再到学校", request.text)
        assertEquals(location, request.location)
        assertNull(request.existingTask)
        val json = NavigationAgentRequestCodec.encode(request)
        assertFalse(json.has("existing_task"))
        assertEquals("new_intent", json.optString("kind"))
    }

    @Test fun modifyTextEncodesCurrentTaskWithStopRolesAndPoiCoordinates() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        val current = NavigationTask(listOf(
            NavigationStop("origin", "当前位置", GeoPoint(39.9, 116.4)),
            NavigationStop("waypoint", "医院", GeoPoint(39.91, 116.41), poiId = "hospital-id"),
            NavigationStop("destination", "学校", GeoPoint(39.92, 116.42), poiId = "school-id", address = "学校地址")
        ), "drive", "去学校", "保留途经点")
        repository.useSession("current-session")
        repository.submitText("改为骑行，终点换到图书馆", "drive", GeoPoint(39.905, 116.405), current)
        val request = transport.sent.single()
        assertEquals(NavigationAgentRequestType.TEXT, request.type)
        assertEquals("modify_task", request.kind)
        assertEquals("ride", request.travelMode)
        assertEquals("ride", request.existingTask?.travelMode)
        assertEquals("drive", current.travelMode)
        assertEquals("current-session", request.sessionId)
        val json = NavigationAgentRequestCodec.encode(request)
        val task = json.optJSONObject("existing_task")!!
        assertEquals("ride", task.optString("travel_mode"))
        val stops = task.optJSONArray("stops")!!
        assertEquals(3, stops.length())
        assertEquals("origin", stops.optJSONObject(0)!!.optString("role"))
        assertEquals("waypoint", stops.optJSONObject(1)!!.optString("role"))
        val destination = stops.optJSONObject(2)!!
        assertEquals("destination", destination.optString("role"))
        val poi = destination.optJSONObject("poi")!!
        assertEquals("学校", poi.optString("name"))
        assertEquals("school-id", poi.optString("poi_id"))
        assertEquals("学校地址", poi.optString("address"))
        assertEquals(39.92, poi.optJSONObject("location")!!.optDouble("lat"))
        assertEquals(116.42, poi.optJSONObject("location")!!.optDouble("lng"))
    }

    @Test fun invalidExistingTaskCannotBeSubmittedForModification() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        assertFailsWith<IllegalArgumentException> {
            repository.submitText("改为步行", existingTask = NavigationTask(listOf(
                NavigationStop("destination", "待确认学校", null, needsConfirmation = true))))
        }
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun existingTaskIsNotRememberedAcrossRequestsOrSessions() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        val task = NavigationTask(listOf(NavigationStop("destination", "学校", GeoPoint(39.92, 116.42))))
        repository.submitText("改为步行", existingTask = task)
        repository.useSession("new-session")
        repository.submitText("去医院", "drive")
        assertEquals("new_intent", transport.sent.last().kind)
        assertNull(transport.sent.last().existingTask)
        assertEquals("drive", transport.sent.last().travelMode)
    }

    @Test fun candidateSelectionRetainsCurrentLocationAndModeContext() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        val location = GeoPoint(39.9, 116.4)
        repository.useSession("s1")
        repository.selectCandidate("group-school", "school-2", location, "walk")
        val request = transport.sent.single()
        assertEquals("disambiguation_choice", request.kind)
        assertEquals(location, request.location)
        assertEquals("walk", request.travelMode)
        assertEquals("group-school", request.groupId)
        assertEquals("school-2", request.candidateId)
        assertEquals("s1", request.sessionId)
    }

    @Test fun repositoryCreatesSessionAndSeparatesFeedbackFromNavigation() {
        val transport = FakeTransport()
        val responses = mutableListOf<NavigationResponse>()
        val repository = NavigationAgentRepository(transport, responses::add)
        repository.submitText("去北京大学")
        val navigationRequestId = transport.sent.single().requestId
        assertTrue(navigationRequestId!!.startsWith("navi-"))
        assertFalse(transport.sent.single().traceId.isNullOrBlank())
        transport.respond(NavigationResponse("ok", "navigation_task", true, true, null,
            sessionId = "s1", requestId = navigationRequestId, traceId = "trace-s1"))
        assertEquals("s1", repository.snapshot().sessionId)
        repository.submitFeedback(5, "路线清晰")
        assertEquals(NavigationAgentRequestType.FEEDBACK, transport.sent.last().type)
        assertEquals("s1", transport.sent.last().sessionId)
        assertEquals(navigationRequestId, transport.sent.last().feedbackRequestId)
        transport.respond(NavigationResponse("ok", "chat_reply", null, false, null,
            requestId = transport.sent.last().requestId!!))
        // Feedback acknowledgement is delivered through its independent callback path.
        assertEquals(1, responses.size)
    }

    @Test fun staleResponseCannotReplaceCurrentRequest() {
        val transport = FakeTransport()
        val responses = mutableListOf<NavigationResponse>()
        val repository = NavigationAgentRepository(transport, responses::add)
        repository.submitText("第一个目的地")
        repository.submitText("第二个目的地")
        transport.respond(NavigationResponse("ok", "chat_reply", null, false, null,
            requestId = transport.sent[0].requestId!!), 0)
        assertTrue(responses.isEmpty())
        transport.respond(NavigationResponse("ok", "chat_reply", null, false, null,
            requestId = transport.sent[1].requestId!!), 1)
        assertEquals(1, responses.size)
    }

    @Test fun invalidFeedbackAndSelectionAreRejectedBeforeTransport() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        assertFailsWith<IllegalArgumentException> { repository.submitFeedback(0) }
        assertFailsWith<IllegalArgumentException> { repository.selectCandidate("", "p1") }
        assertEquals(0, transport.sent.size)
    }

    @Test fun closeCancelsPendingRequestAndPreventsNewCommands() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        repository.submitText("导航")
        repository.closeSession()
        assertEquals(1, transport.cancelled.size)
        assertFailsWith<IllegalStateException> { repository.submitText("再次导航") }
        assertTrue(repository.snapshot().closed)
    }

    @Test fun transportErrorIsRetainedForDiagnostics() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {}, {})
        repository.submitText("导航")
        transport.fail("服务不可用")
        assertEquals("服务不可用", repository.snapshot().lastError)
        assertNull(repository.snapshot().pendingRequestId)
    }

    @Test fun boundSessionMismatchDropsStaleSessionForNextRequest() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {}, {})
        repository.useSession("stale-session")
        repository.submitText("第一次")
        transport.fail("request.session_id does not match bound channel session")
        assertEquals("", repository.snapshot().sessionId)
        repository.submitText("第二次")
        assertNull(transport.sent.last().sessionId)
    }

    @Test fun switchingSessionInvalidatesLateResponseAndFeedbackUsesOriginalIds() {
        val transport = FakeTransport()
        val responses = mutableListOf<NavigationResponse>()
        val repository = NavigationAgentRepository(transport, responses::add)
        repository.useSession("old")
        repository.submitText("旧会话")
        val oldRequest = transport.sent.last()
        repository.useSession("new")
        repository.submitText("新会话")
        val newRequest = transport.sent.last()
        transport.respond(NavigationResponse("ok", "chat_reply", null, false, null,
            sessionId = "old", requestId = oldRequest.requestId!!), 0)
        assertTrue(responses.isEmpty())
        transport.respond(NavigationResponse("ok", "chat_reply", null, false, null,
            sessionId = "new", requestId = newRequest.requestId!!, traceId = "trace-new"), 1)
        assertEquals(1, responses.size)
        var accepted = false
        repository.submitFeedback(true, "确认", onSuccess = { accepted = true })
        val feedback = transport.sent.last()
        assertEquals(newRequest.requestId, feedback.feedbackRequestId)
        assertEquals("trace-new", feedback.traceId)
        transport.respond(NavigationResponse("ok", "feedback", null, false, null,
            sessionId = "new", requestId = feedback.requestId!!), 2)
        assertTrue(accepted)
        assertEquals(newRequest.requestId, repository.snapshot().lastResponseRequestId)
        assertEquals("trace-new", repository.snapshot().lastTraceId)
    }

    @Test fun feedbackFailureCallsIndependentCallback() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        repository.submitText("导航")
        val navigation = transport.sent.last()
        transport.respond(NavigationResponse("ok", "navigation_task", true, false, null,
            sessionId = "s", requestId = navigation.requestId!!, traceId = "t"))
        var failure = ""
        repository.submitFeedback(false, onFailure = { failure = it })
        transport.fail("反馈失败")
        assertEquals("反馈失败", failure)
    }

    @Test fun missingResponseTraceCannotSubmitFeedback() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        repository.submitText("导航")
        val request = transport.sent.last()
        transport.respond(NavigationResponse("ok", "navigation_task", true, false, null,
            sessionId = "s", requestId = request.requestId!!))
        assertFailsWith<IllegalArgumentException> { repository.submitFeedback(true) }
    }

    @Test fun resetSessionAllowsReloginAndClearsPendingContext() {
        val transport = FakeTransport()
        val repository = NavigationAgentRepository(transport, {})
        repository.submitText("第一次")
        repository.resetSession()
        assertEquals("", repository.snapshot().sessionId)
        assertNull(repository.snapshot().pendingRequestId)
        repository.useSession("after-login")
        repository.submitText("第二次")
        assertEquals("after-login", repository.snapshot().sessionId)
        assertEquals(2, transport.sent.count { it.type == NavigationAgentRequestType.TEXT })
    }

    private class FakeTransport : NavigationAgentTransport {
        val sent = mutableListOf<NavigationAgentRequest>()
        val cancelled = mutableListOf<String>()
        private val callbacks = mutableListOf<(NavigationResponse) -> Unit>()
        private val errors = mutableListOf<(String) -> Unit>()
        override fun send(request: NavigationAgentRequest, onResponse: (NavigationResponse) -> Unit,
            onError: (String) -> Unit) { sent += request; callbacks += onResponse; errors += onError }
        override fun cancel(requestId: String) { cancelled += requestId }
        override fun close() {}
        fun respond(value: NavigationResponse, index: Int = callbacks.lastIndex) { callbacks[index](value) }
        fun fail(message: String, index: Int = errors.lastIndex) { errors[index](message) }
    }
}
