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
    data object AddMedicine : Screen("add_medicine?name={name}&dosage={dosage}&frequency={frequency}&times={times}&pillsPerDose={pillsPerDose}&intervalHours={intervalHours}&foodRelation={foodRelation}&instructions={instructions}") {
        fun createRoute(
            name: String? = null,
            dosage: String? = null,
            frequency: String? = null,
            times: String? = null,
            pillsPerDose: Int? = null,
            intervalHours: Int? = null,
            foodRelation: String? = null,
            instructions: String? = null
        ): String {
            val params = mutableListOf<String>()
            if (!name.isNullOrBlank()) params.add("name=${java.net.URLEncoder.encode(name, "UTF-8")}")
            if (!dosage.isNullOrBlank()) params.add("dosage=${java.net.URLEncoder.encode(dosage, "UTF-8")}")
            if (!frequency.isNullOrBlank()) params.add("frequency=${java.net.URLEncoder.encode(frequency, "UTF-8")}")
            if (!times.isNullOrBlank()) params.add("times=${java.net.URLEncoder.encode(times, "UTF-8")}")
            if (pillsPerDose != null && pillsPerDose > 0) params.add("pillsPerDose=$pillsPerDose")
            if (intervalHours != null && intervalHours > 0) params.add("intervalHours=$intervalHours")
            if (!foodRelation.isNullOrBlank()) params.add("foodRelation=${java.net.URLEncoder.encode(foodRelation, "UTF-8")}")
            if (!instructions.isNullOrBlank()) params.add("instructions=${java.net.URLEncoder.encode(instructions, "UTF-8")}")
            return if (params.isEmpty()) "add_medicine" else "add_medicine?${params.joinToString("&")}"
        }
    }
    data object ScanPrescription : Screen("scan_prescription")
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
    data object DoctorDashboard : Screen("doctor_dashboard")
    data object DoctorPatientDetail : Screen("doctor_patient_detail/{patientId}") {
        fun createRoute(patientId: String) = "doctor_patient_detail/$patientId"
    }
    data object CreateProposal : Screen("create_proposal/{patientId}") {
        fun createRoute(patientId: String) = "create_proposal/$patientId"
    }
}
