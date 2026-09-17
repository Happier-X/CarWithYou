pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
    }
}
rootProject.name = "CarWithYou"
include(":app")
include(":phone")
// 纯 JVM 模块：ADB 协议栈。不依赖 Android，所以能直接在桌面上对着真实
// adbd（如 127.0.0.1:5555）验协议，不用等真机和车机。
include(":adb-core")
// 推到手机 /data/local/tmp 里由 app_process 以 shell 身份拉起的采集端。
// 因为要跑在手机上，它必须是个 Android 模块；出包后改名为 .jar 当资源塞进 app。
include(":server")
