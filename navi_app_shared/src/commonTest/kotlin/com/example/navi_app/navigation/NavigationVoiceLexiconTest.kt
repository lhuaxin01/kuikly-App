package com.example.navi_app.navigation

import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.GeoPoint
import com.example.navi_app.navigation.model.Place
import com.example.navi_app.navigation.presentation.NavigationUiState
import com.example.navi_app.navigation.presentation.NavigationVoiceLexicon
import com.example.navi_app.navigation.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationVoiceLexiconTest {
    private val point = GeoPoint(30.2, 120.1)

    @Test fun currentPlacesAndCandidatesHavePriorityAndOnlyValidNamesAreUsed() {
        val lexicon = NavigationVoiceLexicon()
        lexicon.remember(response("灵隐寺"))
        val names = listOf(" 杭州东站 ", "西湖", "西湖", "", "站", "甲".repeat(21), "当前位置")
        val state = NavigationUiState(
            draft = NavigationDraft(destination = Place("西湖", point)),
            candidateGroups = listOf(CandidateGroup("group", 0,
                names.mapIndexed { i, name -> Candidate("$i", name, "", point) }))
        )
        assertEquals(listOf("西湖", "杭州东站", "灵隐寺"), lexicon.words(state))
    }

    @Test fun historyUsesConfirmedStructuredPlacesAndCanBeCleared() {
        val lexicon = NavigationVoiceLexicon()
        lexicon.remember(response("杭州东站"))
        lexicon.remember(response("错误地点").copy(status = "error"))
        lexicon.remember(response("待确认地点").copy(task = NavigationTask(listOf(
            NavigationStop("destination", "待确认地点", point, needsConfirmation = true),
            NavigationStop("waypoint", "无坐标地点", null)
        ))))
        assertEquals(listOf("杭州东站"), lexicon.words(NavigationUiState()))
        lexicon.clear()
        assertTrue(lexicon.words(NavigationUiState()).isEmpty())
    }

    @Test fun hotwordCountIsBoundedWithoutTruncatingPlaceNames() {
        val lexicon = NavigationVoiceLexicon()
        repeat(80) { lexicon.remember(response("历史地点$it")) }
        assertEquals(64, lexicon.words(NavigationUiState()).size)
        assertFalse(lexicon.words(NavigationUiState()).contains("历史地点0"))
        val candidates = (0..250).map { Candidate("$it", "候选地点$it", "", point) }
        val words = lexicon.words(NavigationUiState(candidateGroups = listOf(CandidateGroup("g", 0, candidates))))
        assertEquals(200, words.size)
        assertEquals("候选地点0", words.first())
        assertEquals("候选地点199", words.last())
    }

    private fun response(name: String) = NavigationResponse("ok", "navigation_task", true, false,
        NavigationTask(listOf(NavigationStop("destination", name, point))))
}
