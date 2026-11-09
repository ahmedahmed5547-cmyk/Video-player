import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}

android {
    namespace = "com.pureplayer.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pureplayer.video"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "7.0.0-Senior-Edition"

        resourceConfigurations += listOf("en", "ar")

        // Keep API keys out of source code (read from local.properties or environment variables)
        val openSubKey = localProps.getProperty("OPENSUBTITLES_API_KEY")
            ?: System.getenv("OPENSUBTITLES_API_KEY") ?: ""
        val subdlKey = localProps.getProperty("SUBDL_API_KEY")
            ?: System.getenv("SUBDL_API_KEY") ?: ""

        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$openSubKey\"")
        buildConfigField("String", "SUBDL_API_KEY", "\"$subdlKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // LibVLC Official Engine (64-bit arm64-v8a included): Supports MKV, MP4, AVI, FLV, HEVC/H.265, AV1, AC3, DTS, SRT, ASS, SSA
    implementation("org.videolan.android:libvlc-all:3.6.3")
}
