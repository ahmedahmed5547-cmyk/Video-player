import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore

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

fun verifyKeystoreCredentials(ksFile: File, candidatePass: String, preferredAlias: String): String? {
    var fis: FileInputStream? = null
    return try {
        fis = FileInputStream(ksFile)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(fis, candidatePass.toCharArray())
        val aliasToUse = if (ks.containsAlias(preferredAlias)) {
            preferredAlias
        } else if (ks.aliases().hasMoreElements()) {
            ks.aliases().nextElement()
        } else {
            preferredAlias
        }
        if (ks.getKey(aliasToUse, candidatePass.toCharArray()) != null) aliasToUse else null
    } catch (_: Exception) {
        null
    } finally {
        try { fis?.close() } catch (_: Exception) {}
    }
}

val resolvedKeystorePath = resolveSigningConfigValue("KEYSTORE_PATH", "release.keystore")
var resolvedKeystorePassword = resolveSigningConfigValue("KEYSTORE_PASSWORD", "2855798Aa")
var resolvedKeyAlias = resolveSigningConfigValue("KEY_ALIAS", "pureplayer_release_key")
var resolvedKeyPassword = resolveSigningConfigValue("KEY_PASSWORD", "2855798Aa")

val ksFile = file(resolvedKeystorePath)
if (ksFile.exists()) {
    val candidatePasswords = listOf(
        resolvedKeystorePassword,
        resolvedKeyPassword,
        "2855798Aa",
        "PureVideo#2026!"
    ).filter { it.isNotEmpty() }.distinct()
    val matchedPass = candidatePasswords.firstOrNull { pass ->
        verifyKeystoreCredentials(ksFile, pass, resolvedKeyAlias) != null
    }
    if (matchedPass != null) {
        val matchedAlias = verifyKeystoreCredentials(ksFile, matchedPass, resolvedKeyAlias) ?: resolvedKeyAlias
        resolvedKeystorePassword = matchedPass
        resolvedKeyAlias = matchedAlias
        resolvedKeyPassword = matchedPass
    }
} else {
    val isCi = System.getenv("CI").equals("true", ignoreCase = true)
    if (isCi) {
        throw GradleException(
            "Release keystore not found at ${ksFile.absolutePath} in CI. " +
            "Provide KEYSTORE_BASE64 secret or commit the matching upload keystore; never regenerate a random signing key."
        )
    }
}

android {
    namespace = "com.pureplayer.video"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pureplayer.video"
        minSdk = 24
        targetSdk = 36
        versionCode = 9022
        versionName = "90.22"

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
            buildConfigField("boolean", "ALLOW_DEMO_IPTV_SOURCE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SELF_UPDATE", "false")
            buildConfigField("boolean", "ENABLE_IN_APP_UPDATER", "false")
            buildConfigField("boolean", "ALLOW_DEMO_IPTV_SOURCE", "false")
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
            ndk {
                debugSymbolLevel = "FULL"
            }
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
            useLegacyPackaging = false
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.videolan.android:libvlc-all:3.6.5")

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
    testImplementation("androidx.window:window-testing:1.3.0")
    testImplementation("org.json:json:20231013")
}
