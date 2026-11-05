package org.hhclicker.launcher.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPackagePreparerTest {
    @Test
    fun automatic_package_setup_is_limited_to_debian_13_x86_64() {
        assertTrue(LinuxPackagePreparer.supportsAutoInstall("ID=debian\nVERSION_ID=\"13\"", "x86_64"))
        assertFalse(LinuxPackagePreparer.supportsAutoInstall("ID=arch\n", "x86_64"))
        assertFalse(LinuxPackagePreparer.supportsAutoInstall("ID=ubuntu\nVERSION_ID=\"24.04\"", "x86_64"))
        assertFalse(LinuxPackagePreparer.supportsAutoInstall("ID=debian\nVERSION_ID=\"12\"", "x86_64"))
        assertFalse(LinuxPackagePreparer.supportsAutoInstall("ID=debian\nVERSION_ID=\"13\"", "aarch64"))
    }
}
