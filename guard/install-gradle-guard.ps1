# 安装无绕过的机器级拦截器：阻止 Gradle 卸载任务和 connected 测试。

$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'gradle-init/device-guard.gradle'
$dstDir = Join-Path $env:USERPROFILE '.gradle/init.d'
$dst = Join-Path $dstDir 'device-guard.gradle'

if (-not (Test-Path $src)) { throw "找不到 $src" }
New-Item -ItemType Directory -Force -Path $dstDir | Out-Null

if (Test-Path $dst) {
    $a = (Get-FileHash $src).Hash; $b = (Get-FileHash $dst).Hash
    if ($a -eq $b) { Write-Host "已是最新（$dst）"; exit 0 }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$dst.$stamp.bak"
    Copy-Item $dst $backup
    Write-Host "旧版本已备份到 $backup"
}
Copy-Item $src $dst -Force
Write-Host "已安装无绕过的数据保护 guard: $dst"
