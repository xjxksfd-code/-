package com.autumn.douyin.liquidglass.status

import android.content.Context
import android.os.Build
import com.autumn.douyin.liquidglass.root.CompositeFrameDaemonLauncher
import com.autumn.douyin.liquidglass.settings.ModuleSettings
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticBundleExporter {
    private const val DouyinPackage = "com.ss.android.ugc.aweme"
    private const val DouyinLiquidDirectory =
        "/storage/emulated/0/Android/data/$DouyinPackage/files/liquid-glass"
    private const val DaemonLogPath =
        "/data/local/tmp/douyin_liquid_glass_composite.log"

    fun export(
        context: Context,
        settings: ModuleSettings,
        capability: CompositeFrameDaemonLauncher.CaptureCapability?,
        rootStatus: String,
    ): Result<File> = runCatching {
        val outputDirectory = File(context.cacheDir, "diagnostics").apply {
            mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(outputDirectory, "douyin-liquid-glass-diagnostic-$timestamp.zip")

        val moduleLog = File(outputDirectory, "module.log")
        val moduleOldLog = File(outputDirectory, "module.log.old")
        val daemonLog = File(outputDirectory, "daemon.log")
        val daemonOldLog = File(outputDirectory, "daemon.log.old")
        val mirroredSettings = File(outputDirectory, "douyin-settings.properties")
        val rootFiles = listOf(
            Triple("$DouyinLiquidDirectory/module.log", moduleLog, "module.log"),
            Triple("$DouyinLiquidDirectory/module.log.old", moduleOldLog, "module.log.old"),
            Triple(DaemonLogPath, daemonLog, "daemon.log"),
            Triple("$DaemonLogPath.old", daemonOldLog, "daemon.log.old"),
            Triple(
                "$DouyinLiquidDirectory/settings.properties",
                mirroredSettings,
                "douyin-settings.properties",
            ),
        )
        val statuses = rootFiles.associate { (source, destination, _) ->
            source to copyRootFile(source, destination)
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            rootFiles.forEach { (source, file, entryName) ->
                val result = statuses.getValue(source)
                if ("copied" in result && file.isFile && file.length() > 0L) {
                    zip.writeNext(entryName, file)
                }
            }
            zip.writeNext(
                "environment.txt",
                environmentSummary(
                    context = context,
                    settings = settings,
                    capability = capability,
                    rootStatus = rootStatus,
                    moduleLog = moduleLog,
                    statuses = statuses,
                ),
            )
        }
        zipFile
    }

    private fun environmentSummary(
        context: Context,
        settings: ModuleSettings,
        capability: CompositeFrameDaemonLauncher.CaptureCapability?,
        rootStatus: String,
        moduleLog: File,
        statuses: Map<String, String>,
    ): String {
        val moduleInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val douyinInfo = runCatching {
            context.packageManager.getPackageInfo(DouyinPackage, 0)
        }.getOrNull()
        val metrics = context.resources.displayMetrics
        return buildString {
            appendLine("Douyin Liquid Glass diagnostic bundle")
            appendLine("exportedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("moduleVersion=${moduleInfo?.versionName} versionCode=${moduleInfo?.longVersionCode}")
            appendLine("androidRelease=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.BRAND} ${Build.MODEL} product=${Build.PRODUCT} hardware=${Build.HARDWARE}")
            appendLine("display=${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density} densityDpi=${metrics.densityDpi}")
            appendLine("douyinVersion=${douyinInfo?.versionName} versionCode=${douyinInfo?.longVersionCode}")
            appendLine("rootStatus=$rootStatus")
            appendLine("settings=${settings.copy(revision = settings.revision)}")
            appendLine("performanceFramePeriodMs=${settings.framePeriodMillis}")
            appendLine("performanceCaptureWidth=${settings.captureWidth}")
            appendLine("captureSupported=${capability?.supported} backend=${capability?.backend} reason=${capability?.reason}")
            appendLine("hookMarkerInModuleLog=${moduleLogContains(moduleLog, "load package hook ready")}")
            statuses.forEach { (source, result) ->
                appendLine("file[$source]=$result")
            }
        }
    }

    private fun moduleLogContains(file: File, marker: String): Boolean =
        runCatching {
            file.useLines { lines -> lines.any { marker in it } }
        }.getOrDefault(false)

    private fun copyRootFile(source: String, destination: File): String {
        destination.delete()
        val command = "if [ -f ${shellQuote(source)} ]; then " +
            "/system/bin/cp ${shellQuote(source)} ${shellQuote(destination.absolutePath)} && " +
            "/system/bin/chmod 644 ${shellQuote(destination.absolutePath)}; else " +
            "echo missing; fi"
        return runCatching {
            val process = ProcessBuilder("/system/bin/su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString().trim()
            val exitCode = process.waitFor()
            when {
                exitCode == 0 && destination.isFile -> "copied bytes=${destination.length()} " +
                    "modifiedAt=${formatTimestamp(destination.lastModified())}"
                exitCode == 0 -> "missing"
                else -> "root-failed exit=$exitCode output=$output"
            }
        }.getOrElse { throwable ->
            "copy-failed reason=${throwable.message}"
        }
    }

    private fun ZipOutputStream.writeNext(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.writeNext(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun formatTimestamp(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timeMillis))
}
