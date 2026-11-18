$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'license-policy.ps1')

$preserved = @('LICENSE', 'LICENSE.md', 'license.MD', 'LICENCE.md', 'LICENSE-MIT.md', 'NOTICE.md',
    'COPYING.md', 'COPYRIGHT.md', 'PATENTS.md', 'AUTHORS.md', 'THIRD_PARTY_NOTICES.md',
    'third-party-licenses.md', 'ThirdPartyNotices.md', 'LICENSES.md')
foreach ($directory in @('', 'backend/', 'browser-agent/node_modules/example/')) {
    foreach ($name in $preserved) {
        if (Test-ExcludedMarkdown -Path ($directory + $name)) { throw "Legal file excluded: $name" }
    }
    foreach ($name in @('README.md', 'CHANGELOG.md', 'AGENTS.md', 'PROJECT_HANDOFF.md', 'licensed-example.md')) {
        if (-not (Test-ExcludedMarkdown -Path ($directory + $name))) { throw "Documentation retained: $name" }
    }
}

foreach ($path in @('LICENSES/Apache-2.0.md', 'node_modules/example/legal/attribution.md',
    'node_modules\example\licenses\custom.md', 'UNLICENSE.md')) {
    if (Test-ExcludedMarkdown -Path $path) { throw "Legal path excluded: $path" }
}

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ('hh-license-policy-' + [guid]::NewGuid().ToString('N'))
try {
    $sourceRoot = Join-Path $temporary 'source'
    $destination = Join-Path $temporary 'output'
    foreach ($entry in $script:LicensePolicy.launcherFiles) {
        $file = Join-Path $sourceRoot $entry.source
        New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
        [System.IO.File]::WriteAllText($file, "Synthetic license: $($entry.source)`r`nCopyright Example`n")
    }
    Copy-LauncherLicenses -Launcher $sourceRoot -Destination $destination
    foreach ($entry in $script:LicensePolicy.launcherFiles) {
        if ((Get-FileHash -LiteralPath (Join-Path $sourceRoot $entry.source)).Hash -ne
            (Get-FileHash -LiteralPath (Join-Path $destination $entry.target)).Hash) { throw 'License bytes changed' }
    }
    $failed = $false
    try { Copy-LauncherLicenses -Launcher (Join-Path $temporary 'missing') -Destination $destination }
    catch { $failed = $true }
    if (-not $failed) { throw 'Missing required licenses did not fail staging' }
} finally {
    $resolved = [System.IO.Path]::GetFullPath($temporary)
    $allowedRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected test directory' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
