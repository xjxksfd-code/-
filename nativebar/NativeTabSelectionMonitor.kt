package com.autumn.douyin.liquidglass.nativebar

import android.os.Handler
import android.os.Looper
import com.autumn.douyin.liquidglass.ModuleLog

/**
 * 采样结果：一次采样中同时给出两个「事实来源」。
 *
 * @param nativeSelected 原生底栏按钮的 isSelected 推断值（来源 A）。
 *        仅在「点击底栏 Tab」等场景可靠；系统返回手势 / 返回键 / 页面内跳转
 *        时常常停留在旧值，因此只能作为参考事实来源。
 * @param pageState     从真实内容区（页面容器 / 页签指示器 / 页面标识）推断出的
 *        当前页面下标（来源 B）。这是「当前实际页面」的二级事实来源，
 *        在返回手势等场景下更可靠。
 * @param attached      采样时原生底栏是否仍挂在窗口上。为 false 时两个来源都不可信，
 *        调用方应保持现状而不是强行同步。
 */
data class TabSelectionSample(
    val nativeSelected: Int?,
    val pageState: Int?,
    val attached: Boolean,
)

/**
 * 持续观察抖音当前实际所在页面。
 *
 * 本监控器不再把「原生按钮 isSelected」当作唯一事实来源，而是同时采集：
 *  A) 原生底栏按钮 isSelected（点击底栏 Tab 时可靠）；
 *  B) 真实内容区推断出的当前页面下标（返回手势 / 返回键 / Fragment 切换时可靠）。
 *
 * 两者冲突时，由 [com.autumn.douyin.liquidglass.ui.OverlayController] 仲裁，
 * 以「页面状态」优先，从而保证底栏选中态始终与抖音实际页面一致。
 *
 * 每次采样都会回调（值可能与上次相同）。回调中 attached=false 表示原生底栏
 * 暂时不可用，调用方应保持现状；两个下标字段为 null 表示该来源本帧无法判断。
 */
class NativeTabSelectionMonitor(
    private val nativeBar: NativeBottomBar,
    private val onSample: (TabSelectionSample) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageStateReader = NativePageStateReader(nativeBar)
    private var running = false
    private var lastLogged: String? = null

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
        val attached = nativeBar.home.isAttachedToWindow

        // 来源 A：原生按钮 isSelected（底栏不在窗口上时不可信）。
        val nativeSelected = if (attached) nativeBar.selectedIndexOrNull else null

        // 来源 B：真实内容区推断（底栏不在窗口上时同样跳过，避免读到残留视图）。
        val pageState = if (attached) {
            runCatching { pageStateReader.sample() }
                .onFailure { ModuleLog.info { "page state read failed: ${it.message}" } }
                .getOrNull()
        } else {
            null
        }

        val sample = TabSelectionSample(
            nativeSelected = nativeSelected,
            pageState = pageState,
            attached = attached,
        )

        val signature = "${sample.nativeSelected}|${sample.pageState}|${sample.attached}"
        if (signature != lastLogged) {
            lastLogged = signature
            ModuleLog.info {
                "native tab sample native=${sample.nativeSelected} page=${sample.pageState} attached=${sample.attached}"
            }
        }

        onSample(sample)
    }

    private companion object {
        const val SamplingIntervalMs = 100L
    }
}