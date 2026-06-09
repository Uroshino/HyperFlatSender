package com.hyperionflatsender

import android.app.Application
import com.hyperionflatsender.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HyperionApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    // App-lifetime scope for fire-and-forget writes that must outlive a screen/ViewModel — e.g. the
    // final settings flush when leaving the Calibration screen, which would otherwise be cancelled
    // with the ViewModel's scope before the DataStore write commits.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
