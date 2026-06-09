package com.hyperflatsender.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperflatsender.HyperionApp
import com.hyperflatsender.data.Settings
import com.hyperflatsender.network.ConnectionState
import com.hyperflatsender.service.CaptureService
import com.hyperflatsender.service.CaptureState
import com.hyperflatsender.service.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val settings: Settings = Settings.DEFAULT,
    val captureState: CaptureState = CaptureState.Stopped,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val stats: Stats = Stats.EMPTY
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as HyperionApp).settingsRepository

    val settings: StateFlow<Settings> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings.DEFAULT)

    val captureState: StateFlow<CaptureState> = CaptureService.captureState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureState.Stopped)

    val connectionState: StateFlow<ConnectionState> = CaptureService.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Disconnected)

    val stats: StateFlow<Stats> = CaptureService.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stats.EMPTY)

    fun onProjectionPermissionResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        startCapture(resultCode, data)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(getApplication(), CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(getApplication(), serviceIntent)
    }

    fun stopCapture() {
        getApplication<Application>().stopService(
            Intent(getApplication(), CaptureService::class.java)
        )
    }

    fun saveSettings(s: Settings) {
        viewModelScope.launch(Dispatchers.IO) { repo.saveSettings(s) }
    }
}
