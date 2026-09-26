package com.autumn.douyin.liquidglass.root

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.ui.unit.IntOffset
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.ui.DynamicBitmapBackdrop

/**
 * Grabs the host window's pixels in-process with [PixelCopy] and feeds them to
 * the backdrop as the glass source.
 *
 * This replaces the previous root-daemon transport (loopback socket +
 * `app_process` daemon). No root, no `su`, no cross-process channel and no
 * root-only display-capture backends are involved anymore — which is exactly
 * why the glass backdrop keeps working on modern Android builds where the old
 * display-capture backends are unavailable.
 *
 * Two coordinate spaces are tracked per capture region:
 * - `windowRect` : the source rectangle handed to [PixelCopy] (window coords)
 * - `screenRect` : the same region in screen coords, handed to the backdrop
 *
 * Because the glass overlay lives in its own window, capturing the main window
 * never contains the glass bar itself. The old frame-synchronised
 * capture-exclusion pulsing hack is therefore no longer required.
 *
 * The public surface (`hasDeliveredFrame`, `onFirstFrame`,
 * `updateCaptureRegion`, `start`, `stop`) is unchanged; only the internals were
 * swapped, so callers such as `LiquidGlassOverlayView` need no changes.
 */
class CompositeFrameProvider(
    private val context: Context,
    private val backdrop: DynamicBitmapBackdrop,
    private val sourceWindowProvider: () -> Window? = { null },
) {
    @Volatile
    var hasDeliveredFrame: Boolean = false
        private set

    @Volatile
    var onFirstFrame: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val geometryLock = Any()

    /** Capture rect in the source window's coordinate space (for [PixelCopy]). */
    private var windowRect = Rect()

    /** The same rect translated to screen coordinates (for the backdrop). */
    private var screenRect = Rect()

    private val captureRunnable = Runnable { captureNextFrame() }

    @Volatile
    private var running = false

    private var inFlight = false
    private var consecutiveFailures = 0
    private var successfulFrames = 0L
    private var failedFrames = 0L
    private var lastStatsTime = 0L
    private var lastThrottledLogTime = 0L

    fun updateCaptureRegion(captureRect: Rect, mainWindowOrigin: IntOffset) {
        val nextWindowRect = if (captureRect.isEmpty) Rect() else Rect(captureRect)
        val nextScreenRect = if (captureRect.isEmpty) {
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
            if (windowRect == nextWindowRect && screenRect == nextScreenRect) return
            val wasEmpty = windowRect.isEmpty
            windowRect = nextWindowRect
            screenRect = nextScreenRect
            if (wasEmpty || nextWindowRect.isEmpty) {
                ModuleLog.info {
                    "composite capture region window=$nextWindowRect screen=$nextScreenRect"
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        ModuleLog.info {
            "composite provider ready (in-process pixelcopy) pkg=${context.packageName}"
        }
        mainHandler.post(captureRunnable)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.post {
            backdrop.clearCompositeFrame()
        }
    }

    private fun captureNextFrame() {
        if (!running || inFlight) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            logThrottled { "composite pixelcopy unsupported: sdk=${Build.VERSION.SDK_INT}" }
            scheduleNext(FailureRetryMs)
            return
        }

        val sourceWindow = sourceWindowProvider()
        if (sourceWindow == null) {
            logThrottled { "composite pixelcopy skipped: source window unavailable" }
            scheduleNext(FailureRetryMs)
            return
        }

        if (!isWindowDrawable(sourceWindow)) {
            // During bar show/hide animations and activity transitions the host
            // window can momentarily lose its backing surface. Requesting a
            // PixelCopy then throws "Window doesn't have a backing surface!",
            // so bail out early and drop any stale frame instead of sampling
            // old / uninitialised pixels into the glass.
            logThrottled { "composite pixelcopy skipped: window has no backing surface" }
            degradeBackdropOnFailure()
            scheduleNext(FailureRetryMs)
            return
        }

        val target = synchronized(geometryLock) {
            if (windowRect.isEmpty) {
                null
            } else {
                CaptureTarget(Rect(windowRect), Rect(screenRect))
            }
        } ?: run {
            scheduleNext(IdleRetryMs)
            return
        }

        val sourceRect = target.sourceRect
        val width = sourceRect.width()
        val height = sourceRect.height()
        if (width <= 0 || height <= 0) {
            scheduleNext(IdleRetryMs)
            return
        }

        val bitmap = createBitmap(width, height)
        if (bitmap == null) {
            logThrottled { "composite pixelcopy bitmap allocation failed ${width}x$height" }
            scheduleNext(FailureRetryMs)
            return
        }

        val deliverRect = target.screenRect
        val captureTimestamp = SystemClock.uptimeMillis()
        inFlight = true

        val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
            inFlight = false
            if (running) {
                if (result == PixelCopy.SUCCESS) {
                    consecutiveFailures = 0
                    onFrameCaptured(bitmap, deliverRect, captureTimestamp)
                    scheduleNext(FrameIntervalMs)
                } else {
                    consecutiveFailures += 1
                    failedFrames += 1
                    logThrottled {
                        "composite pixelcopy failed result=$result rect=$sourceRect " +
                            "failures=$consecutiveFailures"
                    }
                    degradeBackdropOnFailure()
                    scheduleNext(FailureRetryMs)
                }
            }
        }

        try {
            PixelCopy.request(sourceWindow, sourceRect, bitmap, listener, mainHandler)
        } catch (throwable: Throwable) {
            inFlight = false
            consecutiveFailures += 1
            failedFrames += 1
            logThrottled { "composite pixelcopy threw: ${throwable.message}" }
            degradeBackdropOnFailure()
            scheduleNext(FailureRetryMs)
        }
    }

    private fun onFrameCaptured(bitmap: Bitmap, screenRect: Rect, captureTimestamp: Long) {
        if (running && !screenRect.isEmpty) {
            backdrop.updateCompositeFrame(bitmap, screenRect, captureTimestamp)
        }
        if (!hasDeliveredFrame) {
            hasDeliveredFrame = true
            onFirstFrame?.invoke()
        }
        successfulFrames += 1
        if (successfulFrames == 1L) {
            ModuleLog.info {
                "composite first frame ok: rect=$screenRect bitmap=${bitmap.width}x${bitmap.height}"
            }
        }
        reportStats()
    }

    private fun createBitmap(width: Int, height: Int): Bitmap? =
        runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()

    private fun isWindowDrawable(window: Window): Boolean {
        val decor = window.peekDecorView() ?: return false
        if (!decor.isAttachedToWindow) return false
        if (decor.windowVisibility != View.VISIBLE) return false
        return decor.width > 0 && decor.height > 0
    }

    /**
     * When a capture fails the cached frame is either stale (the window
     * geometry moved on) or only partially written. Clearing it lets the
     * backdrop fall back to its neutral default fill instead of stretching
     * old / uninitialised pixels across the glass.
     */
    private fun degradeBackdropOnFailure() {
        runCatching { backdrop.clearCompositeFrame() }
    }

    private fun scheduleNext(delayMillis: Long) {
        if (!running) return
        mainHandler.postDelayed(captureRunnable, delayMillis.coerceAtLeast(0L))
    }

    private fun reportStats() {
        val now = System.currentTimeMillis()
        if (lastStatsTime == 0L) {
            lastStatsTime = now
            return
        }
        if (now - lastStatsTime < StatsIntervalMs) return

        val seconds = (now - lastStatsTime) / 1000.0
        ModuleLog.info {
            "composite stats: frames=$successfulFrames failed=$failedFrames " +
                "fps=${"%.2f".format(successfulFrames / seconds)}"
        }
        lastStatsTime = now
        successfulFrames = 0
        failedFrames = 0
    }

    private fun logThrottled(message: () -> String) {
        if (!ModuleLog.isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastThrottledLogTime >= ThrottleLogMs) {
            lastThrottledLogTime = now
            ModuleLog.error(message())
        }
    }

    private class CaptureTarget(
        val sourceRect: Rect,
        val screenRect: Rect,
    )

    private companion object {
        const val FrameIntervalMs = 16L
        const val IdleRetryMs = 32L
        const val FailureRetryMs = 250L
        const val StatsIntervalMs = 5_000L
        const val ThrottleLogMs = 1_000L
    }
}
