param(
    [ValidateSet("app-image", "exe", "msi", "all")]
    [string]$Type = "all"
)

$ErrorActionPreference = "Stop"
$version = "0.1.41"

$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = (Get-Command java.exe -ErrorAction Stop).Source | Split-Path -Parent | Split-Path -Parent
}
$jpackage = Join-Path $javaHome "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    throw "jpackage.exe was not found under JAVA_HOME: $javaHome"
}
$icon = Join-Path $PSScriptRoot "app.ico"

Push-Location $repoRoot
try {
    & .\mvnw.cmd -B -pl product-launcher -am package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven packaging failed with exit code $LASTEXITCODE"
    }

    $target = Join-Path $repoRoot "product-launcher\target"
    $appInput = Join-Path $target "app-input"
    $destination = Join-Path $target "package-$version"
    $appImage = Join-Path $destination "LocalAIAssistant"
    New-Item -ItemType Directory -Path $appInput -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $target "product-launcher.jar") -Destination $appInput -Force
    if (Test-Path $appImage) {
        Remove-Item -LiteralPath $appImage -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null

    & $jpackage `
        --type app-image `
        --name LocalAIAssistant `
        --app-version $version `
        --input $appInput `
        --main-jar product-launcher.jar `
        --main-class com.localai.launcher.ProductLauncher `
        --java-options "--module-path `$APPDIR --add-modules javafx.controls,javafx.fxml" `
        --icon $icon `
        --dest $destination
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE"
    }
    Write-Output "Runnable app: $appImage\LocalAIAssistant.exe"

    if ($Type -ne "app-image") {
        $wixDir = Join-Path $env:LOCALAPPDATA "LocalAIAssistant-build-tools\wix3"
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Test-Path (Join-Path $wixDir "candle.exe"))) {
            $env:PATH = "$wixDir;$env:PATH"
        }
        if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
            throw "WiX Toolset 3.x (candle.exe) is required for installers. Install it with 'winget install WiXToolset.WiXToolset' or extract wix314-binaries.zip to $wixDir. Use -Type app-image to skip installers."
        }
        $installerTypes = if ($Type -eq "all") { @("exe", "msi") } else { @($Type) }
        foreach ($installerType in $installerTypes) {
            & $jpackage `
                --type $installerType `
                --name LocalAIAssistant `
                --app-version $version `
                --vendor "Local AI Assistant" `
                --description "Private, local AI assistant that runs models on your own PC" `
                --app-image $appImage `
                --icon $icon `
                --resource-dir (Join-Path $PSScriptRoot "wix") `
                --install-dir "LocalAIAssistantApp" `
                --win-menu `
                --win-menu-group "Local AI Assistant" `
                --win-shortcut `
                --win-shortcut-prompt `
                --win-dir-chooser `
                --win-upgrade-uuid "6f1d2c7e-3b4a-4e58-9a0d-5c8b7e2f1a93" `
                --dest $destination
            if ($LASTEXITCODE -ne 0) {
                throw "jpackage $installerType installer failed with exit code $LASTEXITCODE"
            }
            Write-Output "Installer: $destination\LocalAIAssistant-$version.$installerType"
        }
    }
} finally {
    Pop-Location
}