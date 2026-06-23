package com.itsme.amkush.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itsme.amkush.AppState
import com.itsme.amkush.R
import com.itsme.amkush.ipc.RemoteConfig
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
                // isRunning is set in onCreate() after the service actually starts
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
        isRunning = true
        AppState.context = applicationContext
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Logger.d("InjectionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package")
        val streamUrl = intent?.getStringExtra("stream_url")
        val mediaUri = intent?.getStringExtra("media_uri")

        if (!targetPackage.isNullOrEmpty()) {
            Logger.d("InjectionService: config → pkg=$targetPackage stream=$streamUrl media=$mediaUri")
            // Write config into the cross-process ContentProvider so the Xposed hook
            // (running in the target app's process) can read it and launch its decoder.
            RemoteConfig.setTargetPackage(this, targetPackage)
            RemoteConfig.setStreamUrl(this, streamUrl)
            RemoteConfig.setMediaUri(this, mediaUri)
            RemoteConfig.setInjectionActive(this, true)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Signal hooked process that injection is stopping
        RemoteConfig.clearAll(this)
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