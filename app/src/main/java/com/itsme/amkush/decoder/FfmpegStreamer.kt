package com.itsme.amkush.decoder

  import android.os.Handler
  import android.os.HandlerThread
  import android.os.ParcelFileDescriptor
  import com.arthenica.ffmpegkit.FFmpegKit
  import com.arthenica.ffmpegkit.FFmpegKitConfig
  import com.arthenica.ffmpegkit.FFmpegSession
  import com.arthenica.ffmpegkit.Level
  import com.arthenica.ffmpegkit.ReturnCode
  import com.itsme.amkush.AppState
  import com.itsme.amkush.CameraState
  import com.itsme.amkush.utils.Logger
  import java.io.FileInputStream
  import java.io.IOException

  /**
   * High-performance FFmpeg-kit stream decoder — runs inside the hooked target-app process.
   *
   * Replaces both LibVlcStreamer (network streams) and VideoDecoder (local media).
   *
   * Architecture:
   *   - Creates a kernel pipe pair via ParcelFileDescriptor.createPipe().
   *   - Runs FFmpeg in-process (same JVM) writing raw NV21 frames to the write end.
   *   - A dedicated reader thread reads full frames from the read end and stores them
   *     via AppState.putFrame() — camera hooks splice them into the target pipeline.
   *   - Hardware decode attempted first via h264_mediacodec / hevc_mediacodec.
   *     On failure the engine automatically retries with software (libavcodec).
   *   - FFmpeg fps + scale + format filters handle per-target-resolution adaptation
   *     before the frame even reaches Kotlin — no extra CPU cost in the hook layer.
   *   - Exponential back-off reconnection: 1s to 2s to 4s capped at 30s, 10 attempts.
   *
   * Supported sources: RTSP, RTMP, HLS, HTTP, HTTPS, UDP, RTP, SRT, MMS, FTP,
   *                    local video files (MP4/MKV/AVI), static images (JPEG/PNG).
   */
  object FfmpegStreamer {

      private const val MAX_RECONNECT_ATTEMPTS = 10
      private const val MAX_BACKOFF_MS         = 30_000L

      @Volatile var isRunning: Boolean = false
          private set

      @Volatile private var stopped = false

      private var activeSession: FFmpegSession? = null
      private var readThread: Thread? = null
      private var pipePair: Array<ParcelFileDescriptor>? = null

      private var reconnectThread: HandlerThread? = null
      private var reconnectHandler: Handler? = null
      @Volatile private var reconnectAttempts = 0

      private var lastUrl: String? = null
      private var isLocal: Boolean = false
      private var isImage: Boolean = false

      // Flipped to false on first HW failure, stays false for subsequent retries
      @Volatile private var useHardwareDecode = true

      // ── Public API ────────────────────────────────────────────────────────────

      /** Start decoding a network stream (RTSP / RTMP / HLS / HTTP / SRT …). */
      fun startStream(url: String) {
          lastUrl           = url
          isLocal           = false
          isImage           = false
          useHardwareDecode = true
          reconnectAttempts = 0
          stopped           = false
          ensureReconnectThread()
          stop(clearUrl = false)
          startInternal()
      }

      /** Start decoding a local media file or static image (loops forever). */
      fun startMedia(uri: String) {
          lastUrl = uri
          isLocal = true
          val lower = uri.lowercase()
          isImage = lower.endsWith(".jpg")  || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png")  || lower.endsWith(".bmp")  ||
                    lower.endsWith(".webp")
          useHardwareDecode = true
          reconnectAttempts = 0
          stopped           = false
          ensureReconnectThread()
          stop(clearUrl = false)
          startInternal()
      }

      /** Permanently stop the streamer. Call when InjectionService is stopped. */
      fun stop() = stop(clearUrl = true)

      // ── Internal ──────────────────────────────────────────────────────────────

      private fun stop(clearUrl: Boolean) {
          if (clearUrl) {
              stopped = true
              lastUrl = null
              reconnectHandler?.removeCallbacksAndMessages(null)
              reconnectThread?.quitSafely()
              reconnectThread = null
              reconnectHandler = null
          }
          try { activeSession?.cancel() } catch (_: Throwable) {}
          activeSession = null

          // Close both pipe ends so the reader thread exits cleanly via EOF
          try { pipePair?.get(1)?.close() } catch (_: Throwable) {}
          try { pipePair?.get(0)?.close() } catch (_: Throwable) {}
          pipePair = null

          try { readThread?.interrupt() } catch (_: Throwable) {}
          readThread = null

          isRunning = false
          Logger.d("FfmpegStreamer: stopped (clearUrl=$clearUrl)")
      }

      private fun ensureReconnectThread() {
          if (reconnectThread?.isAlive == true) return
          reconnectThread = HandlerThread("FfmpegReconnect").also { it.start() }
          reconnectHandler = Handler(reconnectThread!!.looper)
      }

      private fun startInternal() {
          val url = lastUrl ?: return
          try {
              val w   = CameraState.currentWidth.takeIf  { it > 0 } ?: 640
              val h   = CameraState.currentHeight.takeIf { it > 0 } ?: 480
              val fps = CameraState.requestedFps.coerceIn(1, 60)

              // Suppress verbose FFmpeg log output — use our own Logger
              FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)

              // ── Kernel pipe pair ──────────────────────────────────────────────
              // FFmpeg writes decoded NV21 frames to pipes[1] (write end).
              // Reader thread reads full frames from pipes[0] (read end).
              // Data flows through the kernel directly — no filesystem I/O.
              val pipes   = ParcelFileDescriptor.createPipe()
              pipePair    = pipes
              val readPfd  = pipes[0]
              val writePfd = pipes[1]
              val writeFd  = writePfd.fd  // raw FD — valid in this process

              val cmd = buildCommand(url, writeFd, w, h, fps, useHardwareDecode)
              Logger.d("FfmpegStreamer: launching hw=$useHardwareDecode ${w}x${h}@${fps}fps")
              Logger.d("FfmpegStreamer: cmd=$cmd")

              // ── FFmpeg session ────────────────────────────────────────────────
              // executeAsync runs FFmpeg in a background thread within this process,
              // so writeFd is valid for the lifetime of the session.
              activeSession = FFmpegKit.executeAsync(cmd) { session ->
                  // Close write end so reader thread gets EOF and exits naturally
                  try { writePfd.close() } catch (_: Throwable) {}

                  val rc = session.returnCode
                  when {
                      stopped -> {
                          Logger.d("FfmpegStreamer: session ended — stopped intentionally")
                      }
                      ReturnCode.isCancel(rc) -> {
                          Logger.d("FfmpegStreamer: session cancelled")
                      }
                      useHardwareDecode && !ReturnCode.isSuccess(rc) -> {
                          // HW codec rejected or unavailable — fall back to software once
                          Logger.d("FfmpegStreamer: HW decode failed (code=${rc?.value}), retrying with SW")
                          useHardwareDecode = false
                          reconnectAttempts = 0
                          reconnectHandler?.post {
                              if (!stopped && lastUrl != null) {
                                  stop(clearUrl = false)
                                  startInternal()
                              }
                          }
                      }
                      else -> {
                          Logger.d("FfmpegStreamer: session ended (code=${rc?.value}) — scheduling reconnect")
                          isRunning = false
                          scheduleReconnect()
                      }
                  }
              }

              // ── Reader thread ─────────────────────────────────────────────────
              // Reads exactly (w * h * 3/2) bytes per NV21 frame from the pipe read end
              // and hands an immutable copy to AppState via a single volatile write.
              val frameSize = w * h * 3 / 2
              val buf       = ByteArray(frameSize)

              readThread = Thread({
                  val fis = FileInputStream(readPfd.fileDescriptor)
                  try {
                      while (!stopped && !Thread.currentThread().isInterrupted) {
                          // Block until a full frame is available
                          var offset = 0
                          while (offset < frameSize && !Thread.currentThread().isInterrupted) {
                              val n = fis.read(buf, offset, frameSize - offset)
                              if (n <= 0) { offset = -1; break }  // EOF / pipe closed
                              offset += n
                          }
                          if (offset != frameSize) break  // short read — stop reader

                          // Single volatile write — zero lock contention in the hooks
                          AppState.putFrame(buf.copyOf(), w, h)

                          if (!isRunning) {
                              isRunning = true
                              reconnectAttempts = 0
                              Logger.d("FfmpegStreamer: first frame received — stream live")
                          }
                      }
                  } catch (_: IOException)          { /* pipe closed — normal exit */ }
                    catch (_: InterruptedException) { /* interrupted — normal exit */ }
                  finally {
                      try { readPfd.close() } catch (_: Throwable) {}
                      Logger.d("FfmpegStreamer: reader thread exited")
                  }
              }, "FfmpegReader").also {
                  it.isDaemon = true
                  it.start()
              }

          } catch (e: Throwable) {
              Logger.e("FfmpegStreamer.startInternal failed", e)
              scheduleReconnect()
          }
      }

      /**
       * Build the FFmpeg command string for this source.
       *
       * Pipeline: [input flags] -i URL -vf "fps,scale,format=nv21" -f rawvideo pipe:FD
       *
       * fps filter     — caps delivery to the app's requested frame rate
       * scale filter   — bilinear resize to the target camera surface dimensions
       * format=nv21    — pixel-format conversion to NV21 in a single native pass
       */
      private fun buildCommand(
          url: String, writeFd: Int, w: Int, h: Int, fps: Int, hw: Boolean
      ): String = buildString {

          // Input / buffering flags
          if (!isLocal) {
              append("-fflags nobuffer -flags low_delay -analyzeduration 1000000 ")
              if (url.startsWith("rtsp://", ignoreCase = true) ||
                  url.startsWith("rtsps://", ignoreCase = true)) {
                  append("-rtsp_transport tcp ")
              }
          }

          // Loop flags for local sources
          when {
              isImage -> append("-loop 1 ")
              isLocal -> append("-stream_loop -1 -re ")
          }

          // Hardware decode — request MediaCodec H.264 decoder
          if (hw && !isImage) {
              append("-c:v h264_mediacodec ")
          }

          append("-i "$url" ")

          // Video filter: cap FPS, scale to exact surface dims, convert to NV21
          append("-vf "fps=$fps,scale=${w}:${h}:flags=fast_bilinear,format=nv21" ")

          // Output: raw video frames into write end of kernel pipe
          append("-f rawvideo -an pipe:$writeFd")
      }

      private fun scheduleReconnect() {
          if (stopped || lastUrl == null) return
          if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
              Logger.e("FfmpegStreamer: max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — giving up")
              return
          }

          val delayMs = minOf(1_000L shl reconnectAttempts, MAX_BACKOFF_MS)
          reconnectAttempts++
          Logger.d("FfmpegStreamer: reconnect $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")

          ensureReconnectThread()
          reconnectHandler?.postDelayed({
              if (!stopped && lastUrl != null) {
                  stop(clearUrl = false)
                  startInternal()
              }
          }, delayMs)
      }
  }
  