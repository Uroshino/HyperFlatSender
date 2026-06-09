package com.hyperflatsender.data

/**
 * Which ambient-lighting server the app talks to. The FlatBuffers image protocol (screen capture
 * AND the calibration test patterns) is identical for both — same port 19400, same Register/RawImage/
 * Color messages — so this selector only affects the JSON-RPC colour `adjustment` channel, whose
 * schema diverged after HyperHDR forked from Hyperion:
 *
 *  - **Hyperion** exposes per-channel gamma (`gammaRed`/`gammaGreen`/`gammaBlue`) and `brightnessGain`.
 *  - **HyperHDR** collapsed gamma to a single `gamma` key and renamed the brightness gain to
 *    `luminanceGain`.
 *
 * Both schemas are `additionalProperties:false`, so sending the *other* server's keys fails the
 * WHOLE adjustment command (not just the unknown field) — which is why we can't just send a superset
 * and must format the keys per server. See [Adjustment].
 */
enum class ServerType(val label: String) {
    HYPERION("Hyperion"),
    HYPERHDR("HyperHDR")
}
