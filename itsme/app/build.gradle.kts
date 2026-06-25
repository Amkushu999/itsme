import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Read local.properties (for gstreamer.dir) ─────────────────────────────────
val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { props.load(it) }
}
val gstreamerDir: String? = localProps.getProperty("gstreamer.dir")
    ?: System.getenv("GSTREAMER_ROOT")
    ?: System.getenv("GSTREAMER_ROOT_ANDROID")

android {
    namespace  = "com.itsme.amkush"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itsme.amkush"
        minSdk        = 29
        targetSdk     = 35
        versionCode   = 2
        versionName   = "2.0"

        // Only build for ABIs that have GStreamer Android SDK packages
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Pass GSTREAMER_ROOT into CMake so CMakeLists.txt can locate the SDK
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                if (!gstreamerDir.isNullOrEmpty()) {
                    arguments("-DGSTREAMER_ROOT=$gstreamerDir")
                } else {
                    // Warn at configuration time — build will still succeed if the
                    // developer has set GSTREAMER_ROOT in their environment.
                    println("WARNING: gstreamer.dir not set in local.properties.")
                    println("         Set it to the path of the GStreamer Android Universal SDK.")
                    println("         Download: https://gstreamer.freedesktop.org/data/pkg/android/")
                }
            }
        }
    }

    // ── NDK / CMake build ─────────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose     = true
        buildConfig = true
        aidl        = true   // enable AIDL code generation for ISurfaceInjector
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")

    // Compose BOM 2025.06.00 — compatible with Kotlin 2.3.0 / Compose Runtime 1.9.x
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // FFmpeg Kit — retained for the StreamPreviewDialog (module's own UI process).
    // The GStreamer JNI lib handles stream decoding in the injection pipeline.
    // "full" variant includes all codecs; native libs are loaded only in the
    // module's own process (never in the hooked target-app process).
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")

    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")

    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
