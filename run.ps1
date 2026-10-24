$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
if (!(Test-Path (Join-Path $root "build\classes\org\unioncraft\launcher\Main.class"))) {
    & (Join-Path $root "build.ps1")
}
java -cp (Join-Path $root "build\classes") org.unioncraft.launcher.Main
