$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
./gradlew :app:assembleDebug :phone:assembleDebug
$carApk = "$root\app\build\outputs\apk\debug\app-debug.apk"
$phoneApk = "$root\phone\build\outputs\apk\debug\phone-debug.apk"
Write-Host "车机包: $carApk"
Write-Host "手机包: $phoneApk"
if (Test-Path $carApk) { adb install -r $carApk }
if (Test-Path $phoneApk) { adb install -r $phoneApk }
Write-Host "车机装app-debug.apk，手机装phone-debug.apk，别装反"
