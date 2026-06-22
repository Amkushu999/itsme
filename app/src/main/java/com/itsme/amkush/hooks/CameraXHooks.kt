package com.itsme.amkush.hooks

import android.util.Range
import androidx.camera.core.CameraState as CameraXState
import com.itsme.amkush.CameraState
import com.itsme.amkush.MainHook
import com.itsme.amkush.decoder.VideoDecoder
import com.itsme.amkush.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CameraXHooks {

    private const val TAG = "FaceGate"

    fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            hookImageAnalysisAnalyzer(lpparam)
            hookImageAnalysisBuilder(lpparam)
            hookCameraProviderBind(lpparam)
            hookImageAnalysisSetTargetFps(lpparam)
            hookVideoCaptureBuilder(lpparam)
            hookCameraStateObserver(lpparam)
            hookCameraClose(lpparam)
            Logger.d("CameraX hooks installed")
        } catch (e: Throwable) {
            Logger.e("CameraX hooks failed", e)
        }
    }

    private fun hookImageAnalysisAnalyzer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val imageAnalysisClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis",
                lpparam.classLoader
            )

            val analyzerClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis\$Analyzer",
                lpparam.classLoader
            )

            val imageProxyClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageProxy",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                imageAnalysisClass,
                "setAnalyzer",
                java.util.concurrent.Executor::class.java,
                analyzerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            val originalAnalyzer = param.args[1]

                            val wrappedAnalyzer = object {
                                fun analyze(imageProxy: Any) {
                                    try {
                                        val method = originalAnalyzer.javaClass.getMethod("analyze", imageProxyClass)
                                        if (MainHook.dataBuffer.isNotEmpty()) {
                                            val fakeImage = createFakeImageProxy(imageProxy)
                                            if (fakeImage != null) {
                                                method.invoke(originalAnalyzer, fakeImage)
                                                return
                                            }
                                        }
                                        method.invoke(originalAnalyzer, imageProxy)
                                    } catch (e: Throwable) {
                                        Logger.e("Analyzer wrap error", e)
                                    }
                                }
                            }

                            param.args[1] = wrappedAnalyzer
                            Logger.d("CameraX setAnalyzer hooked")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("CameraX Analyzer hook failed", e)
        }
    }

    private fun hookImageAnalysisBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis\$Builder",
                lpparam.classLoader
            )

            val sizeClass = XposedHelpers.findClass(
                "android.util.Size",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                builderClass,
                "setTargetResolution",
                sizeClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val size = param.args[0] as? android.util.Size
                        if (size != null) {
                            CameraState.currentWidth = size.width
                            CameraState.currentHeight = size.height
                            VideoDecoder.setTargetSize(size.width, size.height)
                            Logger.d("CameraX target resolution: ${size.width}x${size.height}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("CameraX Builder hook failed", e)
        }
    }

    private fun hookCameraProviderBind(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val providerClass = XposedHelpers.findClass(
                "androidx.camera.core.CameraProvider",
                lpparam.classLoader
            )

            val lifecycleClass = XposedHelpers.findClass(
                "androidx.lifecycle.LifecycleOwner",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                providerClass,
                "bindToLifecycle",
                lifecycleClass,
                XposedHelpers.findClass("androidx.camera.core.CameraSelector", lpparam.classLoader),
                Class.forName("androidx.camera.core.UseCase"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (MainHook.isHookingActive) {
                            Logger.d("CameraX bindToLifecycle intercepted")
                            CameraState.isPreviewActive = true

                            val useCases = param.args.drop(2)
                            for (useCase in useCases) {
                                readFpsFromUseCase(useCase)
                                observeCameraState(useCase)
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("CameraX bindToLifecycle hook failed", e)
        }
    }

    private fun hookImageAnalysisSetTargetFps(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val imageAnalysisBuilderClass = XposedHelpers.findClass(
                "androidx.camera.core.ImageAnalysis\$Builder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                imageAnalysisBuilderClass,
                "setTargetFps",
                Range::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fpsRange = param.args[0] as? Range<*>
                        if (fpsRange != null) {
                            val max = fpsRange.upper as Int
                            Logger.d("CameraX ImageAnalysis setTargetFps: $max")
                            CameraState.requestedFps = max
                            VideoDecoder.setTargetFps(max)
                        }
                    }
                }
            )

            Logger.d("CameraX ImageAnalysis.setTargetFps hook installed")
        } catch (e: Throwable) {
            Logger.d("CameraX ImageAnalysis.setTargetFps hook not available")
        }
    }

    private fun hookVideoCaptureBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val videoCaptureBuilderClass = XposedHelpers.findClass(
                "androidx.camera.core.VideoCapture\$Builder",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                videoCaptureBuilderClass,
                "setTargetFps",
                Range::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fpsRange = param.args[0] as? Range<*>
                        if (fpsRange != null) {
                            val max = fpsRange.upper as Int
                            Logger.d("CameraX VideoCapture setTargetFps: $max")
                            CameraState.requestedFps = max
                            VideoDecoder.setTargetFps(max)
                        }
                    }
                }
            )

            Logger.d("CameraX VideoCapture.setTargetFps hook installed")
        } catch (e: Throwable) {
            Logger.d("CameraX VideoCapture.setTargetFps hook not available")
        }
    }

    private fun hookCameraStateObserver(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cameraClass = XposedHelpers.findClass(
                "androidx.camera.core.Camera",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "getCameraState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = param.result as? CameraXState
                        if (state != null) {
                            Logger.d("CameraX CameraState: ${state.getType()}")
                            when (state.getType()) {
                                CameraXState.Type.CLOSED -> {
                                    Logger.d("CameraX camera closed - resetting state")
                                    CameraState.reset()
                                }
                                CameraXState.Type.CLOSING -> Logger.d("CameraX camera closing")
                                CameraXState.Type.OPENING -> Logger.d("CameraX camera opening")
                                CameraXState.Type.OPEN -> Logger.d("CameraX camera open")
                                else -> {}
                            }
                        }
                    }
                }
            )

            try {
                val liveDataClass = XposedHelpers.findClass(
                    "androidx.lifecycle.LiveData",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    liveDataClass,
                    "observe",
                    XposedHelpers.findClass("androidx.lifecycle.LifecycleOwner", lpparam.classLoader),
                    XposedHelpers.findClass("androidx.lifecycle.Observer", lpparam.classLoader),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val observer = param.args[1]
                                val observerClass = observer?.javaClass
                                if (observerClass != null) {
                                    XposedHelpers.findAndHookMethod(
                                        observerClass,
                                        "onChanged",
                                        Any::class.java,
                                        object : XC_MethodHook() {
                                            override fun beforeHookedMethod(param: MethodHookParam) {
                                                val state = param.args[0] as? CameraXState
                                                if (state != null && state.getType() == CameraXState.Type.CLOSED) {
                                                    Logger.d("CameraX state observer: camera CLOSED - resetting state")
                                                    CameraState.reset()
                                                }
                                            }
                                        }
                                    )
                                }
                            } catch (e: Throwable) {
                                // Ignore
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("CameraX state observer hook not available")
            }

        } catch (e: Throwable) {
            Logger.e("CameraX state observer hook failed", e)
        }
    }

    private fun hookCameraClose(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cameraClass = XposedHelpers.findClass(
                "androidx.camera.core.Camera",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                cameraClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d("CameraX.close() called - resetting state")
                        CameraState.reset()
                    }
                }
            )

            try {
                val providerClass = XposedHelpers.findClass(
                    "androidx.camera.core.CameraProvider",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    providerClass,
                    "unbind",
                    Array<Any>::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            Logger.d("CameraX.unbind() called - resetting state")
                            CameraState.reset()
                        }
                    }
                )
            } catch (e: Throwable) {
                Logger.d("CameraX.unbind hook not available")
            }

        } catch (e: Throwable) {
            Logger.d("CameraX.close hook not available")
        }
    }

    private fun readFpsFromUseCase(useCase: Any) {
        try {
            try {
                val method = useCase.javaClass.getMethod("getTargetFps")
                val fps = method.invoke(useCase)
                if (fps is Range<*>) {
                    val max = fps.upper as Int
                    Logger.d("CameraX UseCase targetFps: $max")
                    CameraState.requestedFps = max
                    VideoDecoder.setTargetFps(max)
                }
            } catch (e: Throwable) { /* Method not available */ }

            try {
                val method = useCase.javaClass.getMethod("getTargetFrameRate")
                val fps = method.invoke(useCase)
                if (fps is Range<*>) {
                    val max = fps.upper as Int
                    Logger.d("CameraX UseCase targetFrameRate: $max")
                    CameraState.requestedFps = max
                    VideoDecoder.setTargetFps(max)
                }
            } catch (e: Throwable) { /* Method not available */ }

            try {
                val method = useCase.javaClass.getMethod("getFrameRateRange")
                val fps = method.invoke(useCase)
                if (fps is Range<*>) {
                    val max = fps.upper as Int
                    Logger.d("CameraX UseCase frameRateRange: $max")
                    CameraState.requestedFps = max
                    VideoDecoder.setTargetFps(max)
                }
            } catch (e: Throwable) { /* Method not available */ }
        } catch (e: Throwable) {
            Logger.e("Failed to read FPS from UseCase", e)
        }
    }

    private fun observeCameraState(useCase: Any) {
        try {
            try {
                val method = useCase.javaClass.getMethod("getCameraState")
                val state = method.invoke(useCase) as? CameraXState
                if (state != null && state.getType() == CameraXState.Type.CLOSED) {
                    Logger.d("CameraX UseCase: camera CLOSED - resetting state")
                    CameraState.reset()
                }
            } catch (e: Throwable) { /* Method not available */ }
        } catch (e: Throwable) { /* Ignore */ }
    }

    private fun createFakeImageProxy(original: Any): Any? {
        if (MainHook.dataBuffer.isEmpty()) return null
        return try {
            val planesMethod = original.javaClass.getMethod("getPlanes")
            val planes = planesMethod.invoke(original) as? Array<*> ?: return null
            if (planes.isEmpty()) return null

            val plane = planes[0] ?: return null
            val bufferMethod = plane.javaClass.getMethod("getBuffer")
            val byteBuffer = bufferMethod.invoke(plane) as? java.nio.ByteBuffer ?: return null

            if (byteBuffer.isReadOnly) return null

            val src = MainHook.dataBuffer
            val copyLen = minOf(src.size, byteBuffer.capacity())
            byteBuffer.clear()
            byteBuffer.put(src, 0, copyLen)
            byteBuffer.rewind()

            original
        } catch (e: Throwable) {
            Logger.e("createFakeImageProxy failed", e)
            null
        }
    }
}