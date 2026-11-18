$script:LicensePolicy = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'license-policy.json') | ConvertFrom-Json

function Test-ExcludedMarkdown {
    param([Parameter(Mandatory = $true)][string]$Path)
    $inLegalDirectory = @($Path -split '[\\/]' | Select-Object -SkipLast 1 |
        Where-Object { $_ -match $script:LicensePolicy.legalDirectoryPattern }).Count -gt 0
    return [System.IO.Path]::GetExtension($Path).Equals('.md', [System.StringComparison]::OrdinalIgnoreCase) -and
        [System.IO.Path]::GetFileName($Path) -notmatch $script:LicensePolicy.legalFilePattern -and -not $inLegalDirectory
}

function Copy-LauncherLicenses {
    param([Parameter(Mandatory = $true)][string]$Launcher, [Parameter(Mandatory = $true)][string]$Destination)
    foreach ($entry in $script:LicensePolicy.launcherFiles) {
        $source = Join-Path $Launcher $entry.source
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Required license file missing: $($entry.source)" }
        $target = Join-Path $Destination $entry.target
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $target -Force
    }
}
