plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.20"
}

android {
    namespace = "com.example.namazwallpaper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.namazwallpaper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Explicitly bind to Java 17 for the GitHub Actions server
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Force the repositories locally so it never fails to find Google Play Services
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.2.0")
}