pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API repository
        maven {
            url = uri("https://api.xposed.info/releases")
        }
        // JitPack for any other dependencies
        maven {
            url = uri("https://jitpack.io")
        }
        // LibVLC repository
        maven {
            url = uri("https://raw.githubusercontent.com/videolan/vlc-android/master/")
        }
    }
}

rootProject.name = "FaceGate"
include(":app")