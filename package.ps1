$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
& (Join-Path $root "build.ps1")

$input = Join-Path $root "build\package-input"
$dist = Join-Path $root "build\dist"
if (Test-Path $input) { Remove-Item $input -Recurse -Force }
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path $input | Out-Null
Copy-Item -Path (Join-Path $root "web") -Destination (Join-Path $input "web") -Recurse
Copy-Item -Path (Join-Path $root "README.md") -Destination $input -ErrorAction SilentlyContinue
Copy-Item -Path (Join-Path $root "LICENSE") -Destination $input -ErrorAction SilentlyContinue

$jar = Join-Path $input "UnionCraftLauncher.jar"
& jar --create --file $jar --main-class org.unioncraft.launcher.Main -C (Join-Path $root "build\classes") .
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed" }

Write-Host "Running jpackage..." -ForegroundColor Cyan
& jpackage `
  --type app-image `
  --dest $dist `
  --input $input `
  --name "Union Craft Launcher" `
  --app-version "0.2.0" `
  --vendor "Union Craft" `
  --description "A compact Minecraft launcher" `
  --icon (Join-Path $root "assets\icon.ico") `
  --main-jar "UnionCraftLauncher.jar" `
  --main-class "org.unioncraft.launcher.Main" `
  --java-options "-Dfile.encoding=UTF-8" `
  --java-options "-Ducl.portable=true" `
  --add-modules "java.base,java.net.http,java.desktop,jdk.httpserver,java.logging,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
Copy-Item -Path (Join-Path $root "LICENSE") -Destination (Join-Path $dist "Union Craft Launcher") -Force
Copy-Item -Path (Join-Path $root "README.md") -Destination (Join-Path $dist "Union Craft Launcher") -Force
Write-Host "App image ready -> $dist\Union Craft Launcher\Union Craft Launcher.exe" -ForegroundColor Green
