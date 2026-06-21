package com.itsme.amkush.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itsme.amkush.R
import com.itsme.amkush.decoder.VideoDecoder
import com.itsme.amkush.utils.Logger

class InjectionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "facegate_injection_channel"
        private const val CHANNEL_NAME = "FaceGate Injection Service"

        var isRunning = false
            private set

        fun start(context: Context, targetPackage: String, streamUrl: String? = null, mediaUri: String? = null) {
            if (!isRunning) {
                val intent = Intent(context, InjectionService::class.java).apply {
                    putExtra("target_package", targetPackage)
                    putExtra("stream_url", streamUrl)
                    putExtra("media_uri", mediaUri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                isRunning = true
            }
        }

        fun stop(context: Context) {
            if (isRunning) {
                val intent = Intent(context, InjectionService::class.java)
                context.stopService(intent)
                isRunning = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Logger.d("InjectionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package")
        val streamUrl = intent?.getStringExtra("stream_url")
        val mediaUri = intent?.getStringExtra("media_uri")

        if (!targetPackage.isNullOrEmpty()) {
            Logger.d("InjectionService targeting: $targetPackage")

            when {
                !streamUrl.isNullOrEmpty() -> {
                    Logger.d("Starting stream decoder: $streamUrl")
                    VideoDecoder.startStream(streamUrl)
                }
                !mediaUri.isNullOrEmpty() -> {
                    Logger.d("Starting media decoder: $mediaUri")
                    VideoDecoder.startMedia(mediaUri)
                }
                else -> {
                    Logger.d("No stream or media provided")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        VideoDecoder.stop()
        isRunning = false
        Logger.d("InjectionService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FaceGate injection service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceGate")
            .setContentText("Camera injection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}