package com.meditrack.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meditrack.app.BuildConfig
import com.meditrack.app.core.navigation.Screen
import com.meditrack.app.core.ui.theme.MediTrackTheme
import com.meditrack.app.data.prefs.UserPreferences
import kotlinx.coroutines.flow.firstOrNull
import com.meditrack.app.domain.repository.AuthRepository
import com.meditrack.app.feature.auth.CreateAccountScreen
import com.meditrack.app.feature.auth.LoginScreen
import com.meditrack.app.feature.auth.VerifyOtpScreen
import com.meditrack.app.feature.debug.DebugToolsScreen
import com.meditrack.app.feature.home.HomeScreen
import com.meditrack.app.feature.onboarding.BatteryOptimizationScreen
import com.meditrack.app.feature.onboarding.PermissionSetupScreen
import com.meditrack.app.feature.onboarding.SplashScreen
import com.meditrack.app.feature.reminder.receiver.ReminderReceiver
import com.meditrack.app.feature.settings.PrivacyConsentScreen
import com.meditrack.app.feature.settings.SettingsScreen
import com.meditrack.app.feature.verification.VerificationStubScreen
import com.meditrack.app.core.database.DatabaseRecoveryState
import com.meditrack.app.data.db.AppDatabase
import com.meditrack.app.feature.error.DatabaseErrorScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var legacyDataRepair: com.meditrack.app.core.database.LegacyDataRepair

    private val pendingDoseId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            MediTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val dbError = DatabaseRecoveryState.currentError
                    if (dbError != null) {
                        DatabaseErrorScreen(
                            error = dbError,
                            onRetry = {
                                DatabaseRecoveryState.clearError()
                                recreate()
                            },
                            onResetData = {
                                DatabaseRecoveryState.resetLocalData(this@MainActivity, AppDatabase.DATABASE_NAME)
                                recreate()
                            }
                        )
                        return@Surface
                    }

                    val navController = rememberNavController()
                    val targetDoseId by pendingDoseId
                    val flagSecureEnabled by userPreferences.isFlagSecureEnabled.collectAsState(initial = true)

                    LaunchedEffect(flagSecureEnabled) {
                        if (flagSecureEnabled) {
                            window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE
                            )
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }

                    LaunchedEffect(targetDoseId) {
                        targetDoseId?.let { doseId ->
                            navController.navigate(Screen.Verification.createRoute(doseId))
                            pendingDoseId.value = null
                        }
                    }

                    LaunchedEffect(Unit) {
                        val currentUid = userPreferences.currentUserId.firstOrNull()
                        if (!currentUid.isNullOrBlank()) {
                            legacyDataRepair.repairLegacyData(currentUid)
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route
                    ) {
                        composable(Screen.Splash.route) {
                            SplashScreen(
                                onNavigate = { targetRoute ->
                                    navController.navigate(targetRoute) {
                                        popUpTo(Screen.Splash.route) { inclusive = true }
                                    }
                                    targetDoseId?.let { doseId ->
                                        navController.navigate(Screen.Verification.createRoute(doseId))
                                        pendingDoseId.value = null
                                    }
                                }
                            )
                        }

                        composable(Screen.Login.route) {
                            LoginScreen(
                                onNavigateToCreateAccount = {
                                    navController.navigate(Screen.CreateAccount.route)
                                },
                                onLoginSuccess = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.CreateAccount.route) {
                            CreateAccountScreen(
                                onNavigateToLogin = {
                                    navController.popBackStack()
                                },
                                onNavigateToOtp = { contact ->
                                    navController.navigate(Screen.VerifyOtp.createRoute(contact))
                                }
                            )
                        }

                        composable(
                            route = Screen.VerifyOtp.route,
                            arguments = listOf(
                                navArgument("contact") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val contact = backStackEntry.arguments?.getString("contact") ?: ""
                            VerifyOtpScreen(
                                contact = contact,
                                onVerificationSuccess = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.PermissionSetup.route) {
                            PermissionSetupScreen(
                                authRepository = authRepository,
                                onContinue = {
                                    navController.navigate(Screen.BatteryGuidance.route) {
                                        popUpTo(Screen.PermissionSetup.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.BatteryGuidance.route) {
                            BatteryOptimizationScreen(
                                authRepository = authRepository,
                                onContinueToHome = {
                                    navController.navigate(Screen.PrivacyConsent.route) {
                                        popUpTo(Screen.BatteryGuidance.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.PrivacyConsent.route) {
                            PrivacyConsentScreen(
                                onConsentGiven = {
                                    navController.navigate(Screen.Splash.route) {
                                        popUpTo(Screen.PrivacyConsent.route) { inclusive = true }
                                    }
                                },
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToDebugTools = {
                                    if (BuildConfig.DEBUG) {
                                        navController.navigate(Screen.DebugTools.route)
                                    }
                                },
                                onNavigateToAddMedicine = {
                                    navController.navigate(Screen.AddMedicine.route)
                                },
                                onNavigateToMedicinesList = {
                                    navController.navigate(Screen.MedicinesList.route)
                                },
                                onNavigateToVerification = { doseId ->
                                    navController.navigate(Screen.Verification.createRoute(doseId))
                                },
                                onNavigateToHistory = {
                                    navController.navigate(Screen.History.route)
                                },
                                onNavigateToCalendar = {
                                    navController.navigate(Screen.Calendar.route)
                                },
                                onNavigateToCaregiver = {
                                    navController.navigate(Screen.Caregiver.route)
                                },
                                onNavigateToDoctor = {
                                    navController.navigate(Screen.Doctor.route)
                                },
                                onNavigateToScan = {
                                    navController.navigate(Screen.Scan.route)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route)
                                }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToMedicines = { navController.navigate(Screen.MedicinesList.route) },
                                onNavigateToCaregiver = { navController.navigate(Screen.Caregiver.route) },
                                onNavigateToPrivacyConsent = { navController.navigate(Screen.PrivacyConsent.route) },
                                onLogoutSuccess = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onDeletionSuccess = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Caregiver.route) {
                            com.meditrack.app.feature.caregiver.CaregiverScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Doctor.route) {
                            val doctorViewModel = androidx.hilt.navigation.compose.hiltViewModel<com.meditrack.app.feature.doctor.DoctorViewModel>()
                            com.meditrack.app.feature.doctor.DoctorScreen(
                                viewModel = doctorViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Scan.route) {
                            com.meditrack.app.feature.scan.ScanMedicineScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onProceedToAddMedicine = { name, dosage, freq, times, interval, days, duration, food, instr, stock, pills ->
                                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                                        set("prefillName", name)
                                        set("prefillDosage", dosage)
                                        set("prefillFrequency", freq)
                                        set("prefillTimes", times)
                                        set("prefillInterval", interval)
                                        set("prefillDaysOfWeek", days.joinToString(","))
                                        set("prefillDurationDays", duration)
                                        set("prefillFood", food)
                                        set("prefillInstructions", instr)
                                        set("prefillStock", stock)
                                        set("prefillPills", pills)
                                    }
                                    navController.navigate(Screen.AddMedicine.route)
                                }
                            )
                        }

                        composable(Screen.History.route) {
                            com.meditrack.app.feature.history.HistoryScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Calendar.route) {
                            com.meditrack.app.feature.calendar.CalendarScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToVerification = { doseId ->
                                    navController.navigate(Screen.Verification.createRoute(doseId))
                                }
                            )
                        }

                        composable(Screen.MedicinesList.route) {
                            com.meditrack.app.feature.medicine.MedicinesListScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToAddMedicine = { navController.navigate(Screen.AddMedicine.route) },
                                onNavigateToEditMedicine = { medId ->
                                    navController.navigate(Screen.EditMedicine.createRoute(medId))
                                },
                                onNavigateToViewDoses = { medId ->
                                    navController.navigate(Screen.DoseListDebug.createRoute(medId))
                                }
                            )
                        }

                        composable(Screen.AddMedicine.route) {
                            val prevEntry = navController.previousBackStackEntry
                            val prefillName = prevEntry?.savedStateHandle?.get<String>("prefillName")
                            val prefillDosage = prevEntry?.savedStateHandle?.get<String>("prefillDosage")
                            val prefillFrequency = prevEntry?.savedStateHandle?.get<String>("prefillFrequency")
                            val prefillTimes = prevEntry?.savedStateHandle?.get<List<String>>("prefillTimes")
                            val prefillInterval = prevEntry?.savedStateHandle?.get<String>("prefillInterval")
                            val prefillFood = prevEntry?.savedStateHandle?.get<String>("prefillFood")
                            val prefillInstructions = prevEntry?.savedStateHandle?.get<String>("prefillInstructions")
                            val prefillStock = prevEntry?.savedStateHandle?.get<String>("prefillStock")
                            val prefillPills = prevEntry?.savedStateHandle?.get<String>("prefillPills")
                            val prefillDaysOfWeek = prevEntry?.savedStateHandle?.get<String>("prefillDaysOfWeek")
                            val prefillDurationDays = prevEntry?.savedStateHandle?.get<String>("prefillDurationDays")

                            val clearPrefill = {
                                val keys = listOf(
                                    "prefillName", "prefillDosage", "prefillFrequency", "prefillTimes",
                                    "prefillInterval", "prefillDaysOfWeek", "prefillDurationDays",
                                    "prefillFood", "prefillInstructions", "prefillStock", "prefillPills"
                                )
                                keys.forEach { key -> prevEntry?.savedStateHandle?.remove<Any>(key) }
                            }

                            com.meditrack.app.feature.medicine.AddEditMedicineScreen(
                                prefillName = prefillName,
                                prefillDosage = prefillDosage,
                                prefillFrequency = prefillFrequency,
                                prefillTimes = prefillTimes,
                                prefillIntervalHours = prefillInterval,
                                prefillDaysOfWeek = prefillDaysOfWeek,
                                prefillDurationDays = prefillDurationDays,
                                prefillFoodRelation = prefillFood,
                                prefillInstructions = prefillInstructions,
                                prefillStock = prefillStock,
                                prefillPillsPerDose = prefillPills,
                                onConsumePrefill = clearPrefill,
                                onNavigateBack = {
                                    clearPrefill()
                                    navController.popBackStack()
                                },
                                onNavigateToHome = {
                                    clearPrefill()
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Home.route) { inclusive = true }
                                    }
                                },
                                onNavigateToMedicinesList = {
                                    clearPrefill()
                                    navController.navigate(Screen.MedicinesList.route) {
                                        popUpTo(Screen.Home.route)
                                    }
                                }
                            )
                        }

                        composable(
                            route = Screen.EditMedicine.route,
                            arguments = listOf(navArgument("medicineId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val medId = backStackEntry.arguments?.getString("medicineId")
                            com.meditrack.app.feature.medicine.AddEditMedicineScreen(
                                medicineId = medId,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToHome = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Home.route) { inclusive = true }
                                    }
                                },
                                onNavigateToMedicinesList = {
                                    navController.navigate(Screen.MedicinesList.route) {
                                        popUpTo(Screen.Home.route)
                                    }
                                }
                            )
                        }

                        composable(
                            route = Screen.DoseListDebug.route,
                            arguments = listOf(navArgument("medicineId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val medId = backStackEntry.arguments?.getString("medicineId") ?: ""
                            com.meditrack.app.feature.medicine.DoseListDebugScreen(
                                medicineId = medId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = Screen.Verification.route,
                            arguments = listOf(navArgument("doseId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val capturedPhotoPath = backStackEntry.savedStateHandle
                                .getStateFlow<String?>("captured_photo_path", null)
                                .collectAsState().value

                            VerificationStubScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToCapture = { doseId ->
                                    navController.navigate(Screen.Capture.createRoute(doseId))
                                },
                                capturedPhotoPath = capturedPhotoPath,
                                onClearCapturedPhoto = {
                                    backStackEntry.savedStateHandle.remove<String>("captured_photo_path")
                                }
                            )
                        }

                        composable(
                            route = Screen.Capture.route,
                            arguments = listOf(navArgument("doseId") { type = NavType.StringType })
                        ) {
                            com.meditrack.app.feature.verification.CaptureScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateHome = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.Home.route) { inclusive = true }
                                    }
                                },
                                onPhotoVerifiedQuality = { photoPath, pHash, metrics ->
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("captured_photo_path", photoPath)
                                    navController.popBackStack()
                                }
                            )
                        }

                        if (BuildConfig.DEBUG) {
                            composable(Screen.DebugTools.route) {
                                DebugToolsScreen(
                                    onNavigateBack = {
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val doseId = intent?.getStringExtra(ReminderReceiver.EXTRA_DOSE_ID)
        if (!doseId.isNullOrBlank()) {
            pendingDoseId.value = doseId
        }
    }
}
