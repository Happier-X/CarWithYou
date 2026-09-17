plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.carwithyou.lite"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carwithyou.lite"
        minSdk = 29
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 106
        versionName = project.findProperty("versionName") as? String ?: "0.1.6"
    }

    val releaseKeyPath = System.getenv("SIGNING_KEY_FILE").orEmpty().ifBlank { "release-key.jks" }
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // A 路线：车机端自己当 ADB 客户端（纯 JVM 模块，协议栈已在真机 adbd 上自检过）
    implementation(project(":adb-core"))

    // 首次配对：Android 11+ 无线调试要 SPAKE2 + TLS，自己实现成本太高，用成熟库顶上。
    // 只用来配对 + 把 5555 明文端口开出来，之后的投屏链路还是走自研的 :adb-core。
    // 许可：GPL-3.0-or-later OR Apache-2.0，与本项目 GPL-3.0 兼容。
    implementation("com.github.MuntashirAkon:libadb-android:3.0.0")
    // 用我们自己的 ADB 密钥签一张自签 X.509 证书（TLS 客户端证书），
    // 这样配对授权的那把密钥和明文连接用的是同一把，免掉第二次授权弹窗。
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.78")
}

/**
 * server 的 jar 是 app 的 res/raw 资源，但 assembleDebug **不会**自动依赖它。
 * 不显式绑上的话，改了 server 代码后打出来的 APK 里还是旧 jar ——
 * 真机上就会一直跑旧逻辑而看不出来（踩过：flags 改了但设备行为没变）。
 */
tasks.named("preBuild") {
    dependsOn(":server:copyServerJar")
}
