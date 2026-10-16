package com.example.navi_app.navigation.presentation

import com.tencent.kuikly.core.base.Color

/**
 * Visual contract shared by the Kuikly page and the MapTemplate migration.
 * Keep layout values here so a visual adjustment does not change navigation logic.
 */
object NavigationDesignTokens {
    val pageBackground = Color(0xFFF5F7FB)
    val surface = Color.WHITE
    val primary = Color(0xFF245FC7)
    val title = Color(0xFF14253B)
    val secondaryText = Color(0xFF627389)
    val warning = Color(0xFFAD5100)
    val disabledSurface = Color(0xFFE3E9F1)
    val divider = Color(0xFFE2E8F0)
    val mapPlaceholder = Color(0xFFE7EDF4)
    val diagnosticSurface = Color(0xFFE7EDF8)
    val searchPanel = Color(0xCC2B2B2B)
    val searchInput = Color(0x33FFFFFF)
    val navigationSheet = Color(0xF7FFFFFF)
    val navigationBar = Color(0xCC1E1E1E)
    val navigationBarSelected = Color.WHITE

    const val pagePadding = 12f
    const val compactPadding = 8f
    const val headerHeight = 54f
    const val cardRadius = 12f
    const val compactRadius = 8f
    const val actionHeight = 40f
    const val mapMinimumHeight = 220f
    const val titleSize = 25f
    const val cardTitleSize = 16f
    const val bodySize = 14f
    const val secondarySize = 12f
}
