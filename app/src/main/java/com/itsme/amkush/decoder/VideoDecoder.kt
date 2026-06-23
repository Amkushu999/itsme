package com.itsme.amkush.decoder

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.itsme.amkush.AppState
import com.itsme.amkush.utils.Logger
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object VideoDecoder {

    private const val TAG = "FaceGate"

    private var decoderThread: HandlerThread? = null
    private var decoderHandler: Handler? = null
    private var deliveryHandler: Handler? = null
    private var deliveryThread: HandlerThread? = null
    private var isDecoding = false
    private var currentSource: String? = null
    private var reconnectAttempts = 0
    private var maxReconnectAttempts = 5

    private var panX: Float = 0f
    private var panY: Float = 0f
    private var zoom: Float = 1f
    private var targetFormat: Int = ImageFormat.NV21
    private var targetWidth: Int = 640
    private var targetHeight: Int = 480
    private var targetFps: Int = 30
    private var frameIndex: Int = 0
    private var lastFpsUpdateTime: Long = 0
    private var framesSinceLastFpsUpdate: Int = 0

    private val mQueue = LinkedBlockingQueue<ByteArray>()

    interface Callback {
        fun onDecodeFrame(frame: ByteArray, index: Int)
        fun onFinishDecode()
        fun onError(error: String)
        fun onFpsChanged(fps: Int)
        fun onQueueSizeChanged(size: Int)
        fun onDecoderTypeChanged(type: String)
        fun onReconnecting(attempt: Int)
    }

    private var callback: Callback? = null

    private val decodeRunnable = Runnable {
        decodeLoopWithRetry()
    }

    private val deliveryRunnable = Runnable {
        deliveryLoop()
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun getQueue(): LinkedBlockingQueue<ByteArray> = mQueue

    fun startStream(url: String) {
        currentSource = url
        reconnectAttempts = 0
        startDecoder()
    }

    fun startMedia(uri: String) {
        currentSource = uri
        reconnectAttempts = 0
        startDecoder()
    }

    fun stop() {
        isDecoding = false
        decoderHandler?.removeCallbacks(decodeRunnable)
        deliveryHandler?.removeCallbacks(deliveryRunnable)
        decoderThread?.quitSafely()
        deliveryThread?.quitSafely()
        decoderThread = null
        deliveryThread = null
        decoderHandler = null
        deliveryHandler = null
        mQueue.clear()
        frameIndex = 0
        reconnectAttempts = 0
        Logger.d("VideoDecoder stopped")
        callback?.onFinishDecode()
    }

    fun setPanZoom(panX: Float, panY: Float, zoom: Float) {
        this.panX = panX
        this.panY = panY
        this.zoom = zoom
    }

    fun setTargetFormat(format: Int) {
        this.targetFormat = format
    }

    fun setTargetSize(width: Int, height: Int) {
        this.targetWidth = width
        this.targetHeight = height
    }

    fun setTargetFps(fps: Int) {
        if (fps > 0 && fps != this.targetFps) {
            this.targetFps = fps
            Logger.d("Target FPS updated to: $fps")
            callback?.onFpsChanged(fps)

            if (isDecoding) {
                deliveryHandler?.removeCallbacks(deliveryRunnable)
                deliveryHandler?.post(deliveryRunnable)
            }
        }
    }

    fun getTargetFps(): Int = targetFps
    fun getFrameCount(): Int = frameIndex
    fun getQueueSize(): Int = mQueue.size

    private fun startDecoder() {
        if (isDecoding) {
            stop()
        }

        decoderThread = HandlerThread("FaceGateDecoder").apply { start() }
        decoderHandler = Handler(decoderThread!!.looper)

        deliveryThread = HandlerThread("FaceGateDelivery").apply { start() }
        deliveryHandler = Handler(deliveryThread!!.looper)

        isDecoding = true
        frameIndex = 0
        mQueue.clear()
        framesSinceLastFpsUpdate = 0
        lastFpsUpdateTime = System.currentTimeMillis()

        decoderHandler?.post(decodeRunnable)
        deliveryHandler?.post(deliveryRunnable)

        Logger.d("VideoDecoder started with target FPS: $targetFps")
        callback?.onFpsChanged(targetFps)
    }

    private fun decodeLoopWithRetry() {
        while (isDecoding) {
            try {
                if (reconnectAttempts > 0) {
                    val delayMs = 1000L * reconnectAttempts
                    callback?.onReconnecting(reconnectAttempts)
                    Logger.d("Reconnecting attempt $reconnectAttempts, waiting ${delayMs}ms")
                    Thread.sleep(delayMs.coerceAtMost(10000))
                }
                decodeLoop()
                reconnectAttempts = 0
            } catch (e: Exception) {
                reconnectAttempts++
                Logger.e("Decode error (attempt $reconnectAttempts/$maxReconnectAttempts)", e)
                callback?.onError("Reconnection attempt $reconnectAttempts: ${e.message}")

                if (reconnectAttempts >= maxReconnectAttempts) {
                    callback?.onError("Max reconnection attempts ($maxReconnectAttempts) reached")
                    break
                }
            }
        }
        Logger.d("Decode loop with retry ended")
    }

    private fun decodeLoop() {
        Logger.d("Decode loop started")

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                currentSource?.let { source ->
                    if (source.startsWith("http") || source.startsWith("rtsp") || source.startsWith("rtmp")) {
                        setDataSource(source)
                    } else {
                        val uri = Uri.parse(source)
                        if (uri.scheme == "content") {
                            setDataSource(AppState.context ?: return@let, uri, null)
                        } else {
                            setDataSource(source)
                        }
                    }
                }
            }

            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                val error = "No video track found in source"
                Logger.e(error)
                callback?.onError(error)
                return
            }

            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return

            decoder = MediaCodec.createDecoderByType(mime)
            logDecoderType(decoder, mime)

            val caps = decoder.codecInfo.getCapabilitiesForType(mime)

            val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            if (isColorFormatSupported(colorFormat, caps)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            }

            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            while (isDecoding) {
                val hasMoreFrames = decodeFrames(decoder, extractor, mediaFormat)
                if (!hasMoreFrames) {
                    Logger.d("End of stream reached, looping")
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.stop()
                    decoder.start()
                    callback?.onFinishDecode()
                }
            }

        } catch (e: Exception) {
            Logger.e("Decode error", e)
            callback?.onError(e.message ?: "Decode error")
            throw e
        } finally {
            decoder?.release()
            extractor?.release()
        }
    }

    private fun logDecoderType(decoder: MediaCodec, mime: String) {
        try {
            val info = decoder.codecInfo
            val caps = info.getCapabilitiesForType(mime)

            // Note: isHardwareAccelerated, isSoftwareOnly, isVendor require API 29+
            // For older APIs, use name detection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // These APIs are available on API 29+
                // Use reflection to avoid compilation errors on older SDKs
                try {
                    val isHardware = caps.javaClass.getMethod("isHardwareAccelerated").invoke(caps) as? Boolean ?: false
                    val isSoftware = caps.javaClass.getMethod("isSoftwareOnly").invoke(caps) as? Boolean ?: false
                    val isVendor = caps.javaClass.getMethod("isVendor").invoke(caps) as? Boolean ?: false

                    val type = when {
                        isHardware -> "HARDWARE"
                        isSoftware -> "SOFTWARE"
                        else -> "UNKNOWN"
                    }

                    Logger.d("Decoder: $mime -> $type (Hardware=$isHardware, Software=$isSoftware, Vendor=$isVendor)")
                    callback?.onDecoderTypeChanged(type)
                } catch (e: Exception) {
                    // Reflection failed
                    fallbackDecoderDetection(info, mime)
                }
            } else {
                fallbackDecoderDetection(info, mime)
            }
        } catch (e: Exception) {
            Logger.e("Failed to detect decoder type", e)
        }
    }

    private fun fallbackDecoderDetection(info: MediaCodecInfo, mime: String) {
        val name = info.name
        val isSoftware = name.startsWith("OMX.google.") || name.startsWith("c2.android.")
        val type = if (isSoftware) "SOFTWARE" else "HARDWARE"
        Logger.d("Decoder: $name -> $type")
        callback?.onDecoderTypeChanged(type)
    }

    private fun deliveryLoop() {
        Logger.d("Delivery loop started at ${targetFps}FPS")
        val intervalMs = if (targetFps > 0) 1000 / targetFps else 33

        while (isDecoding) {
            try {
                val frame = mQueue.poll(intervalMs.toLong(), TimeUnit.MILLISECONDS)

                if (frame != null && frame.isNotEmpty()) {
                    AppState.dataBuffer = frame

                    framesSinceLastFpsUpdate++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsUpdateTime >= 1000) {
                        callback?.onFpsChanged(framesSinceLastFpsUpdate)
                        framesSinceLastFpsUpdate = 0
                        lastFpsUpdateTime = now
                    }

                    callback?.onDecodeFrame(frame, frameIndex)
                    callback?.onQueueSizeChanged(mQueue.size)
                    frameIndex++
                }

                if (frame == null) {
                    Thread.sleep(1)
                }

            } catch (e: InterruptedException) {
                Logger.d("Delivery loop interrupted")
                break
            } catch (e: Exception) {
                Logger.e("Delivery error", e)
            }
        }
        Logger.d("Delivery loop ended")
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }

    private fun isColorFormatSupported(colorFormat: Int, caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return caps.colorFormats.any { it == colorFormat }
    }

    private fun decodeFrames(decoder: MediaCodec, extractor: MediaExtractor, mediaFormat: MediaFormat): Boolean {
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var hasMoreFrames = false
        val timeoutUs: Long = 10000

        while (!sawOutputEOS && isDecoding) {
            if (!sawInputEOS) {
                val inputBufferId = decoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferId >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferId)
                    inputBuffer?.let { buffer ->
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferId, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(
                                inputBufferId, 0, sampleSize,
                                presentationTimeUs, 0
                            )
                            extractor.advance()
                            hasMoreFrames = true
                        }
                    }
                }
            }

            val outputBufferId = decoder.dequeueOutputBuffer(info, timeoutUs)
            if (outputBufferId >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                    hasMoreFrames = false
                }

                if (info.size > 0) {
                    val image = decoder.getOutputImage(outputBufferId)
                    image?.let { img ->
                        val data = convertImageToFormat(img)
                        if (data.isNotEmpty()) {
                            try {
                                mQueue.put(data)
                            } catch (e: InterruptedException) {
                                Logger.e("Queue put interrupted", e)
                            }
                        }
                        img.close()
                    }
                    decoder.releaseOutputBuffer(outputBufferId, true)
                } else {
                    decoder.releaseOutputBuffer(outputBufferId, false)
                }
            }
        }

        return hasMoreFrames
    }

    private fun convertImageToFormat(image: Image): ByteArray {
        return when (targetFormat) {
            ImageFormat.NV21, ImageFormat.YUV_420_888 -> {
                convertToNV21(image)
            }
            ImageFormat.JPEG -> {
                convertToJPEG(image)
            }
            else -> {
                convertToNV21(image)
            }
        }
    }

    private fun convertToNV21(image: Image): ByteArray {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    private fun convertToJPEG(image: Image): ByteArray {
        val bitmap = imageToBitmap(image)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val nv21 = convertToNV21(image)
        val yuvImage = android.graphics.YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )
        val out = ByteArrayOutputStream()
        val rect = android.graphics.Rect(0, 0, image.width, image.height)
        yuvImage.compressToJpeg(rect, 75, out)
        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        return if (zoom != 1f || panX != 0f || panY != 0f) applyPanZoomToBitmap(bitmap) else bitmap
    }

    private fun applyPanZoomToBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postScale(zoom, zoom)
        matrix.postTranslate(panX * bitmap.width, panY * bitmap.height)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}