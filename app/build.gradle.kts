plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "me.synology.davidevinavil.casa"
    compileSdk = 35

    // Chiave fissa nel repository (privato!): ogni APK compilato da GitHub ha la
    // stessa firma, quindi si installa sopra il precedente senza disinstallare.
    signingConfigs {
        create("casa") {
            storeFile = file("casa.keystore")
            storePassword = "casa-relay"
            keyAlias = "casa"
            keyPassword = "casa-relay"
        }
    }

    defaultConfig {
        // app "Casa": relay + Clima BT + scorciatoie degli Accessi
        applicationId = "me.synology.davidevinavil.casa"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "BASE_URL", "\"https://davidevinavil.synology.me:8443\"")
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("casa") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("casa")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
