import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing lives outside the repository: keystore.properties + the .jks beside it.
// Without that file assembleRelease still runs and emits an unsigned APK, so a
// plain clone builds without asking anyone for a secret.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.shawn.zhaoye"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shawn.zhaoye"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0-dev"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    // The engine mmaps constants.bin and fopens .spv; keep them verbatim so a
    // future in-place read of the APK's asset stream stays possible.
    androidResources {
        noCompress += listOf("bin", "spv", "json", "icc")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("net.engawapg.lib:zoomable:2.9.0")
}
