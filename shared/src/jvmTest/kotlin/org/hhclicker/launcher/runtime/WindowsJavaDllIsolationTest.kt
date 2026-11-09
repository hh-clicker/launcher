package org.hhclicker.launcher.runtime

import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WindowsJavaDllIsolationTest {
    @Test
    fun `runtime lookup is made process local before inherited directory is cleared`() {
        val home = Path.of("test-java").toAbsolutePath().normalize().toString()
        val bin = Path.of(home).resolve("bin").toString()
        val api = FakeDllApi(bin, "$bin;C:\\Windows\\System32;C:\\Tools")
        configureWindowsDllIsolation(api, home)
        assertEquals(listOf("add:$bin", "defaults:4096", "clear", "path:C:\\Windows\\System32;C:\\Tools"), api.calls)
    }

    @Test
    fun `failed process local setup never clears the runtime lookup`() {
        val home = Path.of("test-java").toAbsolutePath().normalize().toString()
        val api = FakeDllApi(Path.of(home).resolve("bin").toString(), "C:\\Tools", allowDefaults = false)
        assertFailsWith<IllegalStateException> { configureWindowsDllIsolation(api, home) }
        assertFalse("clear" in api.calls)
    }

    @Test
    fun `unrelated DLL setting is left alone`() {
        val api = FakeDllApi("C:\\OtherApplication", "C:\\Tools")
        configureWindowsDllIsolation(api, "C:\\Java")
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun `child PATH removes only exact Java runtime entries including case and quotes`() {
        assertEquals("C:\\Tools;;C:\\Java\\bin-extra;D:\\OtherJdk\\bin", withoutJavaRuntimePaths(
            "C:\\Tools;\"c:/JAVA/bin/\";;C:\\Java\\bin\\server;C:\\Java\\bin-extra;D:\\OtherJdk\\bin",
            "C:\\Java",
        ))
    }

    @Test
    fun `clean PATH is preserved byte for byte`() {
        val path = "C:\\Tools; C:\\Windows\\System32;;"
        assertEquals(path, withoutJavaRuntimePaths(path, "C:\\Java"))
    }

    private class FakeDllApi(
        val inherited: String,
        val path: String,
        val allowDefaults: Boolean = true,
    ) : WindowsDllApi {
        val calls = mutableListOf<String>()
        override fun GetDllDirectoryW(size: Int, buffer: CharArray): Int {
            inherited.toCharArray().copyInto(buffer)
            return inherited.length
        }
        override fun AddDllDirectory(directory: WString): Pointer? {
            calls += "add:$directory"
            return Pointer.createConstant(1)
        }
        override fun SetDefaultDllDirectories(flags: Int): Boolean {
            calls += "defaults:$flags"
            return allowDefaults
        }
        override fun SetDllDirectoryW(directory: WString?): Boolean {
            assertEquals(null, directory)
            calls += "clear"
            return true
        }
        override fun GetEnvironmentVariableW(name: WString, buffer: CharArray, size: Int): Int {
            assertEquals("PATH", name.toString())
            path.toCharArray().copyInto(buffer)
            return path.length
        }
        override fun SetEnvironmentVariableW(name: WString, value: WString): Boolean {
            assertEquals("PATH", name.toString())
            calls += "path:$value"
            return true
        }
    }
}
