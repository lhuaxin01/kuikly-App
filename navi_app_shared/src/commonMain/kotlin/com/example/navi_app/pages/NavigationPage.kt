package com.example.navi_app.pages

import com.example.navi_app.bridge.NavigationAgentModule
import com.example.navi_app.bridge.NavigationModule
import com.example.navi_app.bridge.NavigationVoiceModule
import com.example.navi_app.navigation.agent.NavigationAgentRepository
import com.example.navi_app.components.NavigationMap
import com.example.navi_app.navigation.model.*
import com.example.navi_app.navigation.presentation.*
import com.example.navi_app.navigation.voice.*
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import kotlin.math.ceil
import kotlin.math.roundToInt

@Page("navigation", supportInLocal = true)
class NavigationPage : Pager() {
    private var state by observable(NavigationUiState())
    private var shell by observable(NavigationShellState())
    private var authPassword by observable("")
    private var mapDiagnosticsVisible by observable(false)
    private var voiceState by observable(VoiceInputState())
    private var navigationInput: InputView? = null
    private lateinit var store: NavigationStore
    private lateinit var sessions: SessionCoordinator
    private lateinit var agent: NavigationAgentRepository
    private lateinit var voice: VoiceInputCoordinator
    private val voiceLexicon = NavigationVoiceLexicon()
    private val navigation get() = acquireModule<NavigationModule>(NavigationModule.MODULE_NAME)
    private val agentModule get() = acquireModule<NavigationAgentModule>(NavigationAgentModule.MODULE_NAME)
    private val voiceModule get() = acquireModule<NavigationVoiceModule>(NavigationVoiceModule.MODULE_NAME)

    override fun createExternalModules(): Map<String, Module> = mapOf(
        NavigationModule.MODULE_NAME to NavigationModule(),
        NavigationAgentModule.MODULE_NAME to NavigationAgentModule(),
        NavigationVoiceModule.MODULE_NAME to NavigationVoiceModule()
    )

    override fun created() {
        super.created()
        store = NavigationStore(navigation) { next -> state = next; shell = next.shell }
        agent = NavigationAgentRepository(agentModule, { response ->
            store.acceptResponse(response)
            voiceLexicon.remember(response)
            val responseText = SessionPresentation.reply(response)
            val history = if (responseText.isNotBlank()) shell.session.history + NavigationMessage(
                id = response.requestId,
                role = "assistant",
                text = responseText
            ) else shell.session.history
            applyShell(shell.copy(
                input = shell.input.copy(submitting = false, analyzing = false, error = ""),
                session = shell.session.copy(
                    activeSessionId = response.sessionId.ifBlank { shell.session.activeSessionId },
                    history = history
                )
            ))
        }, { error ->
            applyShell(shell.copy(input = shell.input.copy(submitting = false, analyzing = false, error = error)))
            store.message(error)
        })
        sessions = SessionCoordinator(agentModule, { shell.session }, { next ->
            applyShell(shell.copy(session = next))
        }, { id ->
            agent.useSession(id)
            applyShell(shell.copy(input = shell.input.copy(submitting = false, analyzing = false)))
        }, {
            agent.resetSession()
            applyShell(shell.copy(auth = AuthState(error = "登录已失效，请重新登录"), session = SessionState()))
        })
        store.setCandidateSelectionHandler { groupId, candidateId ->
            agent.selectCandidate(groupId, candidateId, state.runtime.location,
                state.candidateTravelMode ?: state.draft.travelMode.wireValue)
        }
        if (pageData.isOhOs) {
            voice = VoiceInputCoordinator(
                port = voiceModule,
                readInput = { shell.input.text },
                onRecognized = { text -> submitInput(text) },
                readHotwords = { voiceLexicon.words(state) },
                publish = { next ->
                    val finished = voiceState.active && !next.active
                    voiceState = next
                    applyShell(shell.copy(input = shell.input.copy(text = next.text, voiceActive = next.active,
                        micListening = next.recording, error = next.error)))
                    if (finished) navigationInput?.setText(next.text)
                }
            )
            navigation.subscribe(store::runtime, store::selectPoint, store::configure, store::commandFailed)
            store.activateMap()
            agentModule.restoreAuth { result ->
                if (result.optBoolean("ok", false) && result.optBoolean("logged_in", false)) {
                    applyShell(shell.copy(auth = AccountPresentation.profile(result).copy(loading = false),
                        session = shell.session.copy(activeSessionId = result.optString("bound_session_id"))))
                    loadSessions()
                } else if (result.optBoolean("ok", false)) {
                    applyShell(shell.copy(auth = AuthState(loading = false)))
                } else {
                    applyShell(shell.copy(auth = shell.auth.copy(loggedIn = false, loading = false, error = AccountPresentation.error(result))))
                }
            }
        } else {
            store.message("此导航版本已接入鸿蒙；当前平台的地图适配尚未接入")
        }
    }

    override fun pageWillDestroy() {
        if (::voice.isInitialized) voice.destroy()
        voiceLexicon.clear()
        navigationInput = null
        sessions.invalidate()
        if (pageData.isOhOs) navigation.unsubscribe()
        agent.closeSession()
        super.pageWillDestroy()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); positionRelative(); backgroundColor(NavigationDesignTokens.pageBackground) }
            // Keep one native map mounted while switching between home and account.
            if (ctx.pageData.isOhOs) {
                NavigationMap { attr { positionAbsolute(); absolutePositionAllZero(); zIndex(0, false); backgroundColor(NavigationDesignTokens.mapPlaceholder) } }
            }
            vif({ ctx.shell.tab == NavigationTab.HOME }) { ctx.homeOverlay(this) }
            vif({ ctx.shell.tab == NavigationTab.MINE }) { ctx.mineOverlay(this) }
            ctx.bottomTabs(this)
            vif({ ctx.state.busy }) { ctx.inputProgress(this) }
        }
    }

    /** MapTemplate's FrameLayout equivalent: native map is the background and every control is an overlay. */
    private fun homeOverlay(parent: ViewContainer<*, *>) {
        val ctx = this
        with(parent) {
            View {
                attr {
                    // MapTemplate places the voice/search bar above the map's bottom
                    // safe area. Keep it above the Kuikly bottom navigation shell.
                    positionAbsolute(); absolutePosition(bottom = 86f, left = 16f, right = 16f); zIndex(20, false)
                    height(64f); flexDirectionRow(); alignItemsCenter(); padding(6f)
                    backgroundColor(NavigationDesignTokens.searchPanel); borderRadius(18f)
                    // Keep the text field mounted while the compact progress row
                    // uses its space, preserving the draft across async phases.
                    visibility(!ctx.state.busy)
                }
                ctx.voiceButton(this)
                View {
                    attr {
                        flex(1f); height(48f); marginLeft(8f); positionRelative()
                        backgroundColor(NavigationDesignTokens.searchInput); borderRadius(8f)
                    }
                    Input {
                        ctx.navigationInput = this
                        attr {
                            positionAbsolute(); absolutePositionAllZero(); text(ctx.shell.input.text)
                            placeholder("输入目的地或导航问题"); placeholderColor(Color(0xCCFFFFFF))
                            backgroundColor(NavigationDesignTokens.searchInput); color(Color.WHITE); fontSize(15f); returnKeyTypeSend()
                            editable(ctx.state.canSendInput)
                        }
                        event {
                            textDidChange(true) { params -> ctx.updateInput(params.text) }
                            inputReturn { params -> ctx.submitInput(params.text) }
                        }
                    }
                }
                ctx.compactAction(this, "发送", { ctx.shell.input.text.isNotBlank() && ctx.state.canSendInput }) {
                    ctx.submitInput()
                }
            }
            with(parent) {
                vif({ ctx.state.candidateGroup != null }) { vbind({ ctx.state.candidateGroup }) {
                    val group = ctx.state.candidateGroup ?: return@vbind
                    View {
                        attr {
                            positionAbsolute(); absolutePosition(top = 116f, left = 12f, right = 12f); zIndex(18, false)
                            padding(10f); backgroundColor(NavigationDesignTokens.surface); borderRadius(NavigationDesignTokens.cardRadius)
                        }
                        Text { attr { text(ctx.state.candidateQuestion.ifBlank { group.hint.ifBlank { "请选择地点" } }); fontSize(NavigationDesignTokens.bodySize); color(NavigationDesignTokens.title); lines(2) } }
                        Text { attr { text("待确认 ${ctx.state.candidateGroups.count { it.candidates.isNotEmpty() }} 个地点，按行程顺序选择"); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(4f); lines(1) } }
                        vif({ ctx.state.candidateTask?.stops?.any { !it.needsConfirmation && it.point?.isValid() == true && it.role != "origin" } == true }) {
                            Text { attr {
                                text("已确认：" + ctx.state.candidateTask?.stops.orEmpty()
                                    .filter { !it.needsConfirmation && it.point?.isValid() == true && it.role != "origin" }
                                    .joinToString(" → ") { it.name })
                                fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(4f); lines(2)
                            } }
                        }
                        Scroller {
                            attr { height(minOf(group.candidates.size * 76f, minOf(260f, maxOf(100f, ctx.pageData.pageViewHeight - 420f)))); marginTop(4f) }
                        group.candidates.forEach { candidate ->
                            View {
                                attr { marginTop(6f); padding(NavigationDesignTokens.compactPadding); borderRadius(NavigationDesignTokens.compactRadius); backgroundColor(NavigationDesignTokens.diagnosticSurface); opacity(if (ctx.state.busy || ctx.voiceState.active) 0.5f else 1f) }
                                Text { attr { text(candidate.name.ifBlank { "未命名地点" }); fontSize(NavigationDesignTokens.bodySize); fontWeightMedium(); color(NavigationDesignTokens.title); lines(1) } }
                                Text { attr { text(candidate.address); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); marginTop(2f); lines(2) } }
                                event { click { ctx.store.chooseCandidate(candidate, group.id) } }
                            }
                        }
                        }
                    }
                } }
            }
            vif({ !ctx.shell.sessionOverlayVisible }) {
                View {
                    attr { positionAbsolute(); absolutePosition(top = 18f, right = 16f); width(72f); height(44f); zIndex(25, false); allCenter(); backgroundColor(NavigationDesignTokens.navigationBar); borderRadius(22f); opacity(if (ctx.state.busy || ctx.voiceState.active) 0.5f else 1f); touchEnable(!ctx.state.busy && !ctx.voiceState.active) }
                    Text { attr { text("会话"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
                    event { click { ctx.openSessionOverlay() } }
                }
            }
            vif({ ctx.shell.sessionOverlayVisible }) { ctx.sessionOverlay(this) }
            with(parent) {
                // Keep the actionable panel mounted after the map becomes usable.
                // Loading the native map itself must not create a standalone status card.
                vif({ ctx.state.canOpen || ctx.state.canLocate || ctx.state.runtime.phase == EnginePhase.LOCATING ||
                    ctx.state.runtime.phase == EnginePhase.PLANNING || ctx.state.runtime.phase == EnginePhase.STARTING || ctx.state.runtime.route != null ||
                    ctx.state.runtime.progress != null || ctx.state.runtime.warning.isNotBlank() ||
                    ctx.state.notice.isNotBlank() }) {
                    View {
                        attr { positionAbsolute(); absolutePosition(bottom = 158f, left = 12f, right = 12f); zIndex(15, false); padding(12f); backgroundColor(NavigationDesignTokens.navigationSheet); borderRadius(16f) }
                        vif({ ctx.state.mode != MapMode.GUIDANCE }) {
                            View {
                                attr { height(38f); flexDirectionRow(); alignItemsCenter() }
                                ctx.modeChip(this, "驾车", TravelMode.DRIVE, { ctx.state.draft.travelMode == TravelMode.DRIVE })
                                ctx.modeChip(this, "步行", TravelMode.WALK, { ctx.state.draft.travelMode == TravelMode.WALK })
                                ctx.modeChip(this, "骑行", TravelMode.RIDE, { ctx.state.draft.travelMode == TravelMode.RIDE })
                            }
                            vif({ !ctx.state.busy }) { Text {
                                attr {
                                    text(ctx.state.runtime.message); fontSize(12f); lines(0); marginTop(4f)
                                    color(if (ctx.state.runtime.phase == EnginePhase.ERROR) NavigationDesignTokens.warning else NavigationDesignTokens.secondaryText)
                                }
                            } }
                        }
                        vif({ ctx.state.mode == MapMode.GUIDANCE && ctx.state.runtime.progress == null }) {
                            Text { attr { text(if (ctx.state.runtime.simulation) "导航演示中，等待进度…" else "实时导航中，等待进度…"); fontSize(14f); color(NavigationDesignTokens.primary) } }
                        }
                        vif({ ctx.state.runtime.location != null && ctx.state.mode != MapMode.GUIDANCE }) {
                            Text { attr { text(ctx.state.runtime.location?.let { "当前位置：${ctx.coordinate(it.latitude)}, ${ctx.coordinate(it.longitude)}" } ?: ""); fontSize(11f); color(NavigationDesignTokens.secondaryText); marginTop(4f); lines(1) } }
                        }
                        vif({ ctx.state.draftRevision > 0 && ctx.state.draft.destination != null }) {
                            Text { attr { text("目的地：${ctx.state.draft.destination?.name.orEmpty()}"); fontSize(14f); color(NavigationDesignTokens.title); marginTop(4f); lines(2) } }
                        }
                        vif({ ctx.state.mode != MapMode.GUIDANCE && ctx.state.draft.waypoints.isNotEmpty() }) { ctx.agentWaypoints(this) }
                        vif({ ctx.state.mode == MapMode.GUIDANCE && ctx.state.draft.waypoints.isNotEmpty() }) {
                            Text { attr { text("途经顺序：" + ctx.state.draft.waypoints.joinToString(" → ") { it.name }); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(4f); lines(2) } }
                        }
                        vif({ !ctx.state.busy && (ctx.state.notice.isNotBlank() || ctx.state.runtime.warning.isNotBlank()) }) {
                            Text { attr { text(ctx.state.notice.ifBlank { ctx.state.runtime.warning }); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.warning); marginTop(4f); lines(0) } }
                        }
                        vif({ ctx.state.runtime.route != null && ctx.state.mode != MapMode.GUIDANCE }) {
                            Text { attr { text(ctx.state.runtime.route?.let { "${ctx.distance(it.distanceMeters)} · 约 ${ceil(it.durationSeconds / 60).toInt()} 分钟" } ?: ""); fontSize(NavigationDesignTokens.cardTitleSize); fontWeightMedium(); marginTop(5f) } }
                        }
                        vif({ ctx.state.runtime.progress != null }) {
                            Text { attr { text(ctx.state.runtime.progress?.let { "${if (ctx.state.runtime.simulation) "模拟 · " else ""}剩余 ${ctx.distance(it.remainingMeters)} · ${ceil(it.remainingSeconds / 60).toInt()} 分钟" } ?: ""); fontSize(18f); fontWeightBold(); color(NavigationDesignTokens.primary); marginTop(5f) } }
                            Text { attr { text(ctx.state.runtime.progress?.let {
                                "${it.currentRoad.ifBlank { "当前道路" }} · 距下一导航点 ${ctx.distance(it.nextTurnMeters)}" +
                                    if (it.nextRoad.isNotBlank()) "\n下一路段：${it.nextRoad}" else ""
                            } ?: ""); fontSize(14f); color(NavigationDesignTokens.title); marginTop(5f); lines(2) } }
                        }
                        vif({ ctx.state.runtime.phase == EnginePhase.ERROR }) {
                            View { attr { flexDirectionRow(); marginTop(6f) }; ctx.action(this, "重试地图", { ctx.state.environment != null && !ctx.state.busy && !ctx.voiceState.active }) { ctx.store.open() } }
                        }
                        vif({ ctx.state.mode == MapMode.GUIDANCE }) {
                            View { attr { flexDirectionRow(); marginTop(6f) }
                                ctx.action(this, if (ctx.state.overview) "跟随当前位置" else "路线全览", { !ctx.state.busy }) { ctx.store.setOverview(!ctx.state.overview) }
                                ctx.action(this, "退出导航", { ctx.state.canExitGuidance }) { ctx.cancelVoice(); ctx.store.exitGuidance() }
                            }
                        }
                        vif({ ctx.state.mode != MapMode.GUIDANCE && ctx.state.runtime.phase != EnginePhase.IDLE && ctx.state.runtime.phase != EnginePhase.ERROR }) {
                            View { attr { flexDirectionRow(); marginTop(6f) }; ctx.action(this, "当前位置", { ctx.state.canLocate }) { ctx.store.locate() }; ctx.action(this, "规划路线", { ctx.state.canPlan }) { ctx.store.plan() }; ctx.action(this, "停止", { !ctx.state.busy }) { ctx.stopNavigation() } }
                        }
                        vif({ ctx.state.canStart }) {
                            View { attr { flexDirectionRow(); marginTop(5f) }; ctx.action(this, "开始导航", { true }) { ctx.store.start(false) }; ctx.action(this, "模拟导航", { true }) { ctx.store.start(true) } }
                        }
                    }
                }
            }
            vif({ ctx.shell.input.error.isNotBlank() }) {
                Text { attr { positionAbsolute(); absolutePosition(top = 118f, left = 18f, right = 18f); height(28f); zIndex(21, false); text(ctx.shell.input.error); fontSize(11f); color(NavigationDesignTokens.warning); lines(2) } }
            }
        }
    }

    private fun voiceButton(parent: ViewContainer<*, *>) {
        val ctx = this
        parent.View {
            attr {
                width(52f); height(52f); allCenter(); borderRadius(26f)
                backgroundColor(if (ctx.voiceState.recording) Color(0xFFB83A3A) else NavigationDesignTokens.primary)
                opacity(if (ctx.state.busy || ctx.voiceState.phase == VoiceInputPhase.STARTING) 0.5f else 1f)
                touchEnable(!ctx.state.busy && ctx.voiceState.phase != VoiceInputPhase.STARTING)
            }
            Text { attr { text(when {
                ctx.voiceState.phase == VoiceInputPhase.STARTING -> "准备中"
                ctx.voiceState.transcribing -> "取消"
                ctx.voiceState.recording -> "结束"
                else -> "语音"
            }); fontSize(12f); color(Color.WHITE) } }
            event { click {
                if (!ctx.state.busy && ctx.voiceState.phase != VoiceInputPhase.STARTING) {
                    if (!ctx.pageData.isOhOs) ctx.store.setInputError("当前平台暂未接入语音识别")
                    else if (ctx.voiceState.transcribing) ctx.voice.cancel()
                    else {
                        if (!ctx.voiceState.active) ctx.navigationInput?.blur()
                        ctx.voice.toggle()
                    }
                }
            } }
        }
        // Show the recording state even when partial text has filled the field.
        with(parent) { vif({ ctx.voiceState.active }) {
            View {
                attr { positionAbsolute(); absolutePosition(bottom = 66f, left = 0f, right = 0f); padding(8f); borderRadius(8f); backgroundColor(NavigationDesignTokens.searchPanel) }
                Text { attr { text(ctx.voiceState.statusText); fontSize(12f); color(Color.WHITE); lines(1) } }
                vif({ ctx.voiceState.text.isNotBlank() }) {
                    Text { attr { text(ctx.voiceState.text.takeLast(64)); fontSize(12f); color(Color(0xCCFFFFFF)); lines(2); marginTop(4f) } }
                }
            }
        } }
    }

    /** The Agent owns the stop list; changes go through the same text/voice request. */
    private fun agentWaypoints(parent: ViewContainer<*, *>) {
        val ctx = this
        with(parent) {
            Text { attr { text("途经顺序 · ${ctx.state.draft.waypoints.size} 站"); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(6f) } }
            Scroller {
                attr { height(minOf(96f, ctx.state.draft.waypoints.size * 32f)); marginTop(4f) }
                vbind({ ctx.state.draft.waypoints }) {
                    ctx.state.draft.waypoints.forEachIndexed { index, place ->
                        Text { attr { height(32f); text("${index + 1}. ${place.name}"); fontSize(13f); color(NavigationDesignTokens.title); lines(1) } }
                    }
                }
            }
        }
    }

    /** Loading stays in the input area. Store guards protect the task while the
     * user can still pan/zoom the map; cancellation remains reachable. */
    private fun inputProgress(parent: ViewContainer<*, *>) {
        val ctx = this
        parent.View {
            attr {
                positionAbsolute(); absolutePosition(bottom = 86f, left = 16f, right = 16f); zIndex(35, false)
                height(64f); flexDirectionRow(); alignItemsCenter(); paddingLeft(16f); paddingRight(6f)
                backgroundColor(NavigationDesignTokens.searchPanel); borderRadius(18f)
            }
            ActivityIndicator { attr { size(18f, 18f); isGrayStyle(false) } }
            View {
                attr { flex(1f); marginLeft(10f); justifyContentCenter() }
                vif({ ctx.shell.input.text.isNotBlank() }) {
                    Text { attr { text(ctx.shell.input.text); fontSize(14f); color(Color.WHITE); lines(1) } }
                }
                Text { attr { text(ctx.state.busyMessage); fontSize(12f); color(Color(0xCCFFFFFF)); lines(1) } }
            }
            View {
                attr { width(56f); height(48f); marginLeft(4f); allCenter(); borderRadius(8f); backgroundColor(NavigationDesignTokens.searchInput) }
                Text { attr { text("取消"); fontSize(14f); color(Color.WHITE) } }
                event { click { ctx.stopNavigation() } }
            }
        }
    }

    /** MapTemplate's top session history sheet. It deliberately sits above the
     * native map but below the search/session controls so map touches cannot leak
     * through the modal surface. */
    private fun sessionOverlay(parent: ViewContainer<*, *>) {
        val ctx = this
        parent.View {
            attr {
                positionAbsolute(); absolutePosition(top = 16f, left = 12f, right = 12f); height(minOf(430f, maxOf(220f, ctx.pageData.pageViewHeight - 180f)))
                zIndex(24, false); padding(14f); backgroundColor(NavigationDesignTokens.surface)
                borderRadius(NavigationDesignTokens.cardRadius)
            }
            View {
                attr { height(40f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text(if (ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST) "切换会话"
                        else ctx.shell.session.activeSessionTitle.ifBlank { "会话历史" })
                        flex(1f); fontSize(18f); fontWeightBold(); color(NavigationDesignTokens.title); lines(1)
                    }
                }
                ctx.overlayAction(this,
                    if (ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST) "内容" else "切换",
                    { !ctx.state.busy && !ctx.voiceState.active && !ctx.shell.session.loading && !ctx.shell.session.historyLoading }) {
                    ctx.applyShell(ctx.shell.copy(sessionOverlayMode =
                        if (ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST) SessionOverlayMode.HISTORY else SessionOverlayMode.LIST))
                    if (ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST) ctx.loadSessions()
                }
                ctx.overlayAction(this, "刷新", { !ctx.state.busy && !ctx.voiceState.active && !ctx.shell.session.loading && !ctx.shell.session.historyLoading }) { ctx.refreshSessionOverlay() }
                ctx.overlayAction(this, "×", { true }) { ctx.applyShell(ctx.shell.copy(sessionOverlayVisible = false)) }
            }
            vif({ !ctx.shell.auth.loggedIn }) {
                Text { attr { marginTop(24f); text("登录后可查看和恢复 navi-claw 会话"); fontSize(NavigationDesignTokens.bodySize); color(NavigationDesignTokens.secondaryText); lines(2) } }
                ctx.action(this, "去登录", { !ctx.state.busy }) {
                    ctx.selectTab(NavigationTab.MINE)
                    ctx.applyShell(ctx.shell.copy(sessionOverlayVisible = false))
                }
            }
            vif({ ctx.shell.auth.loggedIn }) {
                Text {
                    attr {
                        marginTop(8f)
                        text(when {
                            ctx.shell.session.loading -> "正在加载会话..."
                            ctx.shell.session.historyLoading -> "正在加载对话内容..."
                            ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST ->
                                if (ctx.shell.session.items.isEmpty()) "暂无会话，先在我的页面创建或开始一次导航对话"
                                else "选择一个会话查看内容"
                            ctx.shell.session.activeSessionTitle.isNotBlank() -> "正在查看：${ctx.shell.session.activeSessionTitle}"
                            else -> "点击切换选择一个会话"
                        })
                        fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); lines(2)
                    }
                }
                vif({ ctx.shell.session.error.isNotBlank() }) { Text { attr { text(ctx.shell.session.error); fontSize(12f); color(NavigationDesignTokens.warning); marginTop(6f); lines(0) } } }
                vif({ ctx.shell.sessionOverlayMode == SessionOverlayMode.LIST }) {
                    Scroller {
                        attr { flex(1f); marginTop(10f) }
                        vbind({ ctx.shell.session.items }) {
                            ctx.shell.session.items.forEach { item ->
                                View {
                                    attr { marginBottom(8f); padding(12f); backgroundColor(if (item.id == ctx.shell.session.activeSessionId) NavigationDesignTokens.diagnosticSurface else NavigationDesignTokens.pageBackground); borderRadius(12f) }
                                    Text { attr { text(item.title.ifBlank { "未命名会话" }); fontSize(17f); fontWeightBold(); color(NavigationDesignTokens.title); lines(1) } }
                                    Text { attr { text("点击查看对话内容，并在首页继续此会话"); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(6f); lines(2) } }
                                    event { click { if (!ctx.state.busy && !ctx.voiceState.active) { ctx.selectSession(item); ctx.applyShell(ctx.shell.copy(sessionOverlayMode = SessionOverlayMode.HISTORY)) } } }
                                }
                            }
                        }
                    }
                }
                vif({ ctx.shell.sessionOverlayMode == SessionOverlayMode.HISTORY }) {
                    Scroller {
                        attr { flex(1f); marginTop(10f) }
                        ctx.historyMessages(this)
                    }
                }
            }
        }
    }

    /** MapTemplate uses right-aligned user bubbles and left-aligned assistant bubbles. */
    private fun historyMessages(parent: ViewContainer<*, *>) {
        val ctx = this
        with(parent) {
            vif({ ctx.shell.session.history.isEmpty() && !ctx.shell.session.historyLoading }) {
                Text { attr { text(if (ctx.shell.session.activeSessionId.isBlank()) "请选择会话查看对话内容" else "这个会话还没有对话内容"); fontSize(14f); color(NavigationDesignTokens.secondaryText); marginTop(24f); lines(0) } }
            }
            vbind({ ctx.shell.session.history }) {
                ctx.shell.session.history.forEach { item ->
                    View {
                        attr {
                            marginTop(4f); marginBottom(4f); padding(10f); borderRadius(12f)
                            maxWidth(ctx.pageData.pageViewWidth * 0.72f)
                            if (item.role == "user") alignSelfFlexEnd() else alignSelfFlexStart()
                            backgroundColor(if (item.role == "user") Color(0xFF2563EB) else Color(0xFFF8FAFC))
                        }
                        Text { attr { text(item.text); fontSize(14f); lines(0); color(if (item.role == "user") Color.WHITE else Color(0xFF0F172A)) } }
                    }
                }
            }
        }
    }

    private fun mineOverlay(parent: ViewContainer<*, *>) {
        val ctx = this
        parent.Scroller {
            attr { positionAbsolute(); absolutePosition(top = 0f, left = 0f, bottom = 88f, right = 0f); zIndex(12, false); padding(top = 18f, left = 18f, bottom = 18f, right = 18f); backgroundColor(NavigationDesignTokens.pageBackground) }
            View { attr { flexDirectionRow(); alignItemsCenter(); height(42f) }; Text { attr { text("我的"); fontSize(NavigationDesignTokens.titleSize); fontWeightBold(); color(NavigationDesignTokens.title); flex(1f) } }; Text { attr { text(if (ctx.shell.auth.loggedIn) "已登录" else "未登录"); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText) } } }
            vif({ !ctx.shell.auth.loggedIn }) {
                View { attr { marginTop(18f); padding(14f); backgroundColor(NavigationDesignTokens.surface); borderRadius(NavigationDesignTokens.cardRadius) }
                    Text { attr { text(AccountPresentation.title(ctx.shell.auth.registerMode)); fontSize(NavigationDesignTokens.cardTitleSize); fontWeightMedium(); color(NavigationDesignTokens.title) } }
                    Input { attr { marginTop(10f); height(42f); text(ctx.shell.auth.username); placeholder("用户名"); placeholderColor(NavigationDesignTokens.secondaryText); backgroundColor(NavigationDesignTokens.diagnosticSurface); color(NavigationDesignTokens.title) }; event { textDidChange(true) { p -> ctx.applyShell(ctx.shell.copy(auth = ctx.shell.auth.copy(username = p.text, error = ""))) } } }
                    Input { attr { marginTop(8f); height(42f); text(ctx.authPassword); placeholder("密码"); placeholderColor(NavigationDesignTokens.secondaryText); keyboardTypePassword(); backgroundColor(NavigationDesignTokens.diagnosticSurface); color(NavigationDesignTokens.title) }; event { textDidChange(true) { p -> ctx.authPassword = p.text; ctx.applyShell(ctx.shell.copy(auth = ctx.shell.auth.copy(error = ""))) } } }
                    vif({ ctx.shell.auth.registerMode }) { Input { attr { marginTop(8f); height(42f); placeholder("显示名称（可选）"); backgroundColor(NavigationDesignTokens.diagnosticSurface); color(NavigationDesignTokens.title) }; event { textDidChange(true) { p -> ctx.applyShell(ctx.shell.copy(auth = ctx.shell.auth.copy(displayName = p.text, error = ""))) } } } }
                    View { attr { flexDirectionRow(); marginTop(10f) }; ctx.action(this, AccountPresentation.primaryLabel(ctx.shell.auth.registerMode), { !ctx.shell.auth.loading && !ctx.state.busy }) { ctx.authenticate() }; ctx.action(this, AccountPresentation.switchLabel(ctx.shell.auth.registerMode), { !ctx.shell.auth.loading && !ctx.state.busy }) { ctx.applyShell(ctx.shell.copy(auth = ctx.shell.auth.copy(registerMode = !ctx.shell.auth.registerMode, error = ""))) } }
                    vif({ ctx.shell.auth.loading }) { Text { attr { text(if (ctx.shell.auth.registerMode) "正在注册并登录..." else "正在登录..."); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); marginTop(8f) } } }
                    vif({ ctx.shell.auth.error.isNotBlank() }) { Text { attr { text(ctx.shell.auth.error); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.warning); marginTop(8f); lines(2) } } }
                }
            }
            vif({ ctx.shell.auth.loggedIn }) {
                View {
                    attr { marginTop(18f); padding(14f); backgroundColor(NavigationDesignTokens.surface); borderRadius(NavigationDesignTokens.cardRadius) }
                    Text { attr { text(ctx.shell.auth.displayName.ifBlank { ctx.shell.auth.username }); fontSize(NavigationDesignTokens.cardTitleSize); fontWeightMedium(); color(NavigationDesignTokens.title) } }
                    Text { attr { text("账号：${ctx.shell.auth.username}"); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); marginTop(5f); lines(1) } }
                    if (ctx.shell.auth.userId.isNotBlank()) Text { attr { text("用户ID：${ctx.shell.auth.userId}"); fontSize(11f); color(NavigationDesignTokens.secondaryText); marginTop(3f); lines(1) } }
                    View { attr { height(1f); marginTop(12f); backgroundColor(NavigationDesignTokens.divider) } }
                    Text { attr { text("我的会话"); fontSize(NavigationDesignTokens.bodySize); fontWeightMedium(); color(NavigationDesignTokens.title); marginTop(12f) } }
                    Text {
                        attr {
                            text(if (ctx.shell.session.loading) "正在加载会话..." else
                                "当前共有 ${ctx.shell.session.items.size} 个会话，点击会话可查看内容并继续对话")
                            fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); marginTop(6f); lines(2)
                        }
                    }
                    Input {
                        attr {
                            marginTop(10f); height(42f); text(ctx.shell.session.newSessionTitle)
                            placeholder("新会话标题（可选）"); placeholderColor(NavigationDesignTokens.secondaryText)
                            backgroundColor(NavigationDesignTokens.diagnosticSurface); color(NavigationDesignTokens.title)
                        }
                        event { textDidChange(true) { p -> ctx.applyShell(ctx.shell.copy(session = ctx.shell.session.copy(newSessionTitle = p.text, error = ""))) } }
                    }
                    View { attr { flexDirectionRow(); marginTop(8f) }; ctx.action(this, "创建会话", { !ctx.shell.session.loading && !ctx.state.busy }) { ctx.createSession() }; ctx.action(this, "刷新列表", { !ctx.shell.session.loading && !ctx.state.busy }) { ctx.loadSessions() } }
                    Scroller {
                        attr { height(300f); marginTop(12f); padding(6f); backgroundColor(NavigationDesignTokens.pageBackground); borderRadius(12f) }
                        vbind({ ctx.shell.session.items }) {
                            ctx.shell.session.items.forEach { session ->
                                View {
                                    attr {
                                        marginTop(8f); padding(9f)
                                        backgroundColor(if (session.id == ctx.shell.session.activeSessionId) NavigationDesignTokens.diagnosticSurface else NavigationDesignTokens.pageBackground)
                                        borderRadius(NavigationDesignTokens.compactRadius)
                                    }
                                    Text { attr { text(session.title.ifBlank { "未命名会话" }); fontSize(NavigationDesignTokens.bodySize); color(NavigationDesignTokens.title); lines(1) } }
                                    Text { attr { text("点击查看对话内容，并在首页继续此会话"); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(6f); lines(2) } }
                                    event { click { ctx.selectSession(session) } }
                                }
                            }
                        }
                    }
                    vif({ ctx.shell.session.items.isEmpty() && !ctx.shell.session.loading }) {
                        Text { attr { text(ctx.shell.session.emptyMessage); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.secondaryText); marginTop(12f); lines(2) } }
                    }
                    Text { attr { text("当前会话内容：${ctx.shell.session.activeSessionTitle.ifBlank { "未选择会话" }}"); fontSize(16f); fontWeightBold(); color(NavigationDesignTokens.title); marginTop(16f); lines(2) } }
                    vif({ ctx.shell.session.historyLoading }) { Text { attr { text("正在加载对话内容..."); fontSize(12f); color(NavigationDesignTokens.secondaryText); marginTop(8f) } } }
                    Scroller {
                        attr { height(320f); marginTop(10f); padding(8f); backgroundColor(NavigationDesignTokens.pageBackground); borderRadius(12f) }
                        ctx.historyMessages(this)
                    }
                    vif({ ctx.shell.session.error.isNotBlank() }) { Text { attr { text(ctx.shell.session.error); fontSize(NavigationDesignTokens.secondarySize); color(NavigationDesignTokens.warning); marginTop(8f); lines(2) } } }
                    ctx.action(this, "退出登录", { !ctx.state.busy }) { ctx.logout() }
                }
            }
            vif({ ctx.state.environment != null && ctx.pageData.isOhOs }) {
                View {
                    attr { marginTop(14f); padding(14f); backgroundColor(NavigationDesignTokens.surface); borderRadius(NavigationDesignTokens.cardRadius) }
                    ctx.action(this, if (ctx.mapDiagnosticsVisible) "收起地图服务诊断" else "地图服务诊断", { true }) {
                        ctx.mapDiagnosticsVisible = !ctx.mapDiagnosticsVisible
                    }
                    vif({ ctx.mapDiagnosticsVisible }) {
                        Text { attr { text("配置来源：${ctx.state.environment?.configSource.orEmpty()}"); fontSize(12f); marginTop(8f); color(NavigationDesignTokens.secondaryText); lines(0) } }
                        Text { attr { text("鸿蒙 Key 标识：${ctx.state.environment?.keyHint.orEmpty()}（仅用于核对，未验证平台归属）"); fontSize(12f); marginTop(6f); color(NavigationDesignTokens.secondaryText); lines(0) } }
                        Text { attr { text("导航 SDK 集成版本：${ctx.state.environment?.naviSdkVersion.orEmpty()}"); fontSize(12f); marginTop(6f); color(NavigationDesignTokens.secondaryText); lines(0) } }
                        Text { attr { text("当前安装包 AppID：${ctx.state.environment?.appId.orEmpty()}"); fontSize(12f); marginTop(6f); color(NavigationDesignTokens.title); lines(0) } }
                        Text { attr { text("请与高德控制台的 HarmonyOS Key 绑定信息核对；真机与云真机应以各自运行包的 AppID 为准。"); fontSize(12f); marginTop(8f); color(NavigationDesignTokens.secondaryText); lines(0) } }
                    }
                }
            }
        }
    }

    private fun bottomTabs(parent: ViewContainer<*, *>) {
        val ctx = this
        parent.View { attr { positionAbsolute(); absolutePosition(bottom = 12f, left = 16f, right = 16f); height(64f); zIndex(30, false); flexDirectionRow(); padding(6f); backgroundColor(NavigationDesignTokens.navigationBar); borderRadius(32f) }
            ctx.tab(this, "首页", NavigationTab.HOME)
            ctx.tab(this, "我的", NavigationTab.MINE)
        }
    }

    private fun tab(parent: ViewContainer<*, *>, label: String, tab: NavigationTab) {
        val ctx = this
        parent.View {
            attr { flex(1f); height(52f); opacity(if (ctx.state.busy) 0.5f else 1f); touchEnable(!ctx.state.busy) }
            vif({ ctx.shell.tab == tab }) {
                View { attr { flex(1f); allCenter(); borderRadius(26f); backgroundColor(NavigationDesignTokens.navigationBarSelected) }; Text { attr { text(label); fontSize(15f); fontWeightBold(); color(NavigationDesignTokens.title) } }; event { click { ctx.selectTab(tab) } } }
            }
            vif({ ctx.shell.tab != tab }) {
                View { attr { flex(1f); allCenter(); borderRadius(26f); backgroundColor(NavigationDesignTokens.navigationBar) }; Text { attr { text(label); fontSize(15f); fontWeightBold(); color(Color(0xD9FFFFFF)) } }; event { click { ctx.selectTab(tab) } } }
            }
        }
    }

    private fun modeChip(parent: ViewContainer<*, *>, label: String, mode: TravelMode, selected: () -> Boolean) {
        val ctx = this
        parent.View {
            attr { paddingLeft(10f); paddingRight(10f); height(30f); marginRight(6f); opacity(if (ctx.state.canEdit) 1f else 0.5f); touchEnable(ctx.state.canEdit) }
            vif({ selected() }) { View { attr { flex(1f); allCenter(); borderRadius(15f); backgroundColor(NavigationDesignTokens.primary) }; Text { attr { text(label); fontSize(12f); color(Color.WHITE) } }; event { click { ctx.store.selectTravelMode(mode) } } } }
            vif({ !selected() }) { View { attr { flex(1f); allCenter(); borderRadius(15f); backgroundColor(NavigationDesignTokens.surface) }; Text { attr { text(label); fontSize(12f); color(NavigationDesignTokens.title) } }; event { click { ctx.store.selectTravelMode(mode) } } } }
        }
    }

    private fun compactAction(parent: ViewContainer<*, *>, label: String, enabled: () -> Boolean, handler: () -> Unit) {
        val ctx = this
        val isMic = label == "语音" || label == "停止"
        parent.View { attr { width(if (isMic) 52f else 56f); height(if (isMic) 52f else 48f); marginLeft(4f); allCenter(); borderRadius(if (isMic) 26f else 8f); backgroundColor(if (!enabled()) NavigationDesignTokens.disabledSurface else if (label == "发送") NavigationDesignTokens.primary else NavigationDesignTokens.diagnosticSurface); touchEnable(enabled()) }; Text { attr { text(label); fontSize(if (isMic) 11f else 14f); fontWeightMedium(); color(if (enabled()) Color.WHITE else NavigationDesignTokens.secondaryText) } }; event { click { if (enabled()) handler() } } }
    }

    /** Compact controls used by the MapTemplate session overlay header. */
    private fun overlayAction(parent: ViewContainer<*, *>, label: String, enabled: () -> Boolean, handler: () -> Unit) {
        parent.View {
            attr {
                width(if (label == "×") 34f else 52f); height(34f); marginLeft(5f); allCenter()
                borderRadius(8f); backgroundColor(if (enabled()) NavigationDesignTokens.diagnosticSurface else NavigationDesignTokens.disabledSurface)
                touchEnable(enabled())
            }
            Text { attr { text(label); fontSize(if (label == "×") 20f else 12f); fontWeightMedium(); color(if (enabled()) NavigationDesignTokens.primary else NavigationDesignTokens.secondaryText) } }
            event { click { if (enabled()) handler() } }
        }
    }

    private fun applyShell(next: NavigationShellState) {
        shell = next
        if (::store.isInitialized && store.state.shell != next) store.updateShell { next }
    }

    private fun updateInput(value: String) {
        if (!state.canSendInput) return
        if (voiceState.active) voice.editText(value)
        else applyShell(shell.copy(input = shell.input.copy(text = value, error = "")))
    }

    private fun cancelVoice() { if (::voice.isInitialized && voiceState.active) voice.cancel() }

    private fun selectTab(tab: NavigationTab) {
        if (state.busy) return
        if (shell.tab != tab) cancelVoice()
        applyShell(shell.copy(tab = tab))
    }

    private fun stopNavigation() {
        cancelVoice()
        agent.cancelPending()
        store.setInputSubmitting(false)
        store.stop()
    }

    private fun submitInput(value: String = shell.input.text) {
        if (!state.canSendInput) return
        // Unedited dictation waits for the final correction; an explicitly edited
        // draft can be sent immediately. The coordinator retires each attempt once.
        val text = if (voiceState.active) voice.takeForSubmit(value) ?: return else value.trim()
        if (text.isBlank() || !state.canSubmit) return
        if (!shell.auth.loggedIn) {
            store.setInputError("请先登录后使用导航智能体")
            return
        }
        if (shell.session.loading || shell.session.historyLoading) {
            store.message("正在加载或切换会话，请稍候再发送")
            return
        }
        val existingTask = store.taskForModification()
        store.beginDestinationRequest()
        applyShell(shell.copy(input = shell.input.copy(text = text, submitting = true, analyzing = true, error = ""), session = shell.session.copy(history = shell.session.history + NavigationMessage(role = "user", text = text))))
        try {
            agent.submitText(text, state.draft.travelMode.wireValue, state.runtime.location, existingTask)
        } catch (error: Throwable) {
            val message = error.message ?: "导航请求失败"
            applyShell(shell.copy(input = shell.input.copy(submitting = false, analyzing = false, error = message)))
            store.message(message)
        }
    }

    private fun authenticate() {
        if (state.busy || voiceState.active) return
        val username = shell.auth.username.trim()
        val password = authPassword
        val displayName = shell.auth.displayName.trim()
        val validationError = AccountPresentation.validate(username, password)
        if (validationError.isNotBlank()) {
            applyShell(shell.copy(auth = shell.auth.copy(error = validationError)))
            return
        }
        applyShell(shell.copy(auth = shell.auth.copy(loading = true, error = "")))
        agentModule.authenticate(username, password, shell.auth.registerMode, displayName) { result ->
            if (result.optBoolean("ok", false)) {
                sessions.invalidate()
                agent.resetSession()
                val profile = AccountPresentation.profile(result)
                applyShell(shell.copy(auth = profile.copy(
                    username = profile.username.ifBlank { username },
                    displayName = profile.displayName.ifBlank { displayName },
                    loading = false,
                    error = ""
                )))
                authPassword = ""
                loadSessions()
            }
            else applyShell(shell.copy(auth = shell.auth.copy(loading = false, error = AccountPresentation.error(result))))
        }
    }

    private fun loadSessions() = sessions.refreshList()

    private fun selectSession(item: NavigationSessionItem) {
        if (state.busy || voiceState.active) {
            applyShell(shell.copy(session = shell.session.copy(error = "请等待当前导航请求完成后再切换会话")))
            return
        }
        sessions.select(item)
    }

    private fun openSessionOverlay() {
        if (state.busy || voiceState.active) return
        applyShell(shell.copy(sessionOverlayVisible = true))
        if (!shell.auth.loggedIn || shell.input.submitting) return
        if (shell.session.activeSessionId.isBlank()) {
            applyShell(shell.copy(sessionOverlayMode = SessionOverlayMode.LIST))
            loadSessions()
        } else sessions.refreshHistory()
    }

    private fun refreshSessionOverlay() {
        if (!shell.auth.loggedIn || state.busy || voiceState.active) return
        if (shell.sessionOverlayMode == SessionOverlayMode.LIST || shell.session.activeSessionId.isBlank()) loadSessions()
        else sessions.refreshHistory()
    }

    private fun logout() {
        if (state.busy || voiceState.active) return
        voiceLexicon.clear()
        sessions.invalidate()
        agent.resetSession()
        applyShell(NavigationShellState(tab = NavigationTab.MINE))
        authPassword = ""
        agentModule.logout { result ->
            if (!result.optBoolean("ok", false)) {
                store.message(result.optString("message", "退出登录失败，请重试"))
            }
        }
    }
    private fun createSession() {
        if (state.busy || voiceState.active) {
            applyShell(shell.copy(session = shell.session.copy(error = "请等待当前导航请求完成后再创建会话")))
            return
        }
        sessions.create(shell.session.newSessionTitle.trim())
    }
    private fun action(parent: ViewContainer<*, *>, label: String, enabled: () -> Boolean, handler: () -> Unit) {
        parent.View {
            attr { flex(1f); height(NavigationDesignTokens.actionHeight); marginLeft(3f); marginRight(3f); borderRadius(NavigationDesignTokens.compactRadius); allCenter()
                backgroundColor(if (enabled()) NavigationDesignTokens.primary else NavigationDesignTokens.disabledSurface); touchEnable(enabled()) }
            Text { attr { text(label); fontSize(13f); color(if (enabled()) Color.WHITE else Color(0xFF8491A3)) } }
            event { click { if (enabled()) handler() } }
        }
    }

    private fun coordinate(value: Double): String = ((value * 100000).roundToInt() / 100000.0).toString()

    private fun distance(meters: Double): String = if (meters >= 1000) "${(meters / 100).roundToInt() / 10.0} 公里" else "${meters.roundToInt()} 米"
}
