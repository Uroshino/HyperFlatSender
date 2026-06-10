package com.hyperflatsender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.hyperflatsender.service.CaptureService
import com.hyperflatsender.service.CaptureState

/**
 * Headless, transparent "toggle capture" entry point.
 *
 * It is registered as its own launcher activity (MAIN + LAUNCHER + LEANBACK_LAUNCHER, see the
 * manifest) so it shows up as a SECOND, distinctly-labelled entry in the TV Apps row AND in the
 * "Apps" list that remote-button-mapper apps (e.g. Button Mapper) enumerate — exactly how
 * hyperion-android-grabber exposes its toggle. Bind a spare remote key to it and one press starts
 * or stops streaming without opening the full UI. Being `exported`, it is also triggerable from
 * automation or a shell:
 *
 *     adb shell am start -n com.hyperflatsender/.ToggleActivity
 *
 * Behaviour:
 *  - capture RUNNING  → stop the service and finish immediately (no visible UI).
 *  - capture STOPPED / stuck WAITING → launch the system screen-capture consent dialog, then start
 *    the service on grant. The consent dialog is mandatory and cannot be skipped: a MediaProjection
 *    token can't be reused across sessions nor obtained from the background, so a fully silent
 *    "start" is impossible by platform design — only "stop" is truly one-press-silent.
 */
class ToggleActivity : ComponentActivity() {

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_DATA, result.data)
                }
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CaptureService.captureState.value is CaptureState.Running) {
            // Already streaming → stop and get out of the way. stopService → onDestroy resets state.
            stopService(Intent(this, CaptureService::class.java))
            finish()
        } else {
            // Stopped (or stuck WaitingPermission after a boot auto-start) → ask for consent, then
            // start. finish() happens in the launcher callback once the result is back.
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
}
