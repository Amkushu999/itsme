package com.itsme.amkush

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.itsme.amkush.hooks.*
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" || lpparam.packageName == "system") {
            return
        }

        Logger.init(true)
        Logger.d("Loading package: ${lpparam.packageName}")

        hookApplication(lpparam)

        // ==================== CAMERA HOOKS ====================
        try {
            Camera1Hooks.hookAll(lpparam)
            Logger.d("Camera1 hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Camera1 hooks failed", e)
        }

        try {
            Camera2Hooks.hookAll(lpparam)
            Logger.d("Camera2 hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Camera2 hooks failed", e)
        }

        try {
            CameraXHooks.hookAll(lpparam)
            Logger.d("CameraX hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("CameraX hooks failed", e)
        }

        // ==================== EXIF SPOOFING ====================
        try {
            ExifSpoofHooks.hookAll(lpparam)
            Logger.d("EXIF spoof hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("EXIF spoof hooks failed", e)
        }

        // ==================== INTENT CAPTURE HOOKS ====================
        try {
            IntentCaptureHooks.hookAll(lpparam)
            Logger.d("Intent capture hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Intent capture hooks failed", e)
        }

        // ==================== DEVICE SPOOFING ====================
        try {
            DeviceSpoofHooks.hookAll(lpparam)
            Logger.d("Device spoof hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Device spoof hooks failed", e)
        }

        // ==================== DENY LIST ====================
        try {
            DenyListHooks.hookAll(lpparam)
            Logger.d("Deny list hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Deny list hooks failed", e)
        }

        // ==================== ANTI-DETECTION HOOKS ====================
        try {
            EmulatorBypassHooks.hookAll(lpparam)
            Logger.d("Emulator bypass hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Emulator bypass hooks failed", e)
        }

        try {
            RootBypassHooks.hookAll(lpparam)
            Logger.d("Root bypass hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Root bypass hooks failed", e)
        }

        try {
            AntiXposedHooks.hookAll(lpparam)
            Logger.d("Anti-Xposed hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Anti-Xposed hooks failed", e)
        }

        try {
            SELinuxBypassHooks.hookAll(lpparam)
            Logger.d("SELinux bypass hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("SELinux bypass hooks failed", e)
        }

        try {
            ClonerBypassHooks.hookAll(lpparam)
            Logger.d("Cloner bypass hooks installed for ${lpparam.packageName}")
        } catch (e: Throwable) {
            Logger.e("Cloner bypass hooks failed", e)
        }
    }

    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val applicationClass = lpparam.classLoader.loadClass("android.app.Application")

            XposedHelpers.findAndHookMethod(
                applicationClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        AppState.context = param.thisObject as Context
                        SharedPrefs.init(AppState.context!!)
                        AppState.targetPackage = SharedPrefs.getTargetPackage()

                        if (AppState.targetPackage != null && AppState.targetPackage == lpparam.packageName) {
                            Logger.d("Target app detected: ${lpparam.packageName}")
                            AppState.isHookingActive = true
                            DecoderLauncher.ensureLaunched()
                            return
                        }

                        if (SharedPrefs.getDenyList().contains(lpparam.packageName)) {
                            Logger.d("App is in deny list: ${lpparam.packageName}")
                            return
                        }

                        if (AppState.targetPackage.isNullOrEmpty()) {
                            Logger.d("No target set, skipping ${lpparam.packageName}")
                            return
                        }

                        if (AppState.targetPackage != lpparam.packageName) {
                            Logger.d("Not target app: ${lpparam.packageName}")
                            return
                        }

                        AppState.isHookingActive = true
                        DecoderLauncher.ensureLaunched()
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("Failed to hook Application", e)
        }
    }
}