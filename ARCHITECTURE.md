# Launcher architecture

## Current implementation

The UI consumes `LauncherState` and sends intents through `LauncherController`. The interface covers full and runtime
refreshes, port reassignment, preparation of WSL/Docker/Node/Chrome, Codex login/logout and start/stop of the complete
environment. The UI does not know whether a component is a Docker service, HTTP endpoint or Windows process.

`PreflightLauncherController` is the desktop implementation. It runs a read-only preflight at startup, locates the
workspace and checks Docker CLI, Docker Compose, the Docker Engine, Node.js, Chrome, configuration files and Browser
Agent artifacts without reading or printing secret values. Start/stop control the infra Compose project (PostgreSQL,
Backend and Frontend), the Generation Agent Compose project and one launcher-owned Browser Agent host process.
`FakeLauncherController` remains a UI/development implementation, not the pending runtime boundary.

### Windows Java networking compatibility

The desktop entry point calls `WindowsJavaNetworkCompatibility.configure()` before Skiko, the single-instance guard
and any `HttpClient` can be initialized. On Windows, and only when the user has not already configured
`jdk.net.unixdomain.tmpdir`, it points that property to
`%USERPROFILE%\.hh-clicker\unix-domain-sockets-disabled`. The launcher deliberately never creates this directory.

Some Windows builds and restricted environments advertise AF_UNIX support but cannot establish the internal selector
wake-up connection used by the JDK HTTP client. An unavailable UNIX-domain auto-bind directory makes the JDK use its
TCP-loopback fallback instead; HH Clicker does not use UNIX-domain sockets. Non-Windows runtimes and an explicit JVM
configuration remain untouched.

## Machine preparation: WSL 2

Preparing a clean Windows machine is modelled separately from running the services. `LauncherStatus` describes the
local environment; `SetupStatus` (`IDLE`, `PREPARING`, `RESTART_REQUIRED`, `SUCCEEDED`, `FAILED`) describes one
preparation operation, and `SetupStep` names the stage inside it. `RESTART_REQUIRED` is a first class result: after
Windows components change the launcher reports the manual restart instead of claiming the machine is ready, and it
never restarts Windows itself.

The diagnostics keep three independent facts in `WslDiagnostics` instead of one boolean:

| Fact | States | Meaning |
| --- | --- | --- |
| `virtualization` | `ENABLED`, `DISABLED_IN_FIRMWARE`, `UNKNOWN` | Manual blocker only. The launcher never touches BIOS/UEFI. `UNKNOWN` is a warning, not a hard block. |
| `installation` | `NOT_INSTALLED`, `OUTDATED`, `COMPATIBLE`, `CHECK_FAILED` | Only a parsed version from the first line of `wsl --version` counts as proof; the minimum is 2.1.5. |
| `defaultVersion` | `VERSION_2`, `VERSION_1`, `UNKNOWN` | Read from `HKCU\...\Lxss\DefaultVersion`, because `wsl --status` is localized. |

Only an explicitly confirmed `VERSION_2` is accepted as ready. Both `VERSION_1` and `UNKNOWN` are offered the
fixed `wsl.exe --set-default-version 2` action, so an ambiguous configuration can never silently pass preflight.

`WslSetupPlanner` turns those facts into exactly one action — `INSTALL`, `UPDATE`, `SET_DEFAULT_VERSION`,
`BLOCKED_BY_FIRMWARE`, `DIAGNOSTICS_FAILED` or `NONE` — and owns every state transition. It is pure and unit tested;
the screen never chooses a command.

`WindowsWslPreparer` is the only component allowed to change WSL. It runs one of three fixed `wsl.exe` invocations
through a static PowerShell elevation wrapper (`Start-Process -Verb RunAs -Wait -PassThru`), waits for the elevated
process and separates success, a dismissed UAC prompt, a non-zero exit code and a timeout. No user value, path or
environment variable is ever interpolated into the script, no Linux distribution is installed, and the full technical
result stays inside the component while the screen shows a short Russian message.

| Action | Elevated command | Terminal state |
| --- | --- | --- |
| `INSTALL` | `wsl.exe --install --no-distribution --web-download` | `RESTART_REQUIRED` |
| `UPDATE` | `wsl.exe --update --web-download` | `RESTART_REQUIRED` |
| `SET_DEFAULT_VERSION` | `wsl.exe --set-default-version 2` | `SUCCEEDED` |

Docker images and the migration of an existing Docker installation from Hyper-V or Docker VMM to the WSL 2 backend
remain separate later steps.

## Machine preparation: Docker Desktop

Docker is the second preparation target and reuses the same state machine. `SetupTarget` (`WSL`, `DOCKER`, `CHROME`) says which
prerequisite a `SetupState` describes, and `SetupStep` includes the Docker stages `DOWNLOADING_DOCKER`,
`VERIFYING_DOCKER_INSTALLER`, `INSTALLING_DOCKER`, `STARTING_DOCKER`, `WAITING_FOR_DOCKER_ENGINE` and
`VERIFYING_DOCKER_BACKEND`. `SetupState` also carries `downloadedBytes`/`totalBytes`, populated only while a download
runs and only when the server announced a content length. `LauncherStatus` is never used for installation.

### Supported configuration

Windows 10/11 x64 including Home, Linux containers only, WSL 2 backend only, per-user installation, no Linux
distribution and no Docker Hub login. Hyper-V, Docker VMM and Windows containers are unsupported. WSL 2 must be
completely ready before Docker is offered at all.

### Diagnostics

`DockerDiagnostics` keeps eight independent facts instead of a few booleans:

| Fact | States | Notes |
| --- | --- | --- |
| `architecture` | `X64_SUPPORTED`, `ARM_UNSUPPORTED`, `UNKNOWN` | `PROCESSOR_ARCHITEW6432` is read first: an x64 process emulated on ARM64 reports `AMD64` in `PROCESSOR_ARCHITECTURE`. |
| `desktop` | `NOT_INSTALLED`, `INSTALLED`, `OUTDATED`, `CHECK_FAILED` | Found through the uninstall registry, App Paths and the documented install locations — never through PATH. The version is kept separately. |
| `cli` | `AVAILABLE`, `MISSING`, `FAILED` | A `docker.exe` on PATH is not proof that Docker Desktop exists. |
| `compose` | `AVAILABLE`, `MISSING`, `FAILED` | Compose v2 via `docker compose`; a v1 answer counts as missing. |
| `desktopProcess` | `STOPPED`, `STARTING`, `RUNNING`, `UNKNOWN` | Read through the JVM process API rather than by spawning `tasklist`. |
| `engine` | `UNAVAILABLE`, `STARTING`, `RUNNING`, `FAILED` | Real read-only `docker info` with its own 20 s bound. A failure while Docker Desktop is alive means `STARTING`. |
| `containerMode` | `LINUX`, `WINDOWS`, `UNKNOWN` | From `docker info --format {{.OSType}}`. Only `LINUX` is acceptable. |
| `backend` | `WSL_2`, `NOT_WSL_2`, `UNKNOWN` | Never inferred from `OSType=linux`, because Linux containers also run on Hyper-V and Docker VMM. |

The backend comes from the documented `wslEngineEnabled` setting, read from `%APPDATA%\Docker\settings-store.json`
and, as a fallback, from the legacy `%APPDATA%\Docker\settings.json`. Both are parsed as JSON by `MiniJson`, a small
recursive descent parser, never by a regular expression: the key can appear inside an unrelated string and a textual
match would answer the wrong question. A missing file, unreadable JSON, an absent key or a non-boolean value all
answer `UNKNOWN`, and `UNKNOWN` is never treated as readiness. For a fresh installation the backend is instead
guaranteed by the installer flag `--backend=wsl-2`.

### Version policy

The launcher does not demand "the newest version". Its real contract is behavioural — it runs `docker compose up -d`
against Compose files that use the top level `name:` key and `depends_on: condition: service_healthy` — so Compose v2
is the only hard requirement, and everything else is measured rather than inferred from a number.
`DockerVersionPolicy` centralises the thresholds and `SemanticVersion` compares them:

| Threshold | Value | Reason |
| --- | --- | --- |
| `minimumCompose` | 2.0.0 | Hard contract: the launcher only ever calls `docker compose`. |
| `minimumDesktop` | 4.0.0 | The 4.x line is where Compose V2 ships with Docker Desktop on Windows; below it the `docker compose` contract cannot be relied on. |
| `recommendedDesktop` | 4.37.0 | First version with the documented `docker desktop` CLI used as the preferred start path. Older versions still work through `Docker Desktop.exe`. |

`VersionCompatibility` separates `COMPATIBLE`, `UPDATE_RECOMMENDED`, `BELOW_MINIMUM` and `UNKNOWN`. Only
`BELOW_MINIMUM` marks the installation `OUTDATED` and offers an update; `UPDATE_RECOMMENDED` is a warning in the check
message and never a blocker; an unreadable version is left to the measured capabilities instead of being guessed. An
update never uninstalls anything and never changes the per-user or all-users installation mode.

### Actions

`DockerSetupPlanner` turns the facts into exactly one action:

| Action | Situation |
| --- | --- |
| `INSTALL` | Docker Desktop absent, WSL 2 ready, host is x64. |
| `UPDATE` | Installed below the minimum, or Compose v2 missing. |
| `START_DESKTOP` | Installed, application not running. |
| `WAIT_FOR_ENGINE` | Application alive, engine still coming up. |
| `BLOCKED_BY_WSL` | WSL 2 is not completely ready. |
| `BLOCKED_BY_ARCHITECTURE` | Host is not confirmed x64. |
| `BLOCKED_BY_CONTAINER_MODE` | Windows containers. Manual switch to Linux containers. |
| `BLOCKED_BY_BACKEND` | Backend is explicitly not WSL 2. Manual: Settings → General → Use WSL 2 based engine. |
| `BACKEND_UNCONFIRMED` | Backend could not be confirmed. Never reported as ready. |
| `CLI_WITHOUT_DESKTOP` | A `docker.exe` exists without Docker Desktop. Its own error, never an automatic reinstall. |
| `DIAGNOSTICS_FAILED` | The state could not be determined. |
| `NONE` | Ready, or nothing decided yet. |

### Licence

`INSTALL` and `UPDATE` carry `requiresLicenseAcceptance`. The screen shows the sentence "Нажимая «Принять и
установить», вы принимаете Docker Subscription Service Agreement" together with a clickable
<https://www.docker.com/legal/docker-subscription-service-agreement/> and labels the button «Принять и установить
Docker». That click is the acceptance: it is the only path that reaches `prepareDocker(acceptDockerLicense = true)`,
and `--accept-license` is appended only then. Startup, diagnostics and a download never accept anything.

### System operations

`WindowsDockerPreparer` is the only component allowed to install or start Docker Desktop.

| Stage | Operation |
| --- | --- |
| Download | `GET https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe` |
| Verify | `Get-AuthenticodeSignature`; status must be `Valid` and the certificate organisation must be Docker Inc. |
| Install | Missing Docker: `install --quiet --user --accept-license --backend=wsl-2 --no-windows-containers`; before every launcher-initiated start set the per-user `DisplayedOnboarding=true` and `OpenUIOnStartupDisabled=true` preferences; an update is also quiet, retains the detected installation scope and supplies no backend/container-mode flags |
| Start | located `docker.exe desktop start --detach`; older versions fall back to the located `Docker Desktop.exe` |
| Wait | poll `docker info` every 3 s for at most 5 min |
| Verify | engine running, `OSType=linux`, backend `WSL_2`, Compose v2 available |

An explicit `wslEngineEnabled` value always wins. Docker Desktop may omit settings that retain their default;
for a valid settings document and Desktop 4.5.1 or newer, an omitted backend uses the documented WSL 2 default.
A missing or malformed document remains `UNKNOWN`.

`HttpFileDownloader` accepts HTTPS only, follows at most five redirects manually so every hop can be checked, streams
the body to a `.partial` file next to the target, enforces a 1500 MiB ceiling, rejects a truncated response and
deletes the partial file on every failure. The finished installer is cached under
`%LOCALAPPDATA%\HH Clicker\data\launcher-cache\docker`, outside the replaceable application payload, and a cached file is
verified again on every run — a file that fails verification is deleted and downloaded once more.

The Authenticode check runs through the `AuthenticodeVerifier` interface so that tests never execute PowerShell. Its
Windows implementation uses a compile time constant script and hands the file path to the child process through a
dedicated environment variable, so no path is ever concatenated into PowerShell code. The variable is set on the
child process only and never appears in the technical report.

The preparer never uninstalls anything, never changes the installation mode, the Docker data root or Docker settings,
never enables Kubernetes, never logs in to Docker Hub, never installs a Linux distribution, never bypasses SmartScreen
or UAC and never stops a Docker it did not start. Hyper-V, Docker VMM, Windows containers, `--always-run-service`, an
all-users installation and the docker-users group are never requested.

`prepareDocker` shares the controller's single operation mutex with `prepareWsl`, `refreshEnvironment`, `startAll` and
`stopAll`, so none of them can run concurrently, and `LauncherState.busy` disables the competing buttons.

### What stays manual

Switching an existing installation from Hyper-V or Docker VMM to the WSL 2 backend, switching from Windows containers
to Linux containers, a Docker first-run window that asks for input, and repairing a `docker.exe` that exists without
Docker Desktop. Each is reported with a precise instruction instead of being attempted.

## Launcher-managed portable Node.js runtime

`NodeRuntimeLocator` first probes the system `node` from `PATH` and accepts it only when it satisfies the Browser
Agent engine range. Otherwise the setup flow downloads the pinned official Windows x64 Node.js ZIP, verifies it
against the matching official `SHASUMS256.txt`, and extracts only `node.exe` under the current user's
`%LOCALAPPDATA%\HH Clicker\data\runtime`. It does not require elevation, alter PATH or install Node system-wide.
`BrowserAgentProcess` receives the selected absolute executable path.

## Machine preparation: Google Chrome

Chrome is the third and final automatic preparation target. `ChromeInstallationLocator` searches fixed system and
per-user installation locations without consulting `PATH`, and the same locator is used before and after an install.
An existing Chrome produces no action: the launcher never updates, repairs, reinstalls, starts, closes, makes it the
default browser or creates shortcuts.

On Windows x64, a missing Chrome offers one button whose click explicitly accepts the linked Google Chrome terms.
Only then `WindowsChromePreparer` downloads the fixed official Enterprise MSI URL over HTTPS with bounded redirects
and size, verifies a valid Authenticode signer of Google LLC, and invokes Windows' own absolute `msiexec.exe` through
a static PowerShell `Start-Process -Verb RunAs` wrapper with `/i <installer> /qn /norestart`. Runtime paths are passed
through child-process environment variables and never interpolated into the script. The operation distinguishes a
dismissed UAC request, cancellation (1602), another active MSI transaction
(1618), restart recommended (3010), timeout and other failures. Exit 0 or 3010 is not enough by itself: `chrome.exe`
must be found afterwards. Chrome is never launched by the preparation flow.

## Runtime contracts

| UI component | Runtime owner | Start | Readiness | Stop |
| --- | --- | --- | --- | --- |
| Backend | `infra/compose.yaml` (`postgres` + `backend`) | `docker compose up -d`, with `--build` for a new payload or missing image | `GET http://localhost:8080/actuator/health` | `docker compose down` in `infra` |
| Frontend | `infra/compose.yaml` (`frontend`) | started by the same infra Compose project | `GET http://localhost:${FRONTEND_PORT}/` | stopped by the same infra Compose project |
| Generation Agent | `generation-agent/compose.yaml` | `docker compose up -d`, with `--build` for a new payload, changed Codex CLI version or missing image | `GET /health`; Codex readiness additionally requires `GET /auth/status` | `docker compose down` in `generation-agent` |
| Browser Agent | Windows host process | compatible system Node.js or launcher-managed portable `node.exe`, then `browser-agent/dist/main.js` | backend heartbeat; there is no local agent health port | graceful process termination; the agent closes managed Chrome on shutdown |

## Distribution boundary

Compose Desktop creates the application image. Its Gradle-owned staging step copies Git-tracked and non-ignored
non-Markdown runtime files from the five service repositories, omits root `tools/` directories,
builds Browser Agent in staging, prunes its development
dependencies and places the result under the packaged `app/resources` directory. `WorkspaceLocator` reads Compose's
`compose.application.resources.dir` property, so Start menu launches do not depend on a current working directory.

Inno Setup installs replaceable application files under `%LOCALAPPDATA%\HH Clicker\application`. Configuration,
authentication profiles, launcher-managed Node and caches live under `%LOCALAPPDATA%\HH Clicker\data`. Ordinary
uninstall preserves them; the optional full-removal mode deletes all mutable data and production Docker resources.
A source-controlled release definition pins the exact commits of all five service repositories and supplies Russian
release notes. Distribution staging rejects a mismatched or dirty service checkout and generates the packaged release
manifest from that definition.

A newer Setup EXE is the only update entry point. The installer compares versions, rejects downgrade and checks Docker
readiness before updating a configured production runtime. If Docker Desktop is unavailable, the wizard stays open so
the user can start it and retry; clean installation does not require this check. Preparation checks readiness again,
stops the production runtime without removing volumes, backs up the whole application and performs a clean replacement,
so files removed from a release cannot survive an upgrade. Its transaction marker and backup live under `data\updates`; a failed
or interrupted file installation restores the previous application. The installer does not start the updated services,
health-check a migrated database or roll a migration back. There is no automatic update discovery.

The runtime does not import bundled Docker image archives. The launcher records a successful Compose build for each
installed payload (identified by its release manifest) in persistent configuration. It uses `--build` for a new
payload or when a required Docker image is missing; a changed Generation Agent CLI build argument also rebuilds its
image. Repeated starts of the same installed payload use `docker compose up -d` without rebuilding. Developer
checkouts still build on each start because their source files can change without a release manifest.
On the first launcher start after an upgrade, the production `generation.env` moves the previous
default Codex CLI `0.153.0` to `0.163.0` and model `gpt-5.6-luna` to `gpt-6-luna`.
Values that differ from those previous defaults are retained. Development settings are not migrated.

Backend and Frontend are not independent processes from the launcher's point of view: both belong to one Compose
project and Backend also depends on PostgreSQL. Generation Agent is a second independent Compose project. Browser
Agent must remain on the Windows host because it launches visible system Chrome and owns local browser profiles.

## Required launch order

1. Verify the workspace and local configuration without printing secret values.
2. Verify Docker CLI, Docker Compose and an available Docker Engine.
3. Start the infra Compose project and wait for Backend health; then verify Frontend HTTP.
4. Start Generation Agent and wait for `/health`. Treat missing Codex authorization as setup-required, not as a
   crashed service, because AI is optional.
5. Start Browser Agent last and wait for its heartbeat in Backend.
6. Only after readiness open the Frontend URL resolved from `infra/.env`.

Stop in reverse order. Compose shutdown commands must never include `-v`: PostgreSQL, Codex authentication and
generation data live in persistent volumes.

## JVM implementation map

Process- and Windows-specific code stays in `shared/src/jvmMain`:

- `CommandRunner` starts bounded child processes from argument lists.
- `EnvironmentPreflight` and the probes collect read-only machine diagnostics.
- `LauncherRuntimeConfigurationStore` creates profile-local environment files, instance ID, database password and
  stable ports under the data directory.
- `WindowsJavaNetworkCompatibility` selects the JDK TCP-loopback fallback before any HTTP client is initialized on
  Windows systems where the UNIX-domain selector connection may be unavailable.
- `InfraEnvironment` and `GenerationEnvironment` own the two Compose lifecycles and HTTP readiness checks.
- `GenerationAuthClient` reads the running agent's `/health` metadata for the model and Codex CLI
  version shown in launcher settings; authentication remains a separate request.
- `BrowserAgentProcess` owns one host process and verifies its heartbeat through Backend.
- `PreflightLauncherController` enforces dependency ordering, state transitions and operation serialization.

Runtime reconciliation uses instance labels for Docker services and the persisted ownership record for Browser Agent.
It does not adopt containers from another profile or terminate an unrelated Node/Chrome process. Codex authorization
is an explicit device-code flow through Generation Agent; credentials remain inside its persistent volume.

## Current update limitation

Installer rollback covers the replaceable application directory. PostgreSQL data and configuration are preserved, but
the installer does not take a database backup or roll back a Liquibase migration. A release whose `release.json` marks
`databaseMigration=true` therefore requires a deliberate compatibility check before publication.
