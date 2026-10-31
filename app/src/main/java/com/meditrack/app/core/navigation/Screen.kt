package com.meditrack.app.core.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object CreateAccount : Screen("create_account")
    data object VerifyOtp : Screen("verify_otp/{contact}") {
        fun createRoute(contact: String) = "verify_otp/${java.net.URLEncoder.encode(contact, "UTF-8")}"
    }
    data object PermissionSetup : Screen("permission_setup")
    data object BatteryGuidance : Screen("battery_guidance")
    data object Home : Screen("home")
    data object DebugTools : Screen("debug_tools")
}
