package com.itsme.amkush.hooks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import com.itsme.amkush.AppState
import com.itsme.amkush.ipc.ISurfaceInjector
import com.itsme.amkush.utils.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * GStreamerSurfaceRouter
 *
 * Runs INSIDE the hooked target-app process (Xposed context).
 *
 * Responsibilities:
 *   1. Maintain a Binder connection to InjectionService in the module process.
 *   2. When Camera2Hooks / Camera1Hooks collect surfaces from intercepted
 *      createCaptureSession / setPreviewDisplay calls, forward them to
 *      InjectionService via ISurfaceInjector.registerSurfaces().
 *   3. Track which "device handle" owns which session so stop/cleanup works.
 *
 * Connection lifecycle:
 *   - First routeSurfaces() call triggers connectToInjectionService().
 *   - Service reconnects automatically on disconnect (ServiceConnection.onServiceDisconnected).
 *   - stopSession() sends unregisterSession() over Binder.
 */
object GStreamerSurfaceRouter {

    private const val TAG = "GSurfaceRouter"
    private const val MODULE_PKG       = "com.itsme.amkush"
    private const val INJECTOR_ACTION  = "com.itsme.amkush.action.SURFACE_INJECTOR"

    @Volatile private var injector: ISurfaceInjector? = null
    @Volatile private var bindPending = false

    // Maps device/camera handle → sessionId
    private val deviceSessions: ConcurrentHashMap<Any, String> = ConcurrentHashMap()

    // Queued deliveries when the Binder isn't connected yet
    private data class PendingDelivery(
        val surfaces: List<Surface>,
        val widths: IntArray,
        val heights: IntArray,
        val formats: IntArray,
        val sessionId: String
    )
    private val pendingDeliveries: ArrayDeque<PendingDelivery> = ArrayDeque()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            injector = ISurfaceInjector.Stub.asInterface(binder)
            bindPending = false
            Logger.d("$TAG connected to InjectionService")
            drainPending()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            Logger.d("$TAG disconnected from InjectionService — will reconnect on next call")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Route collected camera surfaces to the GStreamer pipeline in the module process.
     * Thread-safe; safe to call from any Xposed hook thread.
     *
     * [owner]     — the fake CameraDevice / Camera1 handle (for session tracking)
     * [surfaces]  — the Surface objects from createCaptureSession / setPreviewDisplay
     * [widths]    — per-surface expected width
     * [heights]   — per-surface expected height
     * [formats]   — per-surface ImageFormat constant
     * [sessionId] — unique identifier for this session
     */
    fun routeSurfaces(
        owner: Any,
        surfaces: List<Any>,
        widths: IntArray,
        heights: IntArray,
        formats: IntArray,
        sessionId: String
    ) {
        if (surfaces.isEmpty()) return
        deviceSessions[owner] = sessionId

        val filtered = surfaces.filterIsInstance<Surface>()
        if (filtered.isEmpty()) return

        val currentInjector = injector
        if (currentInjector != null) {
            deliverNow(currentInjector, filtered, widths, heights, formats, sessionId)
        } else {
            synchronized(pendingDeliveries) {
                pendingDeliveries.addLast(PendingDelivery(filtered, widths, heights, formats, sessionId))
            }
            connectToInjectionService()
        }
    }

    /**
     * Tell InjectionService to stop writing to the surfaces for this device.
     * Called when a fake CameraDevice.close() or Camera.release() is intercepted.
     */
    fun stopSession(owner: Any) {
        val sessionId = deviceSessions.remove(owner) ?: return
        try {
            injector?.unregisterSession(sessionId)
            Logger.d("$TAG unregisterSession: $sessionId")
        } catch (e: Throwable) {
            Logger.e("$TAG stopSession failed", e)
        }
    }

    fun stopAll() {
        try { injector?.stopAll() } catch (_: Throwable) {}
        deviceSessions.clear()
    }

    // ── Binder connection ─────────────────────────────────────────────────────

    private fun connectToInjectionService() {
        if (bindPending || injector != null) return
        val ctx = AppState.context ?: run {
            Logger.e("$TAG cannot connect — no context"); return
        }
        synchronized(this) {
            if (bindPending || injector != null) return
            bindPending = true
            try {
                val intent = Intent(INJECTOR_ACTION).setPackage(MODULE_PKG)
                val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    bindPending = false
                    Logger.e("$TAG bindService returned false — InjectionService not reachable")
                } else {
                    Logger.d("$TAG bindService sent to InjectionService")
                }
            } catch (e: Throwable) {
                bindPending = false
                Logger.e("$TAG connectToInjectionService failed", e)
            }
        }
    }

    private fun deliverNow(
        inj: ISurfaceInjector,
        surfaces: List<Surface>,
        widths: IntArray,
        heights: IntArray,
        formats: IntArray,
        sessionId: String
    ) {
        try {
            inj.registerSurfaces(surfaces, widths, heights, formats, sessionId)
            Logger.d("$TAG delivered ${surfaces.size} surface(s) for session=$sessionId")
        } catch (e: Throwable) {
            Logger.e("$TAG deliverNow failed (session=$sessionId)", e)
        }
    }

    private fun drainPending() {
        val inj = injector ?: return
        synchronized(pendingDeliveries) {
            while (pendingDeliveries.isNotEmpty()) {
                val d = pendingDeliveries.removeFirst()
                deliverNow(inj, d.surfaces, d.widths, d.heights, d.formats, d.sessionId)
            }
        }
    }
}
