package com.example.navi_app.bridge

import com.example.navi_app.navigation.domain.NavigationDraft
import com.example.navi_app.navigation.model.*
import com.example.navi_app.navigation.port.*
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

class NavigationModule : Module(), NavigationPort {
    override fun moduleName() = MODULE_NAME
    private var subscription: CallbackRef? = null
    private var errorHandler: (String) -> Unit = {}

    fun subscribe(onState: (RuntimeSnapshot) -> Unit, onPoint: (GeoPoint) -> Unit,
        onEnvironment: (NavigationEnvironment) -> Unit, onError: (String) -> Unit) {
        unsubscribe()
        errorHandler = onError
        subscription = toNative(true, "subscribe", null, { event ->
            if (event != null) {
                when (event.optString("type")) {
                    "state" -> onState(snapshot(event.optJSONObject("state") ?: JSONObject(), event.optInt("routeRevision", -1)))
                    "point" -> onPoint(GeoPoint(event.optDouble("latitude", Double.NaN), event.optDouble("longitude", Double.NaN)))
                    "environment" -> {
                        val point = event.optJSONObject("destination") ?: JSONObject()
                        val modes = event.optJSONArray("travelModes")
                            ?.let { values -> (0 until values.length()).mapNotNull { index ->
                                TravelMode.entries.firstOrNull { it.wireValue.equals(values.optString(index), true) }
                            }.toSet() }
                            ?.takeIf { it.isNotEmpty() }
                            ?: setOf(TravelMode.DRIVE)
                        onEnvironment(NavigationEnvironment(Place(point.optString("name"), GeoPoint(
                            point.optDouble("latitude", Double.NaN), point.optDouble("longitude", Double.NaN))),
                            event.optBoolean("keyConfigured"), event.optString("appId"),
                            NavigationCapabilities(event.optInt("maxWaypoints", NavigationCapabilities.DEFAULT_MAX_WAYPOINTS).coerceAtLeast(0), modes),
                            configSource = event.optString("configSource"), keyHint = event.optString("keyHint"),
                            naviSdkVersion = event.optString("naviSdkVersion")))
                    }
                    "error" -> onError(event.optString("message"))
                }
            }
        }, false).callbackRef
    }

    fun unsubscribe() {
        if (subscription != null) {
            toNative(false, "unsubscribe", null, null, false)
            subscription?.let { removeCallback(it) }
            subscription = null
        }
    }

    private fun command(name: String, payload: JSONObject? = null) {
        toNative(false, name, payload?.toString(), { result ->
            if (result?.optBoolean("ok") == false) errorHandler(result.optString("message", "操作失败"))
        }, false)
    }
    override fun open() = command("open")
    override fun locate() = command("locate")
    override fun plan(draft: NavigationDraft, revision: Int) {
        val destination = draft.destination ?: return
        val points = JSONArray()
        draft.waypoints.forEach { points.put(place(it)) }
        command("plan", JSONObject().put("destination", place(destination)).put("waypoints", points)
            .put("travelMode", draft.travelMode.wireValue).put("revision", revision))
    }
    override fun start(simulation: Boolean, revision: Int) = command("start",
        JSONObject().put("simulation", simulation).put("revision", revision))
    override fun stop() = command("stop")
    override fun exitGuidance() = command("exitGuidance")
    override fun clearRoute() = command("clearRoute")
    override fun overview() = command("overview")
    override fun follow() = command("follow")
    fun privacy() = command("privacy")

    private fun place(place: Place) = JSONObject().put("name", place.name)
        .put("latitude", place.point.latitude).put("longitude", place.point.longitude)

    private fun snapshot(json: JSONObject, revision: Int): RuntimeSnapshot {
        val location = json.optJSONObject("location")?.optJSONObject("point")
        val route = json.optJSONObject("route")
        val progress = json.optJSONObject("progress")
        return RuntimeSnapshot(EnginePhase.fromWire(json.optString("phase")), json.optString("message"),
            json.optString("warning"), location?.let { GeoPoint(it.optDouble("latitude"), it.optDouble("longitude")) },
            route?.let { RouteSummary(it.optInt("id"), it.optDouble("distanceMeters"), it.optDouble("durationSeconds")) },
            progress?.let { NavigationProgress(it.optDouble("remainingMeters"), it.optDouble("remainingSeconds"),
                it.optDouble("nextTurnMeters"), it.optString("currentRoad"), it.optString("nextRoad")) },
            json.optInt("progressCount"), json.optBoolean("simulation"), revision)
    }

    companion object { const val MODULE_NAME = "KRNavigationModule" }
}
