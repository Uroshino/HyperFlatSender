package com.hyperionflatsender.data

data class Settings(
    val serverIp: String = "",
    val serverPort: Int = 19400,
    val frameWidth: Int = 79,
    val frameHeight: Int = 43,
    val frameRate: Int = 24,
    val multiplier: Int = 1,
    val priority: Int = 150,
    val origin: String = "HyperionFlatSender",
    val autoStartOnBoot: Boolean = false,
    val showStats: Boolean = false,
    val gateMirror: Boolean = false,
    // Colour-wheel animation on the main screen — cosmetic, independent of capture rate.
    val wheelFps: Int = 60,
    val wheelSpinSeconds: Int = 10,
    // Hyperion JSON-RPC port (default 19444) — the SEPARATE control channel used to push the colour
    // adjustments below. The image stream uses serverPort (FlatBuffers, default 19400).
    val jsonRpcPort: Int = 19444,
    // Optional Hyperion API token for the JSON-RPC channel. Required only when this device is NOT on
    // Hyperion's local subnet: Hyperion's "Local API Authentication" bypass authorises loopback/
    // same-subnet peers only, so a TV on a different subnet/VLAN must present a token or its
    // `adjustment` command is rejected ("No Authorization"). Blank = rely on the local bypass.
    // Create one in Hyperion → Network Services → Manage Tokens (a 36-character UUID).
    val jsonToken: String = "",
    // Colour-calibration adjustments, pushed to Hyperion over JSON-RPC (see Adjustment + the
    // Calibration screen). All neutral (1.0) by default so a fresh install changes nothing. These
    // map 1:1 to Hyperion's `adjustment` command fields.
    val gammaRed: Float = 1f,
    val gammaGreen: Float = 1f,
    val gammaBlue: Float = 1f,
    val saturationGain: Float = 1f,
    val brightnessGain: Float = 1f,
    // Calibration "Chase" pattern: the walking block's size as a fraction of each layout axis. Sized
    // to (over)fill a Hyperion LED sampling region so the block reads as solid white, not a dim grey.
    val chaseBlockFraction: Float = 0.22f
) {
    companion object {
        const val PRIORITY_MIN = 100
        const val PRIORITY_MAX = 199
        val MULTIPLIERS = listOf(1, 2, 3, 4)
        val DEFAULT = Settings()

        // Slider bounds for the colour adjustments, matching Hyperion's accepted ranges. 1.0 is the
        // neutral (no-op) point for all three; the defaults above sit there. Mins/maxes must stay
        // inside Hyperion's schema-adjustment.json bounds or the whole command fails validation:
        // gamma* 0.1–5.0, saturationGain 0.0–10.0, brightnessGain 0.1–10.0. brightnessGain's floor
        // is 0.1 (NOT 0) — a 0 here is rejected, which is why this range can't start at 0.
        val GAMMA_RANGE: ClosedFloatingPointRange<Float> = 0.1f..4.0f
        val SATURATION_GAIN_RANGE: ClosedFloatingPointRange<Float> = 0f..5f
        val BRIGHTNESS_GAIN_RANGE: ClosedFloatingPointRange<Float> = 0.1f..5f
        // Chase block size, as a fraction of each layout axis (5%–60% of the frame).
        val CHASE_BLOCK_RANGE: ClosedFloatingPointRange<Float> = 0.05f..0.6f

        /**
         * Snap [w]×[h] to the nearest integer dimensions whose aspect ratio matches the
         * screen's ([screenW]:[screenH]).
         *
         * Auto-nearest strategy: nudge only the single axis that yields the ratio closest
         * to the screen's, preserving the other axis. This guarantees the screen aspect is
         * honoured so the image sent to Hyperion is never geometrically distorted.
         *
         * Example: 79×43 on a 1920×1080 screen →
         *   keep-width  → 79×44 (1.795, err 0.017)
         *   keep-height → 76×43 (1.767, err 0.010)  ← chosen
         *
         * Returns (width, height); a no-op (clamped to ≥1) if any input is non-positive.
         */
        fun snapToAspect(w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
            if (w < 1 || h < 1 || screenW < 1 || screenH < 1) {
                return w.coerceAtLeast(1) to h.coerceAtLeast(1)
            }
            val target = screenW.toDouble() / screenH
            // Candidate A: keep width, derive height from the screen ratio.
            val keepW = w to Math.round(w * screenH.toDouble() / screenW).toInt().coerceAtLeast(1)
            // Candidate B: keep height, derive width from the screen ratio.
            val keepH = Math.round(h * screenW.toDouble() / screenH).toInt().coerceAtLeast(1) to h
            val errKeepW = Math.abs(keepW.first.toDouble() / keepW.second - target)
            val errKeepH = Math.abs(keepH.first.toDouble() / keepH.second - target)
            return if (errKeepW <= errKeepH) keepW else keepH
        }
    }

    val effectiveWidth: Int  get() = frameWidth  * multiplier
    val effectiveHeight: Int get() = frameHeight * multiplier

    val isServerConfigured: Boolean get() = serverIp.isNotBlank() && serverPort in 1..65535
    val captureResolutionLabel: String get() = "${frameWidth}×${frameHeight}"
    val frameIntervalMs: Long get() = if (frameRate > 0) 1000L / frameRate else 40L
}
