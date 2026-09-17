plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 直接继承环境里的 JDK（JAVA_HOME 是 JDK 21），不用独立下 toolchain。
// 但字节码要降到 17：:app 是 Android 模块（D8 dex 时按 17 对齐），
// 不降的话这里会出 Java 21（major 65）的 class，dex 阶段有兼容风险。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 桌面验证入口：./gradlew :adb-core:probe -Ptarget=127.0.0.1:5555
// 不接真机和车机也能把 ADB 协议栈对着真实 adbd 跑一遍。
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "对着真实 adbd 跑一遍 ADB 协议栈自检"
    mainClass.set("com.carwithyou.adb.AdbProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    (project.findProperty("target") as? String)?.let { args(it) }
}
