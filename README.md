# HH Clicker Launcher

[![Release build](https://github.com/hh-clicker/.github/actions/workflows/release.yml/badge.svg)](https://github.com/hh-clicker/.github/actions/workflows/release.yml)
![Windows](https://img.shields.io/badge/Windows-x64-0078D4?logo=windows&logoColor=white) ![Linux](https://img.shields.io/badge/Linux-x86__64-FCC624?logo=linux&logoColor=black) ![macOS](https://img.shields.io/badge/macOS-Intel%20%7C%20Apple%20Silicon-555555?logo=apple&logoColor=white)

Состав выпуска определяется [каталогом релизов](releases/README.md). Windows installer, Linux AppImage и macOS DMG для Intel и Apple Silicon публикуются через
[release workflow](https://github.com/hh-clicker/.github/actions/workflows/release.yml) в репозитории `.github`.

Repository instructions for coding agents: [AGENTS.md](AGENTS.md).

Linux AppImage, подготовка окружения и запуск без лаунчера: [инструкция для Linux](LINUX.md).
Установка и обновление DMG: [инструкция для macOS](MACOS.md).

После новой установки Google Chrome на любой платформе один раз откройте его вручную и завершите
первоначальную настройку. Затем вернитесь в лаунчер и повторите проверку окружения.

Desktop launcher for the local HH Clicker environment on Windows x64, Linux x86_64 and macOS 14+ (Intel/Apple Silicon).
The UI is implemented with Compose Multiplatform.
The desktop controller performs real environment checks and controls the infra Docker Compose project.

## Development

- Hot reload: `gradlew.bat :desktopApp:hotRun --auto`
- Standard run: `gradlew.bat :desktopApp:run`
- Portable application image: `gradlew.bat :desktopApp:createDistributable`
- Per-user Windows installer: `gradlew.bat :desktopApp:packageWindowsInstaller`
- Linux AppImage (on Linux): `bash ./gradlew :desktopApp:packageLinuxAppImage -Pnightly`
- macOS DMG (on the target Mac architecture): `bash ./gradlew :desktopApp:packageDmg -Pnightly`

### Linux

AppImage, запуск из checkout и ручной запуск сервисов описаны в [инструкции для Linux](LINUX.md).

The Inno Setup executable installs application files per user under `%LOCALAPPDATA%\HH Clicker\application`, creates
Start menu and desktop shortcuts, and preserves mutable state under the sibling `data` directory. Before packaging,
Gradle stages files from the pinned, clean service commits and builds a production Browser Agent runtime. On the first
start of a new installed payload, Compose builds its service images from those sources. Later starts reuse the images;
missing images are rebuilt. See `installer/README.md` for the exact boundary.

The version in `gradle.properties` selects `releases/<version>/release.json` and `notes.ru.md`. The release definition
pins the exact commits of `backend`, `browser-agent`, `frontend`, `generation-agent` and `infra`; distribution staging
fails rather than packaging another commit or a dirty service checkout.

For a local test installer, use `gradlew.bat :desktopApp:packageWindowsInstaller -Pnightly`.
Nightly packaging uses the current working trees (tracked and non-ignored untracked files), records each
repository's HEAD and dirty flag, and never edits the official release catalog. The numeric version defaults
to the next patch after `hhClickerVersion`; `-PnightlyVersion=X.Y.Z` can select a version equal to or newer than the base.
Choose `-PnightlyVersion` equal to the target stable version when both must install at the same numeric version.
In PowerShell use `gradlew.bat --% :desktopApp:packageWindowsInstaller -Pnightly`.
The launcher and Setup label the build as nightly, and the output is `HH-Clicker-Setup-<version>-nightly.exe`.
Do not edit the repositories during staging. Nightly uses the same production installation and data,
not an isolated profile; ordinary downgrade protection still applies. Repeated nightlies with the same numeric
version, and the eventual stable build of that version, can be installed over it. Avoid testing destructive
database migrations against valuable data; this does not add database rollback.

## Current boundary

The launcher checks Docker CLI/Compose/Engine, the workspace, Node.js, Chrome and required local files.
Windows additionally requires hardware virtualization, WSL 2 and Docker Desktop. Linux uses Docker Engine;
macOS uses Docker Desktop. Platform-specific setup is described in [LINUX.md](LINUX.md) and [MACOS.md](MACOS.md).

### Windows machine preparation

The "Подготовка компьютера" screen offers one next action at a time. For WSL 2 it can install the package without a
Linux distribution, update an outdated one or select WSL 2 as the default version through a standard Windows UAC
prompt; changing Windows components always ends with an explicit request to restart the computer manually.

Once WSL 2 is completely ready, Docker Desktop becomes the next action. The launcher downloads only the official
installer from `https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe`, verifies its Authenticode
signature against Docker Inc. before running it, and installs a missing Docker Desktop **per user** with
`install --quiet --user --accept-license --backend=wsl-2 --no-windows-containers`. Before the first detached start it also
sets Docker's `DisplayedOnboarding` and `OpenUIOnStartupDisabled` user preferences, so neither the optional
welcome/sign-in survey nor the Dashboard opens during the background start.
On Windows, only x64 hosts, Linux containers and the
WSL 2 backend are supported; Hyper-V, Docker VMM, Windows containers, Kubernetes and a Docker Hub login are never
enabled. An update supplies neither backend nor container-mode flags and preserves the detected installation scope.
The `--accept-license`
flag is added only after the user presses «Принять и
установить Docker», the button that states the
[Docker Subscription Service Agreement](https://www.docker.com/legal/docker-subscription-service-agreement/) is being
accepted; nothing is accepted at startup, during diagnostics or during a download.

After installation the launcher starts Docker Desktop, waits for the engine within a bounded timeout and confirms
Linux containers plus an explicitly confirmed WSL 2 backend before calling anything ready. An installed and
compatible Docker is never reinstalled — a stopped one is only started. A version below the supported minimum is
offered an update that never uninstalls anything and never changes the installation mode; a version that merely lags
behind the recommendation is shown as a warning, not a blocker.

Docker Desktop and its bundled CLI are located independently of the launcher's inherited `PATH`. This lets the same
launcher process continue immediately after a fresh per-user installation in the documented
`%LOCALAPPDATA%\Programs\DockerDesktop` directory.

Some situations stay manual on purpose and are reported with a precise instruction instead of being attempted:
switching an existing installation from Hyper-V or Docker VMM to WSL 2 (Settings → General → Use WSL 2 based engine),
switching from Windows containers to Linux containers, a `docker.exe` found without Docker Desktop, and any Docker
 first-run window that asks for input. Codex login remains an explicit user action in the launcher.

The launcher first accepts a compatible Node.js from `PATH`. If none is available, the setup screen downloads the
pinned official Windows x64 ZIP, verifies its SHA-256 against that release's official `SHASUMS256.txt`, and extracts
only `node.exe` under `%LOCALAPPDATA%\HH Clicker\data\runtime`. It requires no UAC, installs nothing system-wide and never
changes `PATH`.

### Shared runtime

Runtime configuration is independent of replaceable application files. On first launch the application creates a stable
UUID, PostgreSQL password and environment files under the per-user data directory (`%LOCALAPPDATA%\HH Clicker\data\config`
on Windows; see the Linux/macOS guides for their paths). Packaged installations use the
`production` profile and Compose projects `hh-clicker` / `hh-clicker-generation`; a repository checkout uses the
isolated `development` profile and `hh-clicker-dev` / `hh-clicker-generation-dev`. Existing workspace `.env` values
are imported only when a profile is created, and later application updates never rotate its stored database password.
Generation profiles also import `GENERATION_MEMORY_PERCENT` (default 25) and
`GENERATION_JOB_MEMORY_MIB` (default 128). The agent computes memory admission inside its
own OS/container. `GENERATION_MAX_CONCURRENT_ANALYSIS` adds a shared ceiling of 4 simultaneous
relevance/market batches by default. New profiles import this value, falling back to the legacy
`GENERATION_MAX_CONCURRENT_RELEVANCE` when present. Existing profiles retain
their values; absent keys use the Compose defaults.
New profiles reserve stable free host ports, preferring Frontend `80`, Backend `8080` and Generation Agent
`17374`. The environment check rejects a configured port occupied by another application but accepts a port already
published by a container carrying this installation's instance label. For a foreign listener it reports the process
name and PID when Windows exposes them; `Выбрать свободные порты` changes only the conflicting profile values and
immediately repeats the checks. It never terminates the foreign process. PostgreSQL stays private to the Docker network.
While the launcher is open, a lightweight four-second poll reconciles its own labeled Docker services and its owned
Browser Agent process without rerunning machine diagnostics. It notices externally stopped or restarted containers,
but does not adopt containers from another profile and does not treat mere container presence as an HTTP health check.

On Windows, after Docker is ready, a missing Google Chrome becomes the next preparation action. The launcher never updates,
repairs, starts or closes an existing Chrome. With an explicit click accepting the linked Google terms, it downloads
the fixed official x64 Enterprise MSI over HTTPS, verifies an Authenticode signer of Google LLC and invokes Windows'
own absolute `msiexec.exe` path through a standard Windows UAC request, with `/qn /norestart`. A successful MSI code is accepted only when the same locator
finds `chrome.exe` afterwards.

It can build,
start, verify and stop PostgreSQL, Backend and Frontend through `infra/compose.yaml`, followed by Generation Agent
 through its own Compose project and the host Browser Agent process. After Generation Agent starts, the settings
 screen reads its Codex status and provides device-code login, cancellation and logout without exposing credentials
 outside the persistent Docker volume. When all five components are running, the primary action opens the configured
 Frontend URL and stopping remains available as a secondary action. The runtime contracts and the
 implementation map are documented in [ARCHITECTURE.md](ARCHITECTURE.md).

A newer Setup EXE performs a manual, application-only update. It rejects downgrade, displays the target release notes,
stops the production runtime, backs up the installed application and replaces its directory without retaining obsolete
files. Setup restores the previous application if replacement or structural validation fails. User data and Docker
volumes are preserved. The version indicator checks for updates and opens the release page; installation remains manual.


The reproducible icon generator is `tools/generate-icon.ps1`; it writes the tracked ICO resource.
Test modes, isolated development configuration and production nightly behavior are described in
[TESTING.md](TESTING.md). Release publication is documented in the
[release runbook](https://github.com/hh-clicker/.github/blob/master/RELEASING.md).
