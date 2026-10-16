package com.example.navi_app.navigation.domain

import com.example.navi_app.navigation.model.*
import kotlin.math.abs

/** Ported from MapTemplate NavigationDraftManager; contains no SDK, UI, or lifecycle objects. */
data class NavigationDraft(
    val destination: Place? = null,
    val waypoints: List<Place> = emptyList(),
    val travelMode: TravelMode = TravelMode.DRIVE,
    val origin: Place? = null
) {
    fun withDestination(place: Place): NavigationDraft {
        require(place.name.isNotBlank() && place.point.isValid()) { "目的地名称或坐标无效" }
        return copy(destination = place)
    }

    fun withWaypoint(place: Place, limit: Int): NavigationDraft {
        require(place.name.isNotBlank() && place.point.isValid()) { "途经点名称或坐标无效" }
        require(waypoints.none { samePoint(it.point, place.point) }) { "该途经点已存在" }
        require(waypoints.size < limit) { "最多添加 $limit 个途经点" }
        return copy(waypoints = waypoints + place)
    }

    fun validate(capabilities: NavigationCapabilities): String? = when {
        destination == null || !destination.point.isValid() -> "请先选择有效目的地"
        travelMode !in capabilities.travelModes -> "当前平台尚未接入此出行方式"
        waypoints.size > capabilities.maxWaypoints -> "途经点数量超过当前平台支持范围"
        waypoints.any { !it.point.isValid() } -> "途经点坐标无效"
        else -> null
    }

    private fun samePoint(first: GeoPoint, second: GeoPoint) =
        abs(first.latitude - second.latitude) < 1e-6 && abs(first.longitude - second.longitude) < 1e-6
}
