package com.itsme.amkush

import com.itsme.amkush.ipc.RemoteConfig
import com.itsme.amkush.utils.Logger

/**
 * DecoderLauncher — GStreamer architecture stub
 *
 * In the GStreamer architecture the stream decoder runs entirely inside the
 * MODULE PROCESS (InjectionService + GStreamerPipeline) — NOT inside the
 * hooked target-app process.
 *
 * This object is kept for API-compatibility only.  Call sites in MainHook
 * and the hook files no longer call ensureLaunched(); those paths now work
 * through GStreamerSurfaceRouter → ISurfaceInjector Binder → InjectionService.
 *
 * The InjectionService starts the GStreamer pipeline automatically when:
 *   a) It receives the stream URL via onStartCommand (InjectionService.start())
 *   b) It receives the first set of surfaces via ISurfaceInjector.registerSurfaces()
 *
 * Nothing here reaches FfmpegStreamer anymore because FfmpegStreamer running
 * inside a foreign app process caused UnsatisfiedLinkError when GStreamer /
 * FFmpeg Kit native libs are not present in the target app's lib directory.
 */
object DecoderLauncher {

    /**
     * No-op in the GStreamer architecture.
     * GStreamer is started by InjectionService in the module process.
     */
    fun ensureLaunched() {
        Logger.d("DecoderLauncher.ensureLaunched() — GStreamer runs in module process, no action needed")
    }

    /**
     * No-op in the GStreamer architecture.
     * URL changes are handled by broadcasting to InjectionService which restarts
     * the GStreamer pipeline and re-registers all surfaces.
     */
    fun restart(streamUrl: String?, mediaUri: String?) {
        Logger.d("DecoderLauncher.restart($streamUrl) — signal handled by InjectionService")
    }

    /** No-op — cleanup is handled by InjectionService.onDestroy(). */
    fun stop() {
        Logger.d("DecoderLauncher.stop() — no-op in GStreamer architecture")
    }

    /** No-op. */
    fun reset() {}
}
