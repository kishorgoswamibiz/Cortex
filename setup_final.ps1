$ErrorActionPreference = 'Stop'

Write-Host "Setting up JDK 21..."
$jdkUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip"
$jdkZip = "$PSScriptRoot\jdk21.zip"
$jdkDir = "C:\jdk-21"

if (-not (Test-Path $jdkDir)) {
    Write-Host "Downloading JDK..."
    Start-BitsTransfer -Source $jdkUrl -Destination $jdkZip
    Write-Host "Extracting JDK..."
    tar -xf $jdkZip -C "C:\"
    Rename-Item -Path "C:\jdk-21.0.3+9" -NewName "jdk-21"
}
$env:JAVA_HOME = $jdkDir
$env:Path = "$jdkDir\bin;" + $env:Path

Write-Host "Setting up Android Command Line Tools..."
$sdkUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$sdkZip = "$PSScriptRoot\cmdline-tools.zip"
$sdkDir = "C:\Android\sdk"
$cmdlineDir = "$sdkDir\cmdline-tools\latest"

if (-not (Test-Path $cmdlineDir)) {
    New-Item -ItemType Directory -Force -Path $sdkDir | Out-Null
    Write-Host "Downloading SDK Tools..."
    Start-BitsTransfer -Source $sdkUrl -Destination $sdkZip
    Write-Host "Extracting SDK Tools..."
    tar -xf $sdkZip -C "$sdkDir\cmdline-tools"
    Rename-Item -Path "$sdkDir\cmdline-tools\cmdline-tools" -NewName "latest"
}

$env:ANDROID_HOME = $sdkDir
$env:Path = "$cmdlineDir\bin;$sdkDir\platform-tools;" + $env:Path

Write-Host "Accepting licenses and installing Android components..."
echo y | & "$cmdlineDir\bin\sdkmanager.bat" --licenses
& "$cmdlineDir\bin\sdkmanager.bat" "platform-tools" "build-tools;34.0.0" "platforms;android-34"

[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkDir, "User")

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notmatch "$jdkDir\\bin") { $userPath = "$jdkDir\bin;" + $userPath }
if ($userPath -notmatch "$cmdlineDir\\bin") { $userPath = "$cmdlineDir\bin;$sdkDir\platform-tools;" + $userPath }
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")

Write-Host "Setup complete!"
