package com.itsme.amkush

import android.content.Context

/**
 * Neutral shared-state singleton — ZERO Xposed imports.
 *
 * Safe to reference from both runtime contexts:
 *   • Xposed process  — MainHook writes here after hooking Application.onCreate()
 *   • App own process — InjectionService writes context; VideoDecoder reads/writes dataBuffer
 *
 * This breaks the class-loading chain that caused NoClassDefFoundError:
 *   InjectionService → VideoDecoder → MainHook → IXposedHookLoadPackage (compileOnly, absent in APK)
 */
object AppState {
    @Volatile var context: Context? = null
    @Volatile var dataBuffer: ByteArray = ByteArray(0)
    @Volatile var isPlaying: Boolean = false
    @Volatile var targetPackage: String? = null
    @Volatile var isHookingActive: Boolean = false
    const val TAG = "FaceGate"
}
