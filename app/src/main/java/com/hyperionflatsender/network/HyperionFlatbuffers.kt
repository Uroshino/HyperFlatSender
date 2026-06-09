package com.hyperionflatsender.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.Color
import hyperionnet.Command
import hyperionnet.Image
import hyperionnet.ImageType
import hyperionnet.RawImage
import hyperionnet.Register
import hyperionnet.Reply
import hyperionnet.Request
import java.nio.ByteBuffer

class HyperionFlatbuffers {

    // 256 KB covers the largest realistic frame: 4× multiplier at 316×172 = 163 KB payload.
    // FlatBufferBuilder auto-grows if exceeded, but pre-allocating avoids per-frame heap churn.
    private val builder = FlatBufferBuilder(256 * 1024)

    fun buildRegisterMessage(origin: String, priority: Int): ByteArray {
        builder.clear()
        val originOffset = builder.createString(origin)
        val registerOffset = Register.createRegister(builder, originOffset, priority)
        val requestOffset = Request.createRequest(builder, Command.Register, registerOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        return frameMessage(builder.dataBuffer())
    }

    /**
     * A `Color` command — sets every LED to a single [rgb] colour (packed 0xRRGGBB, matching
     * Hyperion's `qRgb` decode). Used for the solid calibration patterns. [duration] is the hold
     * time in ms; -1 leaves it to Hyperion (the calibration screen keeps it alive by re-sending).
     */
    fun buildColorMessage(rgb: Int, duration: Int = -1): ByteArray {
        builder.clear()
        val colorOffset = Color.createColor(builder, rgb and 0xFFFFFF, duration)
        val requestOffset = Request.createRequest(builder, Command.Color, colorOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        return frameMessage(builder.dataBuffer())
    }

    fun buildImageMessage(rgbData: ByteArray, width: Int, height: Int): ByteArray {
        builder.clear()
        val dataOffset = builder.createByteVector(rgbData)
        val rawImageOffset = RawImage.createRawImage(builder, dataOffset, width, height)
        val imageOffset = Image.createImage(builder, ImageType.RawImage, rawImageOffset)
        val requestOffset = Request.createRequest(builder, Command.Image, imageOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        return frameMessage(builder.dataBuffer())
    }

    fun parseReply(data: ByteArray): Reply? = try {
        Reply.getRootAsReply(ByteBuffer.wrap(data))
    } catch (_: Exception) {
        null
    }

    private fun frameMessage(buf: ByteBuffer): ByteArray {
        val len = buf.remaining()
        // ONE allocation: [4-byte big-endian length prefix][payload]. The FlatBuffer payload is
        // read straight out of the builder's buffer into the tail — no separate `payload` array,
        // no ByteBuffer.wrap. Previously this allocated TWO ~payload-sized arrays per frame, which
        // at frame rate was the bulk of the LOS/GC churn seen in logcat.
        val framed = ByteArray(4 + len)
        framed[0] = (len ushr 24).toByte()
        framed[1] = (len ushr 16).toByte()
        framed[2] = (len ushr 8).toByte()
        framed[3] = len.toByte()
        buf.get(framed, 4, len)
        return framed
    }
}
