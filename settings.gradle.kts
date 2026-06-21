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
        // JitPack for dependencies not in MavenCentral
        maven { url = uri("https://jitpack.io") }
        // Xposed API repository (backup)
        maven { url = uri("https://api.xposed.info/releases") }
    }
}

rootProject.name = "FaceGate"
include(":app")