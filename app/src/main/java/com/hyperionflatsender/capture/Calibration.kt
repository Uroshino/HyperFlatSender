package com.hyperionflatsender.capture

/**
 * A calibration test output. [Solid]s are sent as a single Hyperion `Color` command (all LEDs one
 * colour); the per-LED patterns are sent as a synthesized RawImage sized to the Hyperion layout.
 */
sealed class CalibPattern(val label: String) {
    class Solid(label: String, val rgb: Int) : CalibPattern(label)
    data object Chase : CalibPattern("Chase")
    data object GammaRamp : CalibPattern("Gamma ramp")
    data object EdgeMap : CalibPattern("Edge map")
}

/** Calibration pattern catalogue + per-LED image synthesis (all pure functions — no I/O). */
object Calibration {

    val PATTERNS: List<CalibPattern> = listOf(
        CalibPattern.Solid("White", 0xFFFFFF),
        CalibPattern.Solid("Red", 0xFF0000),
        CalibPattern.Solid("Green", 0x00FF00),
        CalibPattern.Solid("Blue", 0x0000FF),
        CalibPattern.Solid("Cyan", 0x00FFFF),
        CalibPattern.Solid("Magenta", 0xFF00FF),
        CalibPattern.Solid("Yellow", 0xFFFF00),
        CalibPattern.Solid("Black", 0x000000),
        CalibPattern.Chase,
        CalibPattern.GammaRamp,
        CalibPattern.EdgeMap,
    )

    /** Number of border pixels the chase dot walks around (top + right + bottom + left). */
    fun perimeter(w: Int, h: Int): Int =
        if (w <= 1 || h <= 1) maxOf(w, h, 1) else 2 * (w + h) - 4

    /**
     * Horizontal grayscale ramp — black at the left edge → white at the right. Reveals gamma: a
     * well-calibrated strip shows a perceptually even brightness sweep across the LEDs.
     */
    fun gammaRamp(w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (x in 0 until w) {
            val g = (if (w <= 1) 255 else 255 * x / (w - 1)).toByte()
            for (y in 0 until h) {
                val i = (y * w + x) * 3
                out[i] = g; out[i + 1] = g; out[i + 2] = g
            }
        }
        return out
    }

    /**
     * Distinct colour per edge by nearest-edge: top red, right green, bottom blue, left yellow.
     * Reveals which screen edge maps to which LED group and the overall strip orientation.
     */
    fun edgeMap(w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dTop = y; val dBottom = h - 1 - y; val dLeft = x; val dRight = w - 1 - x
                val m = minOf(dTop, dBottom, dLeft, dRight)
                val r: Int; val g: Int; val b: Int
                when (m) {
                    dTop -> { r = 255; g = 0; b = 0 }       // top → red
                    dRight -> { r = 0; g = 255; b = 0 }     // right → green
                    dBottom -> { r = 0; g = 0; b = 255 }    // bottom → blue
                    else -> { r = 255; g = 255; b = 0 }     // left → yellow
                }
                val i = (y * w + x) * 3
                out[i] = r.toByte(); out[i + 1] = g.toByte(); out[i + 2] = b.toByte()
            }
        }
        return out
    }

    /**
     * A single bright block walking clockwise around the border (top L→R, right T→B, bottom R→L,
     * left B→T) — reveals LED count, order, direction and start corner on the physical strip. The
     * block spans [fraction] of each axis so it (over)fills a Hyperion LED's sampling region and
     * reads as solid white; it's anchored *inside* the layout at the edges/corners (its top-left is
     * clamped) so the whole block lands in-frame rather than half-clipped off the border. [fraction]
     * is user-tunable (the calibration "Chase block size" slider) to match the LED sampling size.
     */
    fun chase(w: Int, h: Int, pos: Int, fraction: Float): ByteArray =
        ByteArray(w * h * 3).also { fillChase(it, w, h, pos, fraction) }

    /**
     * [chase] into a caller-provided [out] (size `w*h*3`) to avoid allocating a fresh image every
     * animation frame — the render loop reuses one buffer, which keeps the Chase animation from
     * churning the GC. [out] is zeroed (black) first, then the block is drawn.
     */
    fun fillChase(out: ByteArray, w: Int, h: Int, pos: Int, fraction: Float) {
        java.util.Arrays.fill(out, 0)
        if (w < 1 || h < 1) return
        val (px, py) = perimeterXY(w, h, pos)
        val bw = maxOf(3, Math.round(w * fraction))
        val bh = maxOf(3, Math.round(h * fraction))
        val x0 = (px - bw / 2).coerceIn(0, (w - bw).coerceAtLeast(0))
        val y0 = (py - bh / 2).coerceIn(0, (h - bh).coerceAtLeast(0))
        for (y in y0 until minOf(y0 + bh, h)) for (x in x0 until minOf(x0 + bw, w)) {
            val i = (y * w + x) * 3
            out[i] = 255.toByte(); out[i + 1] = 255.toByte(); out[i + 2] = 255.toByte()
        }
    }

    /**
     * A full-frame image painted a single [rgb] (0xRRGGBB) — used for the solid calibration patterns
     * so they go out as a RawImage like every other pattern. A full image always replaces the prior
     * one at our priority server-side, which guarantees switching off Chase fully clears its block
     * (a `Color` command takes a different Hyperion code path that didn't reliably overwrite it).
     */
    fun solid(w: Int, h: Int, rgb: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        if (w < 1 || h < 1) return out
        val r = ((rgb shr 16) and 0xFF).toByte()
        val g = ((rgb shr 8) and 0xFF).toByte()
        val b = (rgb and 0xFF).toByte()
        var i = 0
        while (i < out.size) { out[i] = r; out[i + 1] = g; out[i + 2] = b; i += 3 }
        return out
    }

    /** Maps a perimeter position to an (x, y) border pixel, walking clockwise from the top-left. */
    private fun perimeterXY(w: Int, h: Int, posIn: Int): Pair<Int, Int> {
        val per = perimeter(w, h)
        var p = ((posIn % per) + per) % per
        val top = w
        val right = (h - 1).coerceAtLeast(0)
        val bottom = (w - 1).coerceAtLeast(0)
        if (p < top) return p to 0                       // top edge, x = p
        p -= top
        if (p < right) return (w - 1) to (1 + p)         // right edge, going down
        p -= right
        if (p < bottom) return (w - 2 - p) to (h - 1)    // bottom edge, going left
        p -= bottom
        return 0 to (h - 2 - p)                          // left edge, going up
    }
}
