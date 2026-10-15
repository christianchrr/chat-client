$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = (Get-Command java.exe -ErrorAction Stop).Source | Split-Path -Parent | Split-Path -Parent
}
$jpackage = Join-Path $javaHome "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    throw "jpackage.exe was not found under JAVA_HOME: $javaHome"
}

Push-Location $repoRoot
try {
    & .\mvnw.cmd -B -pl product-launcher -am package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven packaging failed with exit code $LASTEXITCODE"
    }

    $target = Join-Path $repoRoot "product-launcher\target"
    $input = Join-Path $target "app-input"
    $destination = Join-Path $target "package-0.1.29"
    $appImage = Join-Path $destination "LocalAIAssistant"
    New-Item -ItemType Directory -Path $input -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $target "product-launcher.jar") -Destination $input -Force
    if (Test-Path $appImage) {
        Remove-Item -LiteralPath $appImage -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null

    & $jpackage `
        --type app-image `
        --name LocalAIAssistant `
        --app-version 0.1.29 `
        --input $input `
        --main-jar product-launcher.jar `
        --main-class com.localai.launcher.ProductLauncher `
        --java-options "--module-path `$APPDIR --add-modules javafx.controls,javafx.fxml" `
        --dest $destination
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE"
    }

    Write-Output "Runnable app: $appImage\LocalAIAssistant.exe"
} finally {
    Pop-Location
}
