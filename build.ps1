# 一次构建出全部可分发产物
#
#   .\build.ps1              构建 app-image + 便携 zip + 安装包（需要 WiX，缺失时自动下载）
#   .\build.ps1 -NoInstaller 跳过安装包，只出 app-image + zip
#
# 三个模块：
#   .                 ZenithProxy-WebUI 插件（本仓库本体）
#   .\zenithproxy     ZenithProxy 本体（含配置热重载补丁）
#   .\manager         BotManager 管理器
#
# 产物都在 dist/ 下。

[CmdletBinding()]
param(
    [switch]$NoInstaller,
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
$appVersion = '0.1.0'

function Step([string]$msg) {
    Write-Host ''
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Fail([string]$msg) {
    Write-Host "!!  $msg" -ForegroundColor Red
    exit 1
}

# ── 找 JDK 21 ---------------------------------------------------------------
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\jpackage.exe'))) {
    $candidates = @()
    if (Test-Path 'C:\Program Files\Java') {
        $candidates = Get-ChildItem 'C:\Program Files\Java' -Directory |
            Where-Object { $_.Name -like 'jdk-21*' } |
            Sort-Object Name -Descending
    }
    if ($candidates.Count -eq 0) { Fail '找不到 JDK 21（需要 jpackage / jlink）。请设置 JAVA_HOME。' }
    $jdk = $candidates[0].FullName
}
$env:JAVA_HOME = $jdk
Write-Host "JDK: $jdk"

if (-not (Test-Path (Join-Path $jdk 'bin\jlink.exe'))) { Fail "JDK 里没有 jlink：$jdk" }

# ── 清理 --------------------------------------------------------------------
if ($Clean) {
    Step '清理旧产物'
    Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
    foreach ($sub in @('', 'zenithproxy', 'manager')) {
        foreach ($p in @('build', 'dist', 'dist-installer', 'jp-temp')) {
            Remove-Item (Join-Path (Join-Path $root $sub) $p) -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$stage = Join-Path $dist 'stage'
$image = Join-Path $dist 'ZenithProxyBotManager'
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 有些环境 %TEMP% 不可写（WiX 的 light.exe 会直接报 Access denied），统一指到 dist\tmp
$tmp = Join-Path $dist 'tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp

# ── 1. ZenithProxy（含配置热重载补丁） --------------------------------------
Step '构建 ZenithProxy'
Push-Location (Join-Path $root 'zenithproxy')
try {
    & .\gradlew.bat shadowJar --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { Fail 'ZenithProxy 构建失败' }
} finally { Pop-Location }

$zenithJar = Join-Path $root 'zenithproxy\build\libs\ZenithProxy.jar'
if (-not (Test-Path $zenithJar)) { Fail "没找到 $zenithJar" }

# ── 2. WebUI 插件（本仓库） -------------------------------------------------
Step '构建 ZenithProxy-WebUI 插件'
Push-Location $root
try {
    & .\gradlew.bat shadowJar --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { Fail 'WebUI 插件构建失败' }
} finally { Pop-Location }

$pluginJar = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'ZenithProxy-WebUI-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*-sources*' -and $_.Name -notlike '*-javadoc*' } | Select-Object -First 1
if (-not $pluginJar) { Fail '没找到 WebUI 插件 jar' }

# ── 3. 管理器 ---------------------------------------------------------------
Step '构建 BotManager'
Push-Location (Join-Path $root 'manager')
try {
    & .\gradlew.bat shadowJar --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { Fail 'BotManager 构建失败' }
} finally { Pop-Location }

$managerJar = Join-Path $root 'manager\build\libs\BotManager-0.1.0.jar'
if (-not (Test-Path $managerJar)) { Fail '没找到 BotManager jar' }

# ── 4. jlink 运行时 ---------------------------------------------------------
Step 'jlink 生成运行时'
$runtime = Join-Path $stage 'runtime'
& (Join-Path $jdk 'bin\jlink.exe') `
    --add-modules ALL-MODULE-PATH `
    --strip-debug --no-header-files --no-man-pages `
    --compress=zip-6 `
    --output $runtime
if ($LASTEXITCODE -ne 0) { Fail 'jlink 失败' }
if (-not (Test-Path (Join-Path $runtime 'bin\java.exe'))) { Fail 'jlink 出来的运行时里没有 java.exe' }

# ── 5. 组装 jpackage 的输入目录 --------------------------------------------
Step '准备分发内容'
$input = Join-Path $stage 'input'
New-Item -ItemType Directory -Force -Path $input | Out-Null
Copy-Item $managerJar (Join-Path $input 'BotManager-0.1.0.jar')
Copy-Item $zenithJar (Join-Path $input 'ZenithProxy.jar')
Copy-Item (Join-Path $root 'manager\src\main\resources\template') $input -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $input 'plugins') | Out-Null
Copy-Item $pluginJar.FullName (Join-Path $input 'plugins')

$readme = Join-Path $root 'BOT-MANAGER.md'
if (Test-Path $readme) { Copy-Item $readme (Join-Path $input 'README.md') }

# ── 6. app-image ------------------------------------------------------------
Step 'jpackage 生成 app-image'
& (Join-Path $jdk 'bin\jpackage.exe') `
    --type app-image `
    --name ZenithProxyBotManager `
    --app-version $appVersion `
    --input $input `
    --main-jar 'BotManager-0.1.0.jar' `
    --main-class 'com.zenith.manager.Main' `
    --runtime-image $runtime `
    --dest $dist `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { Fail 'jpackage app-image 失败' }

Write-Host "app-image: $image"

# ── 7. 便携 zip -------------------------------------------------------------
Step '打包便携版 zip'
$zip = Join-Path $dist "ZenithProxyBotManager-$appVersion-portable.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path $image -DestinationPath $zip -CompressionLevel Optimal
if (-not (Test-Path $zip)) { Fail 'zip 打包失败' }
Write-Host ("zip: {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))

# ── 8. 安装包（可选，需要 WiX） --------------------------------------------
if (-not $NoInstaller) {
    Step '查找 WiX'
    $wixDir = $null
    if ($env:WIX) {
        $candidate = Join-Path $env:WIX 'bin'
        if (Test-Path (Join-Path $candidate 'candle.exe')) { $wixDir = $candidate }
    }
    if (-not $wixDir) {
        $cmd = Get-Command candle.exe -ErrorAction SilentlyContinue
        if ($cmd) { $wixDir = Split-Path $cmd.Source }
    }
    if (-not $wixDir) {
        foreach ($p in @((Join-Path $root 'tools\wix\tools'), (Join-Path $root 'tools\wix\bin'))) {
            if (Test-Path (Join-Path $p 'candle.exe')) { $wixDir = $p; break }
        }
    }
    if (-not $wixDir) {
        Write-Host '    本地没有 WiX，尝试从 nuget 下载 wix 3.14.1 ...' -ForegroundColor Yellow
        $toolsDir = Join-Path $root 'tools'
        $nupkg = Join-Path $env:TEMP 'wix.3.14.1.nupkg'
        New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
        try {
            Invoke-WebRequest -Uri 'https://www.nuget.org/api/v2/package/wix/3.14.1' -OutFile $nupkg -UseBasicParsing
            Remove-Item (Join-Path $toolsDir 'wix') -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path (Join-Path $toolsDir 'wix') | Out-Null
            Expand-Archive -Path $nupkg -DestinationPath (Join-Path $toolsDir 'wix') -Force
            $wixDir = Join-Path $toolsDir 'wix\tools'
        } catch {
            Write-Host "    下载失败：$($_.Exception.Message)" -ForegroundColor Yellow
            Write-Host '    跳过安装包，只保留 app-image + 便携 zip。' -ForegroundColor Yellow
            $wixDir = $null
        }
    }

    if ($wixDir) {
        Write-Host "    WiX: $wixDir"
        $env:PATH = "$wixDir;$env:PATH"
        Step 'jpackage 生成 Windows 安装包'
        & (Join-Path $jdk 'bin\jpackage.exe') `
            --type exe `
            --name ZenithProxyBotManager `
            --app-version $appVersion `
            --input $input `
            --main-jar 'BotManager-0.1.0.jar' `
            --main-class 'com.zenith.manager.Main' `
            --runtime-image $runtime `
            --dest $dist `
            --java-options '-Dfile.encoding=UTF-8' `
            --win-per-user-install `
            --win-shortcut `
            --win-menu `
            --win-dir-chooser `
            --vendor 'Fishawa'
        if ($LASTEXITCODE -ne 0) {
            Write-Host '!!  安装包构建失败，但 app-image 和 zip 已经好了。' -ForegroundColor Yellow
        }
    }
}

# ── 汇总 --------------------------------------------------------------------
Step '完成'
Get-ChildItem $dist | Where-Object { $_.Name -ne 'stage' } |
    Select-Object Name, @{n = 'Size'; e = { if ($_.PSIsContainer) { '<dir>' } else { '{0:N1} MB' -f ($_.Length / 1MB) } } } |
    Format-Table -AutoSize
