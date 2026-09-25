package com.autumn.douyin.liquidglass.ui

import android.view.SurfaceControl
import android.view.View
import com.autumn.douyin.liquidglass.ModuleLog
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

object ScreenCaptureExclusion {
    private const val MaintenanceIntervalMillis = 100L

    // 帧同步脉冲门控：
    // 模块自身通过 root 守护进程抓“显示器合成帧”作为玻璃背景。当底栏 setSkipScreenshot=false
    // （截图可见）时，底栏自己会被抓进合成帧里，导致背景 = “视频 + 上一层玻璃”，自己叠自己，
    // 表现为发黑、不均匀、像透过透镜的残影。
    //
    // 物理约束：setSkipScreenshot 是同一个位，同时决定“系统截图可见性”和“模块自身抓帧可见性”，
    // 无法只对其中一方生效。因此只能在“时间维度”上折中：
    //   - 绝大多数时间保持 false → 截图/录屏能拍到液态玻璃底栏；
    //   - 每当抓帧链路刚收到一帧（与守护进程抓帧同相）时，用极短的脉冲把底栏切成 true，
    //     使守护进程“下一帧”抓取时底栏隐身 → 玻璃背景干净，不再自己叠自己。
    //
    // 脉冲宽度取略大于一个帧周期，保证覆盖下一帧的抓取窗口；其余时间恢复 false。
    private const val PULSE_DURATION_MILLIS = 20L

    private val maintenanceCallbacks = WeakHashMap<View, Runnable>()
    private val lastAppliedControls = WeakHashMap<View, String>()
    private val reportedFailures = WeakHashMap<View, Boolean>()

    // 记录每个 view 当前是否处于“脉冲（排除）”状态，以及恢复 false 的 Runnable。
    private val pulsed = WeakHashMap<View, Boolean>()
    private val pulseResetCallbacks = WeakHashMap<View, Runnable>()

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
                // 维护线程只负责把底栏恢复到“截图可见”（false）。
                // 若此刻正处于脉冲排除窗口内，则跳过，交给脉冲的恢复回调处理，
                // 避免维护线程把脉冲提前抹掉。
                if (isPulsing(view)) {
                    view.postDelayed(this, MaintenanceIntervalMillis)
                    return
                }
                apply(view, skipScreenshot = false)
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
            pulsed.remove(view)
            pulseResetCallbacks.remove(view)?.let(view::removeCallbacks)
            callback
        }
        callback?.let(view::removeCallbacks)
    }

    /**
     * 帧同步脉冲：收到守护进程新一帧（抓帧同相）后调用，把底栏临时排除出捕获帧，
     * 使“下一帧”的玻璃背景干净；短时间后自动恢复为截图可见（false）。
     * 必须在主线程调用（这里由调用方 post 保证）。
     */
    fun pulseExclusion(view: View) {
        if (!view.isAttachedToWindow) return
        synchronized(maintenanceCallbacks) {
            if (!maintenanceCallbacks.containsKey(view)) return
        }

        val reset = Runnable {
            synchronized(maintenanceCallbacks) {
                pulsed.remove(view)
                pulseResetCallbacks.remove(view)
            }
            apply(view, skipScreenshot = false)
        }

        synchronized(maintenanceCallbacks) {
            val previous = pulseResetCallbacks.put(view, reset)
            if (previous != null) {
                view.removeCallbacks(previous)
            }
            pulsed[view] = true
        }

        // 先切成排除（true），让守护进程下一帧抓取时底栏隐身。
        apply(view, skipScreenshot = true)
        // 一个脉冲窗口后恢复 false（截图可见）。
        view.postDelayed(reset, PULSE_DURATION_MILLIS)
    }

    private fun isPulsing(view: View): Boolean = synchronized(maintenanceCallbacks) {
        pulsed[view] == true
    }

    private fun apply(view: View, skipScreenshot: Boolean): Boolean {
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
                skipScreenshot,
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