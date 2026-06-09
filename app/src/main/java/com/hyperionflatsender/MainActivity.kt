package com.hyperionflatsender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hyperionflatsender.ui.CalibrationScreen
import com.hyperionflatsender.ui.MainScreen
import com.hyperionflatsender.ui.SettingsScreen
import com.hyperionflatsender.ui.theme.HyperionTheme
import com.hyperionflatsender.viewmodel.CaptureViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    private val viewModel: CaptureViewModel by viewModels()

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onProjectionPermissionResult(result.resultCode, result.data)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission granted or denied — foreground service notifications work without it on API ≤32 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            HyperionTheme {
                val navController = rememberNavController()

                // Only settings is needed at this level — it's stable between screens.
                // captureState, connectionState, and stats are collected inside MainScreen
                // so their updates don't cause NavHost-level recompositions.
                val settings by viewModel.settings.collectAsState()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            viewModel = viewModel,
                            onStartClick = { projectionLauncher.launch(projectionManager.createScreenCaptureIntent()) },
                            onStopClick = { viewModel.stopCapture() },
                            onSettingsClick = { navController.navigate("settings") },
                            onCalibrateClick = {
                                // Calibration drives Hyperion over its own connection; stop the live
                                // mirror first so the two don't fight over the same priority.
                                viewModel.stopCapture()
                                navController.navigate("calibration")
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            onSave = { viewModel.saveSettings(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("calibration") {
                        CalibrationScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }

        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
}
