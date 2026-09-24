package com.autumn.douyin.liquidglass.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.nativebar.NativeBottomBar
import com.autumn.douyin.liquidglass.nativebar.NativeMessageBadgeMonitor
import com.autumn.douyin.liquidglass.nativebar.NativeTabSelectionMonitor

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

    private val tabSelectionMonitor = NativeTabSelectionMonitor(nativeBar, ::onNativeSelection)

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
        // 先乐观更新，保证点击后的液态动画立即响应；随后以原生状态为准。
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
     * 原生 Tab 选中状态采样回调（主线程）。
     * 无论切换来自底栏点击、系统返回手势还是页面内跳转，都在这里统一同步。
     */
    private fun onNativeSelection(observed: Int?) {
        // 过渡期没有任何 Tab 被选中：保持当前显示，避免误跳回首页。
        if (observed == null) return

        val pending = pendingTab
        if (pending != null) {
            when {
                // 原生已确认到点击的目标，乐观状态转正。
                observed == pending -> pendingTab = null
                // 原生还没跟上，给它一点时间，期间不要把选中态拉回旧位置。
                SystemClock.elapsedRealtime() < pendingDeadlineMs -> return
                // 超时仍未切换（点击被拒绝或页面被拦截）：放弃乐观状态，回到真实页面。
                else -> pendingTab = null
            }
        }

        if (selectedTab != observed) {
            ModuleLog.info { "sync selected tab $selectedTab -> $observed (native)" }
            selectedTab = observed
        }
    }

    private companion object {
        const val PendingTimeoutMs = 800L
    }
}