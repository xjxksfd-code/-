package com.autumn.douyin.liquidglass.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.nativebar.NativeBottomBar
import com.autumn.douyin.liquidglass.nativebar.NativeMessageBadgeMonitor
import com.autumn.douyin.liquidglass.nativebar.NativeTabSelectionMonitor
import com.autumn.douyin.liquidglass.nativebar.TabSelectionSample

class OverlayController(private val nativeBar: NativeBottomBar) {
    var selectedTab by mutableIntStateOf(nativeBar.selectedIndex)
        private set
    var messageBadgeCount by mutableIntStateOf(0)
        private set

    /** 用户刚点击、原生尚未确认的目标 Tab（乐观更新），以及它的超时时间。 */
    private var pendingTab: Int? = null
    private var pendingDeadlineMs = 0L

    private val messageBadgeMonitor = NativeMessageBadgeMonitor(nativeBar.messages) {
        messageBadgeCount = it
    }

    private val tabSelectionMonitor = NativeTabSelectionMonitor(nativeBar, ::onSample)

    fun start() {
        messageBadgeMonitor.start()
        tabSelectionMonitor.start()
    }

    fun stop() {
        messageBadgeMonitor.stop()
        tabSelectionMonitor.stop()
        pendingTab = null
    }

    fun clickTab(index: Int) {
        // 先乐观更新，保证点击后的液态动画立即响应；随后以真实页面状态为准。
        selectedTab = index
        pendingTab = index
        pendingDeadlineMs = SystemClock.elapsedRealtime() + PendingTimeoutMs
        val accepted = nativeBar.clickTab(index)
        tabSelectionMonitor.sampleNow()
        ModuleLog.info { "click tab=$index accepted=$accepted" }
    }

    fun clickPlus() {
        val accepted = nativeBar.clickPlus()
        ModuleLog.info { "click plus accepted=$accepted" }
    }

    fun longClickPlus(): Boolean {
        val accepted = nativeBar.longClickPlus()
        ModuleLog.info { "long click plus accepted=$accepted" }
        return accepted
    }

    /**
     * 采样回调（主线程）。这里做「双事实来源一致性仲裁」：
     *
     *  来源 B（pageState，真实页面推断）优先级最高 —— 只要它给出确定值，
     *  就立刻纠偏，并立即清除乐观态（乐观态不能让底栏长期偏离真实页面）。
     *
     *  来源 A（nativeSelected，原生按钮 isSelected）作为补充：当页面源无法判断时，
     *  用原生的 isSelected 维持既有行为（含点击后的乐观态保护窗口）。
     *
     * 这样无论切换来自底栏点击、系统返回手势、返回键还是页面内跳转，
     * 底栏选中态都会收敛到抖音的实际当前页面。
     */
    private fun onSample(sample: TabSelectionSample) {
        val page = sample.pageState
        val native = sample.nativeSelected

        if (page != null) {
            // 页面状态是最高优先级事实来源：直接以它为准，并放弃乐观态。
            if (pendingTab != null && pendingTab != page) {
                ModuleLog.info { "page state overrides pending tab=$pendingTab -> $page" }
            }
            pendingTab = null
            if (selectedTab != page) {
                ModuleLog.info { "sync selected tab $selectedTab -> $page (page state)" }
                selectedTab = page
            }
            return
        }

        // 页面源本帧无法判断：退回原有基于 isSelected 的同步与乐观态保护逻辑。
        if (!sample.attached) return
        if (native == null) return

        val pending = pendingTab
        if (pending != null) {
            when {
                // 原生已确认到点击的目标，乐观状态转正。
                native == pending -> pendingTab = null
                // 原生还没跟上，给它一点时间，期间不要把选中态拉回旧位置。
                SystemClock.elapsedRealtime() < pendingDeadlineMs -> return
                // 超时仍未切换（点击被拒绝或页面被拦截）：放弃乐观状态，回到真实页面。
                else -> pendingTab = null
            }
        }

        if (selectedTab != native) {
            ModuleLog.info { "sync selected tab $selectedTab -> $native (native)" }
            selectedTab = native
        }
    }

    private companion object {
        const val PendingTimeoutMs = 800L
    }
}