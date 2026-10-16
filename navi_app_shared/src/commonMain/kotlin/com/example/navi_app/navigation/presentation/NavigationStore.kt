package com.example.navi_app.navigation.presentation

import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.*
import com.example.navi_app.navigation.port.*
import com.example.navi_app.navigation.protocol.*

enum class PendingNavigationCommand { NONE, OPEN, LOCATE, PLAN, START, EXIT_GUIDANCE }

data class NavigationUiState(
    val runtime: RuntimeSnapshot = RuntimeSnapshot(),
    val draft: NavigationDraft = NavigationDraft(),
    val selectingWaypoint: Boolean = false,
    val notice: String = "",
    val draftRevision: Int = 0,
    val environment: NavigationEnvironment? = null,
    val overview: Boolean = false,
    /** A submitted destination must be confirmed before an older preview can run. */
    val resolvingDestination: Boolean = false,
    /** The first unresolved MapTemplate candidate group, shown above the route actions. */
    val candidateGroup: CandidateGroup? = null,
    val candidateQuestion: String = "",
    val candidateTravelMode: String? = null,
    /** Cover the asynchronous bridge gap before the platform publishes its phase. */
    val pendingCommand: PendingNavigationCommand = PendingNavigationCommand.NONE,
    val preparingRoute: Boolean = false,
    /** MapTemplate-compatible page shell state, shared by Kuikly and platform adapters. */
    val shell: NavigationShellState = NavigationShellState()
) {
    val busyMessage get() = when {
        shell.input.submitting -> if (shell.input.analyzing) "正在解析目的地…" else "正在确认地点…"
        pendingCommand == PendingNavigationCommand.EXIT_GUIDANCE -> "正在返回路线…"
        pendingCommand == PendingNavigationCommand.OPEN || runtime.phase in setOf(EnginePhase.INITIALIZING, EnginePhase.LOADING_MAP) -> "正在加载地图…"
        pendingCommand == PendingNavigationCommand.LOCATE || runtime.phase == EnginePhase.LOCATING -> "正在定位…"
        pendingCommand == PendingNavigationCommand.PLAN || runtime.phase == EnginePhase.PLANNING -> "正在规划路线…"
        pendingCommand == PendingNavigationCommand.START || runtime.phase == EnginePhase.STARTING -> "正在启动导航…"
        preparingRoute -> "正在规划路线…"
        else -> ""
    }
    val busy get() = busyMessage.isNotEmpty()
    val canSubmit get() = !busy && runtime.phase != EnginePhase.DISPOSED
    val mode get() = when (runtime.phase) {
        EnginePhase.NAVIGATING, EnginePhase.STARTING -> MapMode.GUIDANCE
        EnginePhase.ROUTE_READY -> MapMode.ROUTE_PREVIEW
        else -> MapMode.MAP
    }
    val canOpen get() = runtime.phase in setOf(EnginePhase.IDLE, EnginePhase.ERROR, EnginePhase.STOPPED, EnginePhase.ARRIVED)
    val canLocate get() = !busy && runtime.phase in setOf(EnginePhase.MAP_READY, EnginePhase.LOCATION_READY, EnginePhase.ROUTE_READY,
        EnginePhase.STOPPED, EnginePhase.ARRIVED)
    val canEdit get() = (canOpen || canLocate) && !busy
    val canPlan get() = runtime.phase in setOf(EnginePhase.LOCATION_READY, EnginePhase.ROUTE_READY) &&
        draft.destination != null && !resolvingDestination && !busy
    val canStart get() = runtime.phase == EnginePhase.ROUTE_READY && runtime.route != null &&
        draft.destination != null && runtime.routeRevision == draftRevision && !resolvingDestination && !busy
    val canExitGuidance get() = mode == MapMode.GUIDANCE && runtime.route != null && !busy
}

/** MapTemplate's Intent → state/effect pattern; execution phase is always supplied by the native session. */
class NavigationStore(private val port: NavigationPort, private val observer: (NavigationUiState) -> Unit) {
    private var candidateSelectionHandler: (String, String) -> Unit = { _, _ -> }
    private var pendingRoutePlan = false
    private var autoLocationRequested = false
    private var updatingDraft = false
    private var replacementDraft: NavigationDraft? = null
    private var mapActivated = false
    private var initialMapOpenRequested = false
    var state = NavigationUiState()
        private set

    fun configure(environment: NavigationEnvironment) {
        // The platform's fixed point is only an initial map camera/demo setting.
        // Re-subscribing after route replacement must never overwrite a user's POI.
        update(state.copy(environment = environment))
        openInitialMapIfReady()
    }
    /** The page starts its map once configuration is available, without a second load button. */
    fun activateMap() {
        mapActivated = true
        openInitialMapIfReady()
    }
    private fun openInitialMapIfReady() {
        if (!mapActivated || initialMapOpenRequested || state.environment == null || state.runtime.phase != EnginePhase.IDLE) return
        initialMapOpenRequested = true
        open()
    }
    fun runtime(snapshot: RuntimeSnapshot) {
        val terminal = snapshot.phase in setOf(EnginePhase.ERROR, EnginePhase.STOPPED, EnginePhase.ARRIVED, EnginePhase.DISPOSED)
        val acknowledged = terminal || when (state.pendingCommand) {
            PendingNavigationCommand.OPEN -> snapshot.phase in setOf(EnginePhase.INITIALIZING, EnginePhase.LOADING_MAP, EnginePhase.MAP_READY, EnginePhase.LOCATING, EnginePhase.LOCATION_READY)
            PendingNavigationCommand.LOCATE -> snapshot.phase in setOf(EnginePhase.LOCATING, EnginePhase.LOCATION_READY)
            PendingNavigationCommand.PLAN -> snapshot.phase in setOf(EnginePhase.LOCATING, EnginePhase.PLANNING, EnginePhase.ROUTE_READY)
            PendingNavigationCommand.START -> snapshot.phase in setOf(EnginePhase.LOCATING, EnginePhase.STARTING, EnginePhase.NAVIGATING)
            PendingNavigationCommand.EXIT_GUIDANCE -> snapshot.phase == EnginePhase.ROUTE_READY
            PendingNavigationCommand.NONE -> false
        }
        val returnedToPreview = state.mode == MapMode.GUIDANCE && snapshot.phase == EnginePhase.ROUTE_READY
        val enteredGuidance = state.runtime.phase != EnginePhase.NAVIGATING && snapshot.phase == EnginePhase.NAVIGATING
        update(state.copy(runtime = snapshot,
            pendingCommand = if (acknowledged) PendingNavigationCommand.NONE else state.pendingCommand,
            preparingRoute = if (terminal || snapshot.phase == EnginePhase.ROUTE_READY) false else state.preparingRoute,
            overview = if (returnedToPreview) true else if (snapshot.phase == EnginePhase.STOPPED || enteredGuidance) false else state.overview))
        openInitialMapIfReady()
        if (snapshot.phase in setOf(EnginePhase.IDLE, EnginePhase.ERROR, EnginePhase.STOPPED, EnginePhase.ARRIVED)) {
            autoLocationRequested = false
        }
        if (snapshot.phase in setOf(EnginePhase.ERROR, EnginePhase.STOPPED, EnginePhase.ARRIVED, EnginePhase.DISPOSED)) {
            pendingRoutePlan = false
        }
        if (snapshot.phase in setOf(EnginePhase.ERROR, EnginePhase.ARRIVED, EnginePhase.DISPOSED)) {
            replacementDraft = null
        }
        if (snapshot.phase == EnginePhase.STOPPED) {
            replacementDraft?.let { draft ->
                replacementDraft = null
                changeDraft(draft, schedulePlan = true)
                open()
                return
            }
        }
        advancePendingWork()
    }
    private fun advancePendingWork() {
        // Native clearRoute may publish a snapshot synchronously. Wait until the new
        // draft and invalidation command have both been applied before dispatching.
        if (updatingDraft) return
        // Match MapTemplate: once the map is ready, request one fresh fix without
        // requiring the user to discover a second location button.
        if (state.runtime.phase == EnginePhase.MAP_READY && !autoLocationRequested) {
            autoLocationRequested = true
            requestLocation()
        }
        // A complete agent response can arrive before the native map/location
        // pipeline is ready. Keep the route request pending and execute it on the
        // first accepted location instead of dropping the navigation intent.
        if (pendingRoutePlan && state.runtime.phase in setOf(EnginePhase.LOCATION_READY, EnginePhase.ROUTE_READY) &&
            state.draft.destination != null && !state.resolvingDestination && !state.shell.input.submitting &&
            state.pendingCommand == PendingNavigationCommand.NONE) {
            dispatchPlan()
        }
    }
    fun message(text: String) = update(state.copy(notice = text))
    fun commandFailed(text: String) {
        pendingRoutePlan = false
        replacementDraft = null
        update(state.copy(pendingCommand = PendingNavigationCommand.NONE, preparingRoute = false, notice = text))
    }
    fun setTab(tab: NavigationTab) = update(state.copy(shell = state.shell.copy(tab = tab)))
    fun setInputText(text: String) = update(state.copy(shell = state.shell.copy(
        input = state.shell.input.copy(text = text, error = ""))))
    fun setMicListening(listening: Boolean) = update(state.copy(shell = state.shell.copy(
        input = state.shell.input.copy(micListening = listening))))
    fun setInputSubmitting(submitting: Boolean, analyzing: Boolean = submitting) =
        update(state.copy(shell = state.shell.copy(input = state.shell.input.copy(
            submitting = submitting, analyzing = analyzing))))
    fun beginDestinationRequest() {
        pendingRoutePlan = false
        replacementDraft = null
        update(state.copy(resolvingDestination = true, preparingRoute = false, candidateGroup = null, candidateQuestion = "", candidateTravelMode = null,
            notice = "正在识别目的地，请等待地点确认后规划路线"))
    }
    /** MapTemplate sends modify_task only during guidance, with the live origin. */
    fun taskForModification(): NavigationTask? {
        if (state.mode != MapMode.GUIDANCE) return null
        val destination = state.draft.destination ?: return null
        val stops = mutableListOf<NavigationStop>()
        state.runtime.location?.takeIf { it.isValid() }?.let {
            stops += NavigationStop("origin", "当前位置", it)
        }
        stops += state.draft.waypoints.map { NavigationStop("waypoint", it.name, it.point) }
        stops += NavigationStop("destination", destination.name, destination.point)
        return NavigationTask(stops, state.draft.travelMode.wireValue)
    }
    fun setInputError(error: String) = update(state.copy(shell = state.shell.copy(
        input = state.shell.input.copy(submitting = false, analyzing = false, error = error))))
    fun setAuth(auth: AuthState) = update(state.copy(shell = state.shell.copy(auth = auth)))
    fun setSession(session: SessionState) = update(state.copy(shell = state.shell.copy(session = session)))
    fun showSessionOverlay(show: Boolean) = update(state.copy(shell = state.shell.copy(
        sessionOverlayVisible = show)))
    fun updateShell(transform: (NavigationShellState) -> NavigationShellState) =
        update(state.copy(shell = transform(state.shell)))
    fun appendSessionMessage(message: NavigationMessage) {
        val history = state.shell.session.history + message
        update(state.copy(shell = state.shell.copy(session = state.shell.session.copy(history = history))))
    }
    fun setCandidateSelectionHandler(handler: (String, String) -> Unit) {
        candidateSelectionHandler = handler
    }
    fun open() {
        if (state.canOpen && state.pendingCommand == PendingNavigationCommand.NONE) {
            update(state.copy(pendingCommand = PendingNavigationCommand.OPEN, notice = ""))
            port.open()
        }
    }
    fun locate() {
        if (!state.canLocate) return
        // A stopped session still owns its visible map. Re-arm it before asking
        // for a new fix; MAP_READY schedules locate through advancePendingWork.
        if (state.canOpen) open() else requestLocation()
    }
    private fun requestLocation() {
        if (state.pendingCommand != PendingNavigationCommand.NONE) return
        update(state.copy(pendingCommand = PendingNavigationCommand.LOCATE, notice = ""))
        port.locate()
    }
    fun selectPoint(point: GeoPoint) {
        if (!state.canEdit || !point.isValid()) return
        try {
            val draft = if (state.selectingWaypoint) state.draft.withWaypoint(Place("途经点 ${state.draft.waypoints.size + 1}", point),
                state.environment?.capabilities?.maxWaypoints ?: 0)
            else state.draft.withDestination(Place("地图选定的目的地", point))
            changeDraft(draft, confirmsDestination = !state.selectingWaypoint)
        } catch (error: IllegalArgumentException) { message(error.message ?: "地点无效") }
    }
    fun toggleWaypoint() { if (state.canEdit) update(state.copy(selectingWaypoint = !state.selectingWaypoint, notice = "")) }
    fun clearWaypoints() { if (state.canEdit) changeDraft(state.draft.copy(waypoints = emptyList()), confirmsDestination = false) }
    fun selectTravelMode(mode: TravelMode) {
        if (!state.canEdit || mode == state.draft.travelMode) return
        changeDraft(state.draft.copy(travelMode = mode), schedulePlan = state.runtime.route != null, confirmsDestination = false)
    }
    fun restoreDestination() {
        if (state.canEdit) state.environment?.let { changeDraft(NavigationDraft(destination = it.defaultDestination)) }
    }
    fun plan() {
        if (!state.canPlan) return
        dispatchPlan()
    }
    private fun dispatchPlan() {
        pendingRoutePlan = false
        val error = state.draft.validate(state.environment?.capabilities ?: NavigationCapabilities())
        if (error != null) { commandFailed(error); return }
        update(state.copy(pendingCommand = PendingNavigationCommand.PLAN, preparingRoute = false, notice = ""))
        port.plan(state.draft, state.draftRevision)
    }
    fun start(simulation: Boolean) { if (state.canStart) {
        update(state.copy(pendingCommand = PendingNavigationCommand.START, notice = "", overview = false))
        port.start(simulation, state.draftRevision)
    } }
    fun exitGuidance() {
        if (!state.canExitGuidance) return
        pendingRoutePlan = false
        replacementDraft = null
        update(state.copy(pendingCommand = PendingNavigationCommand.EXIT_GUIDANCE, preparingRoute = false, notice = ""))
        port.exitGuidance()
    }
    fun stop() {
        pendingRoutePlan = false; replacementDraft = null
        update(state.copy(selectingWaypoint = false, overview = false, preparingRoute = false, pendingCommand = PendingNavigationCommand.NONE,
            shell = state.shell.copy(input = state.shell.input.copy(submitting = false, analyzing = false)),
            candidateGroup = null, candidateQuestion = "", notice = ""))
        port.stop()
    }
    fun chooseCandidate(candidate: Candidate) {
        val group = state.candidateGroup ?: return
        if (state.busy) return
        if (candidate.id.isBlank()) { message("候选地点标识缺失"); return }
        update(state.copy(candidateGroup = null, candidateQuestion = "", notice = "正在确认候选地点"))
        try {
            candidateSelectionHandler(group.id, candidate.id)
        } catch (error: Throwable) {
            setInputError(error.message ?: "候选地点确认失败")
            update(state.copy(candidateGroup = group, notice = error.message ?: "候选地点确认失败"))
        }
    }
    fun setOverview(enabled: Boolean) {
        if (state.runtime.route == null || state.busy) return
        if (enabled) port.overview() else port.follow()
        update(state.copy(overview = enabled))
    }
    fun acceptResponse(response: NavigationResponse) {
        when (val action = NavigationResponseCoordinator.resolve(response)) {
            is ResponseAction.ApplyDraft -> {
                if (state.runtime.phase == EnginePhase.DISPOSED) return
                val error = action.draft.validate(state.environment?.capabilities ?: NavigationCapabilities())
                if (error != null) {
                    message(error)
                } else {
                    // The native route engine always starts from the newest accepted device
                    // fix. Keep the server's destination/waypoints, but do not retain a
                    // stale explicit origin in the editable draft.
                    val draft = action.draft.copy(origin = null)
                    if (state.runtime.phase in setOf(EnginePhase.PLANNING, EnginePhase.STARTING, EnginePhase.NAVIGATING)) {
                        // Release the old SDK generation before accepting callbacks for the
                        // replacement. A stop snapshot may arrive synchronously or later.
                        replacementDraft = draft
                        update(state.copy(resolvingDestination = true, preparingRoute = true, notice = "目的地已确认，正在切换路线"))
                        port.stop()
                    } else {
                        changeDraft(draft, schedulePlan = true)
                        if (state.canOpen) open()
                    }
                }
            }
            is ResponseAction.Disambiguate -> update(state.copy(resolvingDestination = true, preparingRoute = false, candidateGroup = action.group,
                candidateQuestion = action.question.ifBlank { action.group.hint },
                candidateTravelMode = (response.partialTask ?: response.task)?.travelMode, notice = ""))
            is ResponseAction.ShowMessage -> {
                pendingRoutePlan = false
                update(state.copy(preparingRoute = false, candidateGroup = null, candidateQuestion = "", candidateTravelMode = null, notice = action.text))
            }
        }
    }
    private fun changeDraft(draft: NavigationDraft, schedulePlan: Boolean = false, confirmsDestination: Boolean = true) {
        // Publish the new revision before native callbacks can schedule it. Invalidate
        // the old route in shared state too, so a queued UI action cannot start it.
        val unresolved = state.resolvingDestination && !confirmsDestination
        pendingRoutePlan = schedulePlan && !unresolved
        updatingDraft = true
        try {
            update(state.copy(draft = draft, draftRevision = state.draftRevision + 1, resolvingDestination = unresolved, preparingRoute = pendingRoutePlan,
                runtime = state.runtime.copy(route = null, routeRevision = -1),
                shell = state.shell.copy(input = state.shell.input.copy(
                    text = if (confirmsDestination) draft.destination?.name.orEmpty() else state.shell.input.text,
                    submitting = false, analyzing = false)),
                selectingWaypoint = false, candidateGroup = null, candidateQuestion = "", candidateTravelMode = null,
                notice = if (unresolved) "目的地尚未确认，请补充地点或选择候选"
                    else if (schedulePlan) "地点已确认，取得当前位置后自动规划路线" else "地点已更新，请重新规划路线"))
            port.clearRoute()
        } finally {
            updatingDraft = false
        }
        advancePendingWork()
    }
    private fun update(next: NavigationUiState) { state = next; observer(next) }
}
