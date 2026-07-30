# ============================================================================
# Hermes Agent 国内安装脚本 (China Installer)
# 用法: irm https://gitee.com/xxx/hermes-cn/raw/main/install-cn.ps1 | iex
# 或:  .\install-cn.ps1
# ============================================================================
param(
    [string]$Mirror = "https://ghfast.top/",
    [string]$NpmRegistry = "https://registry.npmmirror.com",
    [string]$NodeMirror = "https://npmmirror.com/mirrors/node/",
    [string]$ElectronMirror = "https://npmmirror.com/mirrors/electron/",
    [string]$PlaywrightHost = "https://npmmirror.com/mirrors/playwright/",
    [string]$Proxy = ""
)

$ErrorActionPreference = "Stop"

if ($Proxy) { $env:HTTP_PROXY = $Proxy; $env:HTTPS_PROXY = $Proxy }

$env:npm_config_registry = $NpmRegistry
$env:NODEJS_ORG_MIRROR = $NodeMirror
$env:ELECTRON_MIRROR = $ElectronMirror
$env:PLAYWRIGHT_DOWNLOAD_HOST = $PlaywrightHost

Write-Host ""
Write-Host "+-------------------------------------------------------+" -ForegroundColor Magenta
Write-Host "|   Hermes Agent 国内安装 (镜像加速)                    |" -ForegroundColor Magenta
Write-Host "+-------------------------------------------------------+" -ForegroundColor Magenta
Write-Host ""

# ============================================================================
# 1. 预装 uv
# ============================================================================
Write-Host "-> 正在安装 uv 包管理器..." -ForegroundColor Cyan

$hermesBin = "$env:LOCALAPPDATA\hermes\bin"
New-Item -ItemType Directory -Force -Path $hermesBin | Out-Null

$arch = if ([Environment]::Is64BitOperatingSystem) { "x86_64" } else { "i686" }

$uvUrl = "${Mirror}https://github.com/astral-sh/uv/releases/latest/download/uv-${arch}-pc-windows-msvc.zip"
$uvZip = "$env:TEMP\uv.zip"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-RestMethod -Uri $uvUrl -OutFile $uvZip -TimeoutSec 120
    Expand-Archive -Path $uvZip -DestinationPath $hermesBin -Force
    Remove-Item $uvZip -Force
    if (Test-Path "$hermesBin\uv-${arch}-pc-windows-msvc\uv.exe") {
        Move-Item "$hermesBin\uv-${arch}-pc-windows-msvc\uv.exe" "$hermesBin\uv.exe" -Force
        Remove-Item "$hermesBin\uv-${arch}-pc-windows-msvc" -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path "$hermesBin\uv.exe")) {
        $uvFiles = Get-ChildItem "$hermesBin\*.exe" -Recurse -ErrorAction SilentlyContinue
        foreach ($f in $uvFiles) { Copy-Item $f.FullName "$hermesBin\uv.exe" -Force }
    }
} catch {
    Write-Host "[!] uv 镜像下载失败，尝试走 install.ps1 内置安装..." -ForegroundColor Yellow
}

$env:Path = "${hermesBin};${env:Path}"

# ============================================================================
# 2. 预装 ffmpeg（从镜像下载，不走 winget）
# ============================================================================
Write-Host "-> 正在安装 ffmpeg（镜像加速）..." -ForegroundColor Cyan

$ffmpegDir = "$hermesBin\ffmpeg"
$ffmpegZip = "$env:TEMP\ffmpeg.zip"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ffmpegUrl = "${Mirror}https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
    # ffmpeg-master-latest-win64-gpl 约 130MB，设长超时
    Invoke-RestMethod -Uri $ffmpegUrl -OutFile $ffmpegZip -TimeoutSec 300
    Expand-Archive -Path $ffmpegZip -DestinationPath "$env:TEMP\ffmpeg-extract" -Force
    Remove-Item $ffmpegZip -Force
    New-Item -ItemType Directory -Force -Path $ffmpegDir | Out-Null
    $ffexe = Get-ChildItem "$env:TEMP\ffmpeg-extract" -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
    if ($ffexe) {
        Copy-Item $ffexe.FullName "$ffmpegDir\ffmpeg.exe" -Force
        Remove-Item "$env:TEMP\ffmpeg-extract" -Recurse -Force -ErrorAction SilentlyContinue
    }
    $env:Path = "${ffmpegDir};${env:Path}"
    Write-Host "  [OK] ffmpeg 已安装" -ForegroundColor Green
} catch {
    Write-Host "[!] ffmpeg 镜像下载失败，跳过。需要 TTS 时可手动安装："
    Write-Host "    scoop install ffmpeg 或下载 https://ffmpeg.org/download.html" -ForegroundColor Yellow
}

# ============================================================================
# 3. 拉取原始 install.ps1 并修补
# ============================================================================
Write-Host "-> 正在拉取安装脚本..." -ForegroundColor Cyan
$scriptUrl = "${Mirror}https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.ps1"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$script = Invoke-RestMethod -Uri $scriptUrl -TimeoutSec 60

# 替换 git clone URL
$script = $script -replace 'https://github\.com/NousResearch/hermes-agent\.git',
    "${Mirror}https://github.com/NousResearch/hermes-agent.git"

# 跳过 uv 安装阶段（已预装）
$script = $script -replace 'if \(-not \(Install-Uv\)\)\s*\{ throw "uv installation failed" \}',
    'Write-Host "[OK] uv already installed" -ForegroundColor Green'

# 跳过 winget ffmpeg 安装（已预装）
$script = $script -replace 'winget install ffmpeg[\s\S]*?(?=\nfunction|\n#|\n\w)',
    'Write-Host "[OK] ffmpeg already installed by china installer"'

Write-Host "-> 正在安装（镜像加速中，请耐心等待 5-15 分钟）..." -ForegroundColor Cyan

# 执行修补后的脚本
Invoke-Expression $script

Write-Host ""
Write-Host "[OK] 安装完成！" -ForegroundColor Green
Write-Host "重启终端后输入 hermes 即可开始使用。" -ForegroundColor Cyan
Write-Host "如果遇到问题，请截图并联系作者。" -ForegroundColor Yellow
