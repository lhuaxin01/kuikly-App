package com.example.navi_app.navigation.model

/** Shared coordinates are GCJ-02. Missing numbers must never silently become zero. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class Place(val name: String, val point: GeoPoint)
enum class TravelMode(val wireValue: String) { DRIVE("drive"), WALK("walk"), RIDE("ride") }
enum class MapMode { MAP, ROUTE_PREVIEW, GUIDANCE }

enum class EnginePhase(val wireValue: String) {
    IDLE("idle"), INITIALIZING("initializing"), LOADING_MAP("loading_map"), MAP_READY("map_ready"),
    LOCATING("locating"), LOCATION_READY("location_ready"), PLANNING("planning"), ROUTE_READY("route_ready"),
    STARTING("starting"), NAVIGATING("navigating"), ARRIVED("arrived"), STOPPED("stopped"),
    ERROR("error"), DISPOSED("disposed");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } ?: ERROR
    }
}

data class RouteSummary(val id: Int, val distanceMeters: Double, val durationSeconds: Double)
data class NavigationProgress(val remainingMeters: Double, val remainingSeconds: Double,
    val nextTurnMeters: Double, val currentRoad: String, val nextRoad: String)
data class RuntimeSnapshot(
    val phase: EnginePhase = EnginePhase.IDLE,
    val message: String = "正在准备地图",
    val warning: String = "",
    val location: GeoPoint? = null,
    val route: RouteSummary? = null,
    val progress: NavigationProgress? = null,
    val progressCount: Int = 0,
    val simulation: Boolean = false,
    val routeRevision: Int = -1
)

data class NavigationCapabilities(val maxWaypoints: Int = DEFAULT_MAX_WAYPOINTS, val travelModes: Set<TravelMode> = setOf(TravelMode.DRIVE)) {
    companion object { const val DEFAULT_MAX_WAYPOINTS = 10 }
}
