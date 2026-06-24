package com.itsme.amkush.decoder

  import android.graphics.ImageFormat
  import android.media.Image
  import android.media.ImageReader
  import android.media.MediaCodec
  import android.media.MediaCodecList
  import android.media.MediaExtractor
  import android.media.MediaFormat
  import com.itsme.amkush.AppState
  import com.itsme.amkush.utils.Logger
  import kotlin.math.min

  /**
   * Decodes a network video stream (RTSP / HLS) or local file using Android's
   * built-in MediaExtractor + MediaCodec + ImageReader — no external library needed.
   *
   * Public API is unchanged from the ffmpeg-kit version so DecoderLauncher and
   * any other callers require no modification.
   *
   * Guarantees:
   *  - Hardware decoder preferred (OMX vendor / MediaCodec HW); SW fallback on failure.
   *  - Decoded frames delivered as NV21 ByteArrays via AppState.putFrame().
   *  - Exponential back-off reconnection (1 s → 2 s → 4 s … 30 s, max 10 attempts).
   *  - Thread-safe start / stop / restart.
   */
  object FfmpegStreamer {

      private const val MAX_RETRIES   = 10
      private const val BASE_DELAY_MS = 1_000L
      private const val MAX_DELAY_MS  = 30_000L

      @Volatile private var stopped      = true
      @Volatile private var activeUrl: String? = null
      private var decodeThread: Thread?  = null

      // ── Public API ─────────────────────────────────────────────────────────

      fun startStream(url: String) = start(url)
      fun startMedia(uri: String)  = start(uri)

      fun start(url: String) {
          synchronized(this) {
              stopped   = false
              activeUrl = url
              decodeThread?.interrupt()
              decodeThread = Thread({ decodeLoop(url) }, "FfmpegStreamer").also {
                  it.isDaemon = true
                  it.start()
              }
          }
          Logger.d("FfmpegStreamer: started → $url")
      }

      fun stop() {
          synchronized(this) {
              stopped      = true
              activeUrl    = null
              decodeThread?.interrupt()
              decodeThread = null
          }
          AppState.putFrame(ByteArray(0), 0, 0)
          Logger.d("FfmpegStreamer: stopped")
      }

      fun isRunning(): Boolean = !stopped && decodeThread?.isAlive == true
      fun getUrl(): String?    = activeUrl

      // ── Reconnect loop ─────────────────────────────────────────────────────

      private fun decodeLoop(url: String) {
          var attempt = 0
          while (!stopped && !Thread.currentThread().isInterrupted) {
              try {
                  Logger.d("FfmpegStreamer: connecting (attempt ${attempt + 1}) → $url")
                  decodeSession(url)
                  if (stopped) break
                  Logger.d("FfmpegStreamer: stream ended — will reconnect")
              } catch (e: InterruptedException) {
                  Thread.currentThread().interrupt(); break
              } catch (e: Throwable) {
                  if (stopped) break
                  Logger.e("FfmpegStreamer: error on attempt ${attempt + 1}", e)
              }
              if (++attempt >= MAX_RETRIES) {
                  Logger.e("FfmpegStreamer: giving up after $MAX_RETRIES attempts"); break
              }
              val delay = min(BASE_DELAY_MS * (1L shl attempt.coerceAtMost(5)), MAX_DELAY_MS)
              Logger.d("FfmpegStreamer: retry in ${delay}ms")
              try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
          }
          stopped = true
          Logger.d("FfmpegStreamer: decode loop exited")
      }

      // ── Single decode session — MediaExtractor → MediaCodec → ImageReader ──

      private fun decodeSession(url: String) {
          val extractor = MediaExtractor()
          var codec: MediaCodec?        = null
          var reader: ImageReader?      = null
          try {
              extractor.setDataSource(url)          // blocks until connected or throws

              // Find first video track
              var videoTrack = -1
              var format: MediaFormat? = null
              for (i in 0 until extractor.trackCount) {
                  val fmt  = extractor.getTrackFormat(i)
                  val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                  if (mime.startsWith("video/")) { videoTrack = i; format = fmt; break }
              }
              if (videoTrack < 0 || format == null) {
                  Logger.e("FfmpegStreamer: no video track in $url"); return
              }
              extractor.selectTrack(videoTrack)

              val mime   = format.getString(MediaFormat.KEY_MIME)!!
              val width  = format.getInteger(MediaFormat.KEY_WIDTH)
              val height = format.getInteger(MediaFormat.KEY_HEIGHT)
              Logger.d("FfmpegStreamer: $mime ${width}x$height")

              // ImageReader receives decoded YUV_420_888 frames directly from MediaCodec.
              // On Android 10+ this path is supported for all hardware decoders.
              reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
              reader.setOnImageAvailableListener({ r ->
                  r.acquireLatestImage()?.use { img ->
                      if (!stopped) AppState.putFrame(yuv420ToNv21(img), img.width, img.height)
                  }
              }, null)

              // Prefer a hardware decoder; fall back to the default for the MIME type
              codec = pickDecoder(mime)
              codec.configure(format, reader.surface, null, 0)
              codec.start()

              val info      = MediaCodec.BufferInfo()
              val timeoutUs = 10_000L          // 10 ms

              while (!stopped && !Thread.currentThread().isInterrupted) {
                  // Feed compressed data to the codec
                  val inIdx = codec.dequeueInputBuffer(timeoutUs)
                  if (inIdx >= 0) {
                      val buf  = codec.getInputBuffer(inIdx)!!
                      val size = extractor.readSampleData(buf, 0)
                      if (size < 0) {
                          codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                      } else {
                          codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                          extractor.advance()
                      }
                  }
                  // Render output buffer → fires ImageReader listener → AppState.putFrame()
                  val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                  if (outIdx >= 0) codec.releaseOutputBuffer(outIdx, info.size > 0)
                  if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
              }
          } finally {
              runCatching { codec?.stop()     }
              runCatching { codec?.release()  }
              runCatching { extractor.release() }
              runCatching { reader?.close()   }
          }
      }

      // ── Decoder selection — HW vendor codec first, SW fallback ─────────────

      private fun pickDecoder(mime: String): MediaCodec {
          return try {
              val hw = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                  .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                  .firstOrNull {
                      val n = it.name.lowercase()
                      !n.contains("google") && (n.contains("omx.") || n.contains("c2."))
                  }?.name
              if (hw != null) MediaCodec.createByCodecName(hw)
              else           MediaCodec.createDecoderByType(mime)
          } catch (_: Throwable) {
              MediaCodec.createDecoderByType(mime)
          }
      }

      // ── YUV_420_888 → NV21  (Y plane · then interleaved V U chroma) ────────

      private fun yuv420ToNv21(image: Image): ByteArray {
          val w   = image.width
          val h   = image.height
          val yP  = image.planes[0]
          val uP  = image.planes[1]
          val vP  = image.planes[2]
          val yRS = yP.rowStride
          val uvRS = uP.rowStride
          val uvPS = uP.pixelStride

          val nv21 = ByteArray(w * h * 3 / 2)
          var pos  = 0

          val yBuf = yP.buffer
          for (row in 0 until h) {
              yBuf.position(row * yRS)
              yBuf.get(nv21, pos, w)
              pos += w
          }

          val uBuf = uP.buffer
          val vBuf = vP.buffer
          for (row in 0 until h / 2) {
              for (col in 0 until w / 2) {
                  val idx     = row * uvRS + col * uvPS
                  nv21[pos++] = vBuf.get(idx)   // V first in NV21
                  nv21[pos++] = uBuf.get(idx)   // then U
              }
          }
          return nv21
      }
  }
  