package com.autumn.douyin.liquidglass

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

object ModuleLog {
    private const val MaxLogBytes = 384 * 1024

    val isEnabled: Boolean
        get() = diagnosticsEnabled

    @Volatile
    private var logFile: File? = null
    @Volatile
    private var diagnosticsEnabled = false
    private val generation = AtomicInteger()

    private var executor: ExecutorService? = null

    fun install(context: Context) {
        if (!diagnosticsEnabled) return
        synchronized(this) {
            if (!diagnosticsEnabled) return
            if (logFile != null) return
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(File(base, "liquid-glass"), "module.log")
            file.parentFile?.mkdirs()
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "douyin-liquid-glass-log").apply { isDaemon = true }
            }
            logFile = file
        }
    }

    fun path(): String = logFile?.absolutePath ?: "not initialized"

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsEnabled = enabled
        if (!enabled) {
            synchronized(this) {
                generation.incrementAndGet()
                logFile = null
                executor?.shutdown()
                executor = null
            }
        }
    }

    fun info(message: String) = write("INFO", message, null)

    fun error(message: String, throwable: Throwable? = null) =
        write("ERROR", message, throwable)

    inline fun info(message: () -> String) {
        if (isEnabled) info(message())
    }

    inline fun error(
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (isEnabled) error(message(), throwable)
    }

    private fun write(level: String, message: String, throwable: Throwable?) {
        if (!diagnosticsEnabled) return
        val file: File
        val logExecutor: ExecutorService
        val logGeneration: Int
        synchronized(this) {
            if (!diagnosticsEnabled) return
            val currentFile = logFile ?: return
            val currentExecutor = executor ?: return
            file = currentFile
            logExecutor = currentExecutor
            logGeneration = generation.get()
        }
        logExecutor.execute {
            synchronized(this) {
                if (logGeneration == generation.get() && diagnosticsEnabled) {
                    runCatching {
                        writeLocked(level, message, throwable, file)
                    }
                }
            }
        }
    }

    private fun writeLocked(
        level: String,
        message: String,
        throwable: Throwable?,
        file: File? = this.logFile,
    ) {
        val target = file ?: return
        runCatching {
            if (target.length() > MaxLogBytes) {
                    val old = File(target.parentFile, "module.log.old")
                    if (old.exists()) old.delete()
                    target.renameTo(old)
                }

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileOutputStream(target, true).use { stream ->
                    val text = buildString {
                        append(time).append(' ').append(level).append(' ').append(message).append('\n')
                        throwable?.let { error ->
                            val writer = StringWriter()
                            error.printStackTrace(PrintWriter(writer))
                            append(writer.toString())
                        }
                    }
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
        }
    }
}
