package com.autumn.douyin.liquidglass.nativebar

import android.os.Handler
import android.os.Looper
import com.autumn.douyin.liquidglass.ModuleLog

/**
 * 持续观察抖音原生底栏当前选中的 Tab。
 *
 * 原生按钮被模块设为 INVISIBLE，但抖音仍会在任何来源的 Tab 切换
 * （点击、系统返回手势、页面内跳转等）时更新它们的 isSelected，
 * 因此原生 View 的 isSelected 是“当前实际页面”的唯一事实来源。
 *
 * 每次采样都会回调（值可能与上次相同），由调用方决定如何与本地状态协调。
 * 回调值为 null 表示当前没有任何 Tab 被选中（过渡期），调用方应保持现状。
 */
class NativeTabSelectionMonitor(
    private val nativeBar: NativeBottomBar,
    private val onSample: (Int?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastLogged: Int? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            mainHandler.postDelayed(this, SamplingIntervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastLogged = null
        ModuleLog.info("native tab selection monitor started")
        sample()
        mainHandler.postDelayed(poller, SamplingIntervalMs)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(poller)
        lastLogged = null
        ModuleLog.info("native tab selection monitor stopped")
    }

    /** 立即采样一次（例如在点击后主动催一次同步）。 */
    fun sampleNow() {
        if (running) sample()
    }

    private fun sample() {
        if (!nativeBar.home.isAttachedToWindow) return
        val index = nativeBar.selectedIndexOrNull
        if (index != lastLogged) {
            lastLogged = index
            ModuleLog.info { "native tab selection observed index=$index" }
        }
        onSample(index)
    }

    private companion object {
        const val SamplingIntervalMs = 100L
    }
}