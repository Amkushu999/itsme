package com.itsme.amkush.hooks

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import com.itsme.amkush.AppState
import com.itsme.amkush.CameraState
import com.itsme.amkush.DecoderLauncher
import com.itsme.amkush.decoder.FrameUtils
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayOutputStream
import kotlin.math.min

object Camera1Hooks {

    private const val TAG = "FaceGate"

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cameraClass = XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)

            hookCameraOpen(cameraClass)
            hookSetParameters(cameraClass)
            hookSetPreviewCallback(cameraClass)
            hookSetPreviewCallbackWithBuffer(cameraClass)
            hookAddCallbackBuffer(cameraClass)
            hookSetOneShotPreviewCallback(cameraClass)
            hookOnPreviewFrame()
            hookTakePicture(cameraClass)
            hookSetPreviewDisplay(cameraClass)
            hookSetPreviewTexture(cameraClass)
            hookStartPreview(cameraClass)
            hookStopPreview(cameraClass)
            hookRelease(cameraClass)
            hookErrorCallback(cameraClass)

            Logger.d("Camera1 hooks installed")
        } catch (e: Throwable) {
            Logger.e("Camera1 hooks failed", e)
        }
    }

    private fun hookCameraOpen(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "open",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != null) {
                        CameraState.camera1Instance = param.result
                        Logger.d("Camera1 open intercepted")
                    }
                }
            }
        )
    }

    private fun hookSetParameters(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "setParameters",
            Camera.Parameters::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val params = param.args[0] as Camera.Parameters

                    CameraState.currentFormat = params.previewFormat

                    val size = params.previewSize
                    if (size != null) {
                        CameraState.currentWidth  = size.width
                        CameraState.currentHeight = size.height
                    }


                    val fpsRange = IntArray(2)
                    params.getPreviewFpsRange(fpsRange)
                    if (fpsRange.size >= 2) {
                        val maxFps = fpsRange[1] / 1000
                        CameraState.requestedFps = maxFps
                    }

                    Logger.d("Camera1 params: ${CameraState.currentWidth}x${CameraState.currentHeight}, format=${CameraState.currentFormat}, fps=${CameraState.requestedFps}")
                }
            }
        )
    }

    /**
     * Inject fake frame bytes into a Camera1 preview callback buffer.
     *
     * Steps:
     *  1. Capture a single atomic snapshot of the current frame (data + source dimensions).
     *  2. Scale the NV21 source to the callback buffer's expected size (app's preview resolution).
     *  3. Convert NV21 → YV12 if the app requested YV12 format.
     *  4. Copy the result into [dest], truncating if the buffer is smaller than expected.
     *
     * Returning without copying when the frame is empty lets real camera frames pass
     * through during the brief startup/reconnect window.
     */
    private fun injectFrame(dest: ByteArray) {
        // Atomic snapshot — data + width + height from the same volatile reference
        val frame = AppState.currentFrame
        if (frame.isEmpty) return

        val dstW = CameraState.currentWidth.takeIf  { it > 0 } ?: 640
        val dstH = CameraState.currentHeight.takeIf { it > 0 } ?: 480

        // Scale to the app's preview resolution (no-op if already the right size)
        val scaled = FrameUtils.scaleNv21(frame.data, frame.width, frame.height, dstW, dstH)

        // Convert format if the app requested something other than NV21
        val finalData: ByteArray = when (CameraState.currentFormat) {
            ImageFormat.YV12 -> FrameUtils.nv21ToYv12(scaled, dstW, dstH)
            else             -> scaled  // NV21 / YUV_420_888 — use as-is
        }

        val copyLen = min(finalData.size, dest.size)
        System.arraycopy(finalData, 0, dest, 0, copyLen)
    }

    private fun hookSetPreviewCallback(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "setPreviewCallback",
            Camera.PreviewCallback::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalCallback = param.args[0] as? Camera.PreviewCallback

                    if (originalCallback != null && AppState.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            injectFrame(data)
                            originalCallback.onPreviewFrame(data, camera)
                        }
                        param.args[0] = wrappedCallback
                        Logger.d("Camera1 setPreviewCallback hooked")
                    }
                }
            }
        )
    }

    private fun hookSetPreviewCallbackWithBuffer(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "setPreviewCallbackWithBuffer",
            Camera.PreviewCallback::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalCallback = param.args[0] as? Camera.PreviewCallback

                    if (originalCallback != null && AppState.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            injectFrame(data)
                            originalCallback.onPreviewFrame(data, camera)
                        }
                        param.args[0] = wrappedCallback
                        Logger.d("Camera1 setPreviewCallbackWithBuffer hooked")
                    }
                }
            }
        )
    }

    private fun hookAddCallbackBuffer(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "addCallbackBuffer",
            ByteArray::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val buffer = param.args[0] as? ByteArray
                    if (buffer != null) {
                        param.args[0] = ByteArray(buffer.size)
                        Logger.d("Camera1 addCallbackBuffer hooked")
                    }
                }
            }
        )
    }

    private fun hookSetOneShotPreviewCallback(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "setOneShotPreviewCallback",
            Camera.PreviewCallback::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalCallback = param.args[0] as? Camera.PreviewCallback

                    if (originalCallback != null && AppState.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            injectFrame(data)
                            originalCallback.onPreviewFrame(data, camera)
                        }
                        param.args[0] = wrappedCallback
                        Logger.d("Camera1 setOneShotPreviewCallback hooked")
                    }
                }
            }
        )
    }

    private fun hookOnPreviewFrame() {
        val previewCallbackClass = Camera.PreviewCallback::class.java

        XposedHelpers.findAndHookMethod(
            previewCallbackClass,
            "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (AppState.isHookingActive) {
                        // On-the-fly parameter discovery when camera was already running
                        if (CameraState.currentWidth <= 0) {
                            val camera = param.args[1] as? Camera
                            if (camera != null) {
                                try {
                                    val p  = camera.parameters
                                    val sz = p.previewSize
                                    if (sz != null) {
                                        CameraState.currentWidth  = sz.width
                                        CameraState.currentHeight = sz.height
                                    }
                                    CameraState.currentFormat = p.previewFormat
                                    val fpsRange = IntArray(2)
                                    p.getPreviewFpsRange(fpsRange)
                                    val fps = (fpsRange[1] / 1000).coerceIn(1, 120)
                                    CameraState.requestedFps = fps
                                    Logger.d("Camera1 on-the-fly discovery: ${sz?.width}x${sz?.height}")
                                } catch (e: Throwable) {
                                    Logger.e("Camera1 dynamic discovery failed", e)
                                }
                            }
                        }
                        // Ensure decoder is running (handles apps already running at inject time)
                        DecoderLauncher.ensureLaunched()

                        val data = param.args[0] as ByteArray
                        injectFrame(data)
                    }
                }
            }
        )
    }

    private fun hookTakePicture(cameraClass: Class<*>) {
        val shutterCallbackClass = Camera.ShutterCallback::class.java
        val pictureCallbackClass = Camera.PictureCallback::class.java

        XposedHelpers.findAndHookMethod(
            cameraClass,
            "takePicture",
            shutterCallbackClass,
            pictureCallbackClass,
            pictureCallbackClass,
            pictureCallbackClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (AppState.isHookingActive) {
                        val originalJpegCallback = param.args[3] as? Camera.PictureCallback

                        param.args[3] = Camera.PictureCallback { data, camera ->
                            val fakeJpeg = getFakeJpeg()
                            if (fakeJpeg != null) {
                                originalJpegCallback?.onPictureTaken(fakeJpeg, camera)
                            } else {
                                originalJpegCallback?.onPictureTaken(data, camera)
                            }
                        }
                        Logger.d("Camera1 takePicture hooked")
                    }
                }
            }
        )
    }

    private fun hookSetPreviewDisplay(cameraClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewDisplay",
                android.view.SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (AppState.isHookingActive) {
                            val holder = param.args[0] as? android.view.SurfaceHolder
                            if (holder != null) {
                                Logger.d("Camera1 setPreviewDisplay intercepted")
                                CameraState.camera1Holder = holder
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("setPreviewDisplay hook not available")
        }
    }

    private fun hookSetPreviewTexture(cameraClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewTexture",
                android.graphics.SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (AppState.isHookingActive) {
                            val texture = param.args[0] as? android.graphics.SurfaceTexture
                            if (texture != null) {
                                Logger.d("Camera1 setPreviewTexture intercepted")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("setPreviewTexture hook not available")
        }
    }

    private fun hookStartPreview(cameraClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "startPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (AppState.isHookingActive) {
                            CameraState.isPreviewActive = true
                            Logger.d("Camera1 startPreview intercepted")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("startPreview hook not available")
        }
    }

    private fun hookStopPreview(cameraClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "stopPreview",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (AppState.isHookingActive) {
                            CameraState.isPreviewActive = false
                            Logger.d("Camera1 stopPreview intercepted")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("stopPreview hook not available")
        }
    }

    private fun hookRelease(cameraClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "release",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (AppState.isHookingActive) {
                            Logger.d("Camera1 release called - resetting state")
                            CameraState.reset()
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("release hook not available")
        }
    }

    private fun hookErrorCallback(cameraClass: Class<*>) {
        try {
            val errorCallbackClass = Camera.ErrorCallback::class.java

            XposedHelpers.findAndHookMethod(
                errorCallbackClass,
                "onError",
                Int::class.javaPrimitiveType,
                Camera::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val errorCode = param.args[0] as? Int ?: return
                        // CAMERA_ERROR_RELEASED = 2, CAMERA_ERROR_SERVER_DIED = 100
                        if (errorCode == 2 || errorCode == 100) {
                            Logger.d("Camera1 error callback: resetting state (error $errorCode)")
                            CameraState.reset()
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setErrorCallback",
                Camera.ErrorCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalCallback = param.args[0] as? Camera.ErrorCallback

                        if (originalCallback != null && AppState.isHookingActive) {
                            val wrappedCallback = Camera.ErrorCallback { errorCode, camera ->
                                if (errorCode == 2 || errorCode == 100) {
                                    Logger.d("Camera1 setErrorCallback: resetting state (error $errorCode)")
                                    CameraState.reset()
                                }
                                originalCallback.onError(errorCode, camera)
                            }
                            param.args[0] = wrappedCallback
                            Logger.d("Camera1 setErrorCallback wrapped")
                        }
                    }
                }
            )

        } catch (e: Throwable) {
            Logger.d("ErrorCallback hook not available")
        }
    }

    /**
     * Convert the current fake frame to JPEG for takePicture() callbacks.
     * Uses the frame's own source dimensions (not CameraState) so the YuvImage
     * and scaler are always consistent even if CameraState hasn't been updated.
     */
    private fun getFakeJpeg(): ByteArray? {
        val frame = AppState.currentFrame
        if (frame.isEmpty) return null

        return try {
            // If the buffer already starts with FF D8 it is JPEG — return directly
            if (frame.data.size >= 2
                && frame.data[0] == 0xFF.toByte()
                && frame.data[1] == 0xD8.toByte()
            ) {
                frame.data
            } else {
                // Scale to picture resolution (may differ from preview resolution)
                val picW  = CameraState.currentWidth.takeIf  { it > 0 } ?: frame.width
                val picH  = CameraState.currentHeight.takeIf { it > 0 } ?: frame.height
                val nv21  = FrameUtils.scaleNv21(frame.data, frame.width, frame.height, picW, picH)
                val yuv   = YuvImage(nv21, ImageFormat.NV21, picW, picH, null)
                val stream = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, picW, picH), 90, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Logger.e("getFakeJpeg failed", e)
            null
        }
    }
}
