plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.namazwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.namazwallpaper"
        minSdk = 26 // Required for native HijrahDate calculation
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // Allows background GPS tracking
    implementation("com.google.android.gms:play-services-location:21.2.0")
}
