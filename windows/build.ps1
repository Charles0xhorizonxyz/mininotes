param([switch]$Package)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$jdk = $env:JAVA_HOME
if (!$jdk -or !(Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $compiler = Get-Command javac.exe -ErrorAction Stop
    $jdk = Split-Path (Split-Path $compiler.Source)
}
$env:JAVA_HOME = $jdk
$env:DEBUG = ''
& (Join-Path $project '..\android\gradlew.bat') -p $project --no-daemon test packageInput
if ($LASTEXITCODE -ne 0) { throw 'Windows build or tests failed.' }
if ($Package) {
    # A new destination makes rebuilds non-destructive, including when an older
    # build is open. The ZIP contains the entire app image, not just its launcher.
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $destination = Join-Path $project "build\package-$stamp"
    & (Join-Path $jdk 'bin\jpackage.exe') --type app-image --name Mininotes `
        --app-version 0.0.21 --vendor Mininotes --dest $destination `
        --icon (Join-Path $project 'build\icons\Mininotes.ico') `
        --input (Join-Path $project 'build\package-input') `
        --main-jar Mininotes-Windows-0.0.021.jar --main-class org.mininotes.android.Desktop `
        --java-options '-Dsun.awt.fontconfig=$APPDIR\fontconfig.properties' `
        --add-modules java.base,java.desktop,java.sql,java.logging,java.naming,jdk.crypto.ec,jdk.unsupported
    if ($LASTEXITCODE -ne 0) { throw 'Windows packaging failed.' }
    $latest = Join-Path $project '..\dist\latest'
    $archived = Join-Path $project '..\dist\archive'
    New-Item -ItemType Directory $latest,$archived -Force | Out-Null
    # Only the newest build stays in dist\latest; whatever was there before is moved to dist\archive.
    Get-ChildItem $latest -Filter 'Mininotes-Windows-*' | Where-Object { $_.Name -notlike 'Mininotes-Windows-0.0.021*' } |
        ForEach-Object { try { Move-Item -LiteralPath $_.FullName -Destination $archived -Force } catch { Write-Output "Left in latest (it is open): $($_.TargetObject)" } }
    $archive = Join-Path $latest 'Mininotes-Windows-0.0.021.zip'
    Compress-Archive -Path (Join-Path $destination 'Mininotes') -DestinationPath $archive -Force
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  Mininotes-Windows-0.0.021.zip" | Set-Content -Encoding ascii "$archive.sha256"
    # The app itself, ready to run, beside its zip - one folder, the EXE at its top - and a shortcut to it.
    $app = Join-Path $latest 'Mininotes-Windows-0.0.021'
    if (Test-Path $app) { Remove-Item $app -Recurse -Force }
    Copy-Item (Join-Path $destination 'Mininotes') $app -Recurse
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut((Join-Path $latest 'Mininotes.lnk'))
    $link.TargetPath = (Resolve-Path (Join-Path $app 'Mininotes.exe')).Path
    $link.WorkingDirectory = (Resolve-Path $app).Path
    $link.Description = 'Mininotes for Windows 0.0.021'
    $link.Save()
    # Earlier packaging folders are only copies of what dist keeps; the newest stays, in case it is open.
    Get-ChildItem (Join-Path $project 'build') -Directory -Filter 'package-*' | Where-Object { $_.FullName -ne $destination } |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Output "Built: $app\Mininotes.exe"
    Write-Output "Archive: $archive"
}
