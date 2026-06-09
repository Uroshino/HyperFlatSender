package com.hyperionflatsender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hyperionflatsender.service.CaptureService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repo = (context.applicationContext as HyperionApp).settingsRepository
        val autoStart = runBlocking { repo.settingsFlow.first().autoStartOnBoot }
        if (!autoStart) return

        // Cannot show MediaProjection dialog from background (Android 10+ restriction).
        // Start service in waiting-permission mode; it shows a notification for the user to tap.
        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_WAITING, true)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
