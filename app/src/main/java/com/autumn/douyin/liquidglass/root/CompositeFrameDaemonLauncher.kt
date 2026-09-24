package com.autumn.douyin.liquidglass.root

import android.content.Context
import com.autumn.douyin.liquidglass.ModuleLog
import java.io.File

object CompositeFrameDaemonLauncher {
    const val PORT = 47619
    const val HANDSHAKE_TOKEN = "douyin-liquid-glass-composite-v1"
    const val MODULE_PACKAGE_NAME = "com.autumn.douyin.liquidglass"

    private const val DAEMON_LOG_PATH = "/data/local/tmp/douyin_liquid_glass_composite.log"
    private const val SILENT_LOG_PATH = "/dev/null"
    private const val DEFAULT_FRAME_PERIOD_MILLIS = 17
    private const val DEFAULT_CAPTURE_WIDTH = 480

    data class CaptureCapability(
        val supported: Boolean,
        val backend: String?,
        val reason: String?,
    )

    fun start(
        context: Context,
        loggingEnabled: Boolean = false,
        framePeriodMillis: Int = DEFAULT_FRAME_PERIOD_MILLIS,
        captureWidth: Int = DEFAULT_CAPTURE_WIDTH,
    ): Pair<Boolean, String> {
        if (context.packageName != MODULE_PACKAGE_NAME) {
            return false to "daemon must be started by the module app"
        }
        val apkPath = resolveModuleApkPath(context)
        if (apkPath.isNullOrEmpty()) {
            return false to "module apk path was not found"
        }

        val command = buildString {
            if (loggingEnabled) {
                append("if [ -f ")
                append(shellQuote(DAEMON_LOG_PATH))
                append(" ]; then mv -f ")
                append(shellQuote(DAEMON_LOG_PATH))
                append(' ')
                append(shellQuote("$DAEMON_LOG_PATH.old"))
                append("; fi; ")
            }
            append("CLASSPATH=")
            append(shellQuote(apkPath))
            append(" app_process /system/bin ")
            append(CompositeFrameDaemon::class.java.name)
            append(" --diagnostics=")
            append(loggingEnabled)
            append(" --frame-period-ms=")
            append(framePeriodMillis)
            append(" --transport-width=")
            append(captureWidth)
            append(" >")
            append(if (loggingEnabled) DAEMON_LOG_PATH else SILENT_LOG_PATH)
            append(" 2>&1 &")
        }

        return runCatching {
            val process = ProcessBuilder("/system/bin/su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString().trim()
            val exitCode = process.waitFor()
            (exitCode == 0) to output.ifBlank { "root shell completed" }
        }.getOrElse { throwable ->
            false to throwable.message.orEmpty()
        }.also { result ->
            ModuleLog.info {
                "composite daemon launch success=${result.first} apk=$apkPath output=${result.second}"
            }
        }
    }

    fun probeCapture(context: Context): CaptureCapability {
        if (context.packageName != MODULE_PACKAGE_NAME) {
            return CaptureCapability(
                supported = false,
                backend = null,
                reason = "capture probe must run in the module app",
            )
        }
        val apkPath = resolveModuleApkPath(context)
        if (apkPath.isNullOrEmpty()) {
            return CaptureCapability(
                supported = false,
                backend = null,
                reason = "module apk path was not found",
            )
        }

        val command = buildString {
            append("CLASSPATH=")
            append(shellQuote(apkPath))
            append(" app_process /system/bin ")
            append(CompositeFrameDaemon::class.java.name)
            append(" --probe-capture")
        }
        val result = runRootProcess(command)
        val capability = parseCaptureCapability(result.second)
        ModuleLog.info {
            "capture capability supported=${capability.supported} " +
                "backend=${capability.backend} reason=${capability.reason}"
        }
        return capability
    }

    private fun parseCaptureCapability(output: String): CaptureCapability {
        val supportedLine = output.lineSequence()
            .firstOrNull { it.startsWith("CAPTURE_SUPPORTED ") }
        if (supportedLine != null) {
            return CaptureCapability(
                supported = true,
                backend = supportedLine.parseFieldValue("backend"),
                reason = null,
            )
        }

        val unsupportedLine = output.lineSequence()
            .firstOrNull { it.startsWith("CAPTURE_UNSUPPORTED ") }
        return CaptureCapability(
            supported = false,
            backend = unsupportedLine?.parseFieldValue("backend"),
            reason = unsupportedLine
                ?.substringAfter("reason=", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?: output.ifBlank { "capture probe returned no output" },
        )
    }

    private fun String.parseFieldValue(field: String): String? =
        substringAfter("$field=", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

    private fun resolveModuleApkPath(context: Context): String? {
        runCatching {
            context.createPackageContext(MODULE_PACKAGE_NAME, 0).packageCodePath
        }.onFailure { throwable ->
            ModuleLog.error("failed to create module package context", throwable)
        }.getOrNull()?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?.let { return it }

        runCatching {
            context.packageManager.getApplicationInfo(MODULE_PACKAGE_NAME, 0).sourceDir
        }.getOrNull()?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?.let { return it }

        return resolveModuleApkPathFromClassLoader(javaClass.classLoader)
    }

    private fun resolveModuleApkPathFromClassLoader(classLoader: ClassLoader?): String? {
        var loader = classLoader
        while (loader != null) {
            val pathList = readField(loader, "pathList")
            if (pathList != null) {
                val elements = readField(pathList, "dexElements") as? Array<*>
                if (elements != null) {
                    for (element in elements) {
                        val path = readStringField(element, "path")
                            ?: readStringField(element, "zip")
                            ?: readStringField(element, "file")
                        if (isModuleApkPath(path)) return path
                    }
                }
            }
            loader = loader.parent
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(target) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun readStringField(target: Any?, name: String): String? {
        target ?: return null
        return readField(target, name) as? String
    }

    private fun isModuleApkPath(path: String?): Boolean {
        if (path.isNullOrEmpty() || !path.endsWith(".apk", ignoreCase = true)) return false
        return path.contains(MODULE_PACKAGE_NAME, ignoreCase = true) ||
            File(path).parent?.contains(MODULE_PACKAGE_NAME, ignoreCase = true) == true
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    fun stop(
        context: Context,
        loggingEnabled: Boolean = false,
    ): Pair<Boolean, String> {
        if (context.packageName != MODULE_PACKAGE_NAME) {
            return false to "daemon must be stopped by the module app"
        }
        val apkPath = resolveModuleApkPath(context)
        if (apkPath.isNullOrEmpty()) {
            return false to "module apk path was not found"
        }
        val command = buildString {
            append("CLASSPATH=")
            append(shellQuote(apkPath))
            append(" app_process /system/bin ")
            append(CompositeFrameDaemon::class.java.name)
            append(" --stop")
            append(" >")
            append(if (loggingEnabled) DAEMON_LOG_PATH else SILENT_LOG_PATH)
            append(" 2>&1")
        }
        return runRootProcess(command)
    }

    private fun runRootProcess(command: String): Pair<Boolean, String> =
        runCatching {
            val process = ProcessBuilder("/system/bin/su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString().trim()
            val exitCode = process.waitFor()
            (exitCode == 0) to output.ifBlank { "root shell completed" }
        }.getOrElse { throwable ->
            false to throwable.message.orEmpty()
        }
}
