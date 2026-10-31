plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.qoeprobe.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.qoeprobe.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"
            keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.webkit:webkit:1.12.1") }
