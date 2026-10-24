$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
& (Join-Path $root "build.ps1")

$jre = Join-Path $root "build\linux-jre21.tar.gz"
if (!(Test-Path $jre)) {
    Write-Host "Downloading Linux JRE 21..." -ForegroundColor Cyan
    $url = "https://cdn.azul.com/zulu/bin/zulu21.54.107-ca-jre21.0.13-linux_x64.tar.gz"
    Invoke-WebRequest -Uri $url -OutFile $jre -UseBasicParsing
}

$linux = Join-Path $root "build\linux"
$app = Join-Path $linux "Union Craft Launcher"
if (Test-Path $linux) { Remove-Item $linux -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $app "app") | Out-Null

$jar = Join-Path $app "app\UnionCraftLauncher.jar"
& jar --create --file $jar --main-class org.unioncraft.launcher.Main -C (Join-Path $root "build\classes") .
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed" }

Copy-Item -Path (Join-Path $root "web") -Destination (Join-Path $app "app\web") -Recurse
Copy-Item -Path (Join-Path $root "LICENSE") -Destination $app
Copy-Item -Path $jre -Destination (Join-Path $app "jre-linux-x64.tar.gz")
Copy-Item -Path (Join-Path $root "tools\linux\start.sh") -Destination $app
Copy-Item -Path (Join-Path $root "tools\linux\README-Linux.txt") -Destination $app
Copy-Item -Path (Join-Path $root "assets\icon.png") -Destination $app -ErrorAction SilentlyContinue

# start.sh 必须使用 LF，否则 Linux 上会报 bad interpreter
$start = Join-Path $app "start.sh"
$content = [System.IO.File]::ReadAllText($start).Replace("`r`n", "`n")
[System.IO.File]::WriteAllText($start, $content, (New-Object System.Text.UTF8Encoding($false)))

& python (Join-Path $root "tools\package_linux.py")
if ($LASTEXITCODE -ne 0) { throw "Linux archive creation failed" }
Write-Host "Linux package -> $root\build\dist\UnionCraftLauncher-linux-x64.tar.gz" -ForegroundColor Green
