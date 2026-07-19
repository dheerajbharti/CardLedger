$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot
if (-not (Test-Path ".\gradle\wrapper\gradle-wrapper.jar")) {
    & powershell -ExecutionPolicy Bypass -File ".\bootstrap-wrapper.ps1"
}
& .\gradlew.bat installDebug
exit $LASTEXITCODE
