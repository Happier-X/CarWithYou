#!/usr/bin/env bash
# 一次性设置：创建 GitHub repo + 配置 secrets
# 用法：bash scripts/github-setup.sh
# 需要先：gh auth login（已确认 Happier-X 已登录）

set -e

REPO="Happier-X/CarWithYou"
APP_KEY="app/release-key.jks"

echo "== 1. 创建 GitHub repo =="
if gh repo view "$REPO" 2>/dev/null; then
    echo "repo 已存在：$REPO"
else
    gh repo create "$REPO" --public --description "CarWithYou 投屏 - 手机分屏上车，车机当显示器。自适应分辨率码率。"
    echo "repo 已创建：$REPO"
fi

echo ""
echo "== 2. 推送到 GitHub =="
git remote set-url origin "https://github.com/$REPO.git"
git add -A
git commit -m "feat: 初始版本 - 手机分屏投屏 + 自适应码率/分辨率

- 车机端：StreamReceiverActivity + ReceiverKeepService + 本机分屏+悬浮歌词
- 手机端：ScreenCastService + TouchInjectorService + SenderActivity
- 自适应：丢帧>10%降480p，丢帧<2%升1080p
- 协议：docs/PROTOCOL.md v1.2
" || true
git push -u origin main || true

echo ""
echo "== 3. 在 GitHub Secrets 页面配置 =="
echo ""
echo "请前往 https://github.com/$REPO/settings/secrets/new 添加以下 Secret："
echo ""
echo "  SIGNING_KEY    → base64 编码的 app/release-key.jks（命令：base64 -w0 app/release-key.jks）"
echo "  SIGNING_KEY_ALIAS → 签名别名"
echo "  SIGNING_KEY_PASSWORD → 签名密码"
echo "  SIGNING_STORE_PASSWORD → 证书库密码"
echo ""
echo "== 4. 本地生成 keystore（首次发版前） =="
echo ""
echo "  keytool -genkey -v -keystore app/release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias <你的别名>"
echo ""
echo "完成后运行："
echo "  bash scripts/github-setup.sh"
