plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.urreal3d.adspotter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.urreal3d.adspotter"
        minSdk = 24
        targetSdk = 34
        // Goes up on every GitHub build, so each APK installs as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + 100
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over old ones.
    signingConfigs {
        create("fixed") {
            storeFile = file("adspotter.keystore")
            storePassword = "Urreal3D-AdSpotter-2026"
            keyAlias = "adspotter"
            keyPassword = "Urreal3D-AdSpotter-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
}
