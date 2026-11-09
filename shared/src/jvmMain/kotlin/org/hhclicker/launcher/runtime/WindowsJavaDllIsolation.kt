package org.hhclicker.launcher.runtime

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import org.hhclicker.launcher.model.HostPlatform
import java.nio.file.Path

/** Replace jpackage's inheritable DLL directory with a process-local Java lookup. */
object WindowsJavaDllIsolation {
    @Volatile private var configured = false

    @Synchronized
    fun configure() {
        if (configured || HostPlatformDetector.detect() != HostPlatform.WINDOWS) return
        val api = Native.load("kernel32", WindowsDllApi::class.java)
        configureWindowsDllIsolation(api, System.getProperty("java.home"))
        configured = true
    }

    /** ProcessBuilder can cache the Java environment before native PATH is cleaned. */
    fun cleanChildEnvironment(environment: MutableMap<String, String>) {
        if (HostPlatformDetector.detect() != HostPlatform.WINDOWS) return
        val key = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: return
        environment[key] = withoutJavaRuntimePaths(environment.getValue(key), System.getProperty("java.home"))
    }
}

internal interface WindowsDllApi : StdCallLibrary {
    fun GetDllDirectoryW(size: Int, buffer: CharArray): Int
    fun AddDllDirectory(directory: WString): Pointer?
    fun SetDefaultDllDirectories(flags: Int): Boolean
    fun SetDllDirectoryW(directory: WString?): Boolean
    fun GetEnvironmentVariableW(name: WString, buffer: CharArray, size: Int): Int
    fun SetEnvironmentVariableW(name: WString, value: WString): Boolean
}

internal fun configureWindowsDllIsolation(api: WindowsDllApi, javaHome: String) {
    val buffer = CharArray(32768)
    val length = api.GetDllDirectoryW(buffer.size, buffer)
    check(length in 0 until buffer.size) { "Не удалось прочитать каталог поиска DLL." }
    val inherited = String(buffer, 0, length)
    val javaBin = Path.of(javaHome).resolve("bin").toAbsolutePath().normalize().toString()
    // Development java.exe normally has no inherited directory. Do not replace unrelated settings.
    if (inherited.isNotEmpty() && sameWindowsPath(inherited, javaBin)) {
        check(api.AddDllDirectory(WString(javaBin)) != null) { "Не удалось настроить поиск библиотек Java." }
        // DEFAULT_DIRS = application directory, System32 and process-local AddDllDirectory entries.
        check(api.SetDefaultDllDirectories(0x00001000)) { "Не удалось настроить изоляцию библиотек Java." }
        check(api.SetDllDirectoryW(null)) { "Не удалось сбросить наследуемый каталог DLL." }
    }
    // Also covers native ShellExecute/Desktop.browse, which bypasses ProcessBuilder.
    val pathName = WString("PATH")
    val pathLength = api.GetEnvironmentVariableW(pathName, buffer, buffer.size)
    check(pathLength in 0 until buffer.size) { "Не удалось прочитать PATH процесса." }
    if (pathLength > 0) {
        val original = String(buffer, 0, pathLength)
        val cleaned = withoutJavaRuntimePaths(original, javaHome)
        if (original != cleaned) check(api.SetEnvironmentVariableW(pathName, WString(cleaned))) {
            "Не удалось изолировать PATH процесса."
        }
    }
}

internal fun withoutJavaRuntimePaths(path: String, javaHome: String): String {
    val bin = javaHome.trimEnd('/', '\\') + "\\bin"
    return path.split(';').filterNot { sameWindowsPath(it, bin) || sameWindowsPath(it, "$bin\\server") }.joinToString(";")
}

private fun sameWindowsPath(left: String, right: String): Boolean =
    left.trim().trim('"').replace('/', '\\').trimEnd('\\')
        .equals(right.trim().trim('"').replace('/', '\\').trimEnd('\\'), ignoreCase = true)
