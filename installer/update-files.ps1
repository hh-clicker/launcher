[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('CheckDocker', 'Prepare', 'Rollback', 'Commit', 'Cleanup')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$ApplicationRoot,

    [Parameter(Mandatory = $true)]
    [string]$DataRoot,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$TargetVersion,

    [string]$CurrentVersion = '',

    [Parameter(Mandatory = $true)]
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$application = [System.IO.Path]::GetFullPath($ApplicationRoot).TrimEnd('\')
$data = [System.IO.Path]::GetFullPath($DataRoot).TrimEnd('\')
$log = [System.IO.Path]::GetFullPath($LogPath)
$productRoot = [System.IO.Path]::GetDirectoryName($application)
$expectedData = Join-Path $productRoot 'data'

if ([System.IO.Path]::GetFileName($application) -ne 'application' -or
    -not $data.Equals($expectedData, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unexpected update layout: application=$application data=$data"
}

$updatesRoot = Join-Path $data 'updates'
$activeRoot = Join-Path $updatesRoot 'active'
$backupRoot = Join-Path $activeRoot 'application-backup'
$transactionFile = Join-Path $activeRoot 'transaction.json'
$launcherExecutable = Join-Path $application 'HH Clicker Launcher.exe'
$markerRelativePath = 'app\resources\.hh-clicker-bundle'
$markerPath = Join-Path $application $markerRelativePath

New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($log)) -Force | Out-Null

function Write-Log {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Message)
    Add-Content -LiteralPath $log -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Message" -Encoding UTF8
}

function Write-Transaction {
    param([Parameter(Mandatory = $true)][string]$State)
    New-Item -ItemType Directory -Path $activeRoot -Force | Out-Null
    $document = [ordered]@{
        schemaVersion = 1
        state = $State
        fromVersion = $CurrentVersion
        targetVersion = $TargetVersion
    } | ConvertTo-Json
    $temporary = "$transactionFile.tmp"
    [System.IO.File]::WriteAllText($temporary, $document + [System.Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $transactionFile -Force
}

function Read-TransactionState {
    if (-not (Test-Path -LiteralPath $transactionFile -PathType Leaf)) { return $null }
    try {
        return [string]((Get-Content -Raw -LiteralPath $transactionFile | ConvertFrom-Json).state)
    } catch {
        return $null
    }
}

function Read-BundleVersion {
    param([Parameter(Mandatory = $true)][string]$Root)
    $marker = Join-Path $Root $markerRelativePath
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { return $null }
    $line = Get-Content -LiteralPath $marker | Where-Object { $_ -match '^version=' } | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Substring('version='.Length).Trim()
}

function Test-ApplicationCore {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion
    )
    return (
        (Test-Path -LiteralPath (Join-Path $Root 'HH Clicker Launcher.exe') -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root $markerRelativePath) -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root 'runtime\bin\server\jvm.dll') -PathType Leaf) -and
        ((Read-BundleVersion -Root $Root) -eq $ExpectedVersion)
    )
}

function Copy-DirectoryContents {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -Force -LiteralPath $Source | Copy-Item -Destination $Destination -Recurse -Force
}

function Assert-Backup {
    if (-not (Test-ApplicationCore -Root $backupRoot -ExpectedVersion $CurrentVersion)) {
        throw "Application backup is incomplete: $backupRoot"
    }
}

function Assert-InstalledRelease {
    $required = @(
        'HH Clicker Launcher.exe',
        'runtime\bin\server\jvm.dll',
        'app\resources\infra\compose.yaml',
        'app\resources\generation-agent\compose.yaml',
        'app\resources\browser-agent\package.json',
        'app\resources\release-manifest.json'
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $application $relative) -PathType Leaf)) {
            throw "Installed release is missing $relative"
        }
    }
    $installedVersion = Read-BundleVersion -Root $application
    if ($installedVersion -ne $TargetVersion) {
        throw "Installed version $installedVersion does not match target $TargetVersion"
    }
}

function Restore-Backup {
    Assert-Backup
    Write-Log "Restoring application $CurrentVersion from the update backup."
    if (Test-Path -LiteralPath $application) {
        Remove-Item -LiteralPath $application -Recurse -Force
    }
    Copy-DirectoryContents -Source $backupRoot -Destination $application
    $restoredVersion = Read-BundleVersion -Root $application
    if (-not $restoredVersion) { throw 'Restored application has no release marker' }
    Write-Transaction -State 'ROLLED_BACK'
    Write-Log "Application $restoredVersion restored."
}

function Assert-InstalledLauncherStopped {
    if (-not (Test-Path -LiteralPath $launcherExecutable -PathType Leaf)) { return }
    $candidates = @(Get-Process -Name 'HH Clicker Launcher' -ErrorAction SilentlyContinue)
    foreach ($process in $candidates) {
        $path = try { $process.Path } catch { $null }
        if (-not $path -or -not $path.Equals($launcherExecutable, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        Write-Log "Update refused because launcher process $($process.Id) is still running."
        throw 'HH Clicker Launcher is running; close it and retry the update'
    }
}

function Stop-AbandonedBrowserAgent {
    $handle = Join-Path $data 'runtime\browser-agent.json'
    if (-not (Test-Path -LiteralPath $handle -PathType Leaf)) { return }
    if ((Get-Item -LiteralPath $handle).Length -gt 8192) {
        throw 'Browser Agent ownership record is unexpectedly large'
    }
    $record = try { Get-Content -Raw -LiteralPath $handle | ConvertFrom-Json } catch {
        throw 'Browser Agent ownership record is damaged'
    }
    $process = Get-Process -Id ([int64]$record.processId) -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-Item -LiteralPath $handle -Force
        return
    }

    $path = try { $process.Path } catch { $null }
    $startedAt = try { ([DateTimeOffset]$process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() } catch { $null }
    if (-not $path -or $null -eq $startedAt -or
        -not $path.Equals([string]$record.command, [System.StringComparison]::OrdinalIgnoreCase) -or
        [math]::Abs($startedAt - [int64]$record.startedAtEpochMilli) -gt 1000) {
        throw 'Browser Agent process is still running, but its ownership cannot be proven safely'
    }

    Write-Log "Stopping launcher-owned Browser Agent process $($process.Id)."
    Stop-Process -Id $process.Id
    $process.WaitForExit(10000)
    if (-not $process.HasExited) { throw 'Browser Agent did not stop' }
    Remove-Item -LiteralPath $handle -Force
}

function Resolve-DockerCli {
    $command = Get-Command 'docker.exe' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Docker\Docker\resources\bin\docker.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string]$Docker,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    foreach ($line in $output) { Write-Log ([string]$line) }
    if ($exitCode -ne 0) { throw "docker $($Arguments[0]) failed with exit code $exitCode" }
}

function Stop-ComposeProject {
    param(
        [Parameter(Mandatory = $true)][string]$Docker,
        [Parameter(Mandatory = $true)][string]$Project,
        [Parameter(Mandatory = $true)][string]$DirectoryName,
        [Parameter(Mandatory = $true)][string]$EnvironmentFile
    )
    $compose = Get-ChildItem -LiteralPath $application -Filter 'compose.yaml' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -eq $DirectoryName } |
        Select-Object -First 1
    if ($compose -and (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)) {
        Invoke-Docker -Docker $Docker -Arguments @(
            'compose', '--project-name', $Project, '--env-file', $EnvironmentFile,
            '--file', $compose.FullName, 'down', '--remove-orphans'
        )
        return
    }

    $ids = @(& $Docker 'ps' '-q' '--filter' "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Docker project $Project" }
    if ($ids.Count -gt 0) {
        Invoke-Docker -Docker $Docker -Arguments (@('stop') + $ids)
    }
}

function Get-ProductionDockerAvailability {
    $infraEnvironment = Join-Path $data 'config\production\infra.env'
    $generationEnvironment = Join-Path $data 'config\production\generation.env'
    if (-not (Test-Path -LiteralPath $infraEnvironment -PathType Leaf) -and
        -not (Test-Path -LiteralPath $generationEnvironment -PathType Leaf)) {
        return [pscustomobject]@{ Status = 'NOT_CONFIGURED'; Docker = $null }
    }

    $docker = Resolve-DockerCli
    if (-not $docker) {
        return [pscustomobject]@{ Status = 'CLI_MISSING'; Docker = $null }
    }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        try {
            $null = & $docker 'info' 2>&1
            $engineReady = $LASTEXITCODE -eq 0
        } catch {
            $engineReady = $false
        }
    } finally {
        $ErrorActionPreference = $previous
    }
    if (-not $engineReady) {
        return [pscustomobject]@{ Status = 'ENGINE_UNAVAILABLE'; Docker = $docker }
    }
    return [pscustomobject]@{ Status = 'READY'; Docker = $docker }
}

function Stop-ProductionServices {
    $availability = Get-ProductionDockerAvailability
    switch ($availability.Status) {
        'NOT_CONFIGURED' {
            Write-Log 'Production was never configured; there are no owned services to stop.'
            return
        }
        'CLI_MISSING' { throw 'Docker CLI is unavailable; production services cannot be stopped safely' }
        'ENGINE_UNAVAILABLE' { throw 'Docker Engine is unavailable; start Docker Desktop and retry the update' }
    }

    $docker = $availability.Docker
    $infraEnvironment = Join-Path $data 'config\production\infra.env'
    $generationEnvironment = Join-Path $data 'config\production\generation.env'

    Stop-ComposeProject -Docker $docker -Project 'hh-clicker-generation' -DirectoryName 'generation-agent' `
        -EnvironmentFile $generationEnvironment
    Stop-ComposeProject -Docker $docker -Project 'hh-clicker' -DirectoryName 'infra' `
        -EnvironmentFile $infraEnvironment
}

try {
    Write-Log "Update action $Action started for target $TargetVersion."
    switch ($Action) {
        'CheckDocker' {
            $availability = Get-ProductionDockerAvailability
            switch ($availability.Status) {
                'NOT_CONFIGURED' { Write-Log 'Production was never configured; Docker is not required for this update.'; exit 0 }
                'READY' { Write-Log 'Docker Engine is ready for the update.'; exit 0 }
                'CLI_MISSING' { Write-Log 'Docker CLI is unavailable; Docker Desktop is required for this update.'; exit 21 }
                'ENGINE_UNAVAILABLE' { Write-Log 'Docker Engine is unavailable; start Docker Desktop and retry the update.'; exit 22 }
            }
            throw "Unexpected Docker availability status: $($availability.Status)"
        }
        'Prepare' {
            Assert-InstalledLauncherStopped
            Stop-AbandonedBrowserAgent
            Stop-ProductionServices

            if (Test-Path -LiteralPath $activeRoot -PathType Container) {
                $oldState = Read-TransactionState
                if ($oldState -eq 'COMMITTED') {
                    Remove-Item -LiteralPath $activeRoot -Recurse -Force
                } elseif (-not $oldState -and
                    (Test-ApplicationCore -Root $application -ExpectedVersion $CurrentVersion)) {
                    Write-Log 'Discarding an incomplete preparation that never modified the application.'
                    Remove-Item -LiteralPath $activeRoot -Recurse -Force
                } elseif (Test-Path -LiteralPath $backupRoot -PathType Container) {
                    Write-Log "Recovering unfinished update in state $oldState."
                    Restore-Backup
                    Remove-Item -LiteralPath $activeRoot -Recurse -Force
                } else {
                    throw 'An unfinished update has no usable application backup'
                }
            }

            if (-not (Test-Path -LiteralPath $launcherExecutable -PathType Leaf) -or
                -not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
                throw 'The installed application is incomplete and cannot be updated safely'
            }
            New-Item -ItemType Directory -Path $activeRoot -Force | Out-Null
            Copy-DirectoryContents -Source $application -Destination $backupRoot
            Assert-Backup
            $backupVersion = Read-BundleVersion -Root $backupRoot
            if ($backupVersion -ne $CurrentVersion) {
                throw "Application backup version $backupVersion does not match installed version $CurrentVersion"
            }
            Write-Transaction -State 'APPLICATION_BACKED_UP'
            Write-Log "Application $CurrentVersion backed up for update to $TargetVersion."
        }
        'Rollback' {
            Restore-Backup
        }
        'Commit' {
            Assert-Backup
            Assert-InstalledRelease
            Write-Transaction -State 'COMMITTED'
            Write-Log "Application update to $TargetVersion committed."
        }
        'Cleanup' {
            if ((Read-TransactionState) -ne 'COMMITTED') {
                throw 'Only a committed update may discard its application backup'
            }
            Remove-Item -LiteralPath $activeRoot -Recurse -Force
            Write-Log "Application backup for $TargetVersion removed."
        }
    }
} catch {
    Write-Log "ERROR: $($_.Exception.Message)"
    throw
}
