# 构建并交付 Singu Xfriend / XSalers Android 0.11.0。
# 用法：
#   pwsh -File scripts\release-0.11.ps1            # 正式开发签名 APK + 复制到 D:\build_apk
#   pwsh -File scripts\release-0.11.ps1 -QaOnly    # 仅构建独立 QA fixture APK（不覆盖正式交付）
#   pwsh -File scripts\release-0.11.ps1 -WithTests # 构建前额外跑两个 flavor 的单元测试
param(
    [string]$JavaHome = 'C:/Program Files/Java/jdk-20',
    [switch]$QaOnly,
    [switch]$WithTests
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw "请指定完整 JDK：$JavaHome" }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$root = Split-Path -Parent $PSScriptRoot
$apkRoot = Join-Path $root 'app/build/outputs/apk'
$delivery = 'D:\build_apk'
$common = @('--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process', '--console=plain')

function Invoke-Gradle([string[]]$tasks, [string]$log, [string[]]$extra = @()) {
    $args = @('gradlew.bat') + $common + $extra + $tasks
    Write-Host "== gradlew $($extra + $tasks -join ' ')" -ForegroundColor Cyan
    Push-Location $root
    try {
        & .\gradlew.bat @common @extra @tasks 2>&1 | Tee-Object -FilePath (Join-Path $root $log)
        if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败：$log（exit $LASTEXITCODE）" }
    } finally { Pop-Location }
}

function Copy-Apk([string]$source, [string]$target) {
    if (-not (Test-Path -LiteralPath $source)) { throw "缺少 APK：$source" }
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
    Write-Host "已复制 -> $target" -ForegroundColor Green
}

# QA fixture APK 使用 .qa 包名单独安装，不能与正式包同名覆盖。
if (-not $QaOnly -or $QaOnly) {
    Invoke-Gradle -tasks @(':app:assembleXfriendDebug', ':app:assembleXsalersDebug') -log 'build-0.11-qa.log' -extra @('-PsalesUiFixture=true')
    Copy-Apk (Join-Path $apkRoot 'xfriend/debug/app-xfriend-debug.apk') (Join-Path $root '.artifacts/0.11-qa/Xfriend-0.11-qa.apk')
    Copy-Apk (Join-Path $apkRoot 'xsalers/debug/app-xsalers-debug.apk') (Join-Path $root '.artifacts/0.11-qa/Xsalers-0.11-qa.apk')
}
if ($QaOnly) { Write-Host 'QA 构建完成（正式 APK 未重新构建）。' -ForegroundColor Yellow; exit 0 }

if ($WithTests) {
    Invoke-Gradle -tasks @(':app:testXfriendDebugUnitTest', ':app:testXsalersDebugUnitTest') -log 'test-0.11.log'
}
Invoke-Gradle -tasks @(':app:assembleXfriendDebug', ':app:assembleXsalersDebug') -log 'build-0.11.log'
Copy-Apk (Join-Path $apkRoot 'xfriend/debug/app-xfriend-debug.apk') (Join-Path $delivery 'XFriend-Android-0.11.0-debug.apk')
Copy-Apk (Join-Path $apkRoot 'xsalers/debug/app-xsalers-debug.apk') (Join-Path $delivery 'XSalers-Android-0.11.0-debug.apk')
Write-Host '0.11.0 构建与复制完成。' -ForegroundColor Green
