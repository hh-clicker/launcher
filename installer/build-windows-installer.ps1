[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApplicationImage,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$source = [System.IO.Path]::GetFullPath($ApplicationImage)
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
$support = [System.IO.Path]::GetFullPath($PSScriptRoot)
$definition = Join-Path $support 'hh-clicker.iss'
$manifestPath = Join-Path $source 'app\resources\release-manifest.json'
$releaseNotesPath = Join-Path $source 'app\resources\release-notes.ru.md'
$releaseNotesDirectory = Join-Path $source 'app\resources\release-notes'
$releaseNotesIndexPath = Join-Path $releaseNotesDirectory 'release-notes.index'

if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "Compose application image not found: $source"
}
if (-not (Test-Path -LiteralPath $definition -PathType Leaf)) {
    throw "Inno Setup definition not found: $definition"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Release manifest not found in application image: $manifestPath"
}
if (-not (Test-Path -LiteralPath $releaseNotesPath -PathType Leaf)) {
    throw "Release notes not found in application image: $releaseNotesPath"
}
if (-not (Test-Path -LiteralPath $releaseNotesIndexPath -PathType Leaf)) {
    throw "Release notes index not found in application image: $releaseNotesIndexPath"
}
if (-not (Test-Path -LiteralPath (Join-Path $releaseNotesDirectory "$Version.ru.md") -PathType Leaf)) {
    throw "Target release notes not found in application image for version $Version"
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.version -ne $Version) {
    throw "Application image release manifest does not match version $Version"
}
$buildProperty = $manifest.PSObject.Properties['build']
$nightly = $buildProperty -and $buildProperty.Value.channel -eq 'nightly'
$outputSuffix = if ($nightly) { '-nightly' } else { '' }
$displayVersion = if ($nightly) { "$Version nightly" } else { $Version }
$minimumUpgradeVersion = if ($manifest.minimumUpgradeVersion) {
    [string]$manifest.minimumUpgradeVersion
} else {
    ''
}
if ($minimumUpgradeVersion -and $minimumUpgradeVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid minimum upgrade version: $minimumUpgradeVersion"
}

$programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
$programFilesX86Compiler = if ($programFilesX86) {
    Join-Path $programFilesX86 'Inno Setup 6\ISCC.exe'
} else {
    $null
}

$compilerCandidates = @(
    (Get-Command 'ISCC.exe' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
    (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
    $programFilesX86Compiler
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -Unique

$compiler = $compilerCandidates | Select-Object -First 1
if (-not $compiler) {
    throw 'Inno Setup 6 is required. Install it with: winget install --id JRSoftware.InnoSetup --exact'
}

New-Item -ItemType Directory -Path $output -Force | Out-Null

& $compiler `
    '/Qp' `
    "/DAppVersion=$Version" `
    "/DAppDisplayVersion=$displayVersion" `
    "/DOutputSuffix=$outputSuffix" `
    "/DSourceDir=$source" `
    "/DOutputDir=$output" `
    "/DSupportDir=$support" `
    "/DReleaseNotesDir=$releaseNotesDirectory" `
    "/DReleaseNotesIndex=$releaseNotesIndexPath" `
    "/DMinimumUpgradeVersion=$minimumUpgradeVersion" `
    $definition

if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup failed with exit code $LASTEXITCODE"
}

$result = Join-Path $output "HH-Clicker-Setup-$Version$outputSuffix.exe"
if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
    throw "Installer was not created: $result"
}

Write-Host "Windows installer ready: $result"
