package com.example.navi_app.navigation

import com.example.navi_app.navigation.domain.*
import com.example.navi_app.navigation.model.*
import com.example.navi_app.navigation.port.*
import com.example.navi_app.navigation.presentation.*
import com.example.navi_app.navigation.protocol.*
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.*

class NavigationBusinessTest {
    private val point = GeoPoint(39.992873, 116.310918)
    private val place = Place("北京大学", point)
    private fun response(complete: Boolean? = true, feedback: Boolean = false,
        stops: List<NavigationStop> = listOf(NavigationStop("destination", place.name, point))) =
        NavigationResponse("ok", "navigation_task", complete, feedback, NavigationTask(stops))

    @Test fun mapActivationWaitsForConfigurationAndOpensOnlyOnce() {
        val port = FakePort()
        val store = NavigationStore(port) {}
        store.activateMap()
        assertEquals(0, port.opens)
        store.configure(NavigationEnvironment(place, true, "test-app-id"))
        assertEquals(1, port.opens)
        repeat(3) {
            store.activateMap()
            store.configure(NavigationEnvironment(place, true, "test-app-id"))
            store.runtime(RuntimeSnapshot(phase = EnginePhase.IDLE))
        }
        assertEquals(1, port.opens)
        assertNull(store.state.draft.destination)
        assertEquals(0, port.plans)
    }
    @Test fun mapActivationAlsoWorksWhenConfigurationArrivesFirst() {
        val port = FakePort()
        val store = store(port)
        assertEquals(0, port.opens)
        store.activateMap()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
        store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
        assertEquals(1, port.opens)
        assertEquals(1, port.locates)
    }
    @Test fun failedAutomaticMapLoadWaitsForExplicitRetry() {
        val port = FakePort()
        val store = store(port)
        store.activateMap()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ERROR, message = "网络异常"))
        store.activateMap()
        store.configure(NavigationEnvironment(place, true, "test-app-id"))
        assertEquals(1, port.opens)
        store.open()
        assertEquals(2, port.opens)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.DISPOSED))
        store.activateMap()
        assertEquals(2, port.opens)
    }
    @Test fun activationDoesNotRestartAnAlreadyReadyMap() {
        val port = FakePort()
        val store = store(port)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        store.activateMap()
        assertEquals(0, port.opens)
        assertEquals(point, store.state.runtime.location)
    }
    @Test fun locateAfterStopOrArrivalRearmsTheVisibleMapBeforeGettingAFix() {
        for (phase in listOf(EnginePhase.STOPPED, EnginePhase.ARRIVED)) {
            val port = FakePort()
            val store = store(port)
            store.runtime(RuntimeSnapshot(phase = phase))
            assertTrue(store.state.canLocate)
            store.locate()
            assertEquals(1, port.opens)
            assertEquals(0, port.locates)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
            assertEquals(1, port.locates)
            assertEquals(0, port.plans)
        }
    }

    @Test fun incompleteTaskNeverExecutesOrRequestsRating() {
        val response = response(complete = false, feedback = true)
        assertFalse(response.isReady())
        assertFalse(response.requiresFeedback())
        assertIs<ResponseAction.ShowMessage>(NavigationResponseCoordinator.resolve(response))
    }
    @Test fun completeTaskExecutesWhileFeedbackIsPending() {
        val response = response(feedback = true)
        assertTrue(response.isReady())
        assertTrue(response.requiresFeedback())
        assertIs<ResponseAction.ApplyDraft>(NavigationResponseCoordinator.resolve(response))
    }
    @Test fun legacyResponseStillNeedsValidStops() {
        assertTrue(response(complete = null).isReady())
        assertFalse(response(complete = null, stops = emptyList()).isReady())
        assertFalse(response(stops = listOf(NavigationStop("destination", "未解析地点", null))).isReady())
    }
    @Test fun everyStopNeedsValidConfirmedCoordinates() {
        for (invalid in listOf(GeoPoint(Double.NaN, 116.0), GeoPoint(91.0, 116.0), GeoPoint(39.0, 181.0))) {
            assertFalse(response(stops = listOf(NavigationStop("destination", "目标", invalid))).isReady())
        }
        assertFalse(response(stops = listOf(NavigationStop("destination", "目标", point, true))).isReady())
        assertFalse(response(stops = listOf(NavigationStop("destination", "目标", point), NavigationStop("waypoint", "途经", null))).isReady())
    }
    @Test fun exactlyOneDestinationIsRequired() {
        assertFalse(response(stops = listOf(NavigationStop("waypoint", "途经", point))).isReady())
        assertFalse(response(stops = listOf(NavigationStop("destination", "一", point), NavigationStop("destination", "二", point))).isReady())
    }
    @Test fun errorAndDisambiguationNeverExecute() {
        assertFalse(response().copy(errorMessage = "错误").isReady())
        assertFalse(response().copy(status = "need_disambiguation").isReady())
    }
    @Test fun missingWireCoordinatesNeverBecomeZero() {
        val raw = """{"status":"ok","response_type":"navigation_task","task":{"stops":[{"role":"destination","poi":{"name":"测试","location":{"lat":39.0}}}]}}"""
        val response = NavigationResponseCodec.decode(JSONObject(raw))
        assertTrue(response.task!!.stops.first().point!!.longitude.isNaN())
        assertFalse(response.isReady())
    }
    @Test fun candidateGroupOrderIsPreservedByStopIndex() {
        val item = Candidate("p", "候选", "地址", point)
        val response = response().copy(responseType = "disambiguation", candidateGroups = listOf(
            CandidateGroup("later", 2, listOf(item)), CandidateGroup("first", 0, listOf(item))))
        assertEquals("first", assertIs<ResponseAction.Disambiguate>(NavigationResponseCoordinator.resolve(response)).group.id)
    }
    @Test fun candidateSelectionIsExposedAsStateAndUsesAgentCallback() {
        val selected = mutableListOf<String>()
        val store = NavigationStore(FakePort()) {}.also {
            it.configure(NavigationEnvironment(place, true, "test-app-id"))
            it.setCandidateSelectionHandler { group, candidate -> selected += "$group:$candidate" }
        }
        val candidate = Candidate("poi-1", "中央公园", "主路", point)
        store.acceptResponse(response().copy(responseType = "disambiguation",
            question = "请选择目的地", candidateGroups = listOf(CandidateGroup("group-1", 0, listOf(candidate)))))
        assertEquals("请选择目的地：北京大学\n请选择目的地", store.state.candidateQuestion)
        store.chooseCandidate(candidate)
        assertEquals(listOf("group-1:poi-1"), selected)
        assertNull(store.state.candidateGroup)
    }
    @Test fun malformedCompletionFlagIsNotTreatedAsLegacy() {
        val json = JSONObject("""{"status":"ok","response_type":"navigation_task","task_complete":"invalid","task":{"stops":[{"role":"destination","poi":{"location":{"lat":39.0,"lng":116.0}}}]}}""")
        assertFalse(NavigationResponseCodec.decode(json).isReady())
    }
    @Test fun codecPreservesPartialTaskAndProtocolMetadata() {
        val json = JSONObject("""
            {
              "type":"navigation",
              "status":"ok",
              "response_type":"disambiguation",
              "session_id":"s-1",
              "request_id":"r-1",
              "trace_id":"t-1",
              "question":"请选择目的地",
              "partial_task":{"travel_mode":"walk","task_title":"周末路线","stops":[{"role":"destination","text":"公园","needs_confirmation":true}]},
              "candidate_groups":[{"group_id":"g-1","for_stop_index":0,"hint":"附近有多个","items":[{"id":"p-1","score":0.8,"reasons":["同名地点"],"poi":{"poi_id":"poi-1","name":"中央公园","address":"主路","location":{"lat":39.1,"lng":116.1}}}]}]
            }
        """.trimIndent())
        val response = NavigationResponseCodec.decode(json)
        assertEquals("navigation", response.type)
        assertEquals("s-1", response.sessionId)
        assertEquals("walk", response.partialTask!!.travelMode)
        assertEquals("周末路线", response.partialTask.taskTitle)
        assertEquals("g-1", response.candidateGroups.single().id)
        assertEquals("附近有多个", response.candidateGroups.single().hint)
        assertEquals("poi-1", response.candidateGroups.single().candidates.single().poiId)
        assertEquals(0.8, response.candidateGroups.single().candidates.single().score)
        assertEquals(listOf("同名地点"), response.candidateGroups.single().candidates.single().reasons)
    }
    @Test fun codecPreservesErrorCodeAndUnsupportedReply() {
        val error = NavigationResponseCodec.decode(JSONObject("""
            {"type":"navigation","status":"error","response_type":"error","error":{"code":"POI_NOT_FOUND","message":"没有找到地点"}}
        """.trimIndent()))
        assertEquals("POI_NOT_FOUND", error.errorCode)
        assertTrue(error.isError())
        val unsupported = NavigationResponseCodec.decode(JSONObject("""
            {"status":"ok","response_type":"unsupported","reply":"暂不支持该请求"}
        """.trimIndent()))
        assertTrue(unsupported.isUnsupported())
        assertEquals("暂不支持该请求", assertIs<ResponseAction.ShowMessage>(NavigationResponseCoordinator.resolve(unsupported)).text)
    }
    @Test fun draftDeduplicatesWaypointsAndHonoursPlatformLimit() {
        val draft = NavigationDraft(place).withWaypoint(place, 1)
        assertFailsWith<IllegalArgumentException> { draft.withWaypoint(place, 1) }
        assertFailsWith<IllegalArgumentException> { draft.withWaypoint(Place("第二个", GeoPoint(40.0, 116.0)), 1) }
        assertEquals(1, draft.waypoints.size)
    }
    @Test fun unsupportedModesRemainRejectedAndExplicitOriginFallsBackToLiveLocation() {
        assertNotNull(NavigationDraft(place, travelMode = TravelMode.WALK).validate(NavigationCapabilities()))
        assertNull(NavigationDraft(place, origin = Place("指定起点", point)).validate(NavigationCapabilities()))
        assertNull(NavigationDraft(place).validate(NavigationCapabilities()))
    }
    @Test fun initialMapConfigurationDoesNotSelectTheExampleDestination() {
        val port = FakePort()
        val store = store(port)
        assertNull(store.state.draft.destination)
        assertEquals("", store.state.shell.input.text)
        assertEquals(0, store.state.draftRevision)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertFalse(store.state.canPlan)
        store.plan()
        assertEquals(0, port.plans)
        // A leftover native route snapshot cannot supply a missing shared destination.
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(1, 100.0, 10.0), routeRevision = 0))
        assertFalse(store.state.canStart)
        store.start(false)
        assertEquals(0, port.starts)
    }
    @Test fun repeatedConfigurationPreservesTheConfirmedPoiAndInput() {
        val port = FakePort()
        val store = store(port)
        val destination = Place("故宫博物院东华门", GeoPoint(39.9161, 116.4038))
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", destination.name, destination.point))))
        val revision = store.state.draftRevision
        assertEquals(destination.name, store.state.shell.input.text)
        repeat(2) { store.configure(NavigationEnvironment(place, true, "renewed-app-id")) }
        assertEquals(destination, store.state.draft.destination)
        assertEquals(destination.name, store.state.shell.input.text)
        assertEquals(revision, store.state.draftRevision)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertEquals(1, port.plans)
        assertEquals(destination, port.plannedDraft!!.destination)
    }
    @Test fun unresolvedNewDestinationCannotPlanOrStartTheOldPreview() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        val oldRevision = store.state.draftRevision
        val previousPreview = RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(12, 1000.0, 60.0), routeRevision = oldRevision)
        store.runtime(previousPreview)
        assertTrue(store.state.canStart)
        store.setInputText("去故宫博物院东华门")
        store.beginDestinationRequest()
        store.setInputSubmitting(true)
        assertTrue(store.state.resolvingDestination)
        assertFalse(store.state.canPlan)
        assertFalse(store.state.canStart)
        store.plan()
        store.start(false)
        store.setInputError("地点暂时无法识别")
        store.runtime(previousPreview)
        store.plan()
        store.start(false)
        assertEquals(0, port.plans)
        assertEquals(0, port.starts)
        assertEquals(place, store.state.draft.destination)
        assertEquals("去故宫博物院东华门", store.state.shell.input.text)
        assertFalse(store.state.canStart)
        val target = GeoPoint(39.9161, 116.4038)
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", "故宫博物院东华门", target))))
        assertFalse(store.state.resolvingDestination)
        assertEquals("故宫博物院东华门", store.state.shell.input.text)
        assertEquals(1, port.plans)
        assertEquals(target, port.plannedDraft!!.destination!!.point)
        assertEquals(oldRevision + 1, port.revision)
        assertFalse(store.state.canStart)
    }
    @Test fun modeAndWaypointEditsDoNotConfirmAnUnresolvedDestination() {
        val port = FakePort()
        val store = store(port)
        store.configure(NavigationEnvironment(place, true, "test-app-id",
            NavigationCapabilities(travelModes = TravelMode.entries.toSet())))
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(12, 1000.0, 60.0), routeRevision = store.state.draftRevision))
        store.setInputText("去附近的图书馆")
        store.beginDestinationRequest()
        store.setInputError("有多个同名地点，请进一步说明")
        store.selectTravelMode(TravelMode.WALK)
        store.clearWaypoints()
        assertEquals(TravelMode.WALK, store.state.draft.travelMode)
        assertEquals(place, store.state.draft.destination)
        assertEquals("去附近的图书馆", store.state.shell.input.text)
        assertTrue(store.state.resolvingDestination)
        assertFalse(store.state.canPlan)
        assertFalse(store.state.canStart)
        store.plan()
        store.start(false)
        assertEquals(0, port.plans)
        assertEquals(0, port.starts)
        store.selectPoint(GeoPoint(40.0, 116.2))
        assertFalse(store.state.resolvingDestination)
        assertEquals("地图选定的目的地", store.state.shell.input.text)
        store.plan()
        assertEquals(1, port.plans)
        assertEquals(GeoPoint(40.0, 116.2), port.plannedDraft!!.destination!!.point)
    }
    @Test fun incompleteDestinationResponseKeepsTheOldPreviewBlocked() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(12, 1000.0, 60.0), routeRevision = store.state.draftRevision))
        store.setInputText("去东门")
        store.beginDestinationRequest()
        store.acceptResponse(response(complete = false).copy(question = "请说明哪个地点的东门"))
        assertEquals(place, store.state.draft.destination)
        assertEquals("去东门", store.state.shell.input.text)
        assertTrue(store.state.resolvingDestination)
        assertFalse(store.state.canPlan)
        assertFalse(store.state.canStart)
        store.plan()
        store.start(false)
        assertEquals(0, port.plans)
        assertEquals(0, port.starts)
    }
    @Test fun confirmedReplacementWaitsForSynchronousStopBeforeOpeningAndPlanning() {
        for (phase in listOf(EnginePhase.PLANNING, EnginePhase.STARTING, EnginePhase.NAVIGATING)) {
            val port = FakePort()
            val store = store(port)
            store.restoreDestination()
            store.runtime(RuntimeSnapshot(phase = phase, location = point))
            val target = Place("故宫博物院", GeoPoint(39.9163, 116.3972))
            port.onStop = {
                assertEquals(place, store.state.draft.destination)
                assertTrue(store.state.resolvingDestination)
                assertEquals(0, port.opens)
                assertEquals(0, port.plans)
                store.runtime(RuntimeSnapshot(phase = EnginePhase.STOPPED))
            }
            store.acceptResponse(response(stops = listOf(NavigationStop("destination", target.name, target.point))))
            assertEquals(1, port.stops)
            assertEquals(1, port.opens)
            assertEquals(target, store.state.draft.destination)
            assertEquals(target.name, store.state.shell.input.text)
            assertFalse(store.state.resolvingDestination)
            assertEquals(0, port.plans)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
            assertEquals(1, port.locates)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            assertEquals(1, port.plans)
            assertEquals(target, port.plannedDraft!!.destination)
            assertEquals(2, port.revision)
        }
    }
    @Test fun confirmedReplacementWaitsForAsynchronousStopAndAFreshLocation() {
        for (phase in listOf(EnginePhase.PLANNING, EnginePhase.STARTING, EnginePhase.NAVIGATING)) {
            val port = FakePort()
            val store = store(port)
            store.restoreDestination()
            store.runtime(RuntimeSnapshot(phase = phase, location = point))
            val target = Place("天安门广场", GeoPoint(39.9054, 116.3976))
            store.acceptResponse(response(stops = listOf(NavigationStop("destination", target.name, target.point))))
            assertEquals(1, port.stops)
            assertEquals(place, store.state.draft.destination)
            assertTrue(store.state.resolvingDestination)
            assertEquals(0, port.opens)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            store.plan()
            assertEquals(0, port.plans, "a late old-generation fix must not plan the replacement")
            store.runtime(RuntimeSnapshot(phase = EnginePhase.STOPPED))
            assertEquals(target, store.state.draft.destination)
            assertEquals(1, port.opens)
            assertEquals(0, port.plans)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
            assertEquals(1, port.locates)
            val latestLocation = GeoPoint(39.91, 116.41)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = latestLocation))
            assertEquals(1, port.plans)
            assertEquals(target, port.plannedDraft!!.destination)
            assertEquals(2, port.revision)
            assertEquals(latestLocation, store.state.runtime.location)
        }
    }
    @Test fun explicitStopCancelsAReplacementStillWaitingForNativeStop() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.NAVIGATING, location = point))
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", "新目的地", GeoPoint(40.0, 116.5)))))
        assertEquals(1, port.stops)
        store.stop()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.STOPPED))
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertEquals(2, port.stops)
        assertEquals(0, port.opens)
        assertEquals(0, port.plans)
        assertEquals(place, store.state.draft.destination)
    }
    @Test fun newerDestinationRequestCancelsAnOlderReplacementWaitingForStop() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.NAVIGATING, location = point))
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", "上次请求的目的地", GeoPoint(40.0, 116.5)))))
        assertEquals(1, port.stops)
        store.setInputText("现在改去天安门广场")
        store.beginDestinationRequest()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.STOPPED))
        assertEquals(place, store.state.draft.destination)
        assertEquals("现在改去天安门广场", store.state.shell.input.text)
        assertTrue(store.state.resolvingDestination)
        assertEquals(0, port.opens)
        assertEquals(0, port.plans)
        val latestDestination = Place("天安门广场", GeoPoint(39.9054, 116.3976))
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", latestDestination.name, latestDestination.point))))
        assertEquals(latestDestination, store.state.draft.destination)
        assertEquals(latestDestination.name, store.state.shell.input.text)
        assertEquals(1, port.opens)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertEquals(1, port.plans)
        assertEquals(latestDestination, port.plannedDraft!!.destination)
    }
    @Test fun guidanceModificationIncludesCurrentOriginWaypointsAndDestination() {
        val port = FakePort()
        val store = store(port)
        store.configure(NavigationEnvironment(place, true, "test-app-id",
            NavigationCapabilities(travelModes = TravelMode.entries.toSet())))
        val oldOrigin = GeoPoint(39.8, 116.2)
        val waypoint = GeoPoint(39.9, 116.3)
        store.acceptResponse(response().copy(task = NavigationTask(listOf(
            NavigationStop("origin", "旧起点", oldOrigin),
            NavigationStop("waypoint", "咖啡店", waypoint),
            NavigationStop("destination", place.name, point)
        ), "walk")))
        assertNull(store.state.draft.origin, "native routing always uses the latest accepted fix")
        val currentOrigin = GeoPoint(39.91, 116.31)
        for (phase in listOf(EnginePhase.STARTING, EnginePhase.NAVIGATING)) {
            store.runtime(RuntimeSnapshot(phase = phase, location = currentOrigin))
            val task = assertNotNull(store.taskForModification())
            assertEquals("walk", task.travelMode)
            assertEquals(listOf("origin", "waypoint", "destination"), task.stops.map { it.role })
            assertEquals(listOf(currentOrigin, waypoint, point), task.stops.map { it.point })
            assertEquals(listOf("当前位置", "咖啡店", place.name), task.stops.map { it.name })
        }
        for (location in listOf(null, GeoPoint(Double.NaN, 116.0))) {
            store.runtime(RuntimeSnapshot(phase = EnginePhase.NAVIGATING, location = location))
            assertEquals(listOf("waypoint", "destination"), assertNotNull(store.taskForModification()).stops.map { it.role })
        }
    }
    @Test fun previewAndMapSubmissionsDoNotReuseAnExistingTask() {
        val store = store(FakePort())
        store.restoreDestination()
        for (phase in listOf(EnginePhase.IDLE, EnginePhase.MAP_READY, EnginePhase.LOCATION_READY,
            EnginePhase.PLANNING, EnginePhase.ROUTE_READY, EnginePhase.STOPPED, EnginePhase.ARRIVED)) {
            store.runtime(RuntimeSnapshot(phase = phase, location = point))
            assertNull(store.taskForModification(), "phase $phase must start a new intent")
        }
    }
    @Test fun candidateQuestionUsesThePartialTaskStopRoleAndName() {
        for ((role, label) in listOf("origin" to "起点", "waypoint" to "途经点", "destination" to "目的地")) {
            val store = store(FakePort())
            val candidate = Candidate("poi-1", "公园东门", "公园路", point)
            store.acceptResponse(response().copy(responseType = "disambiguation", question = "附近有多个同名地点",
                partialTask = NavigationTask(listOf(NavigationStop(role, "公园", null, true))),
                candidateGroups = listOf(CandidateGroup("group-1", 0, listOf(candidate)))))
            assertEquals("请选择$label：公园\n附近有多个同名地点", store.state.candidateQuestion)
            assertTrue(store.state.resolvingDestination)
        }
    }
    @Test fun synchronousCandidateCallbackPreservesTheNextCandidateGroup() {
        val store = store(FakePort())
        val first = Candidate("first-poi", "咖啡店南门", "南街", point)
        val second = Candidate("second-poi", "公园东门", "东街", GeoPoint(40.0, 116.0))
        val firstGroup = CandidateGroup("first-group", 0, listOf(first))
        val secondGroup = CandidateGroup("second-group", 1, listOf(second))
        val partial = NavigationTask(listOf(
            NavigationStop("waypoint", "咖啡店", null, true),
            NavigationStop("destination", "公园", null, true)
        ))
        val selected = mutableListOf<String>()
        store.setCandidateSelectionHandler { group, candidate ->
            selected += "$group:$candidate"
            store.acceptResponse(response().copy(responseType = "disambiguation", partialTask = partial,
                candidateGroups = listOf(secondGroup)))
        }
        store.acceptResponse(response().copy(responseType = "disambiguation", partialTask = partial,
            candidateGroups = listOf(firstGroup, secondGroup)))
        store.chooseCandidate(first)
        assertEquals(listOf("first-group:first-poi"), selected)
        assertEquals(secondGroup, store.state.candidateGroup)
        assertEquals("请选择目的地：公园", store.state.candidateQuestion)
        assertTrue(store.state.resolvingDestination)
    }
    @Test fun mapReadyAutomaticallyRequestsLocationAndAgentTaskPlansAfterFix() {
        val port = FakePort()
        val store = store(port)
        store.acceptResponse(response())
        assertEquals(place, store.state.draft.destination)
        assertEquals(0, port.locates)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
        assertEquals(1, port.locates)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY))
        assertEquals(1, port.revision)
        assertEquals(1, port.plans)
    }
    @Test fun completeAgentTaskWaitsThroughMapLoadingAndLocationWithoutDroppingDestination() {
        for (phase in listOf(EnginePhase.INITIALIZING, EnginePhase.LOADING_MAP, EnginePhase.LOCATING)) {
            val port = FakePort()
            val store = store(port)
            store.runtime(RuntimeSnapshot(phase = phase))
            val target = GeoPoint(40.0, 116.5)
            store.acceptResponse(response(stops = listOf(NavigationStop("destination", "新目的地", target))))
            assertEquals(target, store.state.draft.destination!!.point)
            assertEquals(0, port.plans)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            assertEquals(1, port.plans, "one route per response even when GPS keeps updating")
            assertEquals(target, port.plannedDraft!!.destination!!.point)
        }
    }
    @Test fun synchronousClearRouteCallbackPlansNewDraftExactlyOnce() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(1, 500.0, 100.0), routeRevision = 1))
        port.onClearRoute = {
            assertFalse(store.state.canStart)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        }
        val target = GeoPoint(40.0, 116.5)
        store.acceptResponse(response(stops = listOf(NavigationStop("destination", "新目的地", target))))
        assertEquals(1, port.plans)
        assertEquals(2, port.revision)
        assertEquals(target, port.plannedDraft!!.destination!!.point)
    }
    @Test fun changingTravelModeReplansExistingPreviewButDoesNotPlanAnUnplannedDraft() {
        val port = FakePort()
        val store = store(port)
        store.configure(NavigationEnvironment(place, true, "test-app-id",
            NavigationCapabilities(travelModes = TravelMode.entries.toSet())))
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        store.selectTravelMode(TravelMode.WALK)
        assertEquals(0, port.plans)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, location = point,
            route = RouteSummary(1, 500.0, 100.0), routeRevision = store.state.draftRevision))
        port.onClearRoute = { store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point)) }
        store.selectTravelMode(TravelMode.RIDE)
        assertEquals(1, port.plans)
        assertEquals(TravelMode.RIDE, port.plannedDraft!!.travelMode)
        assertEquals(place, port.plannedDraft!!.destination)
        assertFalse(store.state.canStart)
        store.selectTravelMode(TravelMode.RIDE)
        assertEquals(1, port.plans, "selecting the current mode must not duplicate the route request")
    }
    @Test fun stopOrLocationFailureCancelsQueuedAgentRoute() {
        for (phase in listOf(EnginePhase.STOPPED, EnginePhase.ERROR, EnginePhase.DISPOSED)) {
            val port = FakePort()
            val store = store(port)
            store.acceptResponse(response())
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATING))
            store.runtime(RuntimeSnapshot(phase = phase))
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            assertEquals(0, port.plans)
        }
    }
    @Test fun repeatedMapReadyEventsOnlyRequestOneFixPerOpen() {
        val port = FakePort()
        val store = store(port)
        repeat(3) { store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY)) }
        assertEquals(1, port.locates)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.STOPPED))
        store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
        assertEquals(2, port.locates)
    }
    @Test fun routeStartUsesSdkStateAndMatchingDraftRevision() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.start(false)
        assertEquals(0, port.starts)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, route = RouteSummary(12, 1000.0, 60.0), routeRevision = 1))
        assertTrue(store.state.canStart)
        store.selectPoint(GeoPoint(40.0, 116.0))
        assertFalse(store.state.canStart)
        store.start(false)
        assertEquals(0, port.starts)
        // A queued GPS snapshot may still carry the previous route: it must not re-enable start.
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, route = RouteSummary(12, 1000.0, 60.0), routeRevision = 1))
        assertFalse(store.state.canStart)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY))
        store.plan()
        assertEquals(2, port.revision)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.ROUTE_READY, route = RouteSummary(13, 1500.0, 90.0), routeRevision = 2))
        store.start(false)
        assertEquals(1, port.starts)
        assertEquals(2, port.startedRevision)
        assertEquals(MapMode.ROUTE_PREVIEW, store.state.mode, "a command cannot fake the SDK start event")
        store.runtime(RuntimeSnapshot(phase = EnginePhase.NAVIGATING))
        assertEquals(MapMode.GUIDANCE, store.state.mode)
    }
    @Test fun draftCannotChangeDuringCalculationOrNavigation() {
        val store = store(FakePort())
        store.restoreDestination()
        for (phase in listOf(EnginePhase.PLANNING, EnginePhase.STARTING, EnginePhase.NAVIGATING)) {
            store.runtime(RuntimeSnapshot(phase = phase))
            store.selectPoint(GeoPoint(40.0, 116.0))
            assertEquals(place, store.state.draft.destination)
        }
    }
    @Test fun incompleteAgentResponsePreservesCurrentDraft() {
        val store = store(FakePort())
        store.restoreDestination()
        store.acceptResponse(response(complete = false))
        assertEquals(place, store.state.draft.destination)
        assertEquals(1, store.state.draftRevision)
    }
    @Test fun unknownTravelModeIsRejectedWithoutDrivingFallback() {
        val response = response().copy(task = NavigationTask(response().task!!.stops, "flight"))
        assertIs<ResponseAction.ShowMessage>(NavigationResponseCoordinator.resolve(response))
    }
    @Test fun planningCommandImmediatelyBlocksDuplicateCommandsBeforeNativeAcknowledgement() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        store.plan()
        assertEquals(PendingNavigationCommand.PLAN, store.state.pendingCommand)
        assertEquals("正在规划路线…", store.state.busyMessage)
        assertFalse(store.state.canSubmit)
        repeat(3) { store.plan(); store.locate(); store.start(false); store.selectPoint(GeoPoint(40.0, 116.0)) }
        assertEquals(1, port.plans)
        assertEquals(0, port.locates)
        assertEquals(0, port.starts)
        assertEquals(place, store.state.draft.destination)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.PLANNING, location = point))
        assertEquals(PendingNavigationCommand.NONE, store.state.pendingCommand)
        assertTrue(store.state.busy, "the native phase keeps the lock after its command acknowledgement")
        store.runtime(preview(store))
        assertFalse(store.state.busy)
        assertTrue(store.state.canSubmit)
        assertTrue(store.state.canStart)
    }
    @Test fun locatingCommandBlocksDuplicateLocationAndSubmissionUntilAFixArrives() {
        val port = FakePort()
        val store = store(port)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        repeat(3) { store.locate() }
        assertEquals(1, port.locates)
        assertEquals(PendingNavigationCommand.LOCATE, store.state.pendingCommand)
        assertEquals("正在定位…", store.state.busyMessage)
        assertFalse(store.state.canSubmit)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATING))
        assertTrue(store.state.busy)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertFalse(store.state.busy)
        assertTrue(store.state.canSubmit)
    }
    @Test fun startingCommandBlocksBothNavigationModesBeforeNativeAcknowledgement() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        val preview = preview(store)
        store.runtime(preview)
        store.start(false)
        repeat(3) { store.start(true); store.plan(); store.locate() }
        assertEquals(1, port.starts)
        assertFalse(port.startedSimulation)
        assertEquals(0, port.plans)
        assertEquals(0, port.locates)
        assertEquals("正在启动导航…", store.state.busyMessage)
        assertFalse(store.state.canSubmit)
        store.runtime(preview.copy(phase = EnginePhase.STARTING))
        assertTrue(store.state.busy)
        store.runtime(preview.copy(phase = EnginePhase.NAVIGATING))
        assertFalse(store.state.busy)
        assertTrue(store.state.canSubmit, "navigation allows a later spoken/text destination modification")
    }
    @Test fun expiredLocationDuringStartOrPlanDoesNotLeavePendingCommandAfterReplanning() {
        for (starting in listOf(true, false)) {
            val port = FakePort()
            val store = store(port)
            store.restoreDestination()
            val preview = preview(store)
            store.runtime(preview)
            if (starting) store.start(false) else store.plan()
            assertTrue(store.state.busy)
            // The SDK refuses the stale fix and refreshes location instead of
            // acknowledging the requested STARTING/PLANNING phase immediately.
            store.runtime(preview.copy(phase = EnginePhase.LOCATING, route = null, routeRevision = -1))
            assertEquals(PendingNavigationCommand.NONE, store.state.pendingCommand)
            assertTrue(store.state.busy)
            store.runtime(preview.copy(phase = EnginePhase.LOCATION_READY, route = null, routeRevision = -1))
            store.runtime(preview.copy(phase = EnginePhase.PLANNING, route = null, routeRevision = -1))
            assertTrue(store.state.busy)
            store.runtime(preview)
            assertFalse(store.state.busy)
            assertTrue(store.state.canStart)
            assertTrue(store.state.canSubmit)
            assertEquals(if (starting) 1 else 0, port.starts, "refreshing the fix must not automatically issue another start")
            assertEquals(if (starting) 0 else 1, port.plans, "native replanning must not be duplicated by the shared store")
        }
    }
    @Test fun confirmedTaskStaysBusyAcrossMapAndLocationGapsUntilRoutePreviewArrives() {
        val port = FakePort()
        val store = store(port)
        store.beginDestinationRequest()
        store.setInputSubmitting(true)
        assertEquals("正在解析目的地…", store.state.busyMessage)
        store.acceptResponse(response())
        assertTrue(store.state.preparingRoute)
        assertFalse(store.state.shell.input.submitting)
        assertTrue(store.state.busy)
        assertFalse(store.state.canSubmit)
        assertEquals(1, port.opens)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.MAP_READY))
        assertEquals(1, port.locates)
        assertTrue(store.state.busy)
        store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
        assertEquals(1, port.plans)
        assertTrue(store.state.busy)
        store.runtime(preview(store))
        assertFalse(store.state.preparingRoute)
        assertFalse(store.state.busy)
        assertTrue(store.state.canStart)
    }
    @Test fun candidateWaitUnlocksInteractionAndCandidateFailureUnlocksSubmission() {
        val port = FakePort()
        val store = store(port)
        val candidate = Candidate("candidate-1", "公园东门", "公园路", point)
        store.beginDestinationRequest()
        store.setInputSubmitting(true)
        store.acceptResponse(response().copy(responseType = "disambiguation",
            candidateGroups = listOf(CandidateGroup("group-1", 0, listOf(candidate)))))
        // Page ends the transport request after applying its response.
        store.setInputSubmitting(false)
        assertFalse(store.state.busy, "the user must be able to choose the returned candidate")
        assertTrue(store.state.canSubmit)
        store.setCandidateSelectionHandler { _, _ -> store.setInputSubmitting(true, analyzing = false) }
        store.chooseCandidate(candidate)
        assertEquals("正在确认地点…", store.state.busyMessage)
        assertFalse(store.state.canSubmit)
        store.setInputError("候选确认网络失败，请重新输入")
        assertFalse(store.state.busy)
        assertTrue(store.state.canSubmit)
        assertTrue(store.state.resolvingDestination)
        assertFalse(store.state.canPlan)
        assertFalse(store.state.canStart)
        assertEquals(0, port.plans)
    }
    @Test fun failedNativeCommandsClearPendingAndQueuedPreparationWithoutPlanningLater() {
        for (phase in listOf(EnginePhase.ERROR, EnginePhase.STOPPED, EnginePhase.ARRIVED, EnginePhase.DISPOSED)) {
            val port = FakePort()
            val store = store(port)
            store.acceptResponse(response())
            assertTrue(store.state.busy)
            store.runtime(RuntimeSnapshot(phase = phase, message = "处理结束"))
            assertEquals(PendingNavigationCommand.NONE, store.state.pendingCommand)
            assertFalse(store.state.preparingRoute)
            assertFalse(store.state.busy)
            assertEquals(phase != EnginePhase.DISPOSED, store.state.canSubmit)
            store.runtime(RuntimeSnapshot(phase = EnginePhase.LOCATION_READY, location = point))
            assertEquals(0, port.plans, "a late fix cannot revive a failed/cancelled route")
        }
    }
    @Test fun commandRejectionInBridgeGapReleasesTheUiForRetry() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        store.runtime(preview(store))
        store.start(false)
        assertTrue(store.state.busy)
        store.commandFailed("启动导航失败，请重试")
        assertFalse(store.state.busy)
        assertTrue(store.state.canStart)
        assertEquals("启动导航失败，请重试", store.state.notice)
        store.start(true)
        assertEquals(2, port.starts)
        assertTrue(port.startedSimulation)
    }
    @Test fun exitGuidancePreservesDraftAndRouteThenAllowsSimulationWithoutReplanning() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        val draft = store.state.draft
        val revision = store.state.draftRevision
        val preview = preview(store)
        val clearsBefore = port.clears
        store.runtime(preview.copy(phase = EnginePhase.NAVIGATING))
        assertTrue(store.state.canExitGuidance)
        repeat(3) { store.exitGuidance() }
        assertEquals(1, port.exits)
        assertEquals(0, port.stops)
        assertEquals("正在返回路线…", store.state.busyMessage)
        assertFalse(store.state.canStart)
        store.runtime(preview)
        assertEquals(MapMode.ROUTE_PREVIEW, store.state.mode)
        assertEquals(draft, store.state.draft)
        assertEquals(revision, store.state.draftRevision)
        assertEquals(preview.route, store.state.runtime.route)
        assertEquals(clearsBefore, port.clears)
        assertTrue(store.state.overview)
        assertFalse(store.state.busy)
        assertTrue(store.state.canStart)
        store.start(true)
        assertEquals(1, port.starts)
        assertTrue(port.startedSimulation)
        assertEquals(revision, port.startedRevision)
        assertEquals(0, port.plans)
    }
    @Test fun synchronousExitAcknowledgementPreservesPreviewAndClearsBusyState() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        val preview = preview(store)
        store.runtime(preview.copy(phase = EnginePhase.NAVIGATING))
        port.onExit = { store.runtime(preview) }
        store.exitGuidance()
        assertEquals(1, port.exits)
        assertEquals(PendingNavigationCommand.NONE, store.state.pendingCommand)
        assertFalse(store.state.busy)
        assertTrue(store.state.canStart)
        assertEquals(place, store.state.draft.destination)
    }
    @Test fun navigationProgressKeepsUserOverviewUntilFollowOrNewGuidanceEntry() {
        val port = FakePort()
        val store = store(port)
        store.restoreDestination()
        val navigating = preview(store).copy(phase = EnginePhase.NAVIGATING)
        store.runtime(navigating)
        store.setOverview(true)
        assertEquals(1, port.overviews)
        repeat(3) { count -> store.runtime(navigating.copy(progressCount = count + 1,
            progress = NavigationProgress(900.0 - count * 100, 50.0, 20.0, "中关村路", "学院路"))) }
        assertTrue(store.state.overview, "progress updates must not reset a deliberate full-route view")
        store.setOverview(false)
        assertFalse(store.state.overview)
        assertEquals(1, port.follows)
        store.runtime(preview(store))
        assertTrue(store.state.overview)
        store.start(false)
        store.runtime(navigating)
        assertFalse(store.state.overview, "new navigation starts with the current-position camera")
    }
    private fun preview(store: NavigationStore) = RuntimeSnapshot(phase = EnginePhase.ROUTE_READY,
        location = point, route = RouteSummary(12, 1000.0, 60.0), routeRevision = store.state.draftRevision)
    private fun store(port: FakePort) = NavigationStore(port) {}.also {
        it.configure(NavigationEnvironment(place, true, "test-app-id"))
    }
    private class FakePort : NavigationPort {
        var starts = 0
        var stops = 0
        var opens = 0
        var locates = 0
        var plans = 0
        var exits = 0
        var clears = 0
        var overviews = 0
        var follows = 0
        var startedSimulation = false
        var revision = -1
        var startedRevision = -1
        var plannedDraft: NavigationDraft? = null
        var onClearRoute: () -> Unit = {}
        var onStop: () -> Unit = {}
        var onExit: () -> Unit = {}
        override fun open() { opens++ }
        override fun locate() { locates++ }
        override fun plan(draft: NavigationDraft, revision: Int) { plans++; this.revision = revision; plannedDraft = draft }
        override fun start(simulation: Boolean, revision: Int) { starts++; startedRevision = revision; startedSimulation = simulation }
        override fun stop() { stops++; onStop() }
        override fun exitGuidance() { exits++; onExit() }
        override fun clearRoute() { clears++; onClearRoute() }
        override fun overview() { overviews++ }
        override fun follow() { follows++ }
    }
}
