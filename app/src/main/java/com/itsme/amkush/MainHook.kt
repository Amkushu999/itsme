package com.itsme.amkush

  import android.content.Context
  import android.content.SharedPreferences
  import android.net.Uri
  import de.robv.android.xposed.IXposedHookLoadPackage
  import de.robv.android.xposed.XC_MethodHook
  import de.robv.android.xposed.XposedHelpers
  import de.robv.android.xposed.callbacks.XC_LoadPackage
  import com.itsme.amkush.hooks.*
  import com.itsme.amkush.utils.Logger

  class MainHook : IXposedHookLoadPackage {

      override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
          if (lpparam.packageName == "android" || lpparam.packageName == "system") return

          Logger.init(true)
          Logger.d("Loading package: ${lpparam.packageName}")

          hookApplication(lpparam)

          // ==================== CAMERA HOOKS ====================
          try { Camera1Hooks.hookAll(lpparam); Logger.d("Camera1 hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Camera1 hooks failed", e) }

          try { Camera2Hooks.hookAll(lpparam); Logger.d("Camera2 hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Camera2 hooks failed", e) }

          try { CameraXHooks.hookAll(lpparam); Logger.d("CameraX hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("CameraX hooks failed", e) }

          // ==================== EXIF SPOOFING ====================
          try { ExifSpoofHooks.hookAll(lpparam); Logger.d("EXIF spoof hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("EXIF spoof hooks failed", e) }

          // ==================== INTENT CAPTURE HOOKS ====================
          try { IntentCaptureHooks.hookAll(lpparam); Logger.d("Intent capture hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Intent capture hooks failed", e) }

          // ==================== DEVICE SPOOFING ====================
          try { DeviceSpoofHooks.hookAll(lpparam); Logger.d("Device spoof hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Device spoof hooks failed", e) }

          // ==================== DENY LIST ====================
          try { DenyListHooks.hookAll(lpparam); Logger.d("Deny list hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Deny list hooks failed", e) }

          // ==================== ANTI-DETECTION HOOKS ====================
          try { EmulatorBypassHooks.hookAll(lpparam); Logger.d("Emulator bypass hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Emulator bypass hooks failed", e) }

          try { RootBypassHooks.hookAll(lpparam); Logger.d("Root bypass hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Root bypass hooks failed", e) }

          try { AntiXposedHooks.hookAll(lpparam); Logger.d("Anti-Xposed hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Anti-Xposed hooks failed", e) }

          try { SELinuxBypassHooks.hookAll(lpparam); Logger.d("SELinux bypass hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("SELinux bypass hooks failed", e) }

          try { ClonerBypassHooks.hookAll(lpparam); Logger.d("Cloner bypass hooks installed for ${lpparam.packageName}") }
          catch (e: Throwable) { Logger.e("Cloner bypass hooks failed", e) }
      }

      // ──────────────────────────────────────────────────────────────────────────
      //  hookApplication
      //
      //  BUG FIXED: old code called SharedPrefs.init(targetAppContext) which read
      //  the TARGET app's SharedPreferences (always empty), so isHookingActive was
      //  never set and camera injection never activated on any device.
      //
      //  Fix: read the MODULE's own config via a two-layer strategy:
      //    Layer 1 — ContentProvider (rooted + InjectionService running)
      //    Layer 2 — createPackageContext + SharedPreferences
      //              Works in virtual cloner environments (Mochi Cloner, VirtualXposed,
      //              Parallel Space) where module app and target app share the same
      //              virtual sandbox and the module's data directory is accessible.
      // ──────────────────────────────────────────────────────────────────────────
      private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
          try {
              val applicationClass = lpparam.classLoader.loadClass("android.app.Application")
              XposedHelpers.findAndHookMethod(
                  applicationClass,
                  "onCreate",
                  object : XC_MethodHook() {
                      override fun afterHookedMethod(param: MethodHookParam) {
                          val ctx = param.thisObject as Context
                          AppState.context = ctx

                          // Read target package from the MODULE's config — not the target app's prefs
                          val targetPackage = resolveModuleString(ctx, "target_package")
                          AppState.targetPackage = targetPackage

                          if (targetPackage.isNullOrEmpty()) {
                              Logger.d("No target configured — skipping ${lpparam.packageName}")
                              return
                          }
                          if (targetPackage != lpparam.packageName) {
                              Logger.d("Not target (target=$targetPackage) — skipping ${lpparam.packageName}")
                              return
                          }

                          // Deny list from module config
                          val denyList: Set<String> = try {
                              openModulePrefs(ctx, "facegate_prefs")
                                  ?.getStringSet("deny_list", emptySet()) ?: emptySet()
                          } catch (_: Throwable) { emptySet() }

                          if (denyList.contains(lpparam.packageName)) {
                              Logger.d("App in deny list — skipping ${lpparam.packageName}")
                              return
                          }

                          Logger.d("Target app detected: ${lpparam.packageName} — activating injection")
                          AppState.isHookingActive = true
                          DecoderLauncher.ensureLaunched()

                          // Register live-update receiver so URL changes in the module app
                          // take effect immediately without restarting the target app
                          try {
                              ConfigUpdateReceiver.register(ctx)
                          } catch (e: Throwable) {
                              Logger.e("ConfigUpdateReceiver registration failed", e)
                          }
                      }
                  }
              )
          } catch (e: Throwable) {
              Logger.e("Failed to hook Application", e)
          }
      }

      // ── Module config resolution helpers ─────────────────────────────────────

      /**
       * Open one of the module's own SharedPreferences files from inside the hooked
       * target process via createPackageContext.
       *
       * Works on rooted devices (LSPosed grants SELinux access) and inside virtual
       * cloner sandboxes (module app and target app share the same virtual FS).
       */
      private fun openModulePrefs(ctx: Context, prefsName: String): SharedPreferences? = try {
          ctx.createPackageContext("com.itsme.amkush", Context.CONTEXT_IGNORE_SECURITY)
              .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      } catch (_: Throwable) { null }

      /**
       * Resolve a string value from the module's config using two layers:
       *
       *   1. ContentProvider at content://com.itsme.amkush.ipc/config/<key>
       *      Written by InjectionService.  Works when InjectionService is running.
       *
       *   2. createPackageContext + SharedPreferences (facegate_ipc → facegate_prefs)
       *      Works in virtual cloner environments and when InjectionService is not
       *      running but the user already saved settings via the module UI.
       */
      private fun resolveModuleString(ctx: Context, key: String): String? {
          // Layer 1: ContentProvider
          try {
              val uri = Uri.parse("content://com.itsme.amkush.ipc/config/$key")
              ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                  if (c.moveToFirst()) {
                      val idx = c.getColumnIndex("value")
                      if (idx >= 0) return c.getString(idx)
                  }
              }
          } catch (_: Throwable) {
              Logger.d("resolveModuleString: ContentProvider unavailable for $key")
          }

          // Layer 2: Direct SharedPrefs
          return openModulePrefs(ctx, "facegate_ipc")?.getString(key, null)
              ?: openModulePrefs(ctx, "facegate_prefs")?.getString(key, null)
      }
  }
  