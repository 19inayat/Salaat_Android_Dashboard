plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.20"
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
