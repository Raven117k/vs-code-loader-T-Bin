package com.termux.termux

import android.content.Context
import com.termux.util.AppLogger
import java.io.File

class TermuxEnvironment(private val context: Context) {
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val filesDir = context.filesDir

    val usrDir = File(filesDir, "usr")
    val homeDir = File(filesDir, "home")
    val binDir = File(usrDir, "bin")
    val localBinDir = File(usrDir, "local/bin")
    val tmpDir = File(usrDir, "tmp")

    fun ensureLayout() {
        listOf(
            usrDir,
            homeDir,
            binDir,
            localBinDir,
            tmpDir,
            File(usrDir, "lib"),
            File(usrDir, "etc"),
            File(usrDir, "local/lib")
        ).forEach { if (!it.exists()) it.mkdirs() }
    }

    fun resolveBinary(name: String): File {
        val candidates = listOf(
            File(binDir, name),
            File(localBinDir, name),
            File(usrDir, "bin/$name"),
            File(usrDir, "local/bin/$name"),
            File(usrDir, "usr/bin/$name"),
            File(usrDir, "usr/local/bin/$name")
        )
        return candidates.firstOrNull { it.exists() } ?: File(binDir, name)
    }

    fun resolveShellBinary(): File {
        return listOf(
            resolveBinary("bash"),
            resolveBinary("sh"),
            resolveBinary("busybox")
        ).firstOrNull { it.exists() } ?: resolveBinary("bash")
    }

    fun buildEnvironmentArray(): Array<String> {
        return getEnvironment().map { (key, value) -> "$key=$value" }.toTypedArray()
    }

    fun isReady(): Boolean {
        val ready = listOf("bash", "sh", "busybox").any { resolveBinary(it).exists() }
        if (!ready) AppLogger.log("TermuxEnvironment", "No Termux shell binaries found yet")
        return ready
    }

    /**
     * termux-exec provides several LD_PRELOAD shims that intercept execve() to
     * rewrite hardcoded FHS paths (e.g. "#!/usr/bin/env node") to our real prefix.
     * "direct" syscall hooking is preferred on newer Android but can be blocked by
     * OEM SELinux/seccomp policy on some devices — fall back to the linker-based
     * shim, then the generic default if neither exists.
     */
    private fun resolveLdPreloadLib(): File? {
        val direct = File(usrDir, "lib/libtermux-exec-direct-ld-preload.so")
        val linker = File(usrDir, "lib/libtermux-exec-linker-ld-preload.so")
        val generic = File(usrDir, "lib/libtermux-exec-ld-preload.so")
        return when {
            android.os.Build.VERSION.SDK_INT >= 28 && direct.exists() -> direct
            linker.exists() -> linker
            generic.exists() -> generic
            else -> null
        }
    }

    fun getEnvironment(): Map<String, String> {
        val base = mutableMapOf(
            "HOME" to homeDir.absolutePath,
            "PREFIX" to usrDir.absolutePath,
            "PATH" to listOf(
                localBinDir.absolutePath,
                binDir.absolutePath,
                File(usrDir, "usr/local/bin").absolutePath,
                File(usrDir, "usr/bin").absolutePath,
                nativeLibDir
            ).joinToString(":"),
            "LD_LIBRARY_PATH" to listOf(
                File(usrDir, "lib").absolutePath,
                File(usrDir, "usr/lib").absolutePath
            ).joinToString(":"),
            "TMPDIR" to tmpDir.absolutePath,
            "GYP_DEFINES" to "android_ndk_path=''",
            "npm_config_foreground_scripts" to "true"
        )

        resolveLdPreloadLib()?.let { preloadLib ->
            base["LD_PRELOAD"] = preloadLib.absolutePath
        } ?: AppLogger.log("TermuxEnvironment", "No termux-exec LD_PRELOAD shim found — /usr/bin/env-style shebangs will fail")

        return base
    }
}