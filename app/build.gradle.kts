plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.janick.knooppunt"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.janick.knooppunt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Vaste sleutel, zodat updates over de vorige versie heen geinstalleerd kunnen worden.
    signingConfigs {
        create("vast") {
            storeFile = file("knooppunt.jks")
            storePassword = "c5d5a836bcc0c16fb0c2"
            keyAlias = "knooppunt"
            keyPassword = "c5d5a836bcc0c16fb0c2"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("vast")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kaart met OpenStreetMap.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
