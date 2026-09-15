# Changelog

## [Unreleased]

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
