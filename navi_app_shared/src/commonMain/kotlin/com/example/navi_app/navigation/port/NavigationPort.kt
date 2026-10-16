package com.example.navi_app.navigation.port

import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.*

interface NavigationPort {
    fun open()
    fun locate()
    fun plan(draft: NavigationDraft, revision: Int)
    fun start(simulation: Boolean, revision: Int)
    fun stop()
    fun exitGuidance()
    fun clearRoute()
    fun overview()
    fun follow()
}

data class NavigationEnvironment(val defaultDestination: Place, val keyConfigured: Boolean, val appId: String,
    val capabilities: NavigationCapabilities = NavigationCapabilities(),
    val configSource: String = "", val keyHint: String = "", val naviSdkVersion: String = "")
