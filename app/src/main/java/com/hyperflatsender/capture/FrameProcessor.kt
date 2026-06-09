package com.hyperflatsender.capture

import android.media.Image

/**
 * Converts an RGBA_8888 Image into an RGB byte array sized [width × outputHeight].
 *
 * The VirtualDisplay is created at [width × captureHeight] where captureHeight is derived
 * from the screen's actual aspect ratio so no letterboxing occurs.  If captureHeight differs
 * from outputHeight (the user's Hyperion layout height), a nearest-neighbour vertical scale
 * maps the captured rows to the requested output rows before returning.
 */
class FrameProcessor(
    private val width: Int,
    private val captureHeight: Int,
    private val outputHeight: Int = captureHeight
) {
    // Intermediate RGBA→RGB buffer at capture size
    private val captureRgb = ByteArray(width * captureHeight * 3)

    // Final output buffer — reuses captureRgb when no scaling is needed
    private val outputRgb: ByteArray =
        if (captureHeight == outputHeight) captureRgb else ByteArray(width * outputHeight * 3)

    // Pre-computed row map: outputRow → captureRow  (built once, used on every frame)
    private val rowMap: IntArray = IntArray(outputHeight) { outY ->
        (outY.toLong() * captureHeight / outputHeight).toInt().coerceIn(0, captureHeight - 1)
    }

    // Reused scratch buffers for bulk reads out of the GPU/ION buffer — grown at most once, never
    // per frame. Bulk ByteBuffer.get(byte[]) copies are far cheaper than the old per-pixel absolute
    // get()s and, critically, shorten how long the CPU holds the Mali gralloc mapping (the
    // mali_gralloc_ion_sync window seen in logcat). rowBuf holds one source row (padded fallback);
    // frameBuf holds the whole frame on the contiguous fast path.
    private var rowBuf = ByteArray(width * 4)
    private var frameBuf = ByteArray(0)

    fun processImage(image: Image): ByteArray {
        val plane = image.planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride    // may include GPU alignment padding — read at runtime
        val pixelStride = plane.pixelStride

        val dst = captureRgb
        val w = width
        val h = captureHeight

        // RGBA → RGB into captureRgb (alpha stripped during compaction)
        if (pixelStride == 4 && rowStride == w * 4) {
            // Fully contiguous: one bulk copy out of the ION buffer, then drop every 4th byte.
            val total = w * h * 4
            if (frameBuf.size < total) frameBuf = ByteArray(total)
            val buf = frameBuf
            src.position(0)
            src.get(buf, 0, total)
            var s = 0
            var d = 0
            while (s < total) {
                dst[d] = buf[s]
                dst[d + 1] = buf[s + 1]
                dst[d + 2] = buf[s + 2]
                d += 3
                s += 4
            }
        } else {
            // Padded rows (the usual Mali case — width is rarely stride-aligned). Read only the bytes
            // the pixels occupy per row; the minOf cap stops the final row overrunning the buffer's
            // limit when rowStride padding extends past the last pixel.
            val readLen = minOf(rowStride, (w - 1) * pixelStride + 3)
            if (rowBuf.size < readLen) rowBuf = ByteArray(readLen)
            val row = rowBuf
            var d = 0
            for (y in 0 until h) {
                src.position(y * rowStride)
                src.get(row, 0, readLen)
                var base = 0
                for (x in 0 until w) {
                    dst[d++] = row[base]
                    dst[d++] = row[base + 1]
                    dst[d++] = row[base + 2]
                    base += pixelStride
                }
            }
        }

        if (captureHeight == outputHeight) return captureRgb

        // Nearest-neighbour vertical scale: copy the mapped source row for each output row
        val rowBytes = width * 3
        for (outY in 0 until outputHeight) {
            val srcOffset = rowMap[outY] * rowBytes
            val dstOffset = outY * rowBytes
            captureRgb.copyInto(outputRgb, dstOffset, srcOffset, srcOffset + rowBytes)
        }
        return outputRgb
    }
}
