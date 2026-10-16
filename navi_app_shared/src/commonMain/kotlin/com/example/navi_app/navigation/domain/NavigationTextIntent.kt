package com.example.navi_app.navigation.domain

import com.example.navi_app.navigation.model.TravelMode

/**
 * Reuses MapTemplate's last-mentioned travel mode preference. This only supplies
 * a request constraint: destination names, stop order and coordinates are resolved
 * by navi-claw and its POI tools, never invented from local text matching.
 */
object NavigationTextIntent {
    private data class ModeWord(val text: String, val mode: TravelMode, val vehicleName: Boolean = false)

    private val words = listOf(
        ModeWord("驾车", TravelMode.DRIVE), ModeWord("开车", TravelMode.DRIVE),
        ModeWord("驾驶", TravelMode.DRIVE), ModeWord("汽车", TravelMode.DRIVE, true),
        ModeWord("车辆", TravelMode.DRIVE, true),
        ModeWord("步行", TravelMode.WALK), ModeWord("走路", TravelMode.WALK),
        ModeWord("徒步", TravelMode.WALK),
        ModeWord("骑行", TravelMode.RIDE), ModeWord("骑车", TravelMode.RIDE),
        ModeWord("自行车", TravelMode.RIDE, true), ModeWord("单车", TravelMode.RIDE, true),
        ModeWord("电动车", TravelMode.RIDE, true)
    )
    private val placeSuffixes = listOf("街", "道", "站", "店", "馆", "场", "学校", "培训", "俱乐部", "公园", "协会", "基地", "中心")
    private val vehicleActions = listOf("用", "乘", "坐", "骑", "骑着", "开", "驾驶", "选择", "换成")
    private val negatives = listOf("不", "不要", "别", "不想", "不再", "不打算", "不用", "无需", "不能", "不需要", "不考虑", "不是", "并非")

    fun resolveTravelMode(text: String, fallback: TravelMode): TravelMode = inferTravelMode(text) ?: fallback

    fun resolveTravelMode(text: String, fallback: String?): String? = inferTravelMode(text)?.wireValue ?: fallback

    private fun inferTravelMode(text: String): TravelMode? {
        var selected: TravelMode? = null
        var lastIndex = -1
        words.forEach { word ->
            var index = text.indexOf(word.text)
            while (index >= 0) {
                val before = text.substring(0, index).trimEnd()
                val after = text.substring(index + word.text.length).trimStart()
                val action = vehicleActions.firstOrNull { before.endsWith(it) }
                val beforeAction = if (word.vehicleName && action != null) before.dropLast(action.length).trimEnd() else before
                val negated = negatives.any { before.endsWith(it) || beforeAction.endsWith(it) }
                val placeName = placeSuffixes.any { after.startsWith(it) }
                val vehicleExpression = !word.vehicleName || action != null ||
                    listOf("去", "到", "前往").any { after.startsWith(it) }
                if (index > lastIndex && !negated && !placeName && vehicleExpression) {
                    selected = word.mode
                    lastIndex = index
                }
                index = text.indexOf(word.text, index + word.text.length)
            }
        }
        return selected
    }
}
