package com.itsme.amkush.hooks

import android.hardware.Camera
import com.itsme.amkush.CameraState
import com.itsme.amkush.MainHook
import com.itsme.amkush.decoder.VideoDecoder
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
                        CameraState.currentWidth = size.width
                        CameraState.currentHeight = size.height
                        VideoDecoder.setTargetSize(size.width, size.height)
                    }

                    VideoDecoder.setTargetFormat(CameraState.currentFormat)

                    val fpsRange = params.getPreviewFpsRange()
                    if (fpsRange != null && fpsRange.size >= 2) {
                        val maxFps = fpsRange[1] / 1000
                        CameraState.requestedFps = maxFps
                        VideoDecoder.setTargetFps(maxFps)
                    }

                    Logger.d("Camera1 params: ${CameraState.currentWidth}x${CameraState.currentHeight}, format=${CameraState.currentFormat}, fps=${CameraState.requestedFps}")
                }
            }
        )
    }

    private fun hookSetPreviewCallback(cameraClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            cameraClass,
            "setPreviewCallback",
            Camera.PreviewCallback::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalCallback = param.args[0] as? Camera.PreviewCallback

                    if (originalCallback != null && MainHook.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            if (MainHook.dataBuffer.isNotEmpty()) {
                                val frame = MainHook.dataBuffer
                                val copySize = min(frame.size, data.size)
                                System.arraycopy(frame, 0, data, 0, copySize)
                            }
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

                    if (originalCallback != null && MainHook.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            if (MainHook.dataBuffer.isNotEmpty()) {
                                val frame = MainHook.dataBuffer
                                val copySize = min(frame.size, data.size)
                                System.arraycopy(frame, 0, data, 0, copySize)
                            }
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

                    if (originalCallback != null && MainHook.isHookingActive) {
                        val wrappedCallback = Camera.PreviewCallback { data, camera ->
                            if (MainHook.dataBuffer.isNotEmpty()) {
                                val frame = MainHook.dataBuffer
                                val copySize = min(frame.size, data.size)
                                System.arraycopy(frame, 0, data, 0, copySize)
                            }
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
                    if (MainHook.isHookingActive) {
                        val data = param.args[0] as ByteArray
                        if (MainHook.dataBuffer.isNotEmpty()) {
                            val frame = MainHook.dataBuffer
                            val copySize = min(frame.size, data.size)
                            System.arraycopy(frame, 0, data, 0, copySize)
                        }
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
                    if (MainHook.isHookingActive) {
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
                        if (MainHook.isHookingActive) {
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
                        if (MainHook.isHookingActive) {
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
                        if (MainHook.isHookingActive) {
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
                        if (MainHook.isHookingActive) {
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
                        if (MainHook.isHookingActive) {
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
                        
                        // CAMERA_ERROR_RELEASED = 2 (on newer Android versions)
                        // CAMERA_ERROR_SERVER_DIED = 100
                        if (errorCode == 2 || errorCode == 100) {
                            Logger.d("Camera1 error callback: camera released or server died - resetting state")
                            CameraState.reset()
                        }
                    }
                }
            )

            // Also hook setErrorCallback to wrap the callback
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setErrorCallback",
                Camera.ErrorCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalCallback = param.args[0] as? Camera.ErrorCallback
                        
                        if (originalCallback != null && MainHook.isHookingActive) {
                            val wrappedCallback = Camera.ErrorCallback { errorCode, camera ->
                                if (errorCode == 2 || errorCode == 100) {
                                    Logger.d("Camera1 setErrorCallback: camera released or server died - resetting state")
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

    private fun getFakeJpeg(): ByteArray? {
        return null
    }
}