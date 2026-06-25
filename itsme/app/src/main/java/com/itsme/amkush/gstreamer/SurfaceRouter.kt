package com.itsme.amkush.gstreamer

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ImageWriter
import android.view.Surface
import com.itsme.amkush.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SurfaceRouter
 *
 * Runs inside the MODULE process (inside InjectionService).
 *
 * Responsibilities:
 *   1. Accept Surface objects that arrive from the hooked target-app process
 *      via the ISurfaceInjector Binder.
 *   2. Attach an ImageWriter to each Surface with the resolution and format
 *      the surface's consumer (the target app's ImageReader) expects.
 *   3. Receive decoded I420 frames from GStreamerPipeline.FrameCallback and
 *      distribute them — scaled and format-converted — to every registered
 *      Surface through its ImageWriter.
 *
 * Threading:
 *   - registerSurface() is called from the Binder thread.
 *   - onFrameAvailable() is called from the GStreamer streaming thread.
 *   - All ConcurrentHashMap accesses are thread-safe; ImageWriter itself is
 *     thread-safe at the queueInputImage() level.
 */
object SurfaceRouter : GStreamerPipeline.FrameCallback {

    private const val TAG = "SurfaceRouter"

    data class SurfaceEntry(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val format: Int,          // ImageFormat constant
        val sessionId: String,
        val writer: ImageWriter   // one writer per surface
    )

    // sessionId → list of entries
    private val sessions: ConcurrentHashMap<String, MutableList<SurfaceEntry>> = ConcurrentHashMap()

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Called from InjectionService when the Xposed hook delivers surfaces.
     * Creates an ImageWriter for every surface and stores it for frame routing.
     */
    fun registerSession(
        sessionId: String,
        surfaces: List<Surface>,
        widths: IntArray,
        heights: IntArray,
        formats: IntArray
    ) {
        unregisterSession(sessionId)

        val entries = mutableListOf<SurfaceEntry>()
        for (i in surfaces.indices) {
            val surface = surfaces[i]
            val w = widths.getOrElse(i) { 640 }
            val h = heights.getOrElse(i) { 480 }
            val fmt = formats.getOrElse(i) { ImageFormat.YUV_420_888 }
            try {
                // maxImages=2 gives double-buffering headroom without stalling
                val writer = ImageWriter.newInstance(surface, 2)
                entries += SurfaceEntry(surface, w, h, fmt, sessionId, writer)
                Logger.d("$TAG registered surface[$i] ${w}x${h} fmt=$fmt session=$sessionId")
            } catch (e: Throwable) {
                Logger.e("$TAG ImageWriter.newInstance failed for surface[$i]", e)
            }
        }
        if (entries.isNotEmpty()) {
            sessions[sessionId] = entries
        }
    }

    fun unregisterSession(sessionId: String) {
        sessions.remove(sessionId)?.forEach { entry ->
            try { entry.writer.close() } catch (_: Throwable) {}
            Logger.d("$TAG closed writer for session=$sessionId")
        }
    }

    fun unregisterAll() {
        val keys = sessions.keys().toList()
        keys.forEach { unregisterSession(it) }
    }

    // ── Frame distribution (called from GStreamer streaming thread) ───────────

    /**
     * Called by [GStreamerPipeline] each time a new decoded I420 frame arrives.
     * Scales and converts for every registered surface, then submits via ImageWriter.
     *
     * Keep this method FAST — it runs on the GStreamer streaming thread.
     */
    override fun onFrameAvailable(data: ByteArray, width: Int, height: Int, pts: Long) {
        if (sessions.isEmpty()) return

        // Convert I420 → NV21 once (in-place, same array) for the source frame.
        // NV21 is the canonical internal format; per-surface conversions follow.
        val nv21Source = i420ToNv21(data, width, height)

        sessions.values.forEach { entries ->
            entries.forEach { entry ->
                try {
                    pushFrameToSurface(nv21Source, width, height, entry)
                } catch (e: Throwable) {
                    Logger.e("$TAG pushFrameToSurface failed (session=${entry.sessionId})", e)
                }
            }
        }
    }

    // ── Per-surface frame write ───────────────────────────────────────────────

    private fun pushFrameToSurface(
        nv21Src: ByteArray, srcW: Int, srcH: Int,
        entry: SurfaceEntry
    ) {
        val dstW = entry.width
        val dstH = entry.height

        // Scale NV21 to the surface's expected resolution
        val scaled = scaleNv21(nv21Src, srcW, srcH, dstW, dstH)

        when (entry.format) {
            ImageFormat.YUV_420_888,
            ImageFormat.NV21,
            ImageFormat.NV16 -> writeYuvImage(entry.writer, scaled, dstW, dstH, entry.format)

            ImageFormat.JPEG -> {
                val jpeg = nv21ToJpeg(scaled, dstW, dstH) ?: return
                writeJpegImage(entry.writer, jpeg, dstW, dstH)
            }

            ImageFormat.FLEX_RGBA_8888,
            ImageFormat.FLEX_RGB_888 -> {
                val rgba = nv21ToRgba(scaled, dstW, dstH)
                writeRgbaImage(entry.writer, rgba, dstW, dstH)
            }

            else -> {
                // Best-effort: write NV21 bytes to first plane
                writeRawToPlane0(entry.writer, scaled, dstW, dstH)
            }
        }
    }

    // ── ImageWriter plane writes ──────────────────────────────────────────────

    private fun writeYuvImage(writer: ImageWriter, nv21: ByteArray, w: Int, h: Int, format: Int) {
        val image = writer.dequeueInputImage() ?: return
        try {
            val planes = image.planes
            if (planes.size < 2) return

            val ySize = w * h
            val uvSize = ySize / 2

            // Y plane
            val yBuf = planes[0].buffer
            yBuf.clear()
            val yLen = minOf(ySize, nv21.size, yBuf.remaining())
            yBuf.put(nv21, 0, yLen)

            // UV / VU plane (NV21 has VU interleaved, NV12 has UV)
            val uvBuf = planes[1].buffer
            uvBuf.clear()
            val uvLen = minOf(uvSize, nv21.size - ySize, uvBuf.remaining())
            if (uvLen > 0) uvBuf.put(nv21, ySize, uvLen)

            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
        } catch (e: Throwable) {
            Logger.e("$TAG writeYuvImage failed", e)
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun writeJpegImage(writer: ImageWriter, jpeg: ByteArray, w: Int, h: Int) {
        val image = writer.dequeueInputImage() ?: return
        try {
            val buf = image.planes[0].buffer
            buf.clear()
            val len = minOf(jpeg.size, buf.remaining())
            buf.put(jpeg, 0, len)
            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
        } catch (e: Throwable) {
            Logger.e("$TAG writeJpegImage failed", e)
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun writeRgbaImage(writer: ImageWriter, rgba: ByteArray, w: Int, h: Int) {
        val image = writer.dequeueInputImage() ?: return
        try {
            val buf = image.planes[0].buffer
            buf.clear()
            val len = minOf(rgba.size, buf.remaining())
            buf.put(rgba, 0, len)
            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
        } catch (e: Throwable) {
            Logger.e("$TAG writeRgbaImage failed", e)
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun writeRawToPlane0(writer: ImageWriter, data: ByteArray, w: Int, h: Int) {
        val image = writer.dequeueInputImage() ?: return
        try {
            val buf = image.planes[0].buffer
            buf.clear()
            val len = minOf(data.size, buf.remaining())
            buf.put(data, 0, len)
            image.timestamp = System.nanoTime()
            writer.queueInputImage(image)
        } catch (e: Throwable) {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    // ── Pixel-format utilities ────────────────────────────────────────────────

    /**
     * In-place I420 → NV21 conversion.
     * I420: Y(w×h) + U(w/2×h/2) + V(w/2×h/2)
     * NV21: Y(w×h) + VU interleaved(w/2×h/2 × 2)
     */
    private fun i420ToNv21(i420: ByteArray, w: Int, h: Int): ByteArray {
        val ySize   = w * h
        val uvSize  = ySize / 4  // per-plane size for U and V
        val result  = ByteArray(ySize + uvSize * 2)
        System.arraycopy(i420, 0, result, 0, ySize)          // copy Y
        val uOff = ySize
        val vOff = ySize + uvSize
        for (i in 0 until uvSize) {                           // interleave VU
            result[ySize + i * 2]     = i420[vOff + i]       // V
            result[ySize + i * 2 + 1] = i420[uOff + i]       // U
        }
        return result
    }

    /**
     * Nearest-neighbour NV21 scaler.
     * Fast enough for real-time use at typical camera resolutions.
     */
    private fun scaleNv21(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (srcW == dstW && srcH == dstH) return src
        val dst   = ByteArray(dstW * dstH * 3 / 2)
        val xRatio = (srcW shl 16) / dstW
        val yRatio = (srcH shl 16) / dstH

        // Scale Y plane
        for (y in 0 until dstH) {
            val srcY = ((y * yRatio) shr 16).coerceIn(0, srcH - 1)
            val dstRow = y * dstW
            val srcRow = srcY * srcW
            for (x in 0 until dstW) {
                val srcX = ((x * xRatio) shr 16).coerceIn(0, srcW - 1)
                dst[dstRow + x] = src[srcRow + srcX]
            }
        }

        // Scale UV plane (NV21: VU interleaved, half resolution)
        val srcUvOff = srcW * srcH
        val dstUvOff = dstW * dstH
        val dstUvW = dstW / 2
        val dstUvH = dstH / 2
        val srcUvW = srcW / 2

        for (y in 0 until dstUvH) {
            val srcY = ((y * yRatio) shr 16).coerceIn(0, srcH / 2 - 1)
            for (x in 0 until dstUvW) {
                val srcX = ((x * xRatio) shr 16).coerceIn(0, srcW / 2 - 1)
                val dstIdx = dstUvOff + (y * dstUvW + x) * 2
                val srcIdx = srcUvOff + (srcY * srcUvW + srcX) * 2
                dst[dstIdx]     = src[srcIdx]       // V
                dst[dstIdx + 1] = src[srcIdx + 1]   // U
            }
        }
        return dst
    }

    private fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int): ByteArray? {
        return try {
            val yuv  = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out  = ByteArrayOutputStream(nv21.size / 4)
            yuv.compressToJpeg(Rect(0, 0, w, h), 88, out)
            out.toByteArray()
        } catch (e: Throwable) {
            Logger.e("$TAG nv21ToJpeg failed", e)
            null
        }
    }

    /**
     * NV21 → RGBA conversion (software, suitable for preview surfaces).
     * Result is tightly packed: 4 bytes per pixel, R G B A.
     */
    private fun nv21ToRgba(nv21: ByteArray, w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        val ySize = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val y  = (nv21[j * w + i].toInt() and 0xFF) - 16
                val uvIdx = ySize + (j / 2) * w + (i and 1.inv())
                val v  = (nv21[uvIdx].toInt() and 0xFF) - 128
                val u  = (nv21[uvIdx + 1].toInt() and 0xFF) - 128
                val r  = (1.164f * y + 1.596f * v).toInt().coerceIn(0, 255)
                val g  = (1.164f * y - 0.391f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b  = (1.164f * y + 2.018f * u).toInt().coerceIn(0, 255)
                val px = (j * w + i) * 4
                rgba[px]     = r.toByte()
                rgba[px + 1] = g.toByte()
                rgba[px + 2] = b.toByte()
                rgba[px + 3] = 0xFF.toByte()
            }
        }
        return rgba
    }
}
