package com.hyperionflatsender.data

/**
 * The colour-calibration adjustments the app can drive over Hyperion's JSON-RPC `adjustment` command.
 * Each maps a UI slider to a Settings field (persisted) and the Hyperion JSON field name + range.
 */
enum class Adjustment(
    val jsonKey: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>
) {
    GAMMA_RED("gammaRed", "Gamma R", Settings.GAMMA_RANGE),
    GAMMA_GREEN("gammaGreen", "Gamma G", Settings.GAMMA_RANGE),
    GAMMA_BLUE("gammaBlue", "Gamma B", Settings.GAMMA_RANGE),
    SATURATION_GAIN("saturationGain", "Saturation", Settings.SATURATION_GAIN_RANGE),
    BRIGHTNESS_GAIN("brightnessGain", "Brightness gain", Settings.BRIGHTNESS_GAIN_RANGE);

    fun get(s: Settings): Float = when (this) {
        GAMMA_RED -> s.gammaRed
        GAMMA_GREEN -> s.gammaGreen
        GAMMA_BLUE -> s.gammaBlue
        SATURATION_GAIN -> s.saturationGain
        BRIGHTNESS_GAIN -> s.brightnessGain
    }

    fun set(s: Settings, v: Float): Settings = when (this) {
        GAMMA_RED -> s.copy(gammaRed = v)
        GAMMA_GREEN -> s.copy(gammaGreen = v)
        GAMMA_BLUE -> s.copy(gammaBlue = v)
        SATURATION_GAIN -> s.copy(saturationGain = v)
        BRIGHTNESS_GAIN -> s.copy(brightnessGain = v)
    }
}
