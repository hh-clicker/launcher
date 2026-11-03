package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsJavaNetworkCompatibilityTest {
    @Test
    fun windowsForcesTheJdkHttpClientToUseItsTcpLoopbackFallback() {
        var propertyName: String? = null
        var propertyValue: String? = null

        WindowsJavaNetworkCompatibility.configure(
            osName = "Windows 11",
            userHome = "C:\\Users\\tester",
            existingValue = null
        ) { name, value ->
            propertyName = name
            propertyValue = value
        }

        assertEquals("jdk.net.unixdomain.tmpdir", propertyName)
        assertTrue(propertyValue.orEmpty().endsWith("unix-domain-sockets-disabled"))
    }

    @Test
    fun explicitJdkConfigurationIsPreserved() {
        var changed: String? = null

        WindowsJavaNetworkCompatibility.configure(
            osName = "Windows 11",
            userHome = "C:\\Users\\tester",
            existingValue = "C:\\configured"
        ) { _, value -> changed = value }

        assertNull(changed)
    }

    @Test
    fun nonWindowsRuntimeIsUntouched() {
        var changed: String? = null

        WindowsJavaNetworkCompatibility.configure(
            osName = "Linux",
            userHome = "/home/tester",
            existingValue = null
        ) { _, value -> changed = value }

        assertNull(changed)
    }
}
