package com.autumn.douyin.liquidglass.root

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.unit.IntOffset
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.ui.DynamicBitmapBackdrop
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Streams the display-composited frame through the root daemon. Pixels are
 * decoded directly into bitmap buffers and are never inspected or persisted.
 *
 * The transport is continuous: geometry updates flow to the daemon while frames
 * flow back without waiting for a per-frame client request.
 */
class CompositeFrameProvider(
    private val context: Context,
    private val backdrop: DynamicBitmapBackdrop,
) {
    @Volatile
    var hasDeliveredFrame: Boolean = false
        private set

    @Volatile
    var onFirstFrame: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val geometryLock = Any()
    private val lifecycleLock = Any()
    private var screenRect = Rect()
    private var pixelBuffer = ByteArray(0)
    private var worker: Thread? = null
    private val canLaunchDaemon =
        context.packageName == CompositeFrameDaemonLauncher.MODULE_PACKAGE_NAME
    private var loggedExternalDaemonWait = false

    @Volatile
    private var running = false

    @Volatile
    private var activeSocket: Socket? = null


    fun updateCaptureRegion(captureRect: Rect, mainWindowOrigin: IntOffset) {
        val nextRect = if (captureRect.isEmpty) {
            Rect()
        } else {
            Rect(
                captureRect.left + mainWindowOrigin.x,
                captureRect.top + mainWindowOrigin.y,
                captureRect.right + mainWindowOrigin.x,
                captureRect.bottom + mainWindowOrigin.y,
            )
        }

        synchronized(geometryLock) {
            if (screenRect == nextRect) return
            val previousRect = Rect(screenRect)
            screenRect = Rect(nextRect)
            if (previousRect.isEmpty || nextRect.isEmpty) {
                ModuleLog.info { "composite provider screen rect=$nextRect" }
            }
        }
    }

    fun start() {
        synchronized(lifecycleLock) {
            if (running || worker?.isAlive == true) return
            running = true
            val thread = Thread(
                ::runForever,
                "liquid-glass-composite-provider",
            )
            worker = thread
            thread.start()
        }
        ModuleLog.info("composite provider ready")
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            activeSocket?.close()
            worker?.interrupt()
            worker = null
        }
        mainHandler.post {
            backdrop.clearCompositeFrame()
        }
    }

    private fun runForever() {
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
        var lastLaunchAttempt = 0L
        while (running) {
            val socket = runCatching { connect() }.getOrNull()
            if (socket == null) {
                val now = System.currentTimeMillis()
                if (now - lastLaunchAttempt >= DaemonRetryMs) {
                    lastLaunchAttempt = now
                    if (canLaunchDaemon) {
                        val launchResult = CompositeFrameDaemonLauncher.start(context)
                        if (!launchResult.first) {
                            ModuleLog.error { "composite daemon launch failed: ${launchResult.second}" }
                        }
                    } else if (!loggedExternalDaemonWait) {
                        loggedExternalDaemonWait = true
                        ModuleLog.info {
                            "waiting for module app to start composite daemon on " +
                                "127.0.0.1:${CompositeFrameDaemonLauncher.PORT}"
                        }
                    }
                }
                if (!sleepInterruptible(DaemonRetryMs)) return
                continue
            }

            try {
                socket.use {
                    activeSocket = it
                    streamFrames(it)
                }
            } catch (throwable: Throwable) {
                if (running) {
                    ModuleLog.error("composite socket stream ended", throwable)
                }
            } finally {
                activeSocket = null
            }

            if (running && !sleepInterruptible(ReconnectDelayMs)) return
        }
    }

    private fun connect(): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.receiveBufferSize = ReceiveBufferBytes
        socket.sendBufferSize = SendBufferBytes
        socket.connect(
            InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                CompositeFrameDaemonLauncher.PORT,
            ),
            ConnectTimeoutMs,
        )
        socket.soTimeout = ReadTimeoutMs
        return socket
    }

    private fun streamFrames(socket: Socket) {
        val input = DataInputStream(
            BufferedInputStream(socket.getInputStream(), ReceiveBufferBytes),
        )
        val output = DataOutputStream(
            BufferedOutputStream(socket.getOutputStream(), SendBufferBytes),
        )
        output.writeUTF(CompositeFrameDaemonLauncher.HANDSHAKE_TOKEN)
        output.flush()
        var lastSentRect: Rect? = null

        fun sendCurrentRect() {
            val nextRect = synchronized(geometryLock) { Rect(screenRect) }
            if (nextRect == lastSentRect) return
            output.writeInt(nextRect.left)
            output.writeInt(nextRect.top)
            output.writeInt(nextRect.right)
            output.writeInt(nextRect.bottom)
            output.flush()
            lastSentRect = Rect(nextRect)
        }

        sendCurrentRect()

        while (running) {
            val frameStart = System.nanoTime()
            val header = CompositeFrameProtocol.read(input)
            val status = header.status
            val width = header.width
            val height = header.height
            val left = header.left
            val top = header.top
            val right = header.right
            val bottom = header.bottom
            val byteCount = header.byteCount
            val frameTimestamp = header.frameTimestamp

            if (status == CompositeFrameProtocol.StatusIdle) {
                sendCurrentRect()
                if (!sleepInterruptible(IdleSleepMs)) return
                continue
            }
            if (status == CompositeFrameProtocol.StatusError) {
                val message = CompositeFrameProtocol.readError(input)
                logThrottled { "composite daemon error: $message" }
                if (!sleepInterruptible(ErrorSleepMs)) return
                continue
            }

            val expectedBytes = width.toLong() * height.toLong() * BytesPerPixel
            if (
                width <= 0 ||
                height <= 0 ||
                expectedBytes > MaxFrameBytes ||
                byteCount.toLong() != expectedBytes
            ) {
                throw IOExceptionProtocol(
                    "invalid composite frame metadata width=$width height=$height bytes=$byteCount",
                )
            }

            val responseRect = Rect(left, top, right, bottom)
            if (responseRect.isEmpty) {
                throw IOExceptionProtocol("composite response rect is empty")
            }

            val pixels = ensurePixelBuffer(byteCount)
            input.readFully(pixels, 0, byteCount)
            sendCurrentRect()
            val shouldLogLatency =
                successfulFrames == 0L || successfulFrames % LatencyLogFrames == LatencyLogFrames - 1L
            if (shouldLogLatency) {
                ModuleLog.info {
                    "composite receive latency: captureToReceiveMs=" +
                        (SystemClock.uptimeMillis() - frameTimestamp)
                }
            }
            val bitmap = createBitmap(width, height)
                ?: throw IOExceptionProtocol("composite bitmap allocation failed")
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, byteCount))

            if (!hasDeliveredFrame) {
                hasDeliveredFrame = true
                mainHandler.post { onFirstFrame?.invoke() }
            }
            deliverFrame(bitmap, responseRect, frameTimestamp, shouldLogLatency)

            successfulFrames += 1
            framesSinceStats += 1
            val frameElapsed = elapsedMillis(frameStart)
            elapsedSum += frameElapsed
            elapsedMax = maxOf(elapsedMax, frameElapsed)
            if (successfulFrames == 1L) {
                ModuleLog.info {
                    "composite first frame ok: rect=$responseRect bitmap=${width}x$height " +
                        "elapsedMs=$frameElapsed"
                }
            }
            reportStats()
        }
    }

    private fun ensurePixelBuffer(byteCount: Int): ByteArray {
        if (pixelBuffer.size < byteCount) {
            pixelBuffer = ByteArray(byteCount)
        }
        return pixelBuffer
    }

    private fun deliverFrame(
        bitmap: Bitmap,
        rect: Rect,
        timestamp: Long,
        shouldLogLatency: Boolean,
    ) {
        if (!running) return
        if (shouldLogLatency) {
            ModuleLog.info {
                "composite submit latency: captureToSubmitMs=" +
                    (SystemClock.uptimeMillis() - timestamp)
            }
        }
        backdrop.updateCompositeFrame(bitmap, rect, timestamp)
    }

    private fun createBitmap(width: Int, height: Int): Bitmap? =
        runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()

    private fun reportStats() {
        val now = System.currentTimeMillis()
        if (lastStatsTime == 0L) {
            lastStatsTime = now
            return
        }
        if (now - lastStatsTime < StatsIntervalMs || framesSinceStats == 0L) return

        val seconds = (now - lastStatsTime) / 1000.0
        ModuleLog.info {
            "composite stats: totalFrames=$successfulFrames, " +
                "intervalFrames=$framesSinceStats, fps=${"%.2f".format(framesSinceStats / seconds)}, " +
                "avgMs=${"%.2f".format(elapsedSum / framesSinceStats)}, " +
                "maxMs=${"%.2f".format(elapsedMax)}"
        }
        lastStatsTime = now
        framesSinceStats = 0
        elapsedSum = 0.0
        elapsedMax = 0.0
    }

    private fun sleepInterruptible(millis: Long): Boolean {
        return try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun elapsedMillis(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / 1_000_000.0

    private fun logThrottled(message: () -> String) {
        if (!ModuleLog.isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastThrottledLogTime >= ThrottleLogMs) {
            lastThrottledLogTime = now
            ModuleLog.error(message())
        }
    }

    private class IOExceptionProtocol(message: String) : java.io.IOException(message)

    private companion object {
        const val BytesPerPixel = 4L
        const val MaxFrameBytes = 16L * 1024 * 1024
        const val ReceiveBufferBytes = 512 * 1024
        const val SendBufferBytes = 64 * 1024
        const val DaemonRetryMs = 500L
        const val ReconnectDelayMs = 100L
        const val IdleSleepMs = 32L
        const val ErrorSleepMs = 100L
        const val StatsIntervalMs = 5_000L
        const val ThrottleLogMs = 1_000L
        const val ConnectTimeoutMs = 250
        const val ReadTimeoutMs = 1_000
        const val LatencyLogFrames = 300L
    }

    private var successfulFrames = 0L
    private var framesSinceStats = 0L
    private var lastStatsTime = 0L
    private var lastThrottledLogTime = 0L
    private var elapsedSum = 0.0
    private var elapsedMax = 0.0
}
