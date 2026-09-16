# Changelog

## 0.1.4
### Added
- 默认独立虚拟屏投车机：导航/音乐在副屏上跑，手机主屏可继续使用
- 车机桌面、悬浮切换栏；触摸回传打到虚拟屏 displayId

### Changed
- 虚拟屏锁定分辨率，只自适应码率，避免重建 Display 杀掉车上的 App
- 镜像模式仍作为 ROM 不支持副屏时的兜底
- UI 迁到 Jetpack Compose + MIUIX 0.9.3（`miuix-ui` / `miuix-preference`）；投屏解码仍用 SurfaceView
- 工具链：Kotlin 2.4.0、AGP 9.2.1、Gradle 9.6.1、Compose BOM 2026.09.00、compileSdk 37、JDK 21

## 0.1.3
### Fixed
- GitHub Release 使用独立的 CarWithYou 签名钥，上传正式签名 APK

## 0.1.2
### Fixed
- `SIGNING_KEY` 不是合法 base64 时不再阻断发版，回退 debug 签名并仍上传 APK

## 0.1.1
### Fixed
- GitHub Release 不再因 Android SDK 安装失败而空发布
- Release 正确上传车机 / 手机 APK，缺文件时失败而不是一个空 Release
- CI 不再依赖 `android-actions/setup-android` 去安装已下线的 `tools` 包
- 补齐 Gradle Wrapper，并将 Groovy 脚本迁到 Kotlin DSL

## 0.1.0-lite
- 初始版本
- 车机端：主分屏 + 悬浮歌词 + 手机投屏双开
- 手机端：MediaProjection推流 + 自适应码率/分辨率 + 触摸回传
