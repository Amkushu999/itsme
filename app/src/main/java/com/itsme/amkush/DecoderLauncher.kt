package com.itsme.amkush

import com.itsme.amkush.decoder.LibVlcStreamer
import com.itsme.amkush.decoder.VideoDecoder
import com.itsme.amkush.ipc.RemoteConfig
import com.itsme.amkush.utils.Logger

/**
 * Decides which decoder to run inside the hooked target-app process, then
 * launches it.  Called from:
 *   • MainHook.hookApplication — immediately after isHookingActive = true
 *   • Camera hook first-frame path — handles apps already running at inject time
 *
 * Reading RemoteConfig involves a cross-process ContentProvider call which is
 * slightly slow, so we guard with a @Volatile flag to avoid repeating it on
 * every camera frame.
 */
object DecoderLauncher {

    @Volatile private var launched = false

    /**
     * Idempotent — safe to call on every camera frame.
     * Reads RemoteConfig once, then never again until reset().
     */
    fun ensureLaunched() {
        if (launched) return
        val ctx = AppState.context ?: return
        synchronized(this) {
            if (launched) return

            if (!RemoteConfig.isInjectionActive(ctx)) {
                Logger.d("DecoderLauncher: injection not active yet")
                return
            }

            val mediaUri  = RemoteConfig.getMediaUri(ctx)
            val streamUrl = RemoteConfig.getStreamUrl(ctx)

            when {
                !mediaUri.isNullOrEmpty() -> {
                    Logger.d("DecoderLauncher: starting VideoDecoder for local media: $mediaUri")
                    VideoDecoder.startMedia(mediaUri)
                    launched = true
                }
                !streamUrl.isNullOrEmpty() -> {
                    Logger.d("DecoderLauncher: starting LibVlcStreamer for stream: $streamUrl")
                    LibVlcStreamer.startStream(ctx, streamUrl)
                    launched = true
                }
                else -> {
                    Logger.d("DecoderLauncher: no source configured in RemoteConfig")
                }
            }
        }
    }

    /**
     * Stop whichever decoder is running and allow ensureLaunched() to re-run.
     */
    fun stop() {
        synchronized(this) {
            VideoDecoder.stop()
            LibVlcStreamer.stop()
            launched = false
        }
    }

    /**
     * Reset without stopping — call when the target process is reinitialised,
     * or when a decoder signals it has recovered and wants to be re-evaluated.
     * Does NOT restart any decoder; the next ensureLaunched() call will do that.
     */
    fun reset() {
        launched = false
    }
}
