package com.autumn.douyin.liquidglass.status

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.util.concurrent.Executors
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autumn.douyin.liquidglass.root.CompositeFrameDaemonLauncher
import com.autumn.douyin.liquidglass.settings.ModuleSettings
import com.autumn.douyin.liquidglass.settings.ModuleSettingsMirror
import com.autumn.douyin.liquidglass.settings.ModuleSettingsStore
import com.autumn.douyin.liquidglass.ui.LiquidSettingsBackdrop
import com.autumn.douyin.liquidglass.ui.LiquidSettingsScreen

class ModuleStatusActivity : ComponentActivity() {
    private var settings by mutableStateOf(ModuleSettings.Default)
    private var rootStatus by mutableStateOf("检测中")
    private var daemonStatus by mutableStateOf("")
    private var captureStatus by mutableStateOf("检测中")
    private var actionStatus by mutableStateOf("就绪")
    private var captureCapability: CompositeFrameDaemonLauncher.CaptureCapability? by mutableStateOf(
        null,
    )
    private val rootActionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-glass-root-actions").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ModuleSettingsStore.read(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionText = "版本 ${packageInfo.versionName} (${packageInfo.longVersionCode})"
        updateDaemonStatus(settings)

        setContent {
            LiquidSettingsBackdrop {
                LiquidSettingsScreen(
                    versionText = versionText,
                    rootStatus = rootStatus,
                    daemonStatus = daemonStatus,
                    captureStatus = captureStatus,
                    actionStatus = actionStatus,
                    glassBarEnabled = settings.glassBarEnabled,
                    controlAvoidanceEnabled = settings.controlAvoidanceEnabled,
                    dynamicBackdropEnabled = settings.dynamicBackdropEnabled,
                    diagnosticLoggingEnabled = settings.diagnosticLoggingEnabled,
                    framePeriodMillis = settings.framePeriodMillis,
                    captureWidth = settings.captureWidth,
                    onGlassBarChange = { updateSettings(settings.copy(glassBarEnabled = it)) },
                    onControlAvoidanceChange = {
                        updateSettings(settings.copy(controlAvoidanceEnabled = it))
                    },
                    onDynamicBackdropChange = {
                        updateSettings(settings.copy(dynamicBackdropEnabled = it))
                    },
                    onDiagnosticLoggingChange = {
                        updateSettings(settings.copy(diagnosticLoggingEnabled = it))
                    },
                    onFramePeriodChange = {
                        updateSettings(settings.copy(framePeriodMillis = it))
                    },
                    onCaptureWidthChange = {
                        updateSettings(settings.copy(captureWidth = it))
                    },
                    onRestartDouyin = ::forceStopDouyin,
                    onRestartDaemon = ::restartDaemon,
                    onExportDiagnostics = ::exportDiagnostics,
                )
            }
        }

        rootActionExecutor.execute {
            val rootGranted = isRootGranted()
            runOnUiThread {
                rootStatus = if (rootGranted) "已授权" else "未授权"
            }

            val captureCapability = CompositeFrameDaemonLauncher.probeCapture(this)
            runOnUiThread {
                this.captureCapability = captureCapability
                captureStatus = captureCapabilityLabel(captureCapability)
            }

            val current = ModuleSettingsStore.read(this)
            val daemonRunning = current.glassBarEnabled && current.dynamicBackdropEnabled
            val result = if (daemonRunning) {
                CompositeFrameDaemonLauncher.start(
                    this,
                    current.diagnosticLoggingEnabled,
                    current.framePeriodMillis,
                    current.captureWidth,
                ).first
            } else {
                CompositeFrameDaemonLauncher.stop(
                    this,
                    current.diagnosticLoggingEnabled,
                ).first
            }
            runOnUiThread {
                daemonStatus = when {
                    daemonRunning && result -> "运行中"
                    daemonRunning -> "启动失败"
                    else -> "已停止"
                }
            }
        }
    }

    private fun updateSettings(next: ModuleSettings) {
        val previous = settings
        val saved = ModuleSettingsStore.write(this, next)
        settings = saved
        updateDaemonStatus(saved)
        actionStatus = "保存中"

        rootActionExecutor.execute {
            val mirrored = ModuleSettingsMirror.write(this, saved)
            val daemonState = applyDaemonPolicy(previous, saved)
            runOnUiThread {
                actionStatus = if (mirrored) "已同步" else "Provider 已同步"
                daemonStatus = when (daemonState) {
                    true -> "运行中"
                    false -> "启动失败"
                    null -> "已停止"
                }
            }
        }
    }

    private fun applyDaemonPolicy(
        previous: ModuleSettings,
        current: ModuleSettings,
    ): Boolean? {
        val shouldRun = current.glassBarEnabled && current.dynamicBackdropEnabled
        val loggingChanged = previous.diagnosticLoggingEnabled != current.diagnosticLoggingEnabled
        val performanceChanged = previous.framePeriodMillis != current.framePeriodMillis ||
            previous.captureWidth != current.captureWidth
        val shouldRestart = shouldRun && (
            loggingChanged ||
                performanceChanged ||
                !(previous.glassBarEnabled && previous.dynamicBackdropEnabled)
            )

        if (shouldRestart) {
            CompositeFrameDaemonLauncher.stop(this, current.diagnosticLoggingEnabled)
            return CompositeFrameDaemonLauncher.start(
                this,
                current.diagnosticLoggingEnabled,
                current.framePeriodMillis,
                current.captureWidth,
            ).first
        } else if (!shouldRun) {
            CompositeFrameDaemonLauncher.stop(this, current.diagnosticLoggingEnabled)
            return null
        }
        return true
    }

    private fun updateDaemonStatus(current: ModuleSettings) {
        daemonStatus = if (current.glassBarEnabled && current.dynamicBackdropEnabled) {
            "运行中"
        } else {
            "已停止"
        }
    }

    private fun captureCapabilityLabel(
        capability: CompositeFrameDaemonLauncher.CaptureCapability,
    ): String = when {
        capability.supported && capability.backend == "surface-control" ->
            "支持 · Android 13"
        capability.supported && capability.backend == "screen-capture" ->
            "支持 · ScreenCapture"
        capability.supported ->
            "支持"
        else ->
            "不支持"
    }

    private fun isRootGranted(): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        val exitCode = process.waitFor()
        exitCode == 0 && output.contains("uid=0")
    }.getOrDefault(false)

    private fun forceStopDouyin() {
        actionStatus = "处理中"
        rootActionExecutor.execute {
            val result = runRootCommand("/system/bin/am force-stop com.ss.android.ugc.aweme")
            runOnUiThread {
                actionStatus = if (result) "抖音已强停" else "强停失败"
            }
        }
    }

    private fun restartDaemon() {
        actionStatus = "处理中"
        rootActionExecutor.execute {
            CompositeFrameDaemonLauncher.stop(this, settings.diagnosticLoggingEnabled)
            val shouldRun = settings.glassBarEnabled && settings.dynamicBackdropEnabled
            val result = if (shouldRun) {
                CompositeFrameDaemonLauncher.start(
                    this,
                    settings.diagnosticLoggingEnabled,
                    settings.framePeriodMillis,
                    settings.captureWidth,
                ).first
            } else {
                true
            }
            runOnUiThread {
                actionStatus = if (!shouldRun) "Daemon 已停止" else if (result) "Daemon 已重启" else "Daemon 启动失败"
                daemonStatus = if (shouldRun && result) "运行中" else "已停止"
            }
        }
    }

    private fun exportDiagnostics() {
        actionStatus = "正在导出"
        rootActionExecutor.execute {
            val result = DiagnosticBundleExporter.export(
                context = this,
                settings = settings,
                capability = captureCapability,
                rootStatus = rootStatus,
            )
            runOnUiThread {
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("diagnostic bundle", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        startActivity(Intent.createChooser(shareIntent, "分享诊断包"))
                        actionStatus = "诊断包已生成"
                    }.onFailure {
                        actionStatus = "已生成：${file.absolutePath}"
                    }
                }.onFailure { throwable ->
                    actionStatus = "导出失败：${throwable.message ?: "未知错误"}"
                }
            }
        }
    }

    private fun runRootCommand(command: String): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)
}
