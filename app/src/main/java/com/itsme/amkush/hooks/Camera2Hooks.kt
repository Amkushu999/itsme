package com.itsme.amkush.hooks

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.util.Range
import android.view.Surface
import com.itsme.amkush.CameraState
import com.itsme.amkush.MainHook
import com.itsme.amkush.decoder.VideoDecoder
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object Camera2Hooks {

    private const val TAG = "FaceGate"
    private val virtualSurfaceMap = mutableMapOf<Surface, Surface>()
    private var surfaceCount = 0

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookImageReaderCreation(lpparam)
            hookCreateCaptureSession(lpparam)
            hookOnImageAvailableListener(lpparam)
            hookCreateCaptureRequest(lpparam)
            hookSetRepeatingRequest(lpparam)
            hookAddTarget(lpparam)
            hookRemoveTarget(lpparam)
            hookCaptureRequestBuild(lpparam)
            hookDisconnect(lpparam)
            Logger.d("Camera2 hooks installed")
        } catch (e: Throwable) {
            Logger.e("Camera2 hooks failed", e)
        }
    }

    private fun hookImageReaderCreation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val imageReaderClass = XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            imageReaderClass,
            "newInstance",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val width = param.args[0] as Int
                    val height = param.args[1] as Int
                    val format = param.args[2] as Int

                    CameraState.currentWidth = width
                    CameraState.currentHeight = height
                    CameraState.currentFormat = format

                    VideoDecoder.setTargetSize(width, height)
                    VideoDecoder.setTargetFormat(format)

                    Logger.d("ImageReader created: ${width}x${height}, format=$format")
                }
            }
        )
    }

    private fun hookCreateCaptureSession(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceClass = XposedHelpers.findClass(
            "android.hardware.camera2.CameraDevice",
            lpparam.classLoader
        )

        // Method 1: SessionConfiguration (Android 8.0+)
        try {
            val sessionConfigClass = XposedHelpers.findClass(
                "android.hardware.camera2.params.SessionConfiguration",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                cameraDeviceClass,
                "createCaptureSession",
                sessionConfigClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val config = param.args[0] as SessionConfiguration
                            handleSessionConfiguration(config)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("SessionConfiguration hook not available")
        }

        // Method 2: Legacy createCaptureSession
        try {
            XposedHelpers.findAndHookMethod(
                cameraDeviceClass,
                "createCaptureSession",
                List::class.java,
                XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.StateCallback", lpparam.classLoader),
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val outputs = param.args[0] as? List<*>
                            outputs?.let { handleOutputs(it) }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Ignore
        }

        // Method 3: createCaptureSessionByOutputConfigurations (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val outputConfigClass = XposedHelpers.findClass(
                    "android.hardware.camera2.params.OutputConfiguration",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    cameraDeviceClass,
                    "createCaptureSessionByOutputConfigurations",
                    List::class.java,
                    XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.StateCallback", lpparam.classLoader),
                    android.os.Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (MainHook.isHookingActive) {
                                val outputs = param.args[0] as? List<*>
                                outputs?.let { handleOutputs(it) }
                                Logger.d("Camera2 createCaptureSessionByOutputConfigurations hooked")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("createCaptureSessionByOutputConfigurations hook not available")
            }
        }

        // Method 4: createConstrainedHighSpeedCaptureSession (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(
                    cameraDeviceClass,
                    "createConstrainedHighSpeedCaptureSession",
                    List::class.java,
                    XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.StateCallback", lpparam.classLoader),
                    android.os.Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (MainHook.isHookingActive) {
                                val outputs = param.args[0] as? List<*>
                                outputs?.let { handleOutputs(it) }
                                Logger.d("Camera2 createConstrainedHighSpeedCaptureSession hooked")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("createConstrainedHighSpeedCaptureSession hook not available")
            }
        }

        // Method 5: createReprocessableCaptureSession (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                    cameraDeviceClass,
                    "createReprocessableCaptureSession",
                    InputConfiguration::class.java,
                    List::class.java,
                    XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.StateCallback", lpparam.classLoader),
                    android.os.Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (MainHook.isHookingActive) {
                                val outputs = param.args[1] as? List<*>
                                outputs?.let { handleOutputs(it) }
                                Logger.d("Camera2 createReprocessableCaptureSession hooked")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("createReprocessableCaptureSession hook not available")
            }
        }

        // Method 6: createReprocessableCaptureSessionByConfigurations (Android 7.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(
                    cameraDeviceClass,
                    "createReprocessableCaptureSessionByConfigurations",
                    InputConfiguration::class.java,
                    List::class.java,
                    XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.StateCallback", lpparam.classLoader),
                    android.os.Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (MainHook.isHookingActive) {
                                val outputs = param.args[1] as? List<*>
                                outputs?.let { handleOutputs(it) }
                                Logger.d("Camera2 createReprocessableCaptureSessionByConfigurations hooked")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("createReprocessableCaptureSessionByConfigurations hook not available")
            }
        }
    }

    private fun hookAddTarget(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.hardware.camera2.CaptureRequest.Builder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                builderClass,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val surface = param.args[0] as? Surface
                            if (surface != null) {
                                surfaceCount++
                                Logger.d("Camera2 addTarget: $surface (count: $surfaceCount)")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("addTarget hook not available")
        }
    }

    private fun hookRemoveTarget(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.hardware.camera2.CaptureRequest.Builder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                builderClass,
                "removeTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val surface = param.args[0] as? Surface
                            if (surface != null) {
                                surfaceCount--
                                Logger.d("Camera2 removeTarget: $surface (count: $surfaceCount)")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("removeTarget hook not available")
        }
    }

    private fun hookCaptureRequestBuild(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "android.hardware.camera2.CaptureRequest.Builder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            Logger.d("Camera2 CaptureRequest.build() called")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("build hook not available")
        }
    }

    private fun handleSessionConfiguration(config: SessionConfiguration) {
        val outputs = config.outputConfigurations
        outputs?.let { handleOutputs(it) }

        try {
            val sessionParams = config.sessionParameters
            if (sessionParams != null) {
                val fpsRange = sessionParams.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                if (fpsRange is Range<*>) {
                    val max = fpsRange.upper as Int
                    CameraState.requestedFps = max
                    VideoDecoder.setTargetFps(max)
                }
            }
        } catch (e: Throwable) {
            Logger.e("Failed to read FPS from SessionConfiguration", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleOutputs(outputs: List<*>) {
        val virtualSurface = createVirtualSurface()

        for (output in outputs) {
            if (output is OutputConfiguration) {
                val surface = output.surface
                surface?.let {
                    virtualSurfaceMap[it] = virtualSurface
                    Logger.d("All Surface Tracking: $it -> virtual")
                }
            } else {
                try {
                    val surface = output.javaClass.getMethod("getSurface").invoke(output) as? Surface
                    if (surface != null) {
                        virtualSurfaceMap[surface] = virtualSurface
                        Logger.d("All Surface Tracking (reflection): $surface -> virtual")
                    }
                } catch (e: Throwable) {
                    // Ignore
                }
            }
        }

        CameraState.logState()
    }

    private fun createVirtualSurface(): Surface {
        try {
            val texture = android.graphics.SurfaceTexture(0)
            val surface = Surface(texture)

            CameraState.addSurface(
                surface,
                CameraState.SurfaceConfig(
                    CameraState.currentFormat,
                    CameraState.currentWidth,
                    CameraState.currentHeight,
                    "virtual"
                )
            )
            Logger.d("Virtual surface created")
            return surface
        } catch (e: Throwable) {
            Logger.e("Failed to create virtual surface", e)
            throw e
        }
    }

    private fun hookOnImageAvailableListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        val listenerClass = XposedHelpers.findClass(
            "android.media.ImageReader$OnImageAvailableListener",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            listenerClass,
            "onImageAvailable",
            XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (MainHook.isHookingActive && MainHook.dataBuffer.isNotEmpty()) {
                        val reader = param.args[0] as? ImageReader
                        reader?.let {
                            try {
                                val image = it.acquireLatestImage()
                                image?.let { img ->
                                    replaceImageData(img, MainHook.dataBuffer)
                                    img.close()
                                }
                            } catch (e: Throwable) {
                                Logger.e("Error in onImageAvailable", e)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun replaceImageData(image: Image, frameData: ByteArray) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            buffer.rewind()
            val copySize = minOf(frameData.size, buffer.remaining())
            buffer.put(frameData, 0, copySize)
        } catch (e: Throwable) {
            Logger.e("Failed to replace image data", e)
        }
    }

    private fun hookCreateCaptureRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cameraDeviceClass = XposedHelpers.findClass(
            "android.hardware.camera2.CameraDevice",
            lpparam.classLoader
        )

        XposedHelpers.findAndHookMethod(
            cameraDeviceClass,
            "createCaptureRequest",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (MainHook.isHookingActive) {
                        val builder = param.result
                        if (builder != null) {
                            try {
                                val fpsRange = builder.javaClass.getMethod("get", Any::class.java)
                                    .invoke(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                                if (fpsRange is Range<*>) {
                                    val max = fpsRange.upper as Int
                                    CameraState.requestedFps = max
                                    VideoDecoder.setTargetFps(max)
                                }
                            } catch (e: Throwable) {
                                // Ignore if key not present
                            }
                            Logger.d("CaptureRequest created")
                        }
                    }
                }
            }
        )
    }

    private fun hookSetRepeatingRequest(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sessionClass = XposedHelpers.findClass(
                "android.hardware.camera2.CameraCaptureSession",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                sessionClass,
                "setRepeatingRequest",
                XposedHelpers.findClass("android.hardware.camera2.CaptureRequest", lpparam.classLoader),
                XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession$CaptureCallback", lpparam.classLoader),
                android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val request = param.args[0]
                            try {
                                val fpsRange = request.javaClass.getMethod("get", Any::class.java)
                                    .invoke(request, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                                if (fpsRange is Range<*>) {
                                    val max = fpsRange.upper as Int
                                    CameraState.requestedFps = max
                                    VideoDecoder.setTargetFps(max)
                                }
                            } catch (e: Throwable) {
                                // Ignore
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.d("setRepeatingRequest hook not available")
        }
    }

    private fun hookDisconnect(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val stateCallbackClass = XposedHelpers.findClass(
                "android.hardware.camera2.CameraDevice.StateCallback",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                stateCallbackClass,
                "onDisconnected",
                CameraDevice::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d("Camera2 onDisconnected - resetting state")
                        CameraState.reset()
                        virtualSurfaceMap.clear()
                        surfaceCount = 0
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                stateCallbackClass,
                "onError",
                CameraDevice::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d("Camera2 onError - resetting state")
                        CameraState.reset()
                        virtualSurfaceMap.clear()
                        surfaceCount = 0
                    }
                }
            )

            // Hook CameraDevice.close()
            try {
                val cameraDeviceClass = XposedHelpers.findClass(
                    "android.hardware.camera2.CameraDevice",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    cameraDeviceClass,
                    "close",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            Logger.d("CameraDevice.close() - resetting state")
                            CameraState.reset()
                            virtualSurfaceMap.clear()
                            surfaceCount = 0
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("CameraDevice.close hook not available")
            }

        } catch (e: Throwable) {
            Logger.e("Failed to hook disconnect/error", e)
        }
    }
}