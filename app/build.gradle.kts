plugins {
      id("com.android.application")
      id("org.jetbrains.kotlin.android")
  }

  android {
      namespace = "com.itsme.amkush"
      compileSdk = 35

      defaultConfig {
          applicationId = "com.itsme.amkush"
          minSdk = 29
          targetSdk = 35
          versionCode = 1
          versionName = "1.0"

          ndk {
              abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
          }
      }

      buildTypes {
          release {
              isMinifyEnabled = true
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
          viewBinding = true
          buildConfig = true
      }
  }

  kotlin {
      compilerOptions {
          jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
      }
  }

  dependencies {
      // Xposed Framework API
      compileOnly("de.robv.android.xposed:api:82")

      // Kotlin
      implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
      implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")

      // AndroidX Core
      implementation("androidx.core:core-ktx:1.13.1")
      implementation("androidx.appcompat:appcompat:1.7.0")
      implementation("androidx.fragment:fragment-ktx:1.8.2")

      // Lifecycle + ViewModel
      implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
      implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")

      // UI
      implementation("com.google.android.material:material:1.12.0")
      implementation("androidx.recyclerview:recyclerview:1.3.2")
      implementation("androidx.viewpager2:viewpager2:1.1.0")
      implementation("androidx.constraintlayout:constraintlayout:2.1.4")
      implementation("androidx.gridlayout:gridlayout:1.0.0")

      // Image Loading
      implementation("com.github.bumptech.glide:glide:4.16.0")
      annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

      // Networking
      implementation("com.squareup.okhttp3:okhttp:4.12.0")
      implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
      implementation("com.google.code.gson:gson:2.11.0")
      implementation("com.squareup.retrofit2:retrofit:2.11.0")
      implementation("com.squareup.retrofit2:converter-gson:2.11.0")

      // FFmpeg-kit — replaces LibVLC.
      // Guaranteed hardware H.264/H.265 decode via MediaCodec (h264_mediacodec),
      // direct NV21 pipe delivery, multi-protocol support (RTSP/RTMP/HLS/SRT/…),
      // local file loop, static image freeze — all in one dependency.
      implementation("com.arthenica:ffmpeg-kit-full:6.0-2")

      // CameraX
      implementation("androidx.camera:camera-core:1.4.1")
      implementation("androidx.camera:camera-camera2:1.4.1")

      // Preferences
      implementation("androidx.preference:preference-ktx:1.2.1")

      // Coroutines
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

      // Storage
      implementation("androidx.documentfile:documentfile:1.0.1")

      // Logging
      implementation("com.jakewharton.timber:timber:5.0.1")

      // Foreground Service
      implementation("androidx.core:core:1.13.1")

      // Testing
      testImplementation("junit:junit:4.13.2")
      androidTestImplementation("androidx.test.ext:junit:1.2.1")
  }
  