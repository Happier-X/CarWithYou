# 环境确认：车机 ADB 一键导出（终版开发顺序第 1 步）
# 用法：.\scripts\check-car.ps1 ；指定设备：$env:CAR_DEV="192.168.x.x:5555"; .\scripts\check-car.ps1
$dev = $env:CAR_DEV
if (-not $dev) {
  $d = adb devices | Select-String -Pattern "device$" | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ -ne "" -and $_ -ne "List" }
  $dev = $d | Select-Object -First 1
}
if (-not $dev) { Write-Host "没找到adb设备，先连车机（USB/无线adb）"; exit 1 }
Write-Host "== 设备 $dev =="
adb -s $dev shell getprop ro.product.model
adb -s $dev shell getprop ro.product.brand
adb -s $dev shell getprop ro.build.version.release
adb -s $dev shell getprop ro.build.version.sdk
adb -s $dev shell getprop ro.product.cpu.abi
adb -s $dev shell getprop ro.product.cpu.abilist
adb -s $dev shell wm size
adb -s $dev shell wm density
Write-Host "== H264硬解（找avc/AVCDecoder） =="
adb -s $dev shell dumpsys media.player | Select-String -Pattern "avc|AVC|OMX.*decoder" | Select-Object -First 15
Write-Host "== 显示信息 =="
adb -s $dev shell dumpsys display | Select-String -Pattern "mBaseDisplayInfo|DisplayDeviceInfo" | Select-Object -First 5
Write-Host "== 地图/音乐相关包 =="
adb -s $dev shell pm list packages | Select-String -Pattern "autonavi|minimap|amap|qqmusic|netease|kugou|carlife|dudu|yilian|carwith"
Write-Host "== 第三方包 =="
adb -s $dev shell pm list packages -3
Write-Host "== 投屏相关授权状态 =="
adb -s $dev shell "appops get com.carwithyou.lite SYSTEM_ALERT_WINDOW"
adb -s $dev shell "settings get secure enabled_notification_listeners"
adb -s $dev shell "dumpsys deviceidle whitelist" | Select-String -Pattern "carwithyou"
Write-Host "== 复制以上全部发我，帮你定分辨率/码率/默认包 =="
