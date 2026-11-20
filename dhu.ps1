param(
    [string]$DhuPath,
    [string]$AdbPath,
    [ValidateRange(0, 100)]
    [int]$SpeedMps = 15,
    [switch]$Headless
)

$ErrorActionPreference = 'Stop'
$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk",
    "${env:ProgramFiles(x86)}\Android\android-sdk") | Where-Object { $_ }

if (!$DhuPath) {
    $dhuCandidates = @((Join-Path $PSScriptRoot '.tooling\dhu\desktop-head-unit.exe')) + @(
        $sdkRoots | ForEach-Object { Join-Path $_ 'extras\google\auto\desktop-head-unit.exe' })
    $DhuPath = $dhuCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if (!$AdbPath) {
    $AdbPath = (Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (!$AdbPath) {
        $AdbPath = $sdkRoots | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
}
if (!$DhuPath -or !(Test-Path -LiteralPath $DhuPath -PathType Leaf)) {
    throw 'Install the Android Auto Desktop Head Unit from the Android SDK or pass -DhuPath.'
}
if (!$AdbPath -or !(Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'Install Android SDK Platform-Tools or pass -AdbPath.'
}
$DhuPath = (Resolve-Path -LiteralPath $DhuPath).ProviderPath
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).ProviderPath

$device = (& $AdbPath -d get-serialno | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $device -notmatch '^[A-Za-z0-9._:-]+$' -or $device -eq 'unknown') {
    throw 'Connect one phone over USB and authorize USB debugging.'
}

function Invoke-DeviceAdb {
    $output = & $AdbPath -s $device @args
    if ($LASTEXITCODE -ne 0) {
        throw 'ADB command failed. Check the USB connection and USB debugging authorization.'
    }
    return $output
}

$component = 'local.parkbrowser/.DhuControlReceiver'
$session = [Guid]::NewGuid().ToString('N')
$restrictionsAtSpeed = if ($SpeedMps -gt 0) { 'all' } else { 'none' }
$config = Join-Path ([IO.Path]::GetTempPath()) "den-dhu-$session.ini"
$port = $null
$dhu = $null
$connected = $false
try {
    Set-Content -LiteralPath $config -Encoding ascii -Value @('[sensors]', 'driving_status = true', 'speed = true')
    $portOutput = (Invoke-DeviceAdb forward 'tcp:0' 'tcp:5277' | Out-String).Trim()
    if ($portOutput -notmatch '^[1-9][0-9]{0,4}$' -or [int]$portOutput -gt 65535) {
        throw 'ADB did not allocate a local port for the Desktop Head Unit.'
    }
    $port = [int]$portOutput

    $launch = New-Object System.Diagnostics.ProcessStartInfo
    $launch.FileName = $DhuPath
    $launch.WorkingDirectory = Split-Path -Parent $DhuPath
    $launch.Arguments = "--adb=127.0.0.1:$port -c `"$config`""
    if ($Headless) { $launch.Arguments += ' --headless' }
    $launch.UseShellExecute = $false
    $launch.CreateNoWindow = $true
    $launch.RedirectStandardInput = $true
    $dhu = [System.Diagnostics.Process]::Start($launch)
    if ($dhu.WaitForExit(1500)) {
        throw 'The Desktop Head Unit stopped. Unlock the phone and start the head unit server in Android Auto.'
    }
    $dhu.StandardInput.NewLine = "`n"
    $dhu.StandardInput.WriteLine("speed $SpeedMps")
    $dhu.StandardInput.WriteLine("restrict $restrictionsAtSpeed")
    $dhu.StandardInput.Flush()
    $unrestricted = $false

    while (!$dhu.HasExited) {
        $applied = $unrestricted.ToString().ToLowerInvariant()
        $reply = Invoke-DeviceAdb shell am broadcast -a 'local.parkbrowser.dhu.SYNC' -n $component `
            --es session $session --ez unrestricted $applied | Out-String
        if ($reply -notmatch '(?m)^Broadcast completed:.*\bdata="den-dhu:(idle|unrestricted|restricted|busy)"\s*$') {
            throw 'den did not respond. Open den 1.5.0 or newer on the phone and restart this helper.'
        }
        $request = $Matches[1]
        if ($request -eq 'busy') {
            throw 'Another den helper owns the phone session. Close it and try again.'
        }
        if (!$connected) {
            $connected = $true
            Write-Host "Simulated speed: $SpeedMps m/s. Open the den menu on the phone to change emulator restrictions."
        }
        if ($request -ne 'idle' -and !$dhu.HasExited) {
            $unrestricted = $request -eq 'unrestricted'
            $dhuRestrictions = if ($unrestricted) { 'none' } else { $restrictionsAtSpeed }
            $dhu.StandardInput.WriteLine("restrict $dhuRestrictions")
            $dhu.StandardInput.Flush()
            Write-Host "Desktop Head Unit restrictions: $dhuRestrictions"
        }
        Start-Sleep -Milliseconds 750
    }
    if ($dhu.ExitCode -ne 0) {
        throw "The Desktop Head Unit exited with code $($dhu.ExitCode)."
    }
} finally {
    if ($dhu) {
        try {
            if (!$dhu.HasExited) {
                $dhu.StandardInput.WriteLine("restrict $restrictionsAtSpeed")
                $dhu.StandardInput.WriteLine('quit')
                $dhu.StandardInput.Flush()
                if (!$dhu.WaitForExit(2000)) { $dhu.Kill() }
            }
        } catch {
            if (!$dhu.HasExited) { $dhu.Kill() }
        } finally {
            $dhu.Dispose()
        }
    }
    if ($connected) {
        try {
            Invoke-DeviceAdb shell am broadcast -a 'local.parkbrowser.dhu.END' -n $component `
                --es session $session | Out-Null
        } catch {
            Write-Verbose $_
        }
    }
    if ($null -ne $port) {
        try { Invoke-DeviceAdb forward --remove "tcp:$port" | Out-Null } catch { Write-Warning $_ }
    }
    Remove-Item -LiteralPath $config -ErrorAction SilentlyContinue
}
