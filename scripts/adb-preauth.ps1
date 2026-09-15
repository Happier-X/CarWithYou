# ADB 预授权：一键能免的全免掉，免不了的打印手动清单（终版第4节）
# 用法：手机/车机分别连上后各跑一次，可加 $env:CAR_DEV 指定设备
$dev = $env:CAR_DEV
if (-not $dev) {
  $d = adb devices | Select-String -Pattern "device$" | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ -ne "" -and $_ -ne "List" }
  $dev = $d | Select-Object -First 1
}
if (-not $dev) { Write-Host "没找到adb设备"; exit 1 }
$pkgs = @("com.carwithyou.sender", "com.carwithyou.lite")
foreach ($p in $pkgs) {
  Write-Host "== $p =="
  adb -s $dev shell "pm grant $p android.permission.POST_NOTIFICATIONS 2>/dev/null; echo grant-notif-done"
  adb -s $dev shell "cmd deviceidle whitelist +$p"
}
Write-Host ""
Write-Host "以下系统不让ADB代点，必须手动一次："
Write-Host "1. 手机：录屏权限（每次重启后首次投屏点一次允许）——系统底线，绕不过"
Write-Host "2. 手机：无障碍→Car投屏-手机端（触摸回传用）"
Write-Host "3. 手机：电池→无限制 + 锁后台"
Write-Host "4. 车机：通知监听→CarWithYou歌词监听（本机歌词模式用）"
Write-Host "5. 车机：悬浮窗（本机歌词模式用）+ 电池无限制"
