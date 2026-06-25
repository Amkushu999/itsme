package com.itsme.amkush.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itsme.amkush.AppState
import com.itsme.amkush.R
import com.itsme.amkush.gstreamer.GStreamerPipeline
import com.itsme.amkush.gstreamer.SurfaceRouter
import com.itsme.amkush.hooks.ConfigUpdateReceiver
import com.itsme.amkush.ipc.ISurfaceInjector
import com.itsme.amkush.ipc.RemoteConfig
import com.itsme.amkush.utils.Logger
import android.view.Surface

/**
 * InjectionService — module process owner of the GStreamer pipeline.
 *
 * Responsibilities:
 *   1. Run as a foreground service (camera + mediaPlayback type).
 *   2. Expose ISurfaceInjector Binder so that Xposed hooks inside any hooked
 *      target-app process can deliver Surface objects for frame injection.
 *   3. Own and manage the GStreamer decoding pipeline (via GStreamerPipeline JNI).
 *   4. Forward decoded frames to SurfaceRouter which distributes them via
 *      ImageWriter to every registered Surface at the correct resolution/format.
 *   5. Broadcast config changes to already-running hooked processes so URL
 *      swaps take effect without restarting the target app.
 *
 * Threading model:
 *   - Binder calls arrive on Binder thread pool → forwarded to SurfaceRouter.
 *   - GStreamer decoded frames arrive on GStreamer streaming thread → forwarded
 *     to SurfaceRouter.onFrameAvailable() which writes to each ImageWriter.
 *   - ImageWriter.queueInputImage() is thread-safe.
 */
class InjectionService : Service() {

    companion object {
        private const val TAG          = "InjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "facegate_injection_channel"
        private const val CHANNEL_NAME    = "FaceGate Injection Service"

        @Volatile var isRunning = false
            private set

        fun start(
            context: Context,
            targetPackage: String,
            streamUrl: String? = null,
            mediaUri: String?  = null
        ) {
            if (!isRunning) {
                val intent = Intent(context, InjectionService::class.java).apply {
                    putExtra("target_package", targetPackage)
                    putExtra("stream_url",     streamUrl)
                    putExtra("media_uri",      mediaUri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            if (isRunning) {
                context.stopService(Intent(context, InjectionService::class.java))
                isRunning = false
            }
        }
    }

    // ── ISurfaceInjector Binder stub ─────────────────────────────────────────

    private val binderImpl = object : ISurfaceInjector.Stub() {

        /**
         * Called from Xposed hooks (hooked target-app process) via Binder.
         * Registers the camera surfaces and starts GStreamer frame delivery.
         */
        override fun registerSurfaces(
            surfaces: List<Surface>,
            widths: IntArray,
            heights: IntArray,
            formats: IntArray,
            sessionId: String
        ) {
            Logger.d("$TAG registerSurfaces: ${surfaces.size} surface(s) session=$sessionId")
            SurfaceRouter.registerSession(sessionId, surfaces, widths, heights, formats)
            ensurePipelineRunning()
        }

        override fun unregisterSession(sessionId: String) {
            Logger.d("$TAG unregisterSession: $sessionId")
            SurfaceRouter.unregisterSession(sessionId)
        }

        override fun stopAll() {
            Logger.d("$TAG stopAll")
            SurfaceRouter.unregisterAll()
            GStreamerPipeline.stopPipeline()
        }
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppState.context = applicationContext
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        // Initialise GStreamer (loads .so, calls gst_init)
        GStreamerPipeline.init(applicationContext)
        Logger.d("$TAG created — GStreamer ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package")
        val streamUrl     = intent?.getStringExtra("stream_url")
        val mediaUri      = intent?.getStringExtra("media_uri")

        if (!targetPackage.isNullOrEmpty()) {
            Logger.d("$TAG config → pkg=$targetPackage stream=$streamUrl media=$mediaUri")
            RemoteConfig.setTargetPackage(this, targetPackage)
            RemoteConfig.setStreamUrl(this, streamUrl)
            RemoteConfig.setMediaUri(this, mediaUri)
            RemoteConfig.setInjectionActive(this, true)
            sendConfigBroadcast(streamUrl = streamUrl, mediaUri = mediaUri, active = true)
            // Start / restart the GStreamer pipeline with the new URL
            val url = streamUrl ?: mediaUri
            if (!url.isNullOrEmpty()) {
                startGStreamerPipeline(url)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binderImpl

    override fun onDestroy() {
        GStreamerPipeline.cleanup()
        SurfaceRouter.unregisterAll()
        RemoteConfig.clearAll(this)
        sendConfigBroadcast(streamUrl = null, mediaUri = null, active = false)
        isRunning = false
        Logger.d("$TAG destroyed")
        super.onDestroy()
    }

    // ── GStreamer pipeline management ────────────────────────────────────────

    private fun ensurePipelineRunning() {
        if (GStreamerPipeline.pipelineRunning) return
        val url = RemoteConfig.getStreamUrl(this) ?: RemoteConfig.getMediaUri(this)
        if (!url.isNullOrEmpty()) {
            startGStreamerPipeline(url)
        } else {
            Logger.d("$TAG no stream URL configured yet — pipeline deferred")
        }
    }

    private fun startGStreamerPipeline(url: String) {
        Logger.d("$TAG starting GStreamer pipeline: $url")
        GStreamerPipeline.startPipeline(url, SurfaceRouter)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sendConfigBroadcast(streamUrl: String?, mediaUri: String?, active: Boolean) {
        try {
            val broadcast = Intent(ConfigUpdateReceiver.ACTION).apply {
                putExtra(ConfigUpdateReceiver.EXTRA_STREAM_URL, streamUrl)
                putExtra(ConfigUpdateReceiver.EXTRA_MEDIA_URI,  mediaUri)
                putExtra(ConfigUpdateReceiver.EXTRA_ACTIVE,     active)
            }
            sendBroadcast(broadcast)
        } catch (e: Throwable) {
            Logger.e("$TAG sendConfigBroadcast failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "FaceGate GStreamer injection service"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceGate")
            .setContentText("GStreamer camera injection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
}
