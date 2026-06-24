package com.itsme.amkush

  import com.itsme.amkush.decoder.FfmpegStreamer
  import com.itsme.amkush.ipc.RemoteConfig
  import com.itsme.amkush.utils.Logger

  /**
   * Decides which source to decode inside the hooked target-app process,
   * then starts [FfmpegStreamer]. Called from:
   *   - MainHook.hookApplication — immediately after isHookingActive = true
   *   - Camera hook first-frame path — handles apps already running at inject time
   *
   * [FfmpegStreamer] replaces both the former LibVlcStreamer (network) and
   * VideoDecoder (local file) with a single FFmpeg-kit engine: guaranteed
   * hardware-accelerated decode, direct NV21 pipe delivery, automatic SW fallback.
   */
  object DecoderLauncher {

      @Volatile private var launched = false

      /**
       * Idempotent — safe to call on every camera frame.
       * Reads RemoteConfig once, then never again until [reset] or [stop].
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
                      Logger.d("DecoderLauncher: starting FfmpegStreamer for local media: $mediaUri")
                      FfmpegStreamer.startMedia(mediaUri)
                      launched = true
                  }
                  !streamUrl.isNullOrEmpty() -> {
                      Logger.d("DecoderLauncher: starting FfmpegStreamer for stream: $streamUrl")
                      FfmpegStreamer.startStream(streamUrl)
                      launched = true
                  }
                  else -> {
                      Logger.d("DecoderLauncher: no source configured in RemoteConfig")
                  }
              }
          }
      }

      /**
       * Stop the active decoder and allow [ensureLaunched] to re-run
       * on the next camera frame. Call when InjectionService is stopped.
       */
      fun stop() {
          synchronized(this) {
              FfmpegStreamer.stop()
              launched = false
          }
      }

      /**
       * Reset without stopping — use when the target process is reinitialised
       * or a decoder has recovered. The next [ensureLaunched] call restarts
       * with fresh RemoteConfig.
       */
      fun reset() {
          launched = false
      }
  }
  