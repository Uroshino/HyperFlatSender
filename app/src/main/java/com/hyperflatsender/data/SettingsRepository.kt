package com.hyperflatsender.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_IP    = stringPreferencesKey("server_ip")
        val SERVER_PORT  = intPreferencesKey("server_port")
        val SERVER_TYPE  = stringPreferencesKey("server_type")
        val FRAME_WIDTH  = intPreferencesKey("frame_width")
        val FRAME_HEIGHT = intPreferencesKey("frame_height")
        val FRAME_RATE   = intPreferencesKey("frame_rate")
        val MULTIPLIER   = intPreferencesKey("multiplier")
        val PRIORITY     = intPreferencesKey("priority")
        val ORIGIN       = stringPreferencesKey("origin")
        val AUTO_START   = booleanPreferencesKey("auto_start_on_boot")
        val SHOW_STATS   = booleanPreferencesKey("show_stats")
        val GATE_MIRROR  = booleanPreferencesKey("gate_mirror")
        val WHEEL_FPS    = intPreferencesKey("wheel_fps")
        val WHEEL_SPIN_S = intPreferencesKey("wheel_spin_seconds")
        val JSON_RPC_PORT     = intPreferencesKey("json_rpc_port")
        val JSON_TOKEN        = stringPreferencesKey("json_token")
        val GAMMA_RED         = floatPreferencesKey("gamma_red")
        val GAMMA_GREEN       = floatPreferencesKey("gamma_green")
        val GAMMA_BLUE        = floatPreferencesKey("gamma_blue")
        val GAMMA_MONO        = floatPreferencesKey("gamma_mono")
        val SATURATION_GAIN   = floatPreferencesKey("saturation_gain")
        val BRIGHTNESS_GAIN   = floatPreferencesKey("brightness_gain")
        val CHASE_BLOCK_FRAC  = floatPreferencesKey("chase_block_fraction")
    }

    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            serverIp      = prefs[Keys.SERVER_IP]    ?: Settings.DEFAULT.serverIp,
            serverPort    = prefs[Keys.SERVER_PORT]  ?: Settings.DEFAULT.serverPort,
            serverType    = prefs[Keys.SERVER_TYPE]?.let { runCatching { ServerType.valueOf(it) }.getOrNull() }
                ?: Settings.DEFAULT.serverType,
            frameWidth    = prefs[Keys.FRAME_WIDTH]  ?: Settings.DEFAULT.frameWidth,
            frameHeight   = prefs[Keys.FRAME_HEIGHT] ?: Settings.DEFAULT.frameHeight,
            frameRate     = prefs[Keys.FRAME_RATE]   ?: Settings.DEFAULT.frameRate,
            multiplier    = prefs[Keys.MULTIPLIER]   ?: Settings.DEFAULT.multiplier,
            priority      = prefs[Keys.PRIORITY]     ?: Settings.DEFAULT.priority,
            origin        = prefs[Keys.ORIGIN]       ?: Settings.DEFAULT.origin,
            autoStartOnBoot = prefs[Keys.AUTO_START] ?: Settings.DEFAULT.autoStartOnBoot,
            showStats     = prefs[Keys.SHOW_STATS]   ?: Settings.DEFAULT.showStats,
            gateMirror    = prefs[Keys.GATE_MIRROR]  ?: Settings.DEFAULT.gateMirror,
            wheelFps      = prefs[Keys.WHEEL_FPS]    ?: Settings.DEFAULT.wheelFps,
            wheelSpinSeconds = prefs[Keys.WHEEL_SPIN_S] ?: Settings.DEFAULT.wheelSpinSeconds,
            jsonRpcPort   = prefs[Keys.JSON_RPC_PORT]   ?: Settings.DEFAULT.jsonRpcPort,
            jsonToken     = prefs[Keys.JSON_TOKEN]      ?: Settings.DEFAULT.jsonToken,
            gammaRed      = prefs[Keys.GAMMA_RED]       ?: Settings.DEFAULT.gammaRed,
            gammaGreen    = prefs[Keys.GAMMA_GREEN]     ?: Settings.DEFAULT.gammaGreen,
            gammaBlue     = prefs[Keys.GAMMA_BLUE]      ?: Settings.DEFAULT.gammaBlue,
            gammaMono     = prefs[Keys.GAMMA_MONO]      ?: Settings.DEFAULT.gammaMono,
            saturationGain = prefs[Keys.SATURATION_GAIN] ?: Settings.DEFAULT.saturationGain,
            brightnessGain = prefs[Keys.BRIGHTNESS_GAIN] ?: Settings.DEFAULT.brightnessGain,
            chaseBlockFraction = prefs[Keys.CHASE_BLOCK_FRAC] ?: Settings.DEFAULT.chaseBlockFraction
        )
    }

    suspend fun saveSettings(settings: Settings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_IP]    = settings.serverIp
            prefs[Keys.SERVER_PORT]  = settings.serverPort
            prefs[Keys.SERVER_TYPE]  = settings.serverType.name
            prefs[Keys.FRAME_WIDTH]  = settings.frameWidth
            prefs[Keys.FRAME_HEIGHT] = settings.frameHeight
            prefs[Keys.FRAME_RATE]   = settings.frameRate
            prefs[Keys.MULTIPLIER]   = settings.multiplier
            prefs[Keys.PRIORITY]     = settings.priority
            prefs[Keys.ORIGIN]       = settings.origin
            prefs[Keys.AUTO_START]   = settings.autoStartOnBoot
            prefs[Keys.SHOW_STATS]   = settings.showStats
            prefs[Keys.GATE_MIRROR]  = settings.gateMirror
            prefs[Keys.WHEEL_FPS]    = settings.wheelFps
            prefs[Keys.WHEEL_SPIN_S] = settings.wheelSpinSeconds
            prefs[Keys.JSON_RPC_PORT]   = settings.jsonRpcPort
            prefs[Keys.JSON_TOKEN]      = settings.jsonToken
            prefs[Keys.GAMMA_RED]       = settings.gammaRed
            prefs[Keys.GAMMA_GREEN]     = settings.gammaGreen
            prefs[Keys.GAMMA_BLUE]      = settings.gammaBlue
            prefs[Keys.GAMMA_MONO]      = settings.gammaMono
            prefs[Keys.SATURATION_GAIN] = settings.saturationGain
            prefs[Keys.BRIGHTNESS_GAIN] = settings.brightnessGain
            prefs[Keys.CHASE_BLOCK_FRAC] = settings.chaseBlockFraction
        }
    }
}
