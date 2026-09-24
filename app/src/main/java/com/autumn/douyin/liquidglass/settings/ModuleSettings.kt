package com.autumn.douyin.liquidglass.settings

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.StringWriter
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val PreferredFramePeriodMillis = 17
private const val PreferredCaptureWidth = 480

data class ModuleSettings(
    val glassBarEnabled: Boolean,
    val controlAvoidanceEnabled: Boolean,
    val dynamicBackdropEnabled: Boolean,
    val diagnosticLoggingEnabled: Boolean,
    val framePeriodMillis: Int,
    val captureWidth: Int,
    val revision: Long,
) {
    fun withRevision(nextRevision: Long): ModuleSettings = copy(revision = nextRevision)

    companion object {
        val Default = ModuleSettings(
            glassBarEnabled = true,
            controlAvoidanceEnabled = true,
            dynamicBackdropEnabled = true,
            diagnosticLoggingEnabled = false,
            framePeriodMillis = PreferredFramePeriodMillis,
            captureWidth = PreferredCaptureWidth,
            revision = 0L,
        )
    }
}

object ModuleSettingsStore {
    const val Authority = "com.autumn.douyin.liquidglass.settings"
    val SettingsUri: Uri = Uri.parse("content://$Authority/settings")

    private const val PreferencesName = "liquid_glass_settings"
    private const val KeyGlassBar = "glass_bar_enabled"
    private const val KeyControlAvoidance = "control_avoidance_enabled"
    private const val KeyDynamicBackdrop = "dynamic_backdrop_enabled"
    private const val KeyDiagnosticLogging = "diagnostic_logging_enabled"
    private const val KeyFramePeriod = "frame_period_millis"
    private const val KeyCaptureWidth = "capture_width"
    private const val KeyRevision = "revision"

    const val ColumnGlassBar = "glass_bar_enabled"
    const val ColumnControlAvoidance = "control_avoidance_enabled"
    const val ColumnDynamicBackdrop = "dynamic_backdrop_enabled"
    const val ColumnDiagnosticLogging = "diagnostic_logging_enabled"
    const val ColumnFramePeriod = "frame_period_millis"
    const val ColumnCaptureWidth = "capture_width"
    const val ColumnRevision = "revision"

    val FramePeriodChoices = listOf(8, 11, 17, 22, 33)
    val CaptureWidthChoices = listOf(320, 400, 480, 560, 640)
    val DefaultFramePeriod = PreferredFramePeriodMillis
    val DefaultCaptureWidth = PreferredCaptureWidth

    private val revisionSource = AtomicLong(0)

    fun read(context: Context): ModuleSettings {
        val preferences = preferences(context)
        val settings = ModuleSettings(
            glassBarEnabled = preferences.getBoolean(KeyGlassBar, true),
            controlAvoidanceEnabled = preferences.getBoolean(KeyControlAvoidance, true),
            dynamicBackdropEnabled = preferences.getBoolean(KeyDynamicBackdrop, true),
            diagnosticLoggingEnabled = preferences.getBoolean(KeyDiagnosticLogging, false),
            framePeriodMillis = preferences.getInt(
                KeyFramePeriod,
                DefaultFramePeriod,
            ),
            captureWidth = preferences.getInt(
                KeyCaptureWidth,
                DefaultCaptureWidth,
            ),
            revision = preferences.getLong(KeyRevision, 0L),
        )
        revisionSource.updateAndGet { current -> maxOf(current, settings.revision) }
        return settings
    }

    fun write(context: Context, settings: ModuleSettings): ModuleSettings {
        val next = settings.withRevision(nextRevision())
        preferences(context).edit()
            .putBoolean(KeyGlassBar, next.glassBarEnabled)
            .putBoolean(KeyControlAvoidance, next.controlAvoidanceEnabled)
            .putBoolean(KeyDynamicBackdrop, next.dynamicBackdropEnabled)
            .putBoolean(KeyDiagnosticLogging, next.diagnosticLoggingEnabled)
            .putInt(KeyFramePeriod, next.framePeriodMillis)
            .putInt(KeyCaptureWidth, next.captureWidth)
            .putLong(KeyRevision, next.revision)
            .apply()
        context.contentResolver.notifyChange(SettingsUri, null)
        return next
    }

    fun cursor(settings: ModuleSettings): Cursor = MatrixCursor(
        arrayOf(
            ColumnGlassBar,
            ColumnControlAvoidance,
            ColumnDynamicBackdrop,
            ColumnDiagnosticLogging,
            ColumnFramePeriod,
            ColumnCaptureWidth,
            ColumnRevision,
        )
    ).apply {
        addRow(
            listOf(
                settings.glassBarEnabled,
                settings.controlAvoidanceEnabled,
                settings.dynamicBackdropEnabled,
                settings.diagnosticLoggingEnabled,
                settings.framePeriodMillis,
                settings.captureWidth,
                settings.revision,
            )
        )
    }

    fun fromCursor(cursor: Cursor): ModuleSettings? = cursor.use { current ->
        val glassBar = current.getColumnIndex(ColumnGlassBar)
        val controlAvoidance = current.getColumnIndex(ColumnControlAvoidance)
        val dynamicBackdrop = current.getColumnIndex(ColumnDynamicBackdrop)
        val diagnosticLogging = current.getColumnIndex(ColumnDiagnosticLogging)
        val framePeriod = current.getColumnIndex(ColumnFramePeriod)
        val captureWidth = current.getColumnIndex(ColumnCaptureWidth)
        val revision = current.getColumnIndex(ColumnRevision)
        if (!current.moveToFirst() ||
            glassBar < 0 || controlAvoidance < 0 || dynamicBackdrop < 0 ||
            diagnosticLogging < 0 || revision < 0
            || framePeriod < 0 || captureWidth < 0
        ) {
            return null
        }
        ModuleSettings(
            glassBarEnabled = current.getInt(glassBar) != 0,
            controlAvoidanceEnabled = current.getInt(controlAvoidance) != 0,
            dynamicBackdropEnabled = current.getInt(dynamicBackdrop) != 0,
            diagnosticLoggingEnabled = current.getInt(diagnosticLogging) != 0,
            framePeriodMillis = current.getInt(framePeriod),
            captureWidth = current.getInt(captureWidth),
            revision = current.getLong(revision),
        )
    }

    fun serialize(settings: ModuleSettings): String = Properties().apply {
        put(KeyGlassBar, settings.glassBarEnabled.toString())
        put(KeyControlAvoidance, settings.controlAvoidanceEnabled.toString())
        put(KeyDynamicBackdrop, settings.dynamicBackdropEnabled.toString())
        put(KeyDiagnosticLogging, settings.diagnosticLoggingEnabled.toString())
        put(KeyFramePeriod, settings.framePeriodMillis.toString())
        put(KeyCaptureWidth, settings.captureWidth.toString())
        put(KeyRevision, settings.revision.toString())
    }.storeToString()

    fun deserialize(value: String): ModuleSettings? {
        val properties = Properties()
        properties.load(value.reader())
        return ModuleSettings(
            glassBarEnabled = properties.getBooleanProperty(KeyGlassBar, true),
            controlAvoidanceEnabled = properties.getBooleanProperty(KeyControlAvoidance, true),
            dynamicBackdropEnabled = properties.getBooleanProperty(KeyDynamicBackdrop, true),
            diagnosticLoggingEnabled = properties.getBooleanProperty(
                KeyDiagnosticLogging,
                false,
            ),
            framePeriodMillis = properties.getIntProperty(
                KeyFramePeriod,
                DefaultFramePeriod,
            ),
            captureWidth = properties.getIntProperty(
                KeyCaptureWidth,
                DefaultCaptureWidth,
            ),
            revision = properties.getProperty(KeyRevision)?.toLongOrNull() ?: return null,
        )
    }

    fun contentValues(settings: ModuleSettings): ContentValues = ContentValues().apply {
        put(ColumnGlassBar, settings.glassBarEnabled)
        put(ColumnControlAvoidance, settings.controlAvoidanceEnabled)
        put(ColumnDynamicBackdrop, settings.dynamicBackdropEnabled)
        put(ColumnDiagnosticLogging, settings.diagnosticLoggingEnabled)
        put(ColumnFramePeriod, settings.framePeriodMillis)
        put(ColumnCaptureWidth, settings.captureWidth)
        put(ColumnRevision, settings.revision)
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    private fun Properties.storeToString(): String {
        val writer = StringWriter()
        store(writer, null)
        return writer.toString()
    }

    private fun Properties.getBooleanProperty(key: String, default: Boolean): Boolean =
        when (getProperty(key)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    private fun Properties.getIntProperty(key: String, default: Int): Int =
        getProperty(key)?.toIntOrNull() ?: default

    private fun nextRevision(): Long {
        while (true) {
            val current = revisionSource.get()
            val next = System.currentTimeMillis()
            if (next > current && revisionSource.compareAndSet(current, next)) return next
            if (revisionSource.compareAndSet(current, current + 1)) return current + 1
        }
    }
}

object ModuleSettingsMirror {
    private const val DouyinPackage = "com.ss.android.ugc.aweme"
    private const val DirectorySuffix = "files/liquid-glass"
    private const val FileName = "settings.properties"

    fun write(context: Context, settings: ModuleSettings): Boolean {
        return runCatching {
            val temporary = File(context.cacheDir, "douyin-settings.properties")
            val target = targetFile()
            val targetDirectory = target.parentFile ?: return false
            temporary.parentFile?.mkdirs()
            temporary.writeText(ModuleSettingsStore.serialize(settings), Charsets.UTF_8)

            val command = listOf(
                "/system/bin/mkdir",
                "-p",
                shellQuote(targetDirectory.absolutePath),
                "&&",
                "/system/bin/cp",
                shellQuote(temporary.absolutePath),
                shellQuote(target.absolutePath),
                "&&",
                "/system/bin/chmod",
                "644",
                shellQuote(target.absolutePath),
            ).joinToString(" ")
            runRootCommand(command)
        }.getOrDefault(false)
    }

    fun read(): ModuleSettings? {
        val file = targetFile()
        if (!file.isFile) return null
        return runCatching {
            ModuleSettingsStore.deserialize(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun observe(onChanged: () -> Unit): FileObserver? {
        val file = targetFile()
        val directory = file.parentFile ?: return null
        if (!directory.isDirectory) return null
        return object : FileObserver(directory, CLOSE_WRITE or MOVED_TO or DELETE or ATTRIB) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FileName || path == null) onChanged()
            }
        }.also { it.startWatching() }
    }

    private fun targetFile(): File = File(
        "/storage/emulated/0/Android/data/$DouyinPackage/$DirectorySuffix",
        FileName,
    )

    private fun runRootCommand(command: String): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}

object ModuleSettingsBridge {
    private const val DouyinPackage = "com.ss.android.ugc.aweme"
    private const val ModulePackage = "com.autumn.douyin.liquidglass"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-glass-settings-refresh").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArraySet<(ModuleSettings) -> Unit>()
    private var contentObserver: android.database.ContentObserver? = null
    private var fileObserver: FileObserver? = null
    private var currentSettings = ModuleSettings.Default
    private var started = false

    val current: ModuleSettings
        get() = currentSettings

    fun start(context: Context, onSettingsChanged: (ModuleSettings) -> Unit) {
        synchronized(this) {
            if (started) {
                listeners += onSettingsChanged
                return
            }
            started = true
            listeners += onSettingsChanged
        }

        ModuleSettingsMirror.read()?.let(::applySettings)
        installObservers(context)
        refreshAsync(context)
    }

    fun addListener(onSettingsChanged: (ModuleSettings) -> Unit) {
        listeners += onSettingsChanged
    }

    fun removeListener(onSettingsChanged: (ModuleSettings) -> Unit) {
        listeners -= onSettingsChanged
    }

    fun stop(onSettingsChanged: (ModuleSettings) -> Unit) {
        synchronized(this) {
            listeners -= onSettingsChanged
            if (listeners.isNotEmpty()) return
            started = false
            contentObserver?.let { contextResolver()?.unregisterContentObserver(it) }
            fileObserver?.stopWatching()
            refreshExecutor.shutdown()
            contentObserver = null
            fileObserver = null
        }
    }

    private fun contextResolver() = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()?.contentResolver

    private fun installObservers(context: Context) {
        val observer = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshAsync(context)
            }
        }
        val fileWatch = ModuleSettingsMirror.observe { refreshAsync(context) }
        synchronized(this) {
            contentObserver = observer
            fileObserver = fileWatch
        }
        context.contentResolver.registerContentObserver(
            ModuleSettingsStore.SettingsUri,
            false,
            observer,
        )
    }

    private fun refresh(context: Context) {
        val providerSettings = runCatching {
            context.contentResolver.query(
                ModuleSettingsStore.SettingsUri,
                null,
                null,
                null,
                null,
            )?.let(ModuleSettingsStore::fromCursor)
        }.getOrNull()
        val mirroredSettings = ModuleSettingsMirror.read()
        val next = listOf(providerSettings, mirroredSettings)
            .filterNotNull()
            .maxByOrNull { it.revision }
        if (next != null) {
            applySettings(next)
        }
    }

    private fun refreshAsync(context: Context) {
        runCatching {
            refreshExecutor.execute { refresh(context) }
        }
    }

    private fun applySettings(settings: ModuleSettings) {
        val shouldNotify = synchronized(this) {
            if (settings.revision > currentSettings.revision) {
                currentSettings = settings
                true
            } else {
                false
            }
        }
        if (!shouldNotify) return
        mainHandler.post {
            listeners.forEach { listener -> listener(currentSettings) }
        }
    }
}
