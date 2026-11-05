import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

val hhClickerVersion = providers.gradleProperty("hhClickerVersion")
val nightly = providers.gradleProperty("nightly").map { it != "false" }.getOrElse(false)
val distributionVersion = if (nightly) providers.gradleProperty("nightlyVersion").getOrElse(
    hhClickerVersion.get().split('.').let { "${it[0]}.${it[1]}.${it[2].toInt() + 1}" }
) else hhClickerVersion.get()
val distributionSuffix = if (nightly) "-nightly" else ""
require(distributionVersion.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Distribution version must have three numeric components" }
val installerResourcesRoot = layout.buildDirectory.dir("installer-resources")
val windowsInstallerOutput = layout.buildDirectory.dir("compose/binaries/main/installer")
val windowsApplicationImage = layout.buildDirectory.dir("compose/binaries/main/app/HH Clicker Launcher")
val linuxApplicationImage = layout.buildDirectory.dir("compose/binaries/main/app/HH Clicker Launcher")
val linuxAppDir = layout.buildDirectory.dir("appimage/HH-Clicker.AppDir")
val linuxAppImageOutput = layout.buildDirectory.file("compose/binaries/main/appimage/HH-Clicker-$distributionVersion$distributionSuffix-x86_64.AppImage")
val releaseDirectory = rootProject.layout.projectDirectory.dir("releases/${hhClickerVersion.get()}")
val releaseCatalog = rootProject.layout.projectDirectory.dir("releases")
val releaseDefinition = releaseDirectory.file("release.json")
val releaseNotes = releaseDirectory.file("notes.ru.md")
val linuxHost = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val macHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val unixHost = linuxHost || macHost
val macIcon = layout.buildDirectory.file("macos-icon/hh-clicker.icns")

val prepareMacIcon by tasks.registering(Exec::class) {
    group = "distribution"
    val script = rootProject.layout.projectDirectory.file("installer/build-macos-icon.sh")
    val png = project.file("src/main/resources/hh-clicker.png")
    inputs.files(script, png)
    outputs.file(macIcon)
    commandLine("bash", script.asFile.absolutePath, png.absolutePath, macIcon.get().asFile.absolutePath)
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
}

val prepareInstallerPayload by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Stages Git-filtered HH Clicker services and a production Browser Agent runtime"

    val stagingScript = rootProject.layout.projectDirectory.file("installer/prepare-payload.ps1")
    val workspaceRoot = rootProject.layout.projectDirectory.dir("..").asFile
    val destination = installerResourcesRoot.map { it.dir("common").asFile }

    inputs.files(stagingScript, releaseDefinition, releaseNotes)
    inputs.dir(releaseCatalog)
    outputs.dir(installerResourcesRoot)
    outputs.upToDateWhen { false }

    commandLine(listOf(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        stagingScript.asFile.absolutePath,
        "-WorkspaceRoot",
        workspaceRoot.absolutePath,
        "-Destination",
        destination.get().absolutePath,
        "-Version",
        distributionVersion,
        "-ReleaseDefinition",
        releaseDefinition.asFile.absolutePath,
        "-ReleaseNotes",
        releaseNotes.asFile.absolutePath,
    ) + if (nightly) listOf("-Nightly") else emptyList<String>())
}

val prepareLinuxPayload by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Stages the Unix HH Clicker services and compiled Browser Agent"

    val stagingScript = rootProject.layout.projectDirectory.file("installer/prepare-payload.mjs")
    val workspaceRoot = rootProject.layout.projectDirectory.dir("..").asFile
    val destination = installerResourcesRoot.map { it.dir("common").asFile }

    inputs.files(stagingScript, releaseDefinition, releaseNotes)
    inputs.dir(releaseCatalog)
    outputs.dir(installerResourcesRoot)
    outputs.upToDateWhen { false }

    commandLine(
        "node",
        stagingScript.asFile.absolutePath,
        workspaceRoot.absolutePath,
        destination.get().absolutePath,
        distributionVersion,
        releaseDefinition.asFile.absolutePath,
        releaseNotes.asFile.absolutePath,
        if (nightly) "nightly" else "stable",
    )
}

// Compose copies <appResourcesRootDir>/common into $APPDIR/resources. Only distribution tasks build the
// expensive payload; ordinary run/hotRun must stay fast. The ordering makes the Sync observe fresh staging.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    mustRunAfter(if (unixHost) prepareLinuxPayload else prepareInstallerPayload)
}
tasks.matching {
    it.name == "createDistributable"
}.configureEach {
    dependsOn(if (unixHost) prepareLinuxPayload else prepareInstallerPayload)
    if (macHost) dependsOn(prepareMacIcon)
    // Compose's jpackage task does not fingerprint our externally staged service tree reliably.
    outputs.upToDateWhen { false }
}

val packageWindowsInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the HH Clicker Windows installer with Inno Setup"
    dependsOn("createDistributable")

    val buildScript = rootProject.layout.projectDirectory.file("installer/build-windows-installer.ps1")
    val innoScript = rootProject.layout.projectDirectory.file("installer/hh-clicker.iss")
    val dockerCleanup = rootProject.layout.projectDirectory.file("installer/cleanup-docker.ps1")
    val updateFiles = rootProject.layout.projectDirectory.file("installer/update-files.ps1")

    inputs.files(buildScript, innoScript, dockerCleanup, updateFiles, releaseDefinition, releaseNotes)
    inputs.dir(releaseCatalog)
    inputs.dir(windowsApplicationImage)
    outputs.file(windowsInstallerOutput.map { it.file("HH-Clicker-Setup-$distributionVersion$distributionSuffix.exe") })
    outputs.upToDateWhen { false }

    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        buildScript.asFile.absolutePath,
        "-ApplicationImage",
        windowsApplicationImage.get().asFile.absolutePath,
        "-OutputDirectory",
        windowsInstallerOutput.get().asFile.absolutePath,
        "-Version",
        distributionVersion,
    )
}

val packageLinuxAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages the Linux launcher and services as an AppImage"
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Linux", ignoreCase = true) }

    val buildScript = rootProject.layout.projectDirectory.file("installer/build-linux-appimage.sh")
    val linuxIcon = project.file("src/main/resources/hh-clicker.png")
    inputs.files(buildScript, linuxIcon)
    inputs.dir(linuxApplicationImage)
    outputs.file(linuxAppImageOutput)
    outputs.upToDateWhen { false }

    commandLine(
        "bash",
        buildScript.asFile.absolutePath,
        linuxApplicationImage.get().asFile.absolutePath,
        linuxAppDir.get().asFile.absolutePath,
        linuxIcon.absolutePath,
        linuxAppImageOutput.get().asFile.absolutePath,
        distributionVersion,
    )
}

compose.desktop {
    application {
        mainClass = "org.hhclicker.launcher.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            appResourcesRootDir.set(installerResourcesRoot)
            modules("java.net.http")
            packageName = "HH Clicker Launcher"
            packageVersion = distributionVersion
            description = "Local launcher for HH Clicker"
            vendor = "HH Clicker"

            windows {
                iconFile.set(project.file("src/main/resources/hh-clicker.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/hh-clicker.png"))
            }
            macOS {
                bundleID = "org.hhclicker.launcher"
                minimumSystemVersion = "14.0"
                iconFile.set(macIcon.get().asFile)
            }
        }
    }
}
