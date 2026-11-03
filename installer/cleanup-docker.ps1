[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApplicationRoot,

    [Parameter(Mandatory = $true)]
    [string]$DataRoot,

    [Parameter(Mandatory = $true)]
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Log {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Message)
    Add-Content -LiteralPath $LogPath -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $Message" -Encoding UTF8
}

function Resolve-DockerCli {
    $command = Get-Command 'docker.exe' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Docker\Docker\resources\bin\docker.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe')
    )
    return $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    Write-Log "docker $($Arguments -join ' ')"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 turns any native stderr output into an ErrorRecord. Docker may write
        # harmless plugin warnings there even when it exits with code 0, so the exit code is authoritative.
        $ErrorActionPreference = 'Continue'
        $output = @(& $script:Docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    foreach ($line in $output) { Write-Log ([string]$line) }
    if ($exitCode -ne 0) {
        $message = "docker $($Arguments -join ' ') failed with exit code $exitCode"
        if ($AllowFailure) {
            Write-Log "Ignored: $message"
            return $false
        }
        throw $message
    }
    return $true
}

function Get-DockerOutput {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Write-Log "docker $($Arguments -join ' ')"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # See Invoke-Docker: stderr alone is not proof that a native Docker command failed.
        $ErrorActionPreference = 'Continue'
        $output = @(& $script:Docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    foreach ($line in $output) { Write-Log ([string]$line) }
    if ($exitCode -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $exitCode"
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Invoke-ComposeDown {
    param(
        [Parameter(Mandatory = $true)][string]$Project,
        [Parameter(Mandatory = $true)][string]$ParentDirectory,
        [Parameter(Mandatory = $true)][string]$EnvironmentFile
    )

    $compose = Get-ChildItem -LiteralPath $ApplicationRoot -Filter 'compose.yaml' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -eq $ParentDirectory } |
        Select-Object -First 1
    if (-not $compose -or -not (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)) {
        Write-Log "Compose input for project $Project is unavailable; using label cleanup only."
        return @()
    }

    $composeArguments = @(
        'compose',
        '--project-name', $Project,
        '--env-file', $EnvironmentFile,
        '--file', $compose.FullName
    )
    $images = @(
        Get-DockerOutput ($composeArguments + @('images', '-q'))
        Get-DockerOutput ($composeArguments + @('config', '--images')) |
            Where-Object { $_ -like "$Project-*" }
    ) | Where-Object { $_ } | Select-Object -Unique

    $null = Invoke-Docker ($composeArguments + @(
        'down',
        '--volumes',
        '--remove-orphans'
    ))
    return $images
}

function Remove-ByLabel {
    param([Parameter(Mandatory = $true)][string]$Project)

    $containers = @(Get-DockerOutput @('ps', '-aq', '--filter', "label=com.docker.compose.project=$Project"))
    if ($containers.Count -gt 0) { $null = Invoke-Docker (@('rm', '--force', '--volumes') + $containers) }

    $networks = @(Get-DockerOutput @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$Project"))
    if ($networks.Count -gt 0) { $null = Invoke-Docker (@('network', 'rm') + $networks) }

    $volumes = @(Get-DockerOutput @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$Project"))
    if ($volumes.Count -gt 0) { $null = Invoke-Docker (@('volume', 'rm') + $volumes) }
}

try {
    Set-Content -LiteralPath $LogPath -Value "HH Clicker Docker cleanup started at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

    $Docker = Resolve-DockerCli
    if (-not $Docker) { throw 'Docker CLI was not found' }
    Write-Log "Docker CLI: $Docker"

    $null = Invoke-Docker @('info')

    # Production project names are stable and isolated from the development checkout.
    $ownedImages = @()
    $ownedImages += @(Invoke-ComposeDown 'hh-clicker-generation' 'generation-agent' (Join-Path $DataRoot 'config\production\generation.env'))
    $ownedImages += @(Invoke-ComposeDown 'hh-clicker' 'infra' (Join-Path $DataRoot 'config\production\infra.env'))
    Remove-ByLabel 'hh-clicker-generation'
    Remove-ByLabel 'hh-clicker'

    # An image may also be used by a development project. That must not block uninstalling the application.
    foreach ($image in @($ownedImages | Where-Object { $_ } | Select-Object -Unique)) {
        $null = Invoke-Docker -Arguments @('image', 'rm', $image) -AllowFailure
    }

    Write-Log 'HH Clicker Docker resources removed.'
} catch {
    Write-Log "ERROR: $($_.Exception.Message)"
    throw
}
