package com.itsme.amkush.decoder

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.itsme.amkush.AppState
import com.itsme.amkush.CameraState
import com.itsme.amkush.DecoderLauncher
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
 *     convert YUV_420_888 → NV21 and store the result via AppState.putFrame()
 *     so the camera hooks can splice it into the target's camera pipeline.
 *   • FPS throttle — skips frames that arrive faster than the app's requested FPS.
 *   • Exponential back-off reconnection — automatically retries on stream errors
 *     with delays of 1 s, 2 s, 4 s … up to 30 s, for up to 10 attempts.
 *
 * Supported sources: RTSP, RTMP, HLS (.m3u8), HTTP live, UDP, SRT, RTP.
 */
object LibVlcStreamer {

    // ── Active player resources ───────────────────────────────────────────────
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null

    // ── Reconnection state ────────────────────────────────────────────────────
    private var reconnectThread: HandlerThread? = null
    private var reconnectHandler: Handler? = null
    private var reconnectAttempts = 0
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val MAX_BACKOFF_MS         = 30_000L

    private var lastContext: Context? = null
    private var lastUrl: String? = null

    // ── Output dimensions (set from each decoded frame) ───────────────────────
    @Volatile var outputWidth: Int = 0
        private set
    @Volatile var outputHeight: Int = 0
        private set

    // ── FPS throttle ──────────────────────────────────────────────────────────
    @Volatile private var lastFrameDeliveryMs = 0L

    @Volatile var isRunning: Boolean = false
        private set

    // ──────────────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────────────

    fun startStream(context: Context, url: String) {
        lastContext = context
        lastUrl     = url
        reconnectAttempts = 0
        ensureReconnectThread()
        stop()
        startInternal(context, url)
    }

    fun stop() {
        lastUrl = null           // cancel any pending reconnect
        lastContext = null
        stopPlayer()
        reconnectHandler?.removeCallbacksAndMessages(null)
        reconnectThread?.quitSafely()
        reconnectThread = null
        reconnectHandler = null
        isRunning = false
        Logger.d("LibVlcStreamer stopped")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun ensureReconnectThread() {
        if (reconnectThread?.isAlive == true) return
        reconnectThread = HandlerThread("LibVlcReconnect").also { it.start() }
        reconnectHandler = Handler(reconnectThread!!.looper)
    }

    private fun startInternal(context: Context, url: String) {
        try {
            val w = CameraState.currentWidth.takeIf  { it > 0 } ?: 640
            val h = CameraState.currentHeight.takeIf { it > 0 } ?: 480

            // Frame reader thread
            readerThread = HandlerThread("LibVlcReader").also { it.start() }
            val handler = Handler(readerThread!!.looper)

            // ImageReader captures decoded frames — 5 slots to avoid frame-drop under load
            imageReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 5)
            imageReader!!.setOnImageAvailableListener({ reader ->

                // ── FPS throttle ──────────────────────────────────────────────
                val targetFps     = CameraState.requestedFps.coerceIn(1, 120)
                val minIntervalMs = 1000L / targetFps
                val now           = System.currentTimeMillis()
                if (now - lastFrameDeliveryMs < minIntervalMs) {
                    // Too soon — drop this frame to stay within the app's FPS budget
                    try { reader.acquireLatestImage()?.close() } catch (_: Throwable) {}
                    return@setOnImageAvailableListener
                }
                lastFrameDeliveryMs = now

                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // Capture output dimensions on every frame (stream can change)
                    outputWidth  = image.width
                    outputHeight = image.height

                    // Store frame; do NOT update CameraState here — that reflects
                    // the TARGET APP's camera, not VLC's decode size.
                    AppState.putFrame(strideAwareNv21(image), image.width, image.height)
                } catch (e: Throwable) {
                    Logger.e("LibVlcStreamer frame convert error", e)
                } finally {
                    image.close()
                }
            }, handler)

            // Build LibVLC with headless, low-latency options
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

            // ── MediaPlayer event listener — drives reconnection ──────────────
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EncounteredError -> {
                        Logger.e("LibVlcStreamer: VLC encountered error")
                        isRunning = false
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.EndReached -> {
                        Logger.d("LibVlcStreamer: stream ended (live stream treat as error)")
                        isRunning = false
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.Playing -> {
                        Logger.d("LibVlcStreamer: stream playing — reconnect counter reset")
                        reconnectAttempts = 0
                    }
                }
            }

            // Attach ImageReader surface as VLC video output
            // Fix: pass null SurfaceHolder — newer LibVLC requires the two-arg overload
            val vout = player.vlcVout
            vout.setVideoSurface(imageReader!!.surface, null)
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
            Logger.e("LibVlcStreamer.startInternal failed", e)
            stopPlayer()
            scheduleReconnect()
        }
    }

    /**
     * Tear down the active player without resetting reconnect state.
     */
    private fun stopPlayer() {
        try {
            mediaPlayer?.setEventListener(null)
            mediaPlayer?.stop()
            mediaPlayer?.vlcVout?.detachViews()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.stopPlayer media error", e)
        }
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.stopPlayer imageReader error", e)
        }
        try {
            readerThread?.quitSafely()
            readerThread = null
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.stopPlayer thread error", e)
        }
        try {
            libVlc?.release()
            libVlc = null
        } catch (e: Throwable) {
            Logger.e("LibVlcStreamer.stopPlayer vlc error", e)
        }
    }

    /**
     * Schedule a reconnection attempt with exponential back-off.
     * Doubles the delay on each attempt: 1 s, 2 s, 4 s … capped at 30 s.
     * After MAX_RECONNECT_ATTEMPTS the streamer permanently gives up —
     * injection must be restarted from the UI.
     */
    private fun scheduleReconnect() {
        val ctx = lastContext ?: return   // stop() was called intentionally
        val url = lastUrl     ?: return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Logger.e("LibVlcStreamer: max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — giving up")
            // Keep DecoderLauncher.launched = true so we don't restart in an infinite loop.
            // User must restart the InjectionService to try again.
            return
        }

        val delayMs = minOf(1_000L shl reconnectAttempts, MAX_BACKOFF_MS)
        reconnectAttempts++
        Logger.d("LibVlcStreamer: reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")

        ensureReconnectThread()
        reconnectHandler?.postDelayed({
            if (lastUrl == null) return@postDelayed  // stop() cancelled us
            Logger.d("LibVlcStreamer: executing reconnect attempt $reconnectAttempts")
            stopPlayer()
            startInternal(ctx, url)
        }, delayMs)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  NV21 conversion — stride-aware
    // ──────────────────────────────────────────────────────────────────────────

    private fun strideAwareNv21(image: android.media.Image): ByteArray {
        val w      = image.width
        val h      = image.height
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
        val uBuf     = uPlane.buffer
        val vBuf     = vPlane.buffer
        val vuStart  = w * h
        val uvRows   = h / 2
        val uvCols   = w / 2

        for (row in 0 until uvRows) {
            for (col in 0 until uvCols) {
                val uvPos  = row * uvRowStride + col * uvPixelStride
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
