plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.carwithyou.sender"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carwithyou.sender"
        minSdk = 29
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 105
        versionName = project.findProperty("versionName") as? String ?: "0.1.5"
    }

    val releaseKeyPath = System.getenv("SIGNING_KEY_FILE").orEmpty().ifBlank { "../app/release-key.jks" }
    val releaseKeyFile = file(releaseKeyPath)
    val canSignRelease = releaseKeyFile.isFile &&
        !System.getenv("SIGNING_STORE_PASSWORD").isNullOrBlank()

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    buildFeatures {
        compose = true
        // 「检查更新」要读 BuildConfig.VERSION_NAME
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
