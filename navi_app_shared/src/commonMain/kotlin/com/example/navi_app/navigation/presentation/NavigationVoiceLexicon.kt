package com.example.navi_app.navigation.presentation

import com.example.navi_app.navigation.protocol.NavigationResponse

/** Uses structured place names, never chat prose or guessed city names, as ASR hints. */
class NavigationVoiceLexicon {
    private var recentPlaces: List<String> = emptyList()

    fun remember(response: NavigationResponse) {
        if (response.isError()) return
        val confirmed = (response.task ?: response.partialTask)?.stops.orEmpty()
            .filter { !it.needsConfirmation && it.point?.isValid() == true }
            .map { it.name }
        recentPlaces = normalize(confirmed + recentPlaces).take(64)
    }

    fun words(state: NavigationUiState): List<String> = normalize(
        state.draft.waypoints.map { it.name } + listOfNotNull(state.draft.destination?.name,
            state.draft.origin?.name) + state.candidateTask?.stops.orEmpty().map { it.name } +
            state.candidateGroups.flatMap { group -> group.candidates.map { it.name } } + recentPlaces
    )

    fun clear() { recentPlaces = emptyList() }

    private fun normalize(names: List<String>): List<String> = names.asSequence()
        .map { it.trim() }
        .filter { it.length in 2..20 && it !in genericNames }
        .distinct().take(200).toList()

    private val genericNames = setOf("当前位置", "我的位置", "地图地点", "起点", "终点", "目的地")
}
