param([string]$JavaHome = 'C:/Program Files/Java/jdk-20')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw '请指定完整 JDK' }
$env:JAVA_HOME = $JavaHome
Push-Location $PSScriptRoot
try {
    & ./gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:testXfriendDebugUnitTest :app:testXsalersDebugUnitTest :app:assembleXfriendDebug :app:assembleXsalersDebug :app:lintXfriendDebug :app:lintXsalersDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle 验证失败：$LASTEXITCODE" }
} finally { Pop-Location }
