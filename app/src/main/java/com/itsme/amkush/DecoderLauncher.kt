package com.itsme.amkush

  import com.itsme.amkush.decoder.FfmpegStreamer
  import com.itsme.amkush.ipc.RemoteConfig
  import com.itsme.amkush.utils.Logger

  /**
   * Decides which source to decode inside the hooked target-app process,
   * then drives [FfmpegStreamer].  Called from:
   *   - MainHook.hookApplication — immediately after isHookingActive = true
   *   - Camera hook first-frame path — handles apps already open at inject time
   *   - ConfigUpdateReceiver — for live URL changes without restarting the target app
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
                      Logger.d("DecoderLauncher: starting for local media: $mediaUri")
                      FfmpegStreamer.startMedia(mediaUri)
                      launched = true
                  }
                  !streamUrl.isNullOrEmpty() -> {
                      Logger.d("DecoderLauncher: starting for stream: $streamUrl")
                      FfmpegStreamer.startStream(streamUrl)
                      launched = true
                  }
                  else -> Logger.d("DecoderLauncher: no source configured yet")
              }
          }
      }

      /**
       * Immediately swap to a new URL — called by [ConfigUpdateReceiver] when
       * the user changes the stream URL while the target app is already running.
       * Stops the current decode session and starts a fresh one.
       */
      fun restart(streamUrl: String?, mediaUri: String?) {
          synchronized(this) {
              FfmpegStreamer.stop()
              launched = false
              when {
                  !mediaUri.isNullOrEmpty()  -> {
                      Logger.d("DecoderLauncher: restarting for media: $mediaUri")
                      FfmpegStreamer.startMedia(mediaUri!!)
                      launched = true
                  }
                  !streamUrl.isNullOrEmpty() -> {
                      Logger.d("DecoderLauncher: restarting for stream: $streamUrl")
                      FfmpegStreamer.startStream(streamUrl!!)
                      launched = true
                  }
                  else -> Logger.d("DecoderLauncher.restart: no URL supplied — staying stopped")
              }
          }
      }

      /**
       * Stop the active decoder and allow [ensureLaunched] to re-run
       * on the next camera frame.
       */
      fun stop() {
          synchronized(this) {
              FfmpegStreamer.stop()
              launched = false
          }
      }

      /**
       * Reset without stopping — use when the target process is re-initialised
       * or a decoder has recovered.  The next [ensureLaunched] call restarts
       * with fresh RemoteConfig.
       */
      fun reset() {
          launched = false
      }
  }
  