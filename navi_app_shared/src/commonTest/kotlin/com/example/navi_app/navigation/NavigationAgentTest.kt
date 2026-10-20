package com.example.navi_app.navigation

import com.example.navi_app.navigation.agent.*
import com.example.navi_app.navigation.domain.NavigationTextIntent
import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.EnginePhase
import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.model.NavigationCapabilities
import com.example.navi_app.navigation.model.Place
import com.example.navi_app.navigation.model.RuntimeSnapshot
import com.example.navi_app.navigation.model.TravelMode
import com.example.navi_app.navigation.port.NavigationEnvironment
import com.example.navi_app.navigation.port.NavigationPort
import com.example.navi_app.navigation.presentation.NavigationStore
import com.example.navi_app.navigation.protocol.Candidate
import com.example.navi_app.navigation.protocol.CandidateGroup
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

    @Test fun multipleWaypointsAndDestinationConfirmInOrderWithoutLateResponsesReplacingTheNextGroup() {
        val transport = FakeTransport()
        val navigation = RecordingNavigationPort()
        val origin = GeoPoint(39.9, 116.4)
        val selected = listOf(
            Candidate("shared-item-id", "医院东门", "医院路", GeoPoint(39.91, 116.41)),
            Candidate("shared-item-id", "公园南门", "公园路", GeoPoint(39.92, 116.42)),
            Candidate("school-east", "学校东门", "学校路", GeoPoint(39.93, 116.43))
        )
        val groups = selected.mapIndexed { index, candidate ->
            CandidateGroup("stop-group-$index", index + 1, listOf(candidate))
        }
        var pendingTask = NavigationTask(listOf(NavigationStop("origin", "当前位置", origin)) +
            selected.mapIndexed { index, candidate ->
                NavigationStop(if (index == selected.lastIndex) "destination" else "waypoint",
                    candidate.name, null, needsConfirmation = true)
            }, "walk")
        val store = NavigationStore(navigation) {}
        store.configure(NavigationEnvironment(Place("初始地图中心", origin), true, "test-app",
            NavigationCapabilities(travelModes = setOf(TravelMode.DRIVE, TravelMode.WALK, TravelMode.RIDE))))
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = origin))
        val responses = mutableListOf<NavigationResponse>()
        val repository = NavigationAgentRepository(transport, { response ->
            responses += response
            store.acceptResponse(response)
        }, store::setInputError)
        store.setCandidateSelectionHandler { groupId, candidateId ->
            repository.selectCandidate(groupId, candidateId, store.state.runtime.location,
                store.state.candidateTravelMode)
        }
        val sentence = "步行先去医院，再去公园，最后到学校"
        store.setInputText(sentence)
        store.beginDestinationRequest()
        store.setInputSubmitting(true)
        repository.submitText(sentence, "walk", origin)
        val initialResponse = NavigationResponse("ok", "disambiguation", false, false, null,
            candidateGroups = groups.reversed(), partialTask = pendingTask,
            sessionId = "multi-stop-session", requestId = transport.sent.single().requestId!!)
        transport.respond(initialResponse)
        assertEquals(groups.first(), store.state.candidateGroup)
        assertEquals("multi-stop-session", repository.snapshot().sessionId)
        assertTrue(navigation.planned.isEmpty(), "an incomplete itinerary must not be planned")

        val acceptedResponses = mutableListOf(initialResponse)
        for (index in selected.indices) {
            val group = groups[index]
            val candidate = selected[index]
            assertEquals(group, store.state.candidateGroup)
            assertFalse(store.state.busy)
            if (index > 0) {
                val count = transport.sent.size
                store.chooseCandidate(candidate, groups[index - 1].id)
                assertEquals(count, transport.sent.size, "a queued card from the previous group cannot submit this group")
            }
            store.chooseCandidate(candidate, group.id)
            assertTrue(store.state.busy)
            assertEquals(group, store.state.candidateGroup, "keep the current card until the server confirms it")
            val request = transport.sent.last()
            assertEquals(index + 2, transport.sent.size)
            assertEquals("multi-stop-session", request.sessionId)
            assertEquals("disambiguation_choice", request.kind)
            assertEquals(NavigationAgentRequestType.CANDIDATE_SELECTION, request.type)
            assertEquals(group.id, request.groupId)
            assertEquals(candidate.id, request.candidateId)
            assertEquals("walk", request.travelMode)
            // The shared bridge carries one pair; the native adapter maps candidate_id
            // to the sole disambiguation_choices[].chosen_item_id wire entry.
            val encoded = NavigationAgentRequestCodec.encode(request)
            assertEquals(group.id, encoded.optString("group_id"))
            assertEquals(candidate.id, encoded.optString("candidate_id"))
            assertFalse(encoded.has("existing_task"))
            store.chooseCandidate(candidate, group.id)
            assertEquals(index + 2, transport.sent.size, "a second tap cannot send a duplicate choice")

            val stateBeforeLateResponse = store.state
            val responseCount = responses.size
            // The preceding request may deliver its event/response twice after
            // the next choice has already started. Neither callback may unlock it.
            transport.respond(acceptedResponses[index], index)
            transport.respond(acceptedResponses[index], transport.sent.lastIndex)
            assertEquals(stateBeforeLateResponse, store.state)
            assertEquals(responseCount, responses.size)
            assertEquals(request.requestId, repository.snapshot().pendingRequestId)

            pendingTask = pendingTask.copy(stops = pendingTask.stops.mapIndexed { stopIndex, stop ->
                if (stopIndex == group.stopIndex) stop.copy(name = candidate.name,
                    point = candidate.point, needsConfirmation = false) else stop
            })
            val remaining = groups.drop(index + 1)
            val result = NavigationResponse("ok", if (remaining.isEmpty()) "navigation_task" else "disambiguation",
                remaining.isEmpty(), false, if (remaining.isEmpty()) pendingTask else null,
                candidateGroups = remaining.reversed(), partialTask = if (remaining.isEmpty()) null else pendingTask,
                sessionId = "multi-stop-session", requestId = request.requestId!!)
            acceptedResponses += result
            transport.respond(result)
            assertNull(repository.snapshot().pendingRequestId)
            assertEquals(selected.take(index + 1).map { it.point },
                pendingTask.stops.drop(1).take(index + 1).map { it.point })
            if (remaining.isNotEmpty()) {
                assertEquals(pendingTask, store.state.candidateTask)
                assertTrue(navigation.planned.isEmpty())
            }
        }
        assertEquals(4, transport.sent.map { it.requestId }.distinct().size)
        assertEquals(4, responses.size)
        assertNull(store.state.candidateGroup)
        assertTrue(store.state.candidateGroups.isEmpty())
        assertEquals(sentence, store.state.shell.input.text)
        val planned = navigation.planned.single()
        assertEquals(TravelMode.WALK, planned.travelMode)
        assertEquals(selected.dropLast(1).map { Place(it.name, it.point!!) }, planned.waypoints)
        assertEquals(Place(selected.last().name, selected.last().point!!), planned.destination)
        assertEquals(pendingTask, responses.last().task)
        val finalState = store.state
        acceptedResponses.dropLast(1).forEachIndexed { index, late -> transport.respond(late, index) }
        assertEquals(finalState, store.state)
        assertEquals(1, navigation.planned.size)
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

    private class RecordingNavigationPort : NavigationPort {
        val planned = mutableListOf<NavigationDraft>()
        override fun open() {}
        override fun locate() {}
        override fun plan(draft: NavigationDraft, revision: Int) { planned += draft }
        override fun start(simulation: Boolean, revision: Int) {}
        override fun stop() {}
        override fun exitGuidance() {}
        override fun clearRoute() {}
        override fun overview() {}
        override fun follow() {}
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
