import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.GradleException

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

fun resolveSigningValue(primaryKey: String, fallbackKey: String? = null): String? {
    val fromProps = localProps.getProperty(primaryKey)?.trim()
    if (!fromProps.isNullOrEmpty()) return fromProps
    if (fallbackKey != null) {
        val fromFallbackProps = localProps.getProperty(fallbackKey)?.trim()
        if (!fromFallbackProps.isNullOrEmpty()) return fromFallbackProps
    }
    val fromEnv = System.getenv(primaryKey)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    if (fallbackKey != null) {
        val fromFallbackEnv = System.getenv(fallbackKey)?.trim()
        if (!fromFallbackEnv.isNullOrEmpty()) return fromFallbackEnv
    }
    return null
}

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

val resolvedKeystorePath = resolveSigningValue("KEYSTORE_PATH")
val resolvedKeystorePassword = resolveSigningValue("KEYSTORE_PASSWORD", "RESOLVED_KS_PASS")
val resolvedKeyAlias = resolveSigningValue("KEY_ALIAS", "RESOLVED_KEY_ALIAS")
val resolvedKeyPassword = resolveSigningValue("KEY_PASSWORD", "RESOLVED_KEY_PASS")

if (isReleaseTaskRequested) {
    val missingKeys = mutableListOf<String>()
    if (resolvedKeystorePath.isNullOrBlank()) missingKeys.add("KEYSTORE_PATH")
    if (resolvedKeystorePassword.isNullOrBlank()) missingKeys.add("KEYSTORE_PASSWORD / RESOLVED_KS_PASS")
    if (resolvedKeyAlias.isNullOrBlank()) missingKeys.add("KEY_ALIAS / RESOLVED_KEY_ALIAS")
    if (resolvedKeyPassword.isNullOrBlank()) missingKeys.add("KEY_PASSWORD / RESOLVED_KEY_PASS")

    if (missingKeys.isNotEmpty()) {
        throw GradleException(
            "Release build aborted: Missing required signing configuration: ${missingKeys.joinToString(", ")}. " +
                "Configure KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD in GitHub Secrets " +
                "or provide KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD in local.properties."
        )
    }

    val ksFile = file(resolvedKeystorePath!!)
    if (!ksFile.exists() || !ksFile.isFile || ksFile.length() == 0L) {
        throw GradleException(
            "Release build aborted: Keystore file does not exist or is empty at '${ksFile.absolutePath}'."
        )
    }
}

android {
    namespace = "com.pureplayer.video"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pureplayer.video"
        minSdk = 24
        targetSdk = 35
        versionCode = 63
        versionName = "38.07"

        buildConfigField("String", "SUBDL_API_KEY", "\"\"")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SELF_UPDATE", "true")
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SELF_UPDATE", "false")
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "false")
        }
    }

    signingConfigs {
        create("release") {
            if (!resolvedKeystorePath.isNullOrBlank() &&
                !resolvedKeystorePassword.isNullOrBlank() &&
                !resolvedKeyAlias.isNullOrBlank() &&
                !resolvedKeyPassword.isNullOrBlank()
            ) {
                val ksFile = file(resolvedKeystorePath)
                if (ksFile.exists() && ksFile.isFile) {
                    storeFile = ksFile
                    storePassword = resolvedKeystorePassword
                    keyAlias = resolvedKeyAlias
                    keyPassword = resolvedKeyPassword
                }
            }
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
