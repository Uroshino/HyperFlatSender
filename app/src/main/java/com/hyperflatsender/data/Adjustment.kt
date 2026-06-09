package com.hyperflatsender.data

/**
 * The colour-calibration adjustments the app can drive over the server's JSON-RPC `adjustment`
 * command. Each maps a UI slider to a [Settings] field (persisted) and to the JSON field name + range
 * expected by the selected [ServerType].
 *
 * Hyperion and HyperHDR diverged here: Hyperion has per-channel gamma (`gammaRed`/`gammaGreen`/
 * `gammaBlue`) and `brightnessGain`; HyperHDR collapsed gamma to a single `gamma` and renamed the
 * brightness gain to `luminanceGain`. Both schemas are `additionalProperties:false`, so ONLY the keys
 * valid for the active server may be sent — [forServer] filters the slider/field set accordingly and
 * [jsonKey] resolves the per-server key. The shared slider ranges (see [Settings]) sit inside BOTH
 * servers' accepted bounds.
 */
enum class Adjustment(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    /** Which servers expose this slider. */
    val servers: Set<ServerType>
) {
    GAMMA_RED("Gamma R", Settings.GAMMA_RANGE, setOf(ServerType.HYPERION)),
    GAMMA_GREEN("Gamma G", Settings.GAMMA_RANGE, setOf(ServerType.HYPERION)),
    GAMMA_BLUE("Gamma B", Settings.GAMMA_RANGE, setOf(ServerType.HYPERION)),
    // HyperHDR has no per-channel gamma, only a single `gamma`.
    GAMMA_MONO("Gamma", Settings.GAMMA_RANGE, setOf(ServerType.HYPERHDR)),
    SATURATION_GAIN("Saturation", Settings.SATURATION_GAIN_RANGE, setOf(ServerType.HYPERION, ServerType.HYPERHDR)),
    BRIGHTNESS_GAIN("Brightness gain", Settings.BRIGHTNESS_GAIN_RANGE, setOf(ServerType.HYPERION, ServerType.HYPERHDR));

    /** The JSON `adjustment` key for this slider on [serverType]. */
    fun jsonKey(serverType: ServerType): String = when (this) {
        GAMMA_RED -> "gammaRed"
        GAMMA_GREEN -> "gammaGreen"
        GAMMA_BLUE -> "gammaBlue"
        GAMMA_MONO -> "gamma"
        SATURATION_GAIN -> "saturationGain"
        // Hyperion calls it brightnessGain; HyperHDR renamed it luminanceGain. Same stored field.
        BRIGHTNESS_GAIN -> if (serverType == ServerType.HYPERHDR) "luminanceGain" else "brightnessGain"
    }

    fun get(s: Settings): Float = when (this) {
        GAMMA_RED -> s.gammaRed
        GAMMA_GREEN -> s.gammaGreen
        GAMMA_BLUE -> s.gammaBlue
        GAMMA_MONO -> s.gammaMono
        SATURATION_GAIN -> s.saturationGain
        BRIGHTNESS_GAIN -> s.brightnessGain
    }

    fun set(s: Settings, v: Float): Settings = when (this) {
        GAMMA_RED -> s.copy(gammaRed = v)
        GAMMA_GREEN -> s.copy(gammaGreen = v)
        GAMMA_BLUE -> s.copy(gammaBlue = v)
        GAMMA_MONO -> s.copy(gammaMono = v)
        SATURATION_GAIN -> s.copy(saturationGain = v)
        BRIGHTNESS_GAIN -> s.copy(brightnessGain = v)
    }

    companion object {
        /** The sliders/fields valid for [serverType], in declaration order. */
        fun forServer(serverType: ServerType): List<Adjustment> =
            entries.filter { serverType in it.servers }
    }
}
