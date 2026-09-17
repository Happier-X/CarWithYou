plugins {
    id("com.android.application")
}

android {
    namespace = "com.carwithyou.server"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carwithyou.server"
        // server 是推到手机 /data/local/tmp 里由 app_process 拉起的，
        // 它自己永远不会作为应用安装，所以 minSdk 只受车载/手机端下限约束。
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    packaging {
        resources.excludes += setOf("META-INF/*")
    }
}

/**
 * 把构建出来的 APK 改名成 .jar 塞进车机端 APK 的 raw 资源。
 *
 * server 本质就是个 APK，但 app_process 只吃 dex，所以按 scrcpy/Easycontrol 的老办法：
 * 出包后改扩展名，车机端运行时用 sync 推到手机 /data/local/tmp 再 app_process 拉起。
 */
val serverJarName = "carwithyou_server.jar"
val androidAppResRaw = rootProject.layout.projectDirectory.dir("app/src/main/res/raw")

tasks.register<Copy>("copyServerJar") {
    group = "build"
    description = "把 server APK 作为 jar 资源复制进车机端 app 模块"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/server-release-unsigned.apk"))
    into(androidAppResRaw)
    rename { serverJarName }
}

dependencies {
}
