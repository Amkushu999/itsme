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
          try {
              Camera1Hooks.hookAll(lpparam)
              Logger.d("Camera1 hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Camera1 hooks failed", e) }

          try {
              Camera2Hooks.hookAll(lpparam)
              Logger.d("Camera2 hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Camera2 hooks failed", e) }

          try {
              CameraXHooks.hookAll(lpparam)
              Logger.d("CameraX hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("CameraX hooks failed", e) }

          // ==================== EXIF SPOOFING ====================
          try {
              ExifSpoofHooks.hookAll(lpparam)
              Logger.d("EXIF spoof hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("EXIF spoof hooks failed", e) }

          // ==================== INTENT CAPTURE HOOKS ====================
          try {
              IntentCaptureHooks.hookAll(lpparam)
              Logger.d("Intent capture hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Intent capture hooks failed", e) }

          // ==================== DEVICE SPOOFING ====================
          try {
              DeviceSpoofHooks.hookAll(lpparam)
              Logger.d("Device spoof hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Device spoof hooks failed", e) }

          // ==================== DENY LIST ====================
          try {
              DenyListHooks.hookAll(lpparam)
              Logger.d("Deny list hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Deny list hooks failed", e) }

          // ==================== ANTI-DETECTION HOOKS ====================
          try {
              EmulatorBypassHooks.hookAll(lpparam)
              Logger.d("Emulator bypass hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Emulator bypass hooks failed", e) }

          try {
              RootBypassHooks.hookAll(lpparam)
              Logger.d("Root bypass hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Root bypass hooks failed", e) }

          try {
              AntiXposedHooks.hookAll(lpparam)
              Logger.d("Anti-Xposed hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Anti-Xposed hooks failed", e) }

          try {
              SELinuxBypassHooks.hookAll(lpparam)
              Logger.d("SELinux bypass hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("SELinux bypass hooks failed", e) }

          try {
              ClonerBypassHooks.hookAll(lpparam)
              Logger.d("Cloner bypass hooks installed for ${lpparam.packageName}")
          } catch (e: Throwable) { Logger.e("Cloner bypass hooks failed", e) }
      }

      // ──────────────────────────────────────────────────────────────────────────
      //  hookApplication
      //
      //  BUG FIXED: the old code called SharedPrefs.init(targetAppContext) which
      //  reads the TARGET app's SharedPreferences — always empty, so isHookingActive
      //  was never set to true and camera injection never activated on any device.
      //
      //  Fix: read the MODULE's config using a two-layer strategy:
      //    Layer 1 — ContentProvider (rooted + InjectionService running)
      //    Layer 2 — createPackageContext + SharedPreferences
      //              Works in virtual cloner environments (Mochi Cloner, VirtualXposed,
      //              Parallel Space, etc.) where the module app and target app share the
      //              same virtual sandbox and the module's data directory is accessible.
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

                          // Read the configured target package from the MODULE's config —
                          // NOT from the target app's SharedPreferences.
                          val targetPackage = resolveModuleString(ctx, "target_package")
                          AppState.targetPackage = targetPackage

                          if (targetPackage.isNullOrEmpty()) {
                              Logger.d("No target package configured — skipping ${lpparam.packageName}")
                              return
                          }

                          if (targetPackage != lpparam.packageName) {
                              Logger.d("Not target app (target=$targetPackage) — skipping ${lpparam.packageName}")
                              return
                          }

                          // Check deny list from module config
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
                      }
                  }
              )
          } catch (e: Throwable) {
              Logger.e("Failed to hook Application", e)
          }
      }

      // ──────────────────────────────────────────────────────────────────────────
      //  Helpers — module config resolution
      // ──────────────────────────────────────────────────────────────────────────

      /**
       * Open one of the module's own SharedPreferences files from inside the hooked
       * target process via createPackageContext.
       *
       * - On rooted devices (LSPosed): SELinux context allows this; works reliably.
       * - In virtual cloner sandboxes (Mochi Cloner, VirtualXposed, Parallel Space):
       *   both the module app and the target app run in the same virtual environment
       *   so the module's data directory is visible to createPackageContext.
       */
      private fun openModulePrefs(ctx: Context, prefsName: String): SharedPreferences? = try {
          ctx.createPackageContext(
              "com.itsme.amkush",
              Context.CONTEXT_IGNORE_SECURITY
          ).getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      } catch (_: Throwable) { null }

      /**
       * Resolve a string config value using a two-layer fallback:
       *
       *   1. ContentProvider at content://com.itsme.amkush.ipc/config/<key>
       *      Written by InjectionService via FaceGateIpcProvider. Works when the
       *      module app's process is running (standard rooted/LSPosed use-case).
       *
       *   2. createPackageContext + SharedPreferences
       *      Reads "facegate_ipc" (written by InjectionService via the ContentProvider's
       *      backing store) then "facegate_prefs" (written by the UI via SharedPrefs util).
       *      Works in virtual cloner environments and when InjectionService is not running.
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

          // Layer 2: Direct SharedPrefs via createPackageContext
          return openModulePrefs(ctx, "facegate_ipc")?.getString(key, null)
              ?: openModulePrefs(ctx, "facegate_prefs")?.getString(key, null)
      }
  }
  