package com.autumn.douyin.liquidglass.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.nativebar.NativeCommentInputLocator
import com.autumn.douyin.liquidglass.nativebar.NativeCommentPanelLocator
import com.autumn.douyin.liquidglass.settings.ModuleSettingsBridge
import com.autumn.douyin.liquidglass.ui.CommentGlassFrameView
import com.autumn.douyin.liquidglass.ui.CommentInputGlassView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 评论区液态玻璃（档次一）：清除面板满幅不透明底 -> 面板半透明磨砂 -> 面板顶层叠加玻璃装饰框。
 * 全部基于原生 View 树操作，不依赖抖音混淆类名，不引入新的采样链路。
 */
object CommentGlassIntegration {
    private const val FrameTag = "douyin-liquid-glass-comment-frame"
    private const val ScrimAlpha = 158
    private const val OpaqueAlphaThreshold = 200
    private const val FirstScanDelayMs = 400L
    private const val SecondScanDelayMs = 1_200L
    private const val ScanThrottleMs = 150L
    private const val CoverWalkDepth = 4
    private const val CoverMinWidthRatio = 0.80f
    private const val CoverMinHeightRatio = 0.50f
    private const val CoverMinHeightDp = 8f
    private const val InputFrameTag = "douyin-liquid-glass-comment-input-frame"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastScanAt = AtomicLong(0L)
    private val managedPanels = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val panelBackgrounds = WeakHashMap<View, Drawable?>()
    private val coverViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val coverBackgrounds = WeakHashMap<View, Drawable?>()
    private val inputHosts = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val inputBackgrounds = WeakHashMap<View, Drawable?>()
    private val observedRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val applyingBackground = ThreadLocal<Boolean>()

    @Volatile
    private var installed = false

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        observedRoots.toList().filterIsInstance<ViewGroup>().forEach { root ->
            scheduleScan(root, force = false)
        }
    }

    private val drawableSetterHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? View ?: return
            if (isApplyingBackground()) return
            val drawable = param.args.getOrNull(0) as? Drawable ?: return
            if (inputHosts.contains(view)) {
                param.args[0] = null
                return
            }
            if (coverViews.contains(view)) {
                if (isOpaqueDrawable(drawable)) param.args[0] = null
                return
            }
            if (managedPanels.contains(view) && isOpaqueDrawable(drawable)) {
                param.args[0] = ColorDrawable(scrimColor())
            }
        }
    }

    private val colorSetterHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? View ?: return
            if (isApplyingBackground()) return
            val color = param.args.getOrNull(0) as? Int ?: return
            if (Color.alpha(color) < OpaqueAlphaThreshold) return
            if (inputHosts.contains(view)) {
                param.args[0] = Color.TRANSPARENT
                return
            }
            if (coverViews.contains(view)) {
                param.args[0] = Color.TRANSPARENT
                return
            }
            if (managedPanels.contains(view)) {
                param.args[0] = scrimColor()
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        installActivityHooks()
        installBackgroundHooks()
        ModuleLog.info { "comment glass integration installed" }
    }

    /** 设置开关变化时调用：开则重新扫描贴片，关则整体还原。 */
    fun refresh() {
        mainHandler.post {
            if (!ModuleSettingsBridge.current.commentGlassEnabled) {
                releaseAll()
                return@post
            }
            observedRoots.toList().filterIsInstance<ViewGroup>().forEach { root ->
                scheduleScan(root, force = true)
            }
            rescanDelayed()
        }
    }

    private fun installActivityHooks() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        scheduleScan(decorViewOf(activity), force = true)
                        rescanDelayed()
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to hook Activity.onResume for comment glass", it) }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val focused = param.args.getOrNull(0) as? Boolean ?: return
                        if (!focused) return
                        val activity = param.thisObject as? Activity ?: return
                        scheduleScan(decorViewOf(activity), force = true)
                    }
                },
            )
        }.onFailure {
            ModuleLog.error("failed to hook Activity.onWindowFocusChanged for comment glass", it)
        }
    }

    private fun installBackgroundHooks() {
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "setBackground", drawableSetterHook)
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", drawableSetterHook)
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundColor", colorSetterHook)
        }.onFailure { ModuleLog.error("failed to hook View background setters", it) }
    }

    private fun decorViewOf(activity: Activity): ViewGroup? =
        runCatching { activity.window?.decorView as? ViewGroup }.getOrNull()

    private fun scheduleScan(root: ViewGroup?, force: Boolean) {
        if (root == null) return
        registerRoot(root)
        val now = System.currentTimeMillis()
        val last = lastScanAt.get()
        if (!force && now - last < ScanThrottleMs) return
        lastScanAt.set(now)
        mainHandler.post { scan(root) }
    }

    private fun registerRoot(root: ViewGroup) {
        if (!observedRoots.add(root)) return
        runCatching {
            root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        }.onFailure { ModuleLog.error("failed to observe comment glass root", it) }
    }

    private fun rescanDelayed() {
        mainHandler.postDelayed(
            { observedRoots.toList().filterIsInstance<ViewGroup>().forEach { scan(it) } },
            FirstScanDelayMs,
        )
        mainHandler.postDelayed(
            { observedRoots.toList().filterIsInstance<ViewGroup>().forEach { scan(it) } },
            SecondScanDelayMs,
        )
    }

    private fun scan(root: ViewGroup) {
        if (!ModuleSettingsBridge.current.commentGlassEnabled) {
            releaseAll()
            return
        }
        val panel = NativeCommentPanelLocator.find(root) ?: return
        applyScrim(panel)
        clearCovers(panel)
        attachFrame(panel)
        attachInputGlass(panel, root)
    }

    private fun applyScrim(panel: ViewGroup) {
        if (!managedPanels.contains(panel)) {
            managedPanels.add(panel)
            panelBackgrounds[panel] = panel.background
        }
        val background = panel.background
        if (isScrim(background)) return
        if (background != null && !isOpaqueDrawable(background)) return
        applyingBackground.set(true)
        runCatching { panel.setBackgroundColor(scrimColor()) }
            .onFailure { ModuleLog.error("failed to apply comment glass scrim", it) }
        applyingBackground.set(false)
    }

    private fun clearCovers(panel: ViewGroup) {
        val minWidth = panel.width * CoverMinWidthRatio
        val minHeight = maxOf(
            panel.height * CoverMinHeightRatio,
            CoverMinHeightDp * panel.resources.displayMetrics.density,
        )
        walk(panel, 0, CoverWalkDepth) { view ->
            if (view === panel || !view.isShown) return@walk
            if (view.width < minWidth || view.height < minHeight) return@walk
            val background = view.background ?: return@walk
            if (!isOpaqueDrawable(background)) return@walk
            if (!coverViews.contains(view)) {
                coverViews.add(view)
                coverBackgrounds[view] = background
            }
            applyingBackground.set(true)
            runCatching { view.setBackground(null) }
                .onFailure { ModuleLog.error("failed to clear comment cover background", it) }
            applyingBackground.set(false)
        }
    }

    private fun attachFrame(panel: ViewGroup) {
        if (findFrame(panel) != null) return
        val frame = CommentGlassFrameView(panel.context).apply { tag = FrameTag }
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        runCatching { panel.addView(frame, 0, params) }
            .onSuccess {
                ModuleLog.info {
                    "comment glass frame attached on ${panel.javaClass.simpleName} " +
                        "${panel.width}x${panel.height}"
                }
            }
            .onFailure { ModuleLog.error("failed to attach comment glass frame", it) }
    }

    private fun findFrame(panel: ViewGroup): View? {
        for (index in 0 until panel.childCount) {
            val child = panel.getChildAt(index)
            if (child.tag == FrameTag) return child
        }
        return null
    }

    /** Comment input bar (level 2): locate in panel first, then fall back to whole window. */
    private fun attachInputGlass(panel: ViewGroup, root: ViewGroup) {
        val host = NativeCommentInputLocator.find(panel)
            ?: NativeCommentInputLocator.findInRoot(root)
            ?: return
        if (findInputGlass(host) != null) return
        if (!inputHosts.contains(host)) {
            inputHosts.add(host)
            inputBackgrounds[host] = host.background
        }
        applyingBackground.set(true)
        runCatching { host.setBackground(null) }
            .onFailure { ModuleLog.error("failed to clear comment input background", it) }
        applyingBackground.set(false)
        val glass = CommentInputGlassView(host.context).apply { tag = InputFrameTag }
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        runCatching { host.addView(glass, 0, params) }
            .onSuccess {
                ModuleLog.info {
                    "comment input glass attached on " + host.javaClass.simpleName +
                        " " + host.width + "x" + host.height
                }
            }
            .onFailure { ModuleLog.error("failed to attach comment input glass", it) }
    }

    private fun findInputGlass(host: ViewGroup): View? {
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            if (child.tag == InputFrameTag) return child
        }
        return null
    }

    private fun releaseAll() {
        val panels = managedPanels.toList().filterIsInstance<ViewGroup>()
        val covers = coverViews.toList()
        val inputs = inputHosts.toList().filterIsInstance<ViewGroup>()
        applyingBackground.set(true)
        panels.forEach { panel ->
            findFrame(panel)?.let { frame -> runCatching { panel.removeView(frame) } }
            runCatching { panel.setBackground(panelBackgrounds[panel]) }
        }
        covers.forEach { view ->
            runCatching { view.setBackground(coverBackgrounds[view]) }
        }
        inputs.forEach { host ->
            findInputGlass(host)?.let { glass -> runCatching { host.removeView(glass) } }
            runCatching { host.setBackground(inputBackgrounds[host]) }
        }
        applyingBackground.set(false)
        managedPanels.clear()
        panelBackgrounds.clear()
        coverViews.clear()
        coverBackgrounds.clear()
        inputHosts.clear()
        inputBackgrounds.clear()
    }

    private fun walk(view: View, depth: Int, maxDepth: Int, action: (View) -> Unit) {
        if (depth > maxDepth) return
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), depth + 1, maxDepth, action)
            }
        }
    }

    private fun isScrim(drawable: Drawable?): Boolean =
        drawable is ColorDrawable && drawable.color == scrimColor()

    private fun isOpaqueDrawable(drawable: Drawable?): Boolean = when (drawable) {
        null -> false
        is ColorDrawable -> Color.alpha(drawable.color) >= OpaqueAlphaThreshold
        is GradientDrawable -> {
            val color = runCatching { drawable.color?.defaultColor }.getOrNull()
            color != null && Color.alpha(color) >= OpaqueAlphaThreshold
        }
        else -> false
    }

    private fun isApplyingBackground(): Boolean = applyingBackground.get() == true

    private fun scrimColor(): Int = Color.argb(ScrimAlpha, 0x0C, 0x0E, 0x14)
}
