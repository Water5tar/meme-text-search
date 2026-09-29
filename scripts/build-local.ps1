param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath "$JdkPath\bin\java.exe")) { throw 'Pass -JdkPath pointing to JDK 17.' }
if (!(Test-Path -LiteralPath "$SdkPath\platforms\android-35\android.jar")) { throw 'Pass -SdkPath containing Android SDK 35.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-cache'
Push-Location -LiteralPath $projectRoot
try {
    Set-Content -LiteralPath local.properties -Value "sdk.dir=$($SdkPath.Replace('\','/'))" -Encoding ascii
    $variant = if ($Release) { 'Release' } else { 'Debug' }
    & .\gradlew.bat ':core:test' ":app:assemble$variant" ":app:lint$variant" --max-workers=2 --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
