$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$WrapperDir = Join-Path $ProjectRoot "gradle\wrapper"
$JarPath = Join-Path $WrapperDir "gradle-wrapper.jar"
$Url = "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
$ExpectedSha256 = "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"

New-Item -ItemType Directory -Force -Path $WrapperDir | Out-Null
Write-Host "Downloading the official Gradle 8.9 wrapper JAR..."
Invoke-WebRequest -Uri $Url -OutFile $JarPath
$ActualSha256 = (Get-FileHash -Path $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ActualSha256 -ne $ExpectedSha256) {
    Remove-Item -Force $JarPath
    throw "Checksum mismatch. Expected $ExpectedSha256 but received $ActualSha256."
}
Write-Host "Gradle wrapper installed and verified: $JarPath"
