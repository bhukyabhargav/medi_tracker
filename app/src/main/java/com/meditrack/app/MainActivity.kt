package com.meditrack.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meditrack.app.BuildConfig
import com.meditrack.app.core.navigation.Screen
import com.meditrack.app.core.ui.theme.MediTrackTheme
import com.meditrack.app.feature.debug.DebugToolsScreen
import com.meditrack.app.feature.home.HomeScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MediTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToDebugTools = {
                                    if (BuildConfig.DEBUG) {
                                        navController.navigate(Screen.DebugTools.route)
                                    }
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
}