[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$WorkspaceRoot,

    [Parameter(Mandatory = $true)]
    [string]$Destination,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseDefinition,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes,

    [switch]$Nightly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$workspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$launcher = Join-Path $workspace 'launcher'
$allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $launcher 'desktopApp\build'))
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$releaseDefinitionPath = [System.IO.Path]::GetFullPath($ReleaseDefinition)
$releaseNotesPath = [System.IO.Path]::GetFullPath($ReleaseNotes)
$releaseCatalogPath = [System.IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $releaseDefinitionPath)))
$prefix = $allowedRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if (-not $destinationPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The staging directory must be inside desktopApp\build: $destinationPath"
}
if (-not (Test-Path -LiteralPath $releaseDefinitionPath -PathType Leaf)) {
    throw "Release definition not found: $releaseDefinitionPath"
}
if (-not (Test-Path -LiteralPath $releaseNotesPath -PathType Leaf)) {
    throw "Release notes not found: $releaseNotesPath"
}

$release = Get-Content -Raw -LiteralPath $releaseDefinitionPath | ConvertFrom-Json
if ($release.schemaVersion -ne 1) {
    throw "Unsupported release schema version: $($release.schemaVersion)"
}
if (-not $Nightly -and $release.version -ne $Version) {
    throw "Release definition version $($release.version) does not match build version $Version"
}
if ($release.minimumUpgradeVersion -and [string]$release.minimumUpgradeVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "Release definition has an invalid minimumUpgradeVersion: $($release.minimumUpgradeVersion)"
}
if ([int]$release.configVersion -lt 1 -or [int]$release.postgresMajor -lt 1) {
    throw 'Release definition has invalid configuration/database metadata'
}

$repositories = @('backend', 'browser-agent', 'frontend', 'generation-agent', 'infra')
if ($Nightly) {
    if ([System.Version]::Parse($Version) -lt [System.Version]::Parse($release.version)) {
        throw 'Nightly version must not be older than the base release version'
    }
    $release | Add-Member -NotePropertyName build -NotePropertyValue ([PSCustomObject]@{
        channel = 'nightly'
        baseVersion = $release.version
        createdAt = [DateTime]::UtcNow.ToString('o')
    })
    $release.version = $Version
    # A working tree can contain migrations that are not part of the base release.
    $release.databaseMigration = $true
}
$inspectedRepositories = if ($Nightly) { $repositories + @('launcher') } else { $repositories }
foreach ($name in $inspectedRepositories) {
    $repository = Join-Path $workspace $name
    if (-not (Test-Path -LiteralPath (Join-Path $repository '.git'))) {
        throw "Git repository not found: $repository"
    }

    $property = $release.repositories.PSObject.Properties[$name]
    $entry = if ($property) { $property.Value } else { $null }
    if (-not $Nightly -and (-not $entry -or $entry.commit -notmatch '^[0-9a-f]{40}$')) {
        throw "Release definition has no valid commit for $name"
    }
    $actualCommitOutput = @(& git -c "safe.directory=$repository" -C $repository rev-parse HEAD)
    if ($LASTEXITCODE -ne 0) { throw "Cannot read Git revision for $name" }
    $actualCommit = ([string]($actualCommitOutput | Select-Object -First 1)).Trim()
    if (-not $Nightly -and $actualCommit -ne $entry.commit) {
        throw "$name is at $actualCommit, but release $Version requires $($entry.commit)"
    }
    $changes = @(& git -c "safe.directory=$repository" -C $repository status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Git status for $name" }
    if (-not $Nightly -and $changes.Count -gt 0) {
        throw "$name has local changes. A release payload must be built from the exact committed revision."
    }
    if ($Nightly) {
        $release.repositories | Add-Member -Force -NotePropertyName $name -NotePropertyValue ([PSCustomObject]@{
            commit = $actualCommit
            dirty = ($changes.Count -gt 0)
        })
    }
}

if (Test-Path -LiteralPath $destinationPath) {
    Remove-Item -LiteralPath $destinationPath -Recurse -Force
}
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null

foreach ($name in $repositories) {
    $repository = [System.IO.Path]::GetFullPath((Join-Path $workspace $name))
    $target = Join-Path $destinationPath $name
    New-Item -ItemType Directory -Path $target -Force | Out-Null

    $relativeFiles = @(
        & git -c core.quotepath=false -c "safe.directory=$repository" -C $repository `
            ls-files --cached
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot list Git files for $name"
    }
    if ($Nightly) {
        $relativeFiles += @(& git -c core.quotepath=false -c "safe.directory=$repository" -C $repository `
            ls-files --others --exclude-standard)
        if ($LASTEXITCODE -ne 0) { throw "Cannot list untracked Git files for $name" }
        $relativeFiles = @($relativeFiles | Select-Object -Unique)
    }

    foreach ($relative in $relativeFiles) {
        if ([string]::IsNullOrWhiteSpace($relative)) { continue }
        # Root tools are developer utilities, not installed service runtime.
        if ($relative.Replace('\', '/').StartsWith('tools/', [System.StringComparison]::OrdinalIgnoreCase)) { continue }
        if ([System.IO.Path]::GetExtension($relative).Equals('.md', [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $nativeRelative = $relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $source = [System.IO.Path]::GetFullPath((Join-Path $repository $nativeRelative))
        $repositoryPrefix = $repository.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $source.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Git returned a path outside repository ${name}: $relative"
        }
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }

        $targetFile = Join-Path $target $nativeRelative
        $targetDirectory = Split-Path -Parent $targetFile
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $targetFile -Force
    }
}

$browserAgent = Join-Path $destinationPath 'browser-agent'
Push-Location $browserAgent
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw "Browser Agent npm ci failed with exit code $LASTEXITCODE" }

    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Browser Agent build failed with exit code $LASTEXITCODE" }

    & npm.cmd prune --omit=dev
    if ($LASTEXITCODE -ne 0) { throw "Browser Agent npm prune failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

# Runtime needs JavaScript only. Declarations and source maps are build products, not installer payload.
Get-ChildItem -LiteralPath (Join-Path $browserAgent 'dist') -File -Recurse |
    Where-Object { $_.Name.EndsWith('.map') -or $_.Name.EndsWith('.d.ts') } |
    Remove-Item -Force

# npm packages bring their own README and changelog files after the Git-filtered copy is complete.
# Apply the Markdown exclusion to the final payload as well, including production node_modules.
Get-ChildItem -LiteralPath $destinationPath -File -Recurse |
    Where-Object { $_.Extension.Equals('.md', [System.StringComparison]::OrdinalIgnoreCase) } |
    Remove-Item -Force

$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines(
    (Join-Path $destinationPath '.hh-clicker-bundle'),
    [string[]](@("version=$Version", 'layout=installer-v2') + $(if ($Nightly) { @('channel=nightly') } else { @() })),
    $utf8WithoutBom
)

[System.IO.File]::WriteAllText(
    (Join-Path $destinationPath 'release-manifest.json'),
    ($release | ConvertTo-Json -Depth 10) + [System.Environment]::NewLine,
    $utf8WithoutBom
)
if ($Nightly) {
    [System.IO.File]::WriteAllText(
        (Join-Path $destinationPath 'release-notes.ru.md'),
        "# HH Clicker $Version - nightly`r`n`r`nLocal test build from current repository working trees, including uncommitted changes. This is not an official release.`r`n",
        $utf8WithoutBom
    )
} else {
    [System.IO.File]::Copy($releaseNotesPath, (Join-Path $destinationPath 'release-notes.ru.md'), $true)
}

$releaseNotesDirectory = Join-Path $destinationPath 'release-notes'
New-Item -ItemType Directory -Path $releaseNotesDirectory -Force | Out-Null
$targetSemanticVersion = [System.Version]::Parse($Version)
$catalogVersions = @(
    Get-ChildItem -LiteralPath $releaseCatalogPath -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Where-Object { [System.Version]::Parse($_.Name) -le $targetSemanticVersion } |
        ForEach-Object {
            $catalogVersion = [System.Version]::Parse($_.Name)
            $catalogManifestPath = Join-Path $_.FullName 'release.json'
            $catalogNotesPath = Join-Path $_.FullName 'notes.ru.md'
            if (-not (Test-Path -LiteralPath $catalogManifestPath -PathType Leaf) -or
                -not (Test-Path -LiteralPath $catalogNotesPath -PathType Leaf)) {
                throw "Release catalog entry $($_.Name) is incomplete"
            }
            $catalogManifest = Get-Content -Raw -LiteralPath $catalogManifestPath | ConvertFrom-Json
            if ($catalogManifest.version -ne $_.Name) {
                throw "Release catalog directory $($_.Name) contains manifest version $($catalogManifest.version)"
            }
            [PSCustomObject]@{
                Version = $_.Name
                SemanticVersion = $catalogVersion
                Notes = $catalogNotesPath
            }
        } |
        Sort-Object SemanticVersion
)
if (-not $Nightly -and -not ($catalogVersions.Version -contains $Version)) {
    throw "Release catalog does not contain notes for target version $Version"
}
foreach ($catalogEntry in $catalogVersions) {
    if ($Nightly -and $catalogEntry.Version -eq $Version) { continue }
    [System.IO.File]::Copy(
        $catalogEntry.Notes,
        (Join-Path $releaseNotesDirectory "$($catalogEntry.Version).ru.md"),
        $true
    )
}
$notesVersions = @($catalogVersions | Where-Object { -not $Nightly -or $_.Version -ne $Version } | ForEach-Object { $_.Version })
if ($Nightly) {
    [System.IO.File]::Copy((Join-Path $destinationPath 'release-notes.ru.md'), (Join-Path $releaseNotesDirectory "$Version.ru.md"), $true)
    $notesVersions += $Version
}
[System.IO.File]::WriteAllLines(
    (Join-Path $releaseNotesDirectory 'release-notes.index'),
    [string[]]$notesVersions,
    $utf8WithoutBom
)

Write-Host "Installer payload ready: $destinationPath"
