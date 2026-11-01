package com.meditrack.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
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
import com.meditrack.app.feature.verification.VerificationStubScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

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
                    val navController = rememberNavController()
                    val targetDoseId by pendingDoseId

                    LaunchedEffect(targetDoseId) {
                        targetDoseId?.let { doseId ->
                            navController.navigate(Screen.Verification.createRoute(doseId))
                            pendingDoseId.value = null
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
                                    navController.navigate(Screen.Splash.route) {
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
                                    navController.navigate(Screen.PermissionSetup.route) {
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
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(Screen.BatteryGuidance.route) { inclusive = true }
                                    }
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
                            com.meditrack.app.feature.medicine.AddEditMedicineScreen(
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
                        ) {
                            VerificationStubScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToCapture = { doseId ->
                                    navController.navigate(Screen.Capture.createRoute(doseId))
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
