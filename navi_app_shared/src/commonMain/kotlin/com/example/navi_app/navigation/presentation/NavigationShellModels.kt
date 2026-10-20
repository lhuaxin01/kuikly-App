package com.example.navi_app.navigation.presentation

/** The two top-level destinations exposed by the MapTemplate home activity. */
enum class NavigationTab {
    HOME,
    MINE
}

/** State owned by the destination/voice input bar. */
data class NavigationInputState(
    val text: String = "",
    val micListening: Boolean = false,
    val voiceActive: Boolean = false,
    val submitting: Boolean = false,
    val analyzing: Boolean = false,
    val error: String = ""
)

/** Public account state only. The native adapter owns the token and its persistence. */
data class AuthState(
    val loggedIn: Boolean = false,
    val registerMode: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val userId: String = "",
    val loading: Boolean = false,
    val error: String = ""
)

data class NavigationSessionItem(
    val id: String,
    val title: String,
    val updatedAt: String = "",
    val preview: String = "",
    val status: String = "",
    val createdAt: String = "",
    val lastMessageAt: String = ""
)

data class NavigationMessage(
    val id: String = "",
    val role: String,
    val text: String,
    val timestamp: String = ""
)

/** Session list and the currently selected conversation from navi-claw. */
data class SessionState(
    val activeSessionId: String = "",
    val activeSessionTitle: String = "",
    val activeSessionUpdatedAt: String = "",
    val items: List<NavigationSessionItem> = emptyList(),
    val history: List<NavigationMessage> = emptyList(),
    val newSessionTitle: String = "",
    val loading: Boolean = false,
    val historyLoading: Boolean = false,
    val error: String = "",
    val emptyMessage: String = "暂无会话"
)

/** The MapTemplate overlay has two explicit modes: current history and session switcher. */
enum class SessionOverlayMode {
    HISTORY,
    LIST
}

/** Shared shell state used by Kuikly and the Harmony transport adapter. */
data class NavigationShellState(
    val tab: NavigationTab = NavigationTab.HOME,
    val input: NavigationInputState = NavigationInputState(),
    val auth: AuthState = AuthState(),
    val session: SessionState = SessionState(),
    val sessionOverlayVisible: Boolean = false,
    val sessionOverlayMode: SessionOverlayMode = SessionOverlayMode.HISTORY
)
