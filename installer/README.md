# Windows installer

`prepare-payload.ps1` is an internal input of the Gradle distribution pipeline, not a user-facing bundle builder.
It stages the replaceable application payload under `desktopApp/build/installer-resources/common` before Compose
Desktop creates the application image consumed by Inno Setup.

The staging step copies tracked files from `backend`, `browser-agent`, `frontend`, `generation-agent` and `infra`.
Every release has a definition and Russian release notes under `releases/<version>/`; the definition pins the exact
commit of each service repository. Staging refuses a mismatched or dirty checkout. Browser Agent dependencies are
installed afresh in staging; after compilation only production dependencies and runtime JavaScript remain. Markdown
documentation (`*.md`, case-insensitive) is removed from the final payload, including files contributed by npm packages.
Root `tools/` directories contain developer utilities and are omitted from staged service runtime.

Compose creates the application image; Inno Setup 6 owns the Windows installation, upgrade and uninstall flow.
Install Inno Setup once on the build machine:

```powershell
winget install --id JRSoftware.InnoSetup --exact
```

Build on Windows from the launcher repository:

```powershell
.\gradlew.bat :desktopApp:packageWindowsInstaller
```

For uncommitted local changes, add `-Pnightly`. This explicitly bypasses pinned SHA/clean-tree enforcement,
but keeps Git file filtering, staging cleanup, runtime compilation and the ordinary installer update checks.
Ignored files are excluded; deleted tracked files are omitted. The generated manifest includes
`build.channel=nightly`, `build.baseVersion`, a UTC timestamp, and HEAD/dirty metadata for all six repositories.
Compatibility fields come from the base release, with `databaseMigration=true` conservatively because local
changes may include migrations. This flag does not provide a database backup or rollback.
Nightly notes are generated in build output only, alongside the previous official release notes.
The default version is the next patch, and the EXE carries a `-nightly` suffix. It updates the regular production
installation with preserved user data; it is not a second independent installation.
An explicit nightly version may equal the base version. Use a nightly version equal to the target stable version
when the later stable installer must retain the same numeric version; older nightly versions are rejected.

The resulting `Setup.exe` installs application files under `%LOCALAPPDATA%\HH Clicker\application` and keeps mutable
state under `%LOCALAPPDATA%\HH Clicker\data`. The uninstaller removes only the application by default. Its optional
`Полное удаление` mode also removes all mutable data (including browser profiles, configuration, caches and the
launcher-managed portable Node runtime) and resources owned by the production Docker Compose projects. If Docker
cleanup fails, the uninstaller writes diagnostics to `%LOCALAPPDATA%\HH Clicker\uninstall.log` and lets the user retry, cancel,
or continue with an application-only uninstall that preserves the data needed for later cleanup.

Running a newer Setup EXE is the only update entry point; there is no automatic discovery or download. Before replacing
files, Setup validates the installed and target versions and shows the release notes. The user must close a running
launcher before continuing; Setup refuses the update immediately instead of waiting or terminating it forcibly. Setup
checks Docker readiness before starting an update if production Compose was configured. When Docker Desktop is not
ready, Setup explains what is needed and lets the user retry from the same wizard page; a clean installation does not
require Docker at this step. Setup checks again during preparation, then stops the launcher-owned Browser Agent and
the two production Compose projects without removing volumes, and copies the complete application to
`data\updates\active\application-backup`. The application directory is then replaced as a clean unit. A failed or
interrupted installation restores that backup. The transaction covers application files only. Configuration and
PostgreSQL volumes are preserved, but database migrations are neither backed up nor rolled back by Setup; release
compatibility metadata must reflect that risk.
On the first launch after installation, the launcher updates previous default Codex CLI/model
values in the preserved production `generation.env`; custom values remain unchanged. The installer
itself does not start Generation Agent.
