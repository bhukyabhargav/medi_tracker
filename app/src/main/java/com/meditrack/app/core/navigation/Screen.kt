package com.meditrack.app.core.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object DebugTools : Screen("debug_tools")
}
