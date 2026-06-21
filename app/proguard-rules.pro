# Keep Xposed hooks
-keep class com.itsme.amkush.MainHook
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage

# Keep all hook classes
-keep class com.itsme.amkush.hooks.** { *; }
-keep class com.itsme.amkush.decoder.** { *; }

# Keep Camera classes (for hooks)
-keep class android.hardware.camera2.** { *; }
-keep class android.hardware.Camera { *; }
-keep class androidx.camera.core.** { *; }

# Keep LibVLC
-keep class org.videolan.** { *; }
-keep class org.videolan.libvlc.** { *; }

# Keep Retrofit/Gson
-keep class com.squareup.okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.itsme.amkush.network.models.** { *; }

# Keep ViewModels
-keep class com.itsme.amkush.ui.** { *; }

# Keep Timber
-dontwarn com.jakewharton.timber.**
-keep class com.jakewharton.timber.** { *; }

# Keep Xposed classes
-keepnames class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class de.robv.android.xposed.** { *; }

# Keep companion objects
-keepclassmembers class * {
    public static final ** Companion;
}

# Keep ViewBinding classes
-keep class com.itsme.amkush.databinding.** { *; }

# Keep R class
-keep class com.itsme.amkush.R$* { *; }

# Keep Application class
-keep class com.itsme.amkush.FaceGateApplication { *; }

# Don't obfuscate Xposed entry point
-keep public class * extends de.robv.android.xposed.IXposedHookLoadPackage

# Remove debug logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}