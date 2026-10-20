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

    fun validate(capabilities: NavigationCapabilities): String? = when {
        destination == null || !destination.point.isValid() -> "请先选择有效目的地"
        travelMode !in capabilities.travelModes -> "当前平台尚未接入此出行方式"
        waypoints.size > capabilities.maxWaypoints -> "最多支持 ${capabilities.maxWaypoints} 个途经点，请减少地点后重试"
        waypoints.any { !it.point.isValid() || it.name.isBlank() } -> "途经点名称或坐标无效"
        (waypoints + destination).zipWithNext().any { (first, second) -> samePoint(first.point, second.point) } ->
            "相邻地点重复，请通过文字或语音说明需要调整的行程"
        else -> null
    }

    private fun samePoint(first: GeoPoint, second: GeoPoint) =
        abs(first.latitude - second.latitude) < 1e-6 && abs(first.longitude - second.longitude) < 1e-6
}
