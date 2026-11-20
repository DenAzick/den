param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $SdkPath) {
    $sdkCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path ${env:ProgramFiles(x86)} 'Android\android-sdk')
    )
    $SdkPath = $sdkCandidates | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'platforms\android-35\android.jar') -PathType Leaf
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
    throw 'Set -SdkPath to an Android SDK with Platform 35 and Build Tools 35.0.0, and -JdkPath to JDK 17+.'
}

function Invoke-BuildTool($Executable, $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed ($LASTEXITCODE): $Executable"
    }
}

$tools = Join-Path $SdkPath 'build-tools\35.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-35\android.jar'
$aapt2 = Join-Path $tools 'aapt2.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$d8 = Join-Path $tools 'lib\d8.jar'
$apksigner = Join-Path $tools 'lib\apksigner.jar'
$java = Join-Path $JdkPath 'bin\java.exe'
$javac = Join-Path $JdkPath 'bin\javac.exe'
$keytool = Join-Path $JdkPath 'bin\keytool.exe'
foreach ($requiredFile in @($androidJar, $aapt2, $zipalign, $d8, $apksigner, $java, $javac, $keytool)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Tool or SDK file not found: $requiredFile"
    }
}

$source = Join-Path $PSScriptRoot 'app\src\main'
$buildRoot = Join-Path $PSScriptRoot 'build'
$build = Join-Path $buildRoot ([Guid]::NewGuid().ToString('N'))
$apkDir = Join-Path $PSScriptRoot 'dist'
$keyDir = Join-Path $PSScriptRoot '.signing'
$compiledResources = Join-Path $build 'resources.zip'
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$signed = Join-Path $build 'signed.apk'
$outputApk = Join-Path $apkDir 'den.apk'
$keystore = Join-Path $keyDir 'parkbrowser.p12'
$passwordFile = Join-Path $keyDir 'password.txt'

New-Item -ItemType Directory -Force -Path $generated, $classes, $apkDir, $keyDir | Out-Null

try {
    Invoke-BuildTool $aapt2 @('compile', '--dir', (Join-Path $source 'res'), '-o', $compiledResources)
    Invoke-BuildTool $aapt2 @('link', '-I', $androidJar, '--manifest', (Join-Path $source 'AndroidManifest.xml'),
        '--java', $generated, '-o', $unsigned, $compiledResources)

    $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java'), $generated -Recurse -Filter '*.java' -File |
        Select-Object -ExpandProperty FullName)
    Invoke-BuildTool $javac (@('--release', '17', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', $classes) + $javaFiles)

    $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' -File | Select-Object -ExpandProperty FullName)
    # Build Tools 35.0.0 can emit API 34-compatible DEX. The manifest requires API 35 to install.
    Invoke-BuildTool $java (@('-cp', $d8, 'com.android.tools.r8.D8', '--release', '--min-api', '34',
        '--lib', $androidJar, '--output', $build) + $classFiles)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $build 'classes.dex'),
            'classes.dex', [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    } finally {
        $archive.Dispose()
    }

    Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)
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
    Invoke-BuildTool $zipalign @('-c', '4', $signed)
    Move-Item -LiteralPath $signed -Destination $outputApk -Force
    Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
} finally {
    $resolvedBuild = (Resolve-Path -LiteralPath $build).ProviderPath
    $resolvedRoot = (Resolve-Path -LiteralPath $buildRoot).ProviderPath
    if ($resolvedBuild -eq $build -and $resolvedRoot -eq $buildRoot -and
        (Split-Path -Parent $resolvedBuild) -eq $resolvedRoot -and
        -not ((Get-Item -LiteralPath $resolvedBuild).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
    }
}
