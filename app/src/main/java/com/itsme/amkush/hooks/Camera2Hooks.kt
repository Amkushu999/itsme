package com.itsme.amkush.hooks

  import android.graphics.ImageFormat
  import android.hardware.camera2.CameraDevice
  import android.hardware.camera2.params.InputConfiguration
  import android.hardware.camera2.params.OutputConfiguration
  import android.hardware.camera2.params.SessionConfiguration
  import android.media.Image
  import android.media.ImageReader
  import android.media.ImageWriter
  import android.os.Build
  import android.view.Surface
  import com.itsme.amkush.AppState
  import com.itsme.amkush.CameraState
  import com.itsme.amkush.decoder.FrameUtils
  import com.itsme.amkush.utils.Logger
  import de.robv.android.xposed.XC_MethodHook
  import de.robv.android.xposed.XposedHelpers
  import de.robv.android.xposed.callbacks.XC_LoadPackage
  import java.util.concurrent.ConcurrentHashMap
  import kotlin.math.min

  /**
   * Camera2 / ImageReader frame injection.
   *
   * Strategy:
   *  1. Hook ImageReader.newInstance() → record width/height/format per reader instance.
   *  2. Hook ImageReader.acquireLatestImage() and acquireNextImage() → after the real Image
   *     is returned to the app, write our scaled fake frame into its YUV/JPEG plane buffers.
   *  3. Hook createCaptureSession variants → track all output surfaces for per-surface
   *     resolution mapping.
   *  4. Hook CaptureRequest.Builder.addTarget() → track which surfaces are active.
   *
   * Why acquireLatestImage/acquireNextImage hooking works:
   *  On most Android camera HALs, the Image plane ByteBuffers are direct NIO buffers backed
   *  by gralloc-mapped memory. They are writable (the read-only flag is not set by the HAL
   *  on preview/analysis streams). We overwrite in-place before the app reads the pixels.
   *  A try/catch around every write ensures we degrade gracefully on strict HALs.
   */
  object Camera2Hooks {

      // Maps ImageReader instance → its configured resolution + format
      private val readerMeta = ConcurrentHashMap<Any, ReaderMeta>()
      // Maps Surface → its expected resolution (from OutputConfiguration or session list)
      private val surfaceRes  = ConcurrentHashMap<Int, SurfaceMeta>()   // key = Surface.hashCode()

      private data class ReaderMeta(val width: Int, val height: Int, val format: Int)
      private data class SurfaceMeta(val width: Int, val height: Int)

      private var lastFrameNs = 0L

      // ─────────────────────────────────────────────────────────────────────
      fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              hookImageReaderNewInstance(lpparam)
              hookAcquireImage(lpparam)
              hookCreateCaptureSession(lpparam)
              hookAddTarget(lpparam)
              hookDisconnect(lpparam)
              Logger.d("Camera2 hooks installed (multi-surface, per-reader injection)")
          } catch (e: Throwable) {
              Logger.e("Camera2 hooks failed", e)
          }
      }

      // ── 1. Track ImageReader dimensions per instance ──────────────────────
      private fun hookImageReaderNewInstance(lpparam: XC_LoadPackage.LoadPackageParam) {
          val cls = XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader)

          // newInstance(width, height, format, maxImages)
          XposedHelpers.findAndHookMethod(cls, "newInstance",
              Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
              Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
              object : XC_MethodHook() {
                  override fun afterHookedMethod(param: MethodHookParam) {
                      val w = param.args[0] as Int; val h = param.args[1] as Int
                      val fmt = param.args[2] as Int
                      param.result?.let { readerMeta[it] = ReaderMeta(w, h, fmt) }
                      // Also update the shared CameraState for Camera1-style callers
                      CameraState.currentWidth  = w
                      CameraState.currentHeight = h
                      CameraState.currentFormat = fmt
                      Logger.d("Camera2 ImageReader: ${w}x${h} fmt=$fmt")
                  }
              }
          )

          // newInstance(width, height, format, maxImages, usage) — API 29+
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              try {
                  XposedHelpers.findAndHookMethod(cls, "newInstance",
                      Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                      Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                      Long::class.javaPrimitiveType,
                      object : XC_MethodHook() {
                          override fun afterHookedMethod(param: MethodHookParam) {
                              val w = param.args[0] as Int; val h = param.args[1] as Int
                              val fmt = param.args[2] as Int
                              param.result?.let { readerMeta[it] = ReaderMeta(w, h, fmt) }
                              CameraState.currentWidth = w; CameraState.currentHeight = h
                              CameraState.currentFormat = fmt
                          }
                      }
                  )
              } catch (_: Throwable) {}
          }
      }

      // ── 2. Inject fake frame into Image planes on every acquire ──────────
      private fun hookAcquireImage(lpparam: XC_LoadPackage.LoadPackageParam) {
          val cls = XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader)

          for (method in listOf("acquireLatestImage", "acquireNextImage")) {
              XposedHelpers.findAndHookMethod(cls, method, object : XC_MethodHook() {
                  override fun afterHookedMethod(param: MethodHookParam) {
                      if (!AppState.isHookingActive) return
                      val image = param.result as? Image ?: return
                      // Determine resolution: prefer per-reader meta, fall back to global state
                      val meta  = readerMeta[param.thisObject]
                      val dstW  = meta?.width  ?: image.width.takeIf { it > 0 }  ?: CameraState.currentWidth.takeIf  { it > 0 } ?: 640
                      val dstH  = meta?.height ?: image.height.takeIf { it > 0 } ?: CameraState.currentHeight.takeIf { it > 0 } ?: 480
                      val fmt   = meta?.format ?: image.format
                      injectIntoImage(image, dstW, dstH, fmt)
                      updateFps()
                  }
              })
          }
      }

      /**
       * Write fake NV21/YUV/JPEG data directly into an Image's plane ByteBuffers.
       *
       * Camera2 Image planes on preview/analysis streams are usually writable gralloc
       * buffers.  We guard every write in try/catch so a read-only buffer on a strict
       * HAL degrades silently without crashing the target app.
       */
      private fun injectIntoImage(image: Image, dstW: Int, dstH: Int, format: Int) {
          val frame = AppState.currentFrame
          if (frame.isEmpty) return
          try {
              when (format) {
                  ImageFormat.YUV_420_888,
                  ImageFormat.NV21,
                  ImageFormat.NV16 -> {
                      val nv21   = FrameUtils.scaleNv21(frame.data, frame.width, frame.height, dstW, dstH)
                      val planes = image.planes
                      if (planes.isEmpty()) return

                      // Y plane (full resolution)
                      writePlane(planes[0].buffer, nv21, 0, dstW * dstH)

                      if (planes.size >= 3) {
                          val uvBase = dstW * dstH
                          val halfW  = dstW / 2
                          val halfH  = dstH / 2

                          // U plane (or Cb) — offset 1 in NV21's UV block
                          writePlane(planes[1].buffer, nv21, uvBase + 1, halfW * halfH)

                          // V plane (or Cr) — offset 0 in NV21's UV block
                          writePlane(planes[2].buffer, nv21, uvBase, halfW * halfH)
                      } else if (planes.size == 2) {
                          // Some devices return only Y + UV interleaved
                          val uvBase = dstW * dstH
                          writePlane(planes[1].buffer, nv21, uvBase, dstW * dstH / 2)
                      }
                  }

                  ImageFormat.JPEG -> {
                      val jpeg = getFakeJpeg() ?: return
                      val planes = image.planes
                      if (planes.isNotEmpty()) writePlane(planes[0].buffer, jpeg, 0, jpeg.size)
                  }

                  else -> {
                      // Best-effort: treat as raw byte stream, write NV21 and hope for the best
                      val nv21 = FrameUtils.scaleNv21(frame.data, frame.width, frame.height, dstW, dstH)
                      image.planes.firstOrNull()?.buffer?.let { writePlane(it, nv21, 0, nv21.size) }
                  }
              }
          } catch (e: Throwable) {
              Logger.d("Camera2 injectIntoImage error: ${e.message}")
          }
      }

      /** Safely write [src] bytes (starting at [srcOffset], up to [maxLen]) into [buf]. */
      private fun writePlane(buf: java.nio.ByteBuffer, src: ByteArray, srcOffset: Int, maxLen: Int) {
          if (buf.isReadOnly || srcOffset >= src.size) return
          buf.clear()
          val copyLen = min(min(maxLen, buf.remaining()), src.size - srcOffset)
          if (copyLen > 0) buf.put(src, srcOffset, copyLen)
      }

      // ── 3. Track output surfaces and their resolutions from createCaptureSession ──
      private fun hookCreateCaptureSession(lpparam: XC_LoadPackage.LoadPackageParam) {
          val cameraDeviceClass = XposedHelpers.findClass("android.hardware.camera2.CameraDevice", lpparam.classLoader)

          // Modern: SessionConfiguration (API 28+)
          try {
              XposedHelpers.findAndHookMethod(cameraDeviceClass, "createCaptureSession",
                  XposedHelpers.findClass("android.hardware.camera2.params.SessionConfiguration", lpparam.classLoader),
                  object : XC_MethodHook() {
                      override fun beforeHookedMethod(param: MethodHookParam) {
                          val config = param.args[0] as? SessionConfiguration ?: return
                          config.outputConfigurations.forEach { oc -> registerOutputConfig(oc) }
                      }
                  }
              )
          } catch (_: Throwable) {}

          // Legacy: List<Surface>
          try {
              XposedHelpers.findAndHookMethod(cameraDeviceClass, "createCaptureSession",
                  List::class.java,
                  XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession$StateCallback", lpparam.classLoader),
                  android.os.Handler::class.java,
                  object : XC_MethodHook() {
                      override fun beforeHookedMethod(param: MethodHookParam) {
                          (param.args[0] as? List<*>)?.forEach { s -> (s as? Surface)?.let { registerSurface(it) } }
                      }
                  }
              )
          } catch (_: Throwable) {}

          // API 24+: createCaptureSessionByOutputConfigurations
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
              try {
                  XposedHelpers.findAndHookMethod(cameraDeviceClass, "createCaptureSessionByOutputConfigurations",
                      List::class.java,
                      XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession$StateCallback", lpparam.classLoader),
                      android.os.Handler::class.java,
                      object : XC_MethodHook() {
                          override fun beforeHookedMethod(param: MethodHookParam) {
                              (param.args[0] as? List<*>)?.filterIsInstance<OutputConfiguration>()
                                  ?.forEach { registerOutputConfig(it) }
                          }
                      }
                  )
              } catch (_: Throwable) {}
          }
      }

      private fun registerOutputConfig(oc: OutputConfiguration) {
          try {
              val surface = oc.surface ?: return
              registerSurface(surface)
          } catch (_: Throwable) {}
      }

      private fun registerSurface(surface: Surface) {
          try {
              // Read width/height from the Surface via reflection (works on most Android versions)
              val w = XposedHelpers.callMethod(surface, "getWidth",  *emptyArray<Any>()) as? Int ?: return
              val h = XposedHelpers.callMethod(surface, "getHeight", *emptyArray<Any>()) as? Int ?: return
              if (w > 0 && h > 0) {
                  surfaceRes[System.identityHashCode(surface)] = SurfaceMeta(w, h)
                  Logger.d("Camera2 surface registered: ${w}x${h}")
              }
          } catch (_: Throwable) {}
      }

      // ── 4. Track CaptureRequest targets (addTarget) ───────────────────────
      private fun hookAddTarget(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              val builderClass = XposedHelpers.findClass(
                  "android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader)
              XposedHelpers.findAndHookMethod(builderClass, "addTarget", Surface::class.java,
                  object : XC_MethodHook() {
                      override fun beforeHookedMethod(param: MethodHookParam) {
                          (param.args[0] as? Surface)?.let { registerSurface(it) }
                      }
                  }
              )
          } catch (_: Throwable) {}
      }

      // ── 5. Clean up on camera disconnect ──────────────────────────────────
      private fun hookDisconnect(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              val cls = XposedHelpers.findClass("android.hardware.camera2.CameraDevice", lpparam.classLoader)
              XposedHelpers.findAndHookMethod(cls, "close", object : XC_MethodHook() {
                  override fun beforeHookedMethod(param: MethodHookParam) {
                      readerMeta.clear(); surfaceRes.clear()
                      CameraState.reset()
                      Logger.d("Camera2 closed — state reset")
                  }
              })
          } catch (_: Throwable) {}
      }

      // ── Helpers ────────────────────────────────────────────────────────────
      private fun updateFps() {
          val now = System.nanoTime()
          if (lastFrameNs > 0) {
              val deltaMs = (now - lastFrameNs) / 1_000_000L
              if (deltaMs in 1..5000) CameraState.requestedFps = (1000L / deltaMs).toInt().coerceIn(1, 120)
          }
          lastFrameNs = now
      }

      private fun getFakeJpeg(): ByteArray? = try {
          val frame = AppState.currentFrame
          if (frame.isEmpty) null
          else {
              val bmp = android.graphics.Bitmap.createBitmap(frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888)
              val yuvImg = android.graphics.YuvImage(frame.data, android.graphics.ImageFormat.NV21, frame.width, frame.height, null)
              val out = java.io.ByteArrayOutputStream()
              yuvImg.compressToJpeg(android.graphics.Rect(0, 0, frame.width, frame.height), 90, out)
              out.toByteArray()
          }
      } catch (_: Throwable) { null }
  }
  