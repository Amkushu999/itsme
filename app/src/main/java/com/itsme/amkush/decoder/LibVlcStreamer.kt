package com.itsme.amkush.decoder

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.itsme.amkush.AppState
import com.itsme.amkush.CameraState
import com.itsme.amkush.utils.Logger
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Headless LibVLC stream decoder that runs entirely inside the hooked target
 * app's process space.
 *
 * Strategy:
 *   • Creates a LibVLC MediaPlayer with audio disabled.
 *   • Attaches an ImageReader Surface as the video output target; VLC renders
 *     decoded frames into it via ANativeWindow.
 *   • onImageAvailable() fires for every decoded frame; we stride-safely
 *     convert YUV_420_888 → NV21 and store the result in AppState.dataBuffer
 *     so the camera hooks can splice it into the target's camera pipeline.
 *
 * Supported sources: RTSP, RTMP, HLS (.m3u8), HTTP live, UDP, SRT, RTP.
 */
object LibVlcStreamer {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    @Volatile var isRunning: Boolean = false
        private set

    // ──────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────

    fun startStream(context: Context, url: String) {
        stop()
        try {
            val w = CameraState.currentWidth.takeIf { it > 0 } ?: 640
            val h = CameraState.currentHeight.takeIf { it > 0 } ?: 480

            // Frame reader thread
            readerThread = HandlerThread("LibVlcReader").also { it.start() }
            val handler = Handler(readerThread!!.looper)

            // ImageReader captures decoded frames via its Surface
            imageReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // Update CameraState dimensions if this is the first frame
                    if (CameraState.currentWidth != image.width || CameraState.currentHeight != image.height) {
                        CameraState.currentWidth = image.width
                        CameraState.currentHeight = image.height
                        VideoDecoder.setTargetSize(image.width, image.height)
                    }
                    AppState.dataBuffer = strideAwareNv21(image)
                } catch (e: Throwable) {
                    Logger.e("LibVlcStreamer frame convert error", e)
                } finally {
                    image.close()
                }
            }, handler)

            // Build LibVLC with headless options
            val args = arrayListOf(
                "--no-audio",
                "--no-video-title",
                "--network-caching=300",
                "--live-caching=100",
                "--no-stats",
                "--no-snapshot-preview"
            )
            libVlc = LibVLC(context, args)
            val player = MediaPlayer(libVlc!!)
            mediaPlayer = player

            // Attach ImageReader surface as VLC video output
            val vout = player.vlcVout
            vout.setVideoSurface(imageReader!!.surface)
            vout.setWindowSize(w, h)
            vout.attachViews()

            // Set media and play
            val media = Media(libVlc!!, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            player.media = media
            media.release()
            player.play()

            isRunning = true
            Logger.d("LibVlcStreamer started: $url (${w}x${h})")
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.startStream failed", e)
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.vlcVout?.detachViews()
            mediaPlayer?.release()
            mediaPlayer = null
            imageReader?.close()
            imageReader = null
            readerThread?.quitSafely()
            readerThread = null
            libVlc?.release()
            libVlc = null
            isRunning = false
            Logger.d("LibVlcStreamer stopped")
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.stop error", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  NV21 conversion — stride-aware (same algorithm as VideoDecoder)
    // ──────────────────────────────────────────────────────────────

    private fun strideAwareNv21(image: android.media.Image): ByteArray {
        val w = image.width
        val h = image.height
        val planes = image.planes

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(w * h + w * (h / 2))

        // Copy Y — strip row padding
        val yBuf = yPlane.buffer
        for (row in 0 until h) {
            val srcPos = row * yRowStride
            if (srcPos + w > yBuf.limit()) break
            yBuf.position(srcPos)
            yBuf.get(nv21, row * w, w)
        }

        // Interleave V then U (NV21 = Y…VU…)
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val vuStart = w * h
        val uvRows  = h / 2
        val uvCols  = w / 2

        for (row in 0 until uvRows) {
            for (col in 0 until uvCols) {
                val uvPos = row * uvRowStride + col * uvPixelStride
                val dstPos = vuStart + row * w + col * 2
                if (uvPos < vBuf.limit() && dstPos + 1 < nv21.size) {
                    nv21[dstPos]     = vBuf.get(uvPos)   // V
                    nv21[dstPos + 1] = uBuf.get(uvPos)   // U
                }
            }
        }
        return nv21
    }
}
