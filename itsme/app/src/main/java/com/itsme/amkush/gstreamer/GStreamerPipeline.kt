package com.itsme.amkush.gstreamer

import android.content.Context
import android.view.Surface
import com.itsme.amkush.utils.Logger

/**
 * GStreamerPipeline
 *
 * JNI wrapper around the native GStreamer pipeline in libfacegate_gst.so.
 *
 * Pipeline topology (built inside C++):
 *
 *   For RTSP / RTP:
 *     rtspsrc location=$URL protocols=tcp
 *       → rtpjitterbuffer → queue
 *       → decodebin
 *       → videoconvert
 *       → video/x-raw,format=I420
 *       → appsink [delivers frames to Java via onFrameAvailable callback]
 *
 *   For HLS / HTTP(S):
 *     souphttpsrc location=$URL
 *       → hlsdemux (if .m3u8) or direct
 *       → queue → decodebin
 *       → videoconvert
 *       → video/x-raw,format=I420
 *       → appsink
 *
 * The single appsink delivers decoded frames (I420, native resolution) to
 * [SurfaceRouter] which then scales and pushes into each registered Surface
 * via ImageWriter at the per-surface resolution/format.
 *
 * All JNI symbols follow the package path:
 *   Java_com_itsme_amkush_gstreamer_GStreamerPipeline_native*
 */
object GStreamerPipeline {

    private const val TAG = "GStreamerPipeline"
    private var loaded = false
    private var pipelineHandle = 0L     // native PipelineCtx*

    /** True when a pipeline is currently running (handle is non-zero). */
    val pipelineRunning: Boolean get() = pipelineHandle != 0L

    // ── Library loading ──────────────────────────────────────────────────────

    fun load(): Boolean {
        if (loaded) return true
        return try {
            // GStreamer Android SDK bootstraps itself via this library
            System.loadLibrary("gstreamer_android")
            // Our own JNI layer
            System.loadLibrary("facegate_gst")
            loaded = true
            Logger.d("$TAG native libs loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            Logger.e("$TAG native lib load failed — ensure GStreamer Android SDK is present", e)
            false
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * One-time initialisation: must be called before any pipeline is created.
     * [context] is the module application's Context.
     */
    fun init(context: Context): Boolean {
        if (!load()) return false
        return try {
            nativeInit(context)
            true
        } catch (e: Throwable) {
            Logger.e("$TAG nativeInit failed", e)
            false
        }
    }

    /**
     * Create and start the decoding pipeline.
     *
     * [url] — the stream URL (rtsp://, http://, https://, rtsps://, etc.)
     * [frameCallback] — called from the GStreamer streaming thread each time a
     *                   decoded frame is available; must be very fast.
     *
     * Returns true if the pipeline was successfully started.
     */
    fun startPipeline(url: String, frameCallback: FrameCallback): Boolean {
        if (!loaded) { Logger.e("$TAG not loaded"); return false }
        stopPipeline()
        return try {
            pipelineHandle = nativeCreatePipeline(url, frameCallback)
            pipelineHandle != 0L
        } catch (e: Throwable) {
            Logger.e("$TAG startPipeline failed", e)
            false
        }
    }

    fun stopPipeline() {
        if (pipelineHandle != 0L) {
            try { nativeDestroyPipeline(pipelineHandle) } catch (_: Throwable) {}
            pipelineHandle = 0L
        }
    }

    fun cleanup() {
        stopPipeline()
        if (loaded) {
            try { nativeCleanup() } catch (_: Throwable) {}
        }
    }

    // ── Callback interface ───────────────────────────────────────────────────

    /**
     * Called by the native layer whenever a new I420 frame is ready.
     * Invoked on the GStreamer streaming thread — keep it non-blocking.
     *
     * @param data    Raw I420 bytes: Y-plane (w×h) followed by U (w/2×h/2)
     *                followed by V (w/2×h/2).
     * @param width   Frame width in pixels.
     * @param height  Frame height in pixels.
     * @param pts     Presentation timestamp in nanoseconds.
     */
    interface FrameCallback {
        fun onFrameAvailable(data: ByteArray, width: Int, height: Int, pts: Long)
    }

    // ── Native declarations ──────────────────────────────────────────────────

    /**
     * Initialise GStreamer from the Android JNI environment.
     * Must be called once before any pipeline creation.
     */
    @JvmStatic private external fun nativeInit(context: Context)

    /**
     * Create the decoding pipeline for [url].  Returns an opaque native pointer
     * (cast to Long) that identifies this pipeline, or 0 on failure.
     * [callback] receives decoded I420 frames.
     */
    @JvmStatic private external fun nativeCreatePipeline(url: String, callback: FrameCallback): Long

    /** Free all pipeline resources. The handle is invalid after this call. */
    @JvmStatic private external fun nativeDestroyPipeline(handle: Long)

    /** Shut down the GStreamer main loop and release all global state. */
    @JvmStatic private external fun nativeCleanup()
}
