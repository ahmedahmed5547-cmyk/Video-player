plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pureplayer.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pureplayer.video"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "4.0.0-Pro-64bit"

        // Optimize strictly for 64-bit Flagship Processors (arm64-v8a) & modern x86_64
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Android 15 & Android 16 (16KB page size alignment & high-speed native library loading)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // LibVLC Official 64-bit Engine: Supports MKV, MP4, AVI, FLV, HEVC/H.265, AV1, AC3, DTS, SRT, ASS, SSA
    implementation("org.videolan.android:libvlc-all:3.6.3")
}
