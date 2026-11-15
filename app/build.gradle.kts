import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val propFile = rootProject.file("local.properties")
    if (propFile.exists()) {
        FileInputStream(propFile).use { load(it) }
    }
}

fun resolveSigningConfigValue(key: String, defaultVal: String): String {
    val fromProps = localProps.getProperty(key)?.trim()
    if (!fromProps.isNullOrEmpty()) return fromProps
    val fromEnv = System.getenv(key)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    return defaultVal
}

val resolvedKeystorePath = resolveSigningConfigValue("KEYSTORE_PATH", "release.keystore")
val resolvedKeystorePassword = resolveSigningConfigValue("KEYSTORE_PASSWORD", "PureVideo#2026!")
val resolvedKeyAlias = resolveSigningConfigValue("KEY_ALIAS", "pureplayer_release_key")
val resolvedKeyPassword = resolveSigningConfigValue("KEY_PASSWORD", "PureVideo#2026!")

val ksFile = file(resolvedKeystorePath)
if (!ksFile.exists()) {
    ksFile.parentFile?.mkdirs()
    val pb = ProcessBuilder(
        "keytool", "-genkeypair", "-v",
        "-keystore", ksFile.absolutePath,
        "-alias", resolvedKeyAlias,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", resolvedKeystorePassword,
        "-keypass", resolvedKeyPassword,
        "-dname", "CN=Pure Video Player, OU=Mobile, O=PurePlayer, L=Riyadh, ST=Riyadh, C=SA"
    )
    pb.inheritIO().start().waitFor()
}

android {
    namespace = "com.pureplayer.video"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pureplayer.video"
        minSdk = 24
        targetSdk = 34
        versionCode = 58
        versionName = "38.02"

        buildConfigField("String", "SUBDL_API_KEY", "\"\"")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SELF_UPDATE", "false")
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SELF_UPDATE", "false")
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "false")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = ksFile
            storePassword = resolvedKeystorePassword
            keyAlias = resolvedKeyAlias
            keyPassword = resolvedKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.videolan.android:libvlc-all:3.6.3")

    // Room 2.6.1 + KSP
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-paging:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Paging 3
    val pagingVersion = "3.2.1"
    implementation("androidx.paging:paging-runtime-ktx:$pagingVersion")
    testImplementation("androidx.paging:paging-common-ktx:$pagingVersion")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
