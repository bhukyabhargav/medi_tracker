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
    data object MedicinesList : Screen("medicines_list")
    data object AddMedicine : Screen("add_medicine")
    data object EditMedicine : Screen("edit_medicine/{medicineId}") {
        fun createRoute(medicineId: String) = "edit_medicine/$medicineId"
    }
    data object DoseListDebug : Screen("dose_list_debug/{medicineId}") {
        fun createRoute(medicineId: String) = "dose_list_debug/$medicineId"
    }
    data object Verification : Screen("verification/{doseId}") {
        fun createRoute(doseId: String) = "verification/$doseId"
    }
    data object Capture : Screen("capture/{doseId}") {
        fun createRoute(doseId: String) = "capture/$doseId"
    }
    data object History : Screen("history")
    data object Calendar : Screen("calendar")
    data object Caregiver : Screen("caregiver")
    data object CaregiverPatientDetail : Screen("caregiver_patient/{patientId}") {
        fun createRoute(patientId: String) = "caregiver_patient/$patientId"
    }
    data object Doctor : Screen("doctor")
}
