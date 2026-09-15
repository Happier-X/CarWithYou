# 安全配置

## GitHub Secrets（Settings → Secrets and variables → Actions）

发版签名需要以下 Secret，通过 `gh secret set` 或网页配置：

| Secret | 说明 | 获取方式 |
|---|---|---|
| `SIGNING_KEY` | base64 编码的 release keystore | `base64 -w0 app/release-key.jks` |
| `SIGNING_KEY_ALIAS` | 签名别名 | 创建 keystore 时指定 |
| `SIGNING_KEY_PASSWORD` | 签名密码 | 创建 keystore 时指定 |
| `SIGNING_STORE_PASSWORD` | 证书库密码 | 创建 keystore 时指定 |

## 本地生成 keystore

```bash
keytool -genkey -v \
  -keystore app/release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias <你的别名>
```

## 发版流程

```bash
# 1. 确认 CHANGELOG.md 已更新
# 2. 推送标签触发自动发版
git tag v0.1.0
git push origin v0.1.0

# 或使用脚本一键创建 repo + 推代码 + 配置 secrets
bash scripts/github-setup.sh
```

## 自动化触发

- `main` / `develop` 推送 → `ci-build.yml` 自动构建 Debug APK
- `v*` 标签推送 → `release.yml` 自动签名构建 + 创建 GitHub Release
- 成功后 Release 附件为 `CarWithYou-car-vX.Y.Z.apk` 和 `CarWithYou-phone-vX.Y.Z.apk`
- 未配签名 Secret 时会改用 debug 签名，保证 APK 仍可安装，但不会再创建空 Release
