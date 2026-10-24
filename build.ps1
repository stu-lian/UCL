$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$classes = Join-Path $root "build\classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = Get-ChildItem -Path (Join-Path $root "src\main\java") -Filter *.java -Recurse | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) Java sources..." -ForegroundColor Cyan
& javac -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
Write-Host "Build OK -> $classes" -ForegroundColor Green
