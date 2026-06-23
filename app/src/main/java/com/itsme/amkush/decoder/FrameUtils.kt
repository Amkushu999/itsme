package com.itsme.amkush.decoder

/**
 * Shared frame-buffer utilities used by LibVlcStreamer, VideoDecoder,
 * and all camera hooks.
 */
object FrameUtils {

    /**
     * Nearest-neighbor NV21 scaler.
     * Returns [src] unchanged if the dimensions already match (no allocation).
     * Returns [src] unchanged if the buffer is too small to be valid (guards against crashes).
     */
    fun scaleNv21(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (srcW == dstW && srcH == dstH) return src
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return src
        val expectedSrcSize = srcW * srcH * 3 / 2
        if (src.size < expectedSrcSize) return src

        val dst = ByteArray(dstW * dstH * 3 / 2)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH

        // Scale Y plane
        for (dstY in 0 until dstH) {
            val srcY = (dstY * yRatio).toInt().coerceIn(0, srcH - 1)
            val srcRowOffset = srcY * srcW
            val dstRowOffset = dstY * dstW
            for (dstX in 0 until dstW) {
                val srcX = (dstX * xRatio).toInt().coerceIn(0, srcW - 1)
                dst[dstRowOffset + dstX] = src[srcRowOffset + srcX]
            }
        }

        // Scale UV plane — NV21 layout is V then U interleaved
        val srcUvBase = srcW * srcH
        val dstUvBase = dstW * dstH
        val srcUvH    = srcH / 2
        val srcUvW    = srcW / 2

        for (dstY in 0 until dstH / 2) {
            val srcY = (dstY * yRatio).toInt().coerceIn(0, srcUvH - 1)
            for (dstX in 0 until dstW / 2) {
                val srcX   = (dstX * xRatio).toInt().coerceIn(0, srcUvW - 1)
                val srcIdx = srcUvBase + srcY * srcW + srcX * 2
                val dstIdx = dstUvBase + dstY * dstW + dstX * 2
                if (srcIdx + 1 < src.size && dstIdx + 1 < dst.size) {
                    dst[dstIdx]     = src[srcIdx]       // V
                    dst[dstIdx + 1] = src[srcIdx + 1]   // U
                }
            }
        }
        return dst
    }

    /**
     * Convert NV21 → YV12.
     * NV21: Y…(VU interleaved)
     * YV12: Y plane | V plane | U plane  (all separate, no interleaving)
     */
    fun nv21ToYv12(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val ySize  = w * h
        val uvSize = ySize / 4
        val yv12   = ByteArray(ySize + uvSize * 2)

        // Copy Y plane
        System.arraycopy(nv21, 0, yv12, 0, minOf(ySize, nv21.size))

        // De-interleave VU → separate V then U planes (YV12 order)
        val vStart = ySize
        val uStart = ySize + uvSize
        var uvIn   = ySize  // NV21 chroma start
        var i      = 0
        while (i < uvSize && uvIn + 1 < nv21.size) {
            yv12[vStart + i] = nv21[uvIn]       // V
            yv12[uStart + i] = nv21[uvIn + 1]   // U
            i++
            uvIn += 2
        }
        return yv12
    }

    /**
     * Infer the width and height of an NV21 buffer from its byte count and a
     * known-good hint (e.g. the dimensions LibVlcStreamer or VideoDecoder reported).
     * Returns null if the hint is inconsistent with the buffer size.
     */
    fun validateDimensions(buf: ByteArray, hintW: Int, hintH: Int): Pair<Int, Int>? {
        if (hintW <= 0 || hintH <= 0) return null
        val expected = hintW * hintH * 3 / 2
        return if (buf.size >= expected) Pair(hintW, hintH) else null
    }
}
