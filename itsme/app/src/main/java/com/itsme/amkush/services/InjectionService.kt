package com.itsme.amkush.services

  import android.app.*
  import android.content.Context
  import android.content.Intent
  import android.os.Build
  import android.os.IBinder
  import androidx.core.app.NotificationCompat
  import com.itsme.amkush.AppState
  import com.itsme.amkush.R
  import com.itsme.amkush.hooks.ConfigUpdateReceiver
  import com.itsme.amkush.ipc.RemoteConfig
  import com.itsme.amkush.utils.Logger

  class InjectionService : Service() {

      companion object {
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
          val streamUrl     = intent?.getStringExtra("stream_url")
          val mediaUri      = intent?.getStringExtra("media_uri")

          if (!targetPackage.isNullOrEmpty()) {
              Logger.d("InjectionService: config → pkg=$targetPackage stream=$streamUrl media=$mediaUri")

              // Write config into the cross-process ContentProvider so any freshly-hooked
              // process can read it via RemoteConfig.
              RemoteConfig.setTargetPackage(this, targetPackage)
              RemoteConfig.setStreamUrl(this, streamUrl)
              RemoteConfig.setMediaUri(this, mediaUri)
              RemoteConfig.setInjectionActive(this, true)

              // Also broadcast the change so an ALREADY-RUNNING hooked process can swap
              // its active stream immediately — no target-app restart required.
              // ConfigUpdateReceiver (registered inside the hooked process) catches this.
              sendConfigBroadcast(streamUrl = streamUrl, mediaUri = mediaUri, active = true)
          }

          return START_STICKY
      }

      override fun onDestroy() {
          RemoteConfig.clearAll(this)
          // Signal the hooked process to stop injection
          sendConfigBroadcast(streamUrl = null, mediaUri = null, active = false)
          isRunning = false
          Logger.d("InjectionService destroyed")
          super.onDestroy()
      }

      override fun onBind(intent: Intent?): IBinder? = null

      // ── Helpers ───────────────────────────────────────────────────────────────

      /**
       * Send a broadcast to the hooked target process.
       *
       * On rooted devices (LSPosed) this is a standard cross-process broadcast.
       * In virtual cloner environments (Mochi Cloner, VirtualXposed, etc.) the
       * broadcast travels within the cloner's virtual process space where both
       * the module app and the target app reside.
       */
      private fun sendConfigBroadcast(streamUrl: String?, mediaUri: String?, active: Boolean) {
          try {
              val broadcast = Intent(ConfigUpdateReceiver.ACTION).apply {
                  putExtra(ConfigUpdateReceiver.EXTRA_STREAM_URL, streamUrl)
                  putExtra(ConfigUpdateReceiver.EXTRA_MEDIA_URI,  mediaUri)
                  putExtra(ConfigUpdateReceiver.EXTRA_ACTIVE,     active)
              }
              sendBroadcast(broadcast)
              Logger.d("InjectionService: broadcast sent (active=$active url=$streamUrl)")
          } catch (e: Throwable) {
              Logger.e("InjectionService: broadcast failed", e)
          }
      }

      private fun createNotificationChannel() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val channel = NotificationChannel(
                  CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
              ).apply { description = "FaceGate injection service"; setShowBadge(false) }
              getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
          }
      }

      private fun createNotification(): Notification =
          NotificationCompat.Builder(this, CHANNEL_ID)
              .setContentTitle("FaceGate")
              .setContentText("Camera injection active")
              .setSmallIcon(R.drawable.ic_notification)
              .setPriority(NotificationCompat.PRIORITY_LOW)
              .setOngoing(true)
              .setAutoCancel(false)
              .build()
  }
  