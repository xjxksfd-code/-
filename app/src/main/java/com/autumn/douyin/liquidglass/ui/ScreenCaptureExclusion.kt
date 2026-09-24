package com.autumn.douyin.liquidglass.ui

import android.view.SurfaceControl
import android.view.View
import com.autumn.douyin.liquidglass.ModuleLog
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

object ScreenCaptureExclusion {
    private const val MaintenanceIntervalMillis = 100L

    private val maintenanceCallbacks = WeakHashMap<View, Runnable>()
    private val lastAppliedControls = WeakHashMap<View, String>()
    private val reportedFailures = WeakHashMap<View, Boolean>()

    fun request(view: View) {
        start(view)
    }

    fun start(view: View) {
        val callback = object : Runnable {
            override fun run() {
                if (!view.isAttachedToWindow) {
                    stop(view)
                    return
                }
                apply(view)
                view.postDelayed(this, MaintenanceIntervalMillis)
            }
        }

        synchronized(maintenanceCallbacks) {
            if (maintenanceCallbacks.containsKey(view)) return
            maintenanceCallbacks[view] = callback
        }

        ModuleLog.info("screen capture exclusion maintenance started")
        view.post(callback)
    }

    fun refresh(view: View) {
        val callback = synchronized(maintenanceCallbacks) {
            maintenanceCallbacks[view]
        }
        if (callback == null) {
            start(view)
            return
        }

        view.removeCallbacks(callback)
        view.post(callback)
    }

    fun stop(view: View) {
        val callback = synchronized(maintenanceCallbacks) {
            val callback = maintenanceCallbacks.remove(view)
            lastAppliedControls.remove(view)
            reportedFailures.remove(view)
            callback
        }
        callback?.let(view::removeCallbacks)
    }

    private fun apply(view: View): Boolean {
        if (!view.isAttachedToWindow) return false

        return runCatching {
            val viewRoot = XposedHelpers.callMethod(view, "getViewRootImpl")
                ?: return false
            val control = XposedHelpers.callMethod(viewRoot, "getSurfaceControl")
                as? SurfaceControl
                ?: return false
            if (!control.isValid) return false

            val transaction = SurfaceControl.Transaction()
            XposedHelpers.callMethod(
                transaction,
                "setSkipScreenshot",
                control,
                true,
            )
            XposedHelpers.callMethod(transaction, "apply")
            clearFailure(view)
            logNewControl(view, control)
            true
        }.getOrElse { throwable ->
            logFailureOnce(view, throwable)
            false
        }
    }

    private fun logNewControl(view: View, control: SurfaceControl) {
        val shouldLog = synchronized(maintenanceCallbacks) {
            if (lastAppliedControls.containsKey(view)) {
                false
            } else {
                lastAppliedControls[view] = control.toString()
                true
            }
        }
        if (shouldLog) {
            ModuleLog.info { "screen capture exclusion applied: control=$control" }
        }
    }

    private fun clearFailure(view: View) {
        synchronized(maintenanceCallbacks) {
            reportedFailures.remove(view)
        }
    }

    private fun logFailureOnce(view: View, throwable: Throwable) {
        val shouldLog = synchronized(maintenanceCallbacks) {
            reportedFailures.putIfAbsent(view, true) == null
        }
        if (shouldLog) {
            ModuleLog.error("screen capture exclusion failed", throwable)
        }
    }
}
