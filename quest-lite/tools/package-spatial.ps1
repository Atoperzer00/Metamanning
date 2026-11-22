param([switch]$Install, [string]$Serial)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$work = Split-Path $repo
$portableJava = Join-Path $work 'jdk17/jdk-17.0.21+9'
$portableSdk = Join-Path $work 'android-sdk'
$portableGradle = Join-Path $work 'gradle/gradle-8.13/bin/gradle.bat'
if (!$env:JAVA_HOME -and (Test-Path $portableJava)) { $env:JAVA_HOME = $portableJava }
if (!$env:ANDROID_HOME -and (Test-Path $portableSdk)) { $env:ANDROID_HOME = $portableSdk }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME/bin;$env:Path" }
$gradle = if (Test-Path $portableGradle) { $portableGradle } else { 'gradle' }
Push-Location $repo
try {
    & npm --prefix quest-lite test
    if ($LASTEXITCODE -ne 0) { throw 'Release checks failed.' }
    & $gradle -p quest-lite :spatial:assembleDebug :spatial:testDebugUnitTest :spatial:lintDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Android checks failed.' }
    $metadata = Get-Content 'quest-lite/spatial/build/outputs/apk/debug/output-metadata.json' -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    if ($version -notmatch '^[a-zA-Z0-9.-]+$') { throw 'Invalid build version.' }
    $filename = "Luma-Mind-Kit-$version.apk"
    $destination = Join-Path $repo "downloads/$filename"
    Copy-Item -LiteralPath (Join-Path $repo 'quest-lite/spatial/build/outputs/apk/debug/spatial-debug.apk') -Destination $destination -Force
    $digest = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLower()
    Write-Output "Packaged $filename"
    Write-Output "SHA256 $digest"
    if ($Install) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        $devices = @(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if (!$Serial) {
            if ($devices.Count -ne 1) { throw 'Connect one authorized Quest, or pass -Serial with its device identifier.' }
            $Serial = $devices[0]
        }
        if ($Serial -notin $devices) { throw 'The selected Quest is not connected and authorized.' }
        & $adb -s $Serial install -r $destination
        if ($LASTEXITCODE -ne 0) { throw 'Update failed. Existing app data was kept. Do not uninstall without a backup.' }
        & $adb -s $Serial shell am start -n com.metamanning.spatial/com.metamanning.questlite.RoomActivity
        if ($LASTEXITCODE -ne 0) { throw 'Installed, but the headset could not start Luma.' }
    }
} finally { Pop-Location }
