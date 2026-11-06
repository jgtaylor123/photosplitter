plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.photosplitter"
    compileSdk = 35
    signingConfigs {
        create("personal") {
            storeFile = file(System.getenv("PHOTOSPLITTER_KEYSTORE_PATH") ?: "missing-signing-key.jks")
            storePassword = System.getenv("PHOTOSPLITTER_KEYSTORE_PASSWORD") ?: ""
            keyAlias = "photosplitter"
            keyPassword = System.getenv("PHOTOSPLITTER_KEYSTORE_PASSWORD") ?: ""
        }
    }
    defaultConfig {
        applicationId = "com.photosplitter"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core:1.15.0")
}
