param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $SdkPath) {
    $sdkCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path ${env:ProgramFiles(x86)} 'Android\android-sdk'),
        (Join-Path $PSScriptRoot '.tooling\den-gecko-emulator')
    )
    $SdkPath = $sdkCandidates | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'platforms\android-37.2\android.jar') -PathType Leaf
    } | Select-Object -First 1
}

if (-not $JdkPath) {
    $jdkCandidates = @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        (Join-Path ${env:ProgramFiles(x86)} 'Android\openjdk\jdk-17.0.14')
    )
    $JdkPath = $jdkCandidates | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe') -PathType Leaf
    } | Select-Object -First 1
}

if (-not $SdkPath -or -not $JdkPath) {
    throw 'Set -SdkPath to an Android SDK with Platform 37.2 and Build Tools 36.0.0, and -JdkPath to JDK 17+.'
}

function Invoke-BuildTool($Executable, $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed ($LASTEXITCODE): $Executable"
    }
}

$tools = Join-Path $SdkPath 'build-tools\36.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-37.2\android.jar'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'lib\apksigner.jar'
$java = Join-Path $JdkPath 'bin\java.exe'
$keytool = Join-Path $JdkPath 'bin\keytool.exe'
$gradle = Join-Path $PSScriptRoot 'gradlew.bat'
foreach ($requiredFile in @($androidJar, $zipalign, $apksigner, $java, $keytool, $gradle)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Tool or SDK file not found: $requiredFile"
    }
}

$buildRoot = Join-Path $PSScriptRoot 'build'
$build = Join-Path $buildRoot ([Guid]::NewGuid().ToString('N'))
$apkDir = Join-Path $PSScriptRoot 'dist'
$keyDir = Join-Path $PSScriptRoot '.signing'
$unsigned = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$signed = Join-Path $build 'signed.apk'
$outputApk = Join-Path $apkDir 'den.apk'
$keystore = Join-Path $keyDir 'parkbrowser.p12'
$passwordFile = Join-Path $keyDir 'password.txt'

$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$previousGradleHome = $env:GRADLE_USER_HOME

New-Item -ItemType Directory -Force -Path $build, $apkDir, $keyDir | Out-Null

try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).ProviderPath
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkPath).ProviderPath
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    if (-not $env:GRADLE_USER_HOME) {
        $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tooling\gradle-home'
    }
    Invoke-BuildTool $gradle @('--project-dir', $PSScriptRoot, ':app:assembleRelease', '--no-daemon', '--console=plain')

    Invoke-BuildTool $zipalign @('-P', '16', '-f', '4', $unsigned, $aligned)
    if (-not (Test-Path -LiteralPath $passwordFile -PathType Leaf)) {
        if (Test-Path -LiteralPath $keystore) {
            throw 'Restore .signing\password.txt for the existing signing key.'
        }
        $passwordBytes = New-Object byte[] 24
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($passwordBytes) } finally { $random.Dispose() }
        Set-Content -LiteralPath $passwordFile -Value ([Convert]::ToBase64String($passwordBytes)) -Encoding ascii -NoNewline
    }
    if (-not (Test-Path -LiteralPath $keystore)) {
        Invoke-BuildTool $keytool @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
            '-storepass:file', $passwordFile, '-alias', 'parkbrowser', '-keyalg', 'RSA', '-keysize', '3072',
            '-validity', '10000', '-dname', 'CN=ParkBrowser Local Test Key')
    }
    Invoke-BuildTool $java @('-jar', $apksigner, 'sign', '--ks', $keystore, '--ks-key-alias', 'parkbrowser',
        '--ks-pass', "file:$passwordFile", '--out', $signed, $aligned)
    Invoke-BuildTool $java @('-jar', $apksigner, 'verify', '--verbose', $signed)
    Invoke-BuildTool $zipalign @('-c', '-P', '16', '4', $signed)
    Move-Item -LiteralPath $signed -Destination $outputApk -Force
    Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:ANDROID_SDK_ROOT = $previousAndroidSdkRoot
    $env:GRADLE_USER_HOME = $previousGradleHome
    $resolvedBuild = (Resolve-Path -LiteralPath $build).ProviderPath
    $resolvedRoot = (Resolve-Path -LiteralPath $buildRoot).ProviderPath
    if ($resolvedBuild -eq $build -and $resolvedRoot -eq $buildRoot -and
        (Split-Path -Parent $resolvedBuild) -eq $resolvedRoot -and
        -not ((Get-Item -LiteralPath $resolvedBuild).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
    }
}
