package org.hhclicker.launcher.runtime

import java.nio.file.Path

object WindowsJavaNetworkCompatibility {
    private const val UNIX_DOMAIN_TMPDIR_PROPERTY = "jdk.net.unixdomain.tmpdir"

    fun configure() = configure(
        osName = System.getProperty("os.name").orEmpty(),
        userHome = System.getProperty("user.home").orEmpty(),
        existingValue = System.getProperty(UNIX_DOMAIN_TMPDIR_PROPERTY),
        setProperty = System::setProperty,
    )

    internal fun configure(
        osName: String,
        userHome: String,
        existingValue: String?,
        setProperty: (String, String) -> Unit,
    ) {
        if (!osName.startsWith("Windows", ignoreCase = true) || existingValue != null) {
            return
        }

        val base = userHome.takeIf { it.isNotBlank() } ?: "."

        val unavailableDirectory = Path.of(
            base,
            ".hh-clicker",
            "unix-domain-sockets-disabled",
        ).toAbsolutePath().normalize()

        setProperty(
            UNIX_DOMAIN_TMPDIR_PROPERTY,
            unavailableDirectory.toString(),
        )
    }
}
