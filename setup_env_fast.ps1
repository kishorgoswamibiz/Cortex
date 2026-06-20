$ErrorActionPreference = 'Stop'

Write-Host "Setting up JDK 21..."
$jdkZip = "$PSScriptRoot\jdk21.zip"
$jdkDir = "C:\jdk-21"

if (-not (Test-Path $jdkDir)) {
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
    Invoke-WebRequest -Uri $sdkUrl -OutFile $sdkZip
    Write-Host "Extracting SDK Tools..."
    tar -xf $sdkZip -C "$sdkDir\cmdline-tools"
    Rename-Item -Path "$sdkDir\cmdline-tools\cmdline-tools" -NewName "latest"
    Remove-Item $sdkZip
}

$env:ANDROID_HOME = $sdkDir
$env:Path = "$cmdlineDir\bin;$sdkDir\platform-tools;" + $env:Path

Write-Host "Accepting licenses and installing Android components..."
# Accept all licenses
echo y | & "$cmdlineDir\bin\sdkmanager.bat" --licenses

Write-Host "Installing platform-tools, build-tools, and platforms..."
& "$cmdlineDir\bin\sdkmanager.bat" "platform-tools" "build-tools;34.0.0" "platforms;android-34"

Write-Host "Adding paths to User Environment Variables permanently..."
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkDir, "User")

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notmatch "$jdkDir\\bin") {
    $userPath = "$jdkDir\bin;" + $userPath
}
if ($userPath -notmatch "$cmdlineDir\\bin") {
    $userPath = "$cmdlineDir\bin;$sdkDir\platform-tools;" + $userPath
}
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")

Write-Host "Setup complete. Verifying:"
java -version
& "$cmdlineDir\bin\sdkmanager.bat" --version

Write-Host "Please close this terminal and open a new one to use the updated PATH."
